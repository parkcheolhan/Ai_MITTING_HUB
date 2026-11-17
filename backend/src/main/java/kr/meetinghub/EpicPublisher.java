package kr.meetinghub;

import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.persistence.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.meetinghub.Domain.*;

/** One durable Epic per meeting/project/Jira target, with uncertain POST reconciliation. */
@Service
public class EpicPublisher {
    final Epics epics;final Meetings meetings;final Audits audits;final TransactionTemplate tx;final ObjectMapper json;
    final EpicContents contents;
    EpicPublisher(Epics epics,Meetings meetings,Audits audits,TransactionTemplate tx,ObjectMapper json,EpicContents contents){this.epics=epics;this.meetings=meetings;this.audits=audits;this.tx=tx;this.json=json;this.contents=contents;}
    record Claim(Epic epic,boolean reconcile,boolean send){}
    public Epic ensure(String meetingId,String project,Adapters.Jira jira){
        String id=UUID.nameUUIDFromBytes((meetingId+"|"+project+"|"+jira.target()).getBytes(StandardCharsets.UTF_8)).toString();
        return ensure(id,meetingId,project,jira,false);
    }
    public Epic ensureSelected(String id,String meetingId,String project,Adapters.Jira jira){return ensure(id,meetingId,project,jira,true);}
    Epic ensure(String id,String meetingId,String project,Adapters.Jira jira,boolean selected){
        var claim=tx.execute(s->{
            var meeting=meetings.locked(meetingId).orElseThrow(MeetingService::missing);
            if(selected)MeetingService.require(epics.existsById(id),"선택한 에픽을 찾을 수 없습니다.");
            var epic=epics.findById(id).orElseGet(()->{
                var e=new Epic();e.id=id;e.meetingId=meetingId;e.projectKey=project;e.target=jira.target();
                e.summary=("[회의] "+meeting.title);if(e.summary.length()>200)e.summary=e.summary.substring(0,200);
                try{var content=json.readValue(meeting.payload,Content.class);e.description=EpicContents.description(meeting.title,content);}
                catch(Exception ignored){e.description="회의 업무 묶음: /meetings/"+meetingId;}
                return e;
            });
            MeetingService.require(epic.meetingId.equals(meetingId)&&epic.projectKey.equals(project)&&epic.target.equals(jira.target()),"에픽의 회의·프로젝트·Jira 대상이 다릅니다.");
            if(Set.of("PUBLISHED","PUBLISHING").contains(epic.status))return new Claim(epic,false,false);
            boolean reconcile=epic.status.equals("UNKNOWN");
            if(!reconcile){epic.status="PUBLISHING";epic.error=null;epics.saveAndFlush(epic);audits.save(new Audit("EPIC_PUBLISH_STARTED",id));}
            return new Claim(epic,reconcile,true);
        });
        if(!claim.send())return claim.epic().status.equals("PUBLISHED")&&!claim.epic().external?contents.sync(id,jira):claim.epic();
        try{
            Draft draft=asDraft(claim.epic());Result result;
            if(claim.reconcile()){
                var existing=jira.find(draft);if(existing.isEmpty())return claim.epic();result=existing.get();
            }else result=jira.create(draft);
            tx.executeWithoutResult(s->{var e=epics.locked(id).orElseThrow();e.status="PUBLISHED";e.issueKey=result.issueKey();e.issueUrl=result.issueUrl();e.error=null;epics.save(e);audits.save(new Audit("EPIC_PUBLISHED",id));});
            return contents.sync(id,jira);
        }catch(CloudJira.Uncertain e){return fail(id,"UNKNOWN",e.getMessage());}
        catch(IllegalArgumentException e){return fail(id,claim.reconcile()?"UNKNOWN":"FAILED",e.getMessage());}
        catch(Exception e){return fail(id,"UNKNOWN","에픽 등록 결과를 확인할 수 없습니다. 자동 재생성하지 않습니다.");}
    }
    Epic fail(String id,String status,String message){return tx.execute(s->{var e=epics.locked(id).orElseThrow();if(!e.status.equals("PUBLISHED")){e.status=status;e.error=message;epics.save(e);}return e;});}
    static Draft asDraft(Epic epic){var d=new Draft();d.scheduleMode="MANUAL";d.id=epic.id;d.meetingId=epic.meetingId;d.projectKey=epic.projectKey;d.issueType="Epic";d.summary=epic.summary;d.description=epic.description;d.priority="";return d;}
}
@Entity @Table(name="meeting_epics")
class Epic {
    @Id public String id;
    public String meetingId,projectKey,target,summary,status="PENDING",issueKey,issueUrl;
    @Column(columnDefinition="text") public String description;
    public String contentStatus="PENDING";
    @Column(nullable=false,columnDefinition="boolean default false") public boolean external;
    @Column(length=2000) public String contentError;
    @Column(length=2000) public String error;
    @Version public long version;
}
interface Epics extends JpaRepository<Epic,String>{
    List<Epic> findByMeetingId(String meetingId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from Epic e where e.id=:id") Optional<Epic> locked(@Param("id") String id);
}
