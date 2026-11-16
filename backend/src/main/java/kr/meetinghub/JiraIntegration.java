package kr.meetinghub;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import kr.meetinghub.Domain.*;

@Service
public class JiraIntegration {
    final Adapters.Jira jira;final JiraSettingsStore settings;final MeetingService service;final Publisher publisher;final TransactionTemplate tx;
    JiraIntegration(Adapters.Jira jira,JiraSettingsStore settings,MeetingService service,Publisher publisher,TransactionTemplate tx) {
        this.jira=jira;this.settings=settings;this.service=service;this.publisher=publisher;this.tx=tx;
    }
    public Map<String,Object> settings() {
        return settings.view();
    }
    public synchronized Map<String,Object> saveSettings(JiraSettingsStore.Input input) {
        return settings.save(input);
    }
    public synchronized Map<String,Object> saveProject(String project) {
        var selected=jira.snapshot();
        if(selected.mode().equals("cloud"))MeetingService.require(selected.projects().stream().anyMatch(p->project.equals(p.get("key"))),"현재 계정에서 접근할 수 있는 프로젝트를 선택하세요.");
        return settings.saveProject(project);
    }
    public synchronized Map<String,Object> removeToken() {
        return settings.removeToken();
    }
    public synchronized Result testIssue(String project,String type,boolean approved) {
        MeetingService.require(approved,"실제 테스트 이슈 생성 동의가 필요합니다.");
        var selected=jira.snapshot();
        MeetingService.require(selected.mode().equals("cloud"),"실제 Jira Cloud 모드에서만 테스트 이슈를 생성할 수 있습니다.");
        CloudJira.projectKey(project);selected.test();
        String suffix=UUID.nameUUIDFromBytes((selected.target()+":"+project).getBytes(StandardCharsets.UTF_8)).toString();
        String meetingId=UUID.nameUUIDFromBytes(("test-meeting:"+suffix).getBytes(StandardCharsets.UTF_8)).toString();
        String draftId=UUID.nameUUIDFromBytes(("test-draft:"+suffix).getBytes(StandardCharsets.UTF_8)).toString();
        tx.executeWithoutResult(s->{
            if(service.drafts().existsById(draftId)) {
                var previous=service.drafts().locked(draftId).orElseThrow();
                if(previous.status.equals("FAILED")) { previous.issueType=type;previous.status="APPROVED";previous.approvedTarget=selected.target();service.drafts().save(previous); }
                return;
            }
            if(!service.meetings().existsById(meetingId)) {
                var m=new Meeting();m.id=meetingId;m.title="Jira 실제 연동 테스트";m.status="REVIEW_PENDING";
                var content=new Content();content.projectKey=project;content.memo="설정 화면에서 사용자가 요청한 테스트입니다.";service.save(m,content);
            }
            var d=new Draft();d.scheduleMode="MANUAL";d.id=draftId;d.meetingId=meetingId;d.projectKey=project;d.issueType=type;
            d.summary="[Meeting Hub 연동 테스트] 회의 업무 티켓 생성 확인";d.priority="";
            d.description="1. 배경 및 목적\n사용자가 요청한 Jira 실제 연동 테스트입니다.\n\n2. 해야 하는 작업 목록\n☐ 생성된 이슈 및 한국어 설명 확인\n\n3. 확인해야 하는 항목\n☐ 프로젝트·이슈 유형·Jira 링크 확인\n\n4. 완료 조건\n사용자가 Jira에서 이슈를 열어 확인\n\n5. 의존성 및 제약사항\n테스트 이슈는 필요 시 사용자가 Jira에서 삭제\n\n6. 회의 근거\n회의 분석 결과가 아닌 사용자 요청에 따른 연결 검증용 데이터입니다.";
            d.status="APPROVED";d.approvedTarget=selected.target();d.labels.add("meetinghub-integration-test");service.drafts().save(d);
            service.audits().save(new Audit("TEST_ISSUE_APPROVED",draftId));
        });
        return publisher.publish(draftId,selected);
    }
}
