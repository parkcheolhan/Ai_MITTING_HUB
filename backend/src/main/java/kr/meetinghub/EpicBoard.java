package kr.meetinghub;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import kr.meetinghub.Domain.*;

@Service
public class EpicBoard {
    final MeetingService service;final TransactionTemplate tx;
    EpicBoard(MeetingService service,TransactionTemplate tx){this.service=service;this.tx=tx;}
    public MeetingView add(String meetingId,AddEpic input){
        var jira=service.jira().snapshot();var before=service.get(meetingId);
        String key=Objects.toString(input.issueKey(),"").strip().toUpperCase(Locale.ROOT),summary=Objects.toString(input.summary(),"").strip();
        MeetingService.require(key.isBlank()!=summary.isBlank(),"기존 에픽을 선택하거나 새 에픽 제목을 입력하세요.");
        JiraEpic remote=key.isBlank()?null:jira.epic(before.content().projectKey,key);
        tx.executeWithoutResult(s->{
            var m=service.lock(meetingId);service.editable(m);var c=service.content(m);
            MeetingService.require(before.content().projectKey.equals(c.projectKey),"회의 프로젝트가 변경되었습니다. 다시 조회하세요.");
            var existing=service.epics().findByMeetingId(meetingId).stream().filter(e->!key.isBlank()&&key.equals(e.issueKey)&&e.target.equals(jira.target())).findFirst();
            if(existing.isEmpty()){
                var e=new Epic();e.id=remote==null?UUID.randomUUID().toString():UUID.nameUUIDFromBytes((meetingId+"|"+jira.target()+"|"+key).getBytes(StandardCharsets.UTF_8)).toString();
                e.meetingId=meetingId;e.projectKey=c.projectKey;e.target=jira.target();e.external=remote!=null;
                e.summary=remote==null?summary:remote.summary();e.description=EpicContents.description(m.title,c);
                if(remote!=null){e.issueKey=remote.key();e.issueUrl=remote.issueUrl();e.status="PUBLISHED";e.contentStatus="EXTERNAL";}
                service.epics().save(e);service.audits().save(new Audit(remote==null?"EPIC_PLANNED":"EXISTING_EPIC_SELECTED",e.id));
            }
            c.epicBoardEnabled=true;service.save(m,c);
        });
        return service.get(meetingId);
    }
    public List<Draft> move(String draftId,MoveEpic input){
        String target=service.jira().snapshot().target();
        return tx.execute(s->{
            var root=service.drafts().locked(draftId).orElseThrow(MeetingService::missing);
            var m=service.lock(root.meetingId);service.editable(m);var c=service.content(m);
            MeetingService.require(root.version==input.version(),"티켓이 변경되었습니다. 최신 화면에서 다시 이동하세요.");
            MeetingService.require(Objects.toString(root.parentDraftId,"").isBlank(),"업무 티켓을 이동하면 하위업무가 함께 이동합니다. 상위업무를 선택하세요.");
            var ids=service.drafts().findByParentDraftId(root.id).stream().map(d->d.id).sorted().toList();
            var group=new ArrayList<Draft>();group.add(root);for(String id:ids)group.add(service.drafts().locked(id).orElseThrow(MeetingService::missing));
            MeetingService.require(group.stream().allMatch(d->SchedulingService.editable(d)&&!"EXISTING".equals(d.registrationMode)&&Objects.toString(d.issueKey,"").isBlank()),"등록된 티켓·기존 이슈 연결·등록 결과 확인 중인 티켓은 이동할 수 없습니다.");
            String id=Objects.toString(input.epicId(),"").strip(),key="";
            if(!id.isBlank()){
                var epic=service.epics().findById(id).orElseThrow(MeetingService::missing);
                MeetingService.require(epic.meetingId.equals(m.id)&&epic.projectKey.equals(c.projectKey)&&epic.projectKey.equals(root.projectKey)&&epic.target.equals(target),"같은 회의·프로젝트·Jira 대상의 에픽으로 이동하세요.");
                MeetingService.require(Set.of("PENDING","FAILED","PUBLISHED").contains(epic.status),"에픽의 등록 결과를 확인한 뒤 이동하세요.");key=Objects.toString(epic.issueKey,"");
            }
            MeetingService.require(group.stream().allMatch(d->d.projectKey.equals(root.projectKey)),"업무와 하위업무의 프로젝트를 확인하세요.");
            if(!id.isBlank())MeetingService.require(group.stream().allMatch(d->!d.summary.isBlank()&&!d.description.isBlank()),"제목과 설명이 필요합니다.");
            // Placing a task in an Epic also approves its content for this Jira target.
            // Publication still checks schedule readiness and never runs as part of moving.
            for(var d:group){
                d.epicId=id;d.epicKey=key;d.parent=d.id.equals(root.id)?key:"";d.status=id.isBlank()?"DRAFT":"APPROVED";
                d.approvedTarget=id.isBlank()?null:target;d.publishTarget=null;d.error=null;service.drafts().save(d);
                if(!id.isBlank())service.audits().save(new Audit("DRAFT_APPROVED",d.id));
            }
            c.epicBoardEnabled=true;service.save(m,c);service.audits().save(new Audit("DRAFT_EPIC_MOVED",root.id));
            service.drafts().flush();return group;
        });
    }
}
