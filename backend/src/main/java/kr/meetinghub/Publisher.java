package kr.meetinghub;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import kr.meetinghub.Domain.*;

@Service
public class Publisher {
    final Drafts drafts; final Meetings meetings; final Audits audits; final Adapters.Jira jira; final TransactionTemplate tx;
    final EpicPublisher epicPublisher;
    Publisher(Drafts drafts,Meetings meetings,Audits audits,Adapters.Jira jira,TransactionTemplate tx,EpicPublisher epicPublisher) {
        this.drafts=drafts;this.meetings=meetings;this.audits=audits;this.jira=jira;this.tx=tx;
        this.epicPublisher=epicPublisher;
    }
    record Claim(Draft draft,Result result,boolean reconcile) {}
    public List<Result> publishBatch(List<String> ids){
        var selected=new ArrayList<>(new LinkedHashSet<>(ids));var adapter=jira.snapshot();
        selected.sort(Comparator.comparingInt(id->drafts.findById(id).map(d->Objects.toString(d.parentDraftId,"").isBlank()?0:1).orElse(0)));
        var outcomes=new HashMap<String,Epic>();
        return selected.stream().map(id->{try{
            var d=drafts.findById(id).orElseThrow(MeetingService::missing);
            if(!"EXISTING".equals(d.registrationMode)&&Objects.toString(d.parentDraftId,"").isBlank()&&Set.of("APPROVED","FAILED").contains(d.status)&&adapter.target().equals(d.approvedTarget)&&(d.publishTarget==null||adapter.target().equals(d.publishTarget))){
                String chosen=Objects.toString(d.epicId,"");
                if(chosen.isBlank()&&epicPublisher.json.readValue(meetings.findById(d.meetingId).orElseThrow().payload,Content.class).epicBoardEnabled){
                    chosen=epicPublisher.epics.findByMeetingId(d.meetingId).stream().filter(e->Objects.equals(e.issueKey,d.epicKey)&&!Objects.toString(d.epicKey,"").isBlank()&&e.target.equals(adapter.target())).map(e->e.id).findFirst().orElse("");
                    if(chosen.isBlank())return new Result(id,"BLOCKED",null,null,"티켓을 에픽 박스에 배치한 뒤 등록하세요.");
                }
                final String epicId=chosen;
                String group=epicId.isBlank()?d.meetingId+"|"+d.projectKey:epicId;
                var epic=outcomes.computeIfAbsent(group,k->epicId.isBlank()?epicPublisher.ensure(d.meetingId,d.projectKey,adapter):epicPublisher.ensureSelected(epicId,d.meetingId,d.projectKey,adapter));
                if(!epic.status.equals("PUBLISHED"))return new Result(id,"BLOCKED",null,null,"에픽이 먼저 등록되어야 합니다. 에픽 상태: "+epic.status+". "+Objects.toString(epic.error,"잠시 후 등록 결과를 다시 확인하세요."));
                if(!epic.external&&!"SYNCED".equals(epic.contentStatus))return new Result(id,"BLOCKED",null,null,"에픽 회의록·녹음 첨부를 먼저 완료하세요. "+Objects.toString(epic.contentError,"동기화가 필요합니다."));
                return publish(id,adapter,epic.issueKey);
            }
            return publish(id,adapter);
        }catch(Exception e){return new Result(id,"FAILED",null,null,"등록 처리 실패. 결과 확인 후 재시도하세요.");}}).toList();
    }
    public Result publish(String id) {
        return publish(id,jira.snapshot());
    }
    Result publish(String id,Adapters.Jira selectedJira) {
        return publish(id,selectedJira,null);
    }
    Result publish(String id,Adapters.Jira selectedJira,String epicKey) {
        // Commit a durable claim before any remote POST, including process-crash recovery.
        var claim=tx.execute(s->{
            var d=drafts.locked(id).orElseThrow(MeetingService::missing);
            if(d.status.equals("PUBLISHED"))return new Claim(null,new Result(id,"SKIPPED",d.issueKey,d.issueUrl,null),false);
            if(d.publishTarget!=null&&!d.publishTarget.equals(selectedJira.target()))return new Claim(null,new Result(id,"BLOCKED",null,null,"기존 등록 대상과 현재 Jira 설정이 다릅니다. 원래 설정으로 확인하세요."),false);
            if(d.status.equals("PUBLISHING"))return new Claim(null,new Result(id,"PUBLISHING",null,null,"이미 처리 중입니다. 잠시 후 결과를 확인하세요."),false);
            if(!Objects.toString(d.parentDraftId,"").isBlank()){
                var parent=drafts.findById(d.parentDraftId).orElse(null);
                if(parent==null||!parent.status.equals("PUBLISHED")||parent.issueKey==null)
                    return new Claim(null,new Result(id,"BLOCKED",null,null,"부모 티켓을 먼저 승인·등록하세요. 부모의 등록 결과가 불확실하면 하위 티켓은 생성하지 않습니다."),false);
                if(!parent.meetingId.equals(d.meetingId)||!parent.projectKey.equals(d.projectKey)||!selectedJira.target().equals(parent.publishTarget))
                    return new Claim(null,new Result(id,"BLOCKED",null,null,"부모와 하위 티켓의 회의·프로젝트·Jira 등록 대상이 다릅니다."),false);
                if(!Objects.toString(parent.parentDraftId,"").isBlank()||(!parent.parent.isBlank()&&!parent.parent.equals(parent.epicKey)))
                    return new Claim(null,new Result(id,"BLOCKED",null,null,"하위 티켓은 한 단계만 등록할 수 있습니다."),false);
                d.parent=parent.issueKey;
                d.epicId=parent.epicId;d.epicKey=parent.epicKey;
            }
            if(d.status.equals("UNKNOWN"))return new Claim(d,null,true);
            if(!Set.of("APPROVED","FAILED").contains(d.status))return new Claim(null,new Result(id,"BLOCKED",null,null,"사용자 승인이 필요합니다."),false);
            try{ScheduleDetails.ready(d);}catch(IllegalArgumentException e){return new Claim(null,new Result(id,"BLOCKED",null,null,e.getMessage()),false);}
            if(!selectedJira.target().equals(d.approvedTarget))return new Claim(null,new Result(id,"BLOCKED",null,null,"승인한 Jira 대상과 현재 설정이 다릅니다. 현재 설정에서 티켓을 다시 승인하세요."),false);
            if(epicKey!=null){d.epicKey=epicKey;d.parent=epicKey;}
            d.status="PUBLISHING";d.publishTarget=selectedJira.target();d.error=null;drafts.saveAndFlush(d);
            audits.save(new Audit("TICKET_PUBLISH_STARTED",id));return new Claim(d,null,false);
        });
        if(claim.result()!=null)return claim.result();
        boolean linking="EXISTING".equals(claim.draft().registrationMode);
        try {
            if(linking)return complete(id,selectedJira.linkExisting(claim.draft()),selectedJira.mode());
            if(claim.reconcile()) {
                var existing=selectedJira.find(claim.draft());
                if(existing.isEmpty())return new Result(id,"UNKNOWN",null,null,"아직 검색되지 않습니다. Jira 반영 지연 또는 권한 문제일 수 있습니다. 자동 재생성하지 않습니다.");
                return complete(id,existing.get(),selectedJira.mode());
            }
            return complete(id,selectedJira.create(claim.draft()),selectedJira.mode());
        } catch(CloudJira.Uncertain e) { return failed(id,linking?"FAILED":"UNKNOWN",linking?"기존 이슈 응답을 확인할 수 없습니다. 다시 조회하세요.":e.getMessage()); }
        catch(IllegalArgumentException e) { return failed(id,!linking&&claim.reconcile()?"UNKNOWN":"FAILED",e.getMessage()); }
        catch(Exception e) { return failed(id,linking?"FAILED":"UNKNOWN",linking?"기존 이슈를 확인하지 못했습니다. 연결 설정과 이슈 키를 확인한 뒤 재시도하세요.":"등록 결과 확인이 필요합니다. 중복 방지를 위해 자동 재생성하지 않습니다."); }
    }
    Result complete(String id,Result result,String mode) {
        return tx.execute(s->{
            var d=drafts.locked(id).orElseThrow(MeetingService::missing);
            d.status="PUBLISHED";d.issueKey=result.issueKey();d.issueUrl=result.issueUrl();d.publishedAt=Instant.now().toString();d.error=null;
            drafts.saveAndFlush(d);audits.save(new Audit("EXISTING".equals(d.registrationMode)?"EXISTING_ISSUE_LINKED":mode.equals("cloud")?"JIRA_TICKET_PUBLISHED":"MOCK_TICKET_PUBLISHED",id));
            var m=meetings.locked(d.meetingId).orElseThrow(MeetingService::missing);
            m.status=drafts.findByMeetingId(d.meetingId).stream().allMatch(t->t.status.equals("PUBLISHED"))?"COMPLETED":"REVIEW_PENDING";
            meetings.save(m);return result;
        });
    }
    Result failed(String id,String status,String message) {
        return tx.execute(s->{
            var d=drafts.locked(id).orElseThrow(MeetingService::missing);
            if(d.status.equals("PUBLISHED"))return new Result(id,"SKIPPED",d.issueKey,d.issueUrl,null);
            d.status=status;d.error=message;drafts.save(d);audits.save(new Audit("PUBLISH_"+status,id));
            return new Result(id,status,null,null,message);
        });
    }
}
