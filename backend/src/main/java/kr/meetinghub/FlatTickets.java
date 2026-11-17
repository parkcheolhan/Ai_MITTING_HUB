package kr.meetinghub;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.meetinghub.Domain.*;

/** Promotes editable legacy subtasks without changing already published Jira issues. */
@Service @Transactional
public class FlatTickets {
    final MeetingService service;
    FlatTickets(MeetingService service){this.service=service;}
    public void prepare(String meetingId){
        var m=service.lock(meetingId);service.editable(m);var all=service.drafts().findByMeetingId(meetingId);
        var byId=new HashMap<String,Draft>();all.forEach(d->byId.put(d.id,d));
        var destination=new HashMap<String,Draft>();
        for(var d:all){var root=d;var visited=new HashSet<String>();while(!Objects.toString(root.parentDraftId,"").isBlank()&&visited.add(root.id)&&byId.containsKey(root.parentDraftId))root=byId.get(root.parentDraftId);destination.put(d.id,root);}
        boolean changed=false;
        for(var d:all){
            if(!SchedulingService.editable(d))continue;
            boolean child=!Objects.toString(d.parentDraftId,"").isBlank();
            if(!child&&d.issueType.equals("Task"))continue;
            if(Objects.toString(d.flatHierarchyBackup,"").isBlank())try{d.flatHierarchyBackup=service.mapper().writeValueAsString(d);}catch(Exception e){throw new IllegalStateException("업무 구조 변경 전 보관 실패",e);}
            if(child){var root=destination.get(d.id);d.epicId=Objects.toString(root.epicId,"");d.epicKey=Objects.toString(root.epicKey,"");d.parentDraftId="";d.parent=d.epicKey;}
            d.issueType="Task";d.status="DRAFT";d.approvedTarget=null;d.publishTarget=null;d.error=null;
            service.drafts().save(d);service.audits().save(new Audit("DRAFT_CONVERTED_TO_TASK",d.id));changed=true;
        }
        if(changed){service.drafts().flush();service.clearAiSchedules(meetingId);}
    }
    public void prepareDraft(String id){prepare(service.drafts().findById(id).orElseThrow(MeetingService::missing).meetingId);}
    DraftInput taskInput(DraftInput input){
        MeetingService.require(Objects.toString(input.parentDraftId(),"").isBlank(),"하위업무는 지원하지 않습니다. 에픽 아래에 작업을 배치하세요.");
        return new DraftInput(input.projectKey(),"Task",input.summary(),input.description(),input.assignee(),input.priority(),input.dueDate(),input.labels(),"",input.analysisItemIds(),"",input.registrationMode(),input.existingIssueKey(),input.assigneeName(),input.startAt(),input.endAt(),input.estimatedMinutes(),input.scheduleMode(),input.dependencyDraftIds());
    }
    public Draft add(String meetingId,DraftInput input){prepare(meetingId);return service.addDraft(meetingId,taskInput(input));}
    public Draft update(String id,DraftInput input){prepareDraft(id);return service.update(id,taskInput(input));}
    public void delete(String id,Long version){
        var d=service.drafts().locked(id).orElseThrow(MeetingService::missing);service.editable(service.lock(d.meetingId));
        MeetingService.require(version==null||version==d.version,"업무가 변경되었습니다. 최신 화면에서 다시 삭제하세요.");service.deleteDraft(id);
    }
}
