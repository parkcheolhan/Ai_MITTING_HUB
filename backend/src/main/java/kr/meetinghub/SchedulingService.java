package kr.meetinghub;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import kr.meetinghub.Domain.*;

@Service
class SchedulingService {
    final MeetingService service;final TransactionTemplate tx;
    private final Object allocationLock=new Object();
    SchedulingService(MeetingService service,TransactionTemplate tx){this.service=service;this.tx=tx;}
    static boolean editable(Draft d){return Set.of("DRAFT","APPROVED","FAILED").contains(d.status)&&!"EXISTING".equals(d.registrationMode)&&Objects.toString(d.issueKey,"").isBlank();}
    static boolean target(Draft d){return editable(d)&&("AI".equals(d.scheduleMode)||(blank(d.startAt)&&blank(d.endAt)&&blank(d.dueDate)));}
    static boolean blank(String s){return s==null||s.isBlank();}
    record Snapshot(String title,String project,List<Draft> drafts,ScheduleRequest request){}
    void run(String jobId){
        var snapshot=tx.execute(s->{
            var job=service.jobs().locked(jobId).orElseThrow();if(job.status.equals("CANCELLED"))return null;
            var meeting=service.lock(job.meetingId);ScheduleRequest request;
            try{request=service.mapper().readValue(job.requestPayload,ScheduleRequest.class);}catch(Exception e){throw new IllegalArgumentException("저장된 일정 설정을 읽을 수 없습니다.");}
            SchedulePlanner.validate(request);job.status="RUNNING";job.stage="ESTIMATING_SCHEDULE";job.progress=15;service.jobs().save(job);meeting.status="ESTIMATING_SCHEDULE";service.meetings().save(meeting);
            return new Snapshot(meeting.title,service.content(meeting).projectKey,service.drafts().findByMeetingId(meeting.id),request);
        });
        if(snapshot==null)return;
        var byId=new HashMap<String,Draft>();snapshot.drafts().forEach(d->byId.put(d.id,d));
        var parentIds=new HashSet<String>();snapshot.drafts().forEach(d->{if(!blank(d.parentDraftId))parentIds.add(d.parentDraftId);});
        var leaves=snapshot.drafts().stream().filter(d->!parentIds.contains(d.id)).toList();
        var targets=leaves.stream().filter(SchedulingService::target).toList();
        MeetingService.require(!targets.isEmpty(),"AI 일정을 설정할 미등록 업무가 없습니다. 기한 방식을 AI로 바꾸거나 수동 일정을 확인하세요.");
        MeetingService.require(leaves.size()<=200,"일정 분석은 한 회의의 실행 업무 200개 이하로 진행하세요.");
        for(var d:targets)MeetingService.require(!blank(d.assignee),"담당자가 없는 업무가 있습니다: "+d.summary+". 먼저 담당자를 지정하세요.");
        var relevant=leaves.stream().filter(d->target(d)||!blank(d.startAt)||!blank(d.endAt)||(editable(d)&&!blank(d.dueDate))).toList();
        var tasks=relevant.stream().map(d->new ScheduleTask(d.id,byId.containsKey(d.parentDraftId)?byId.get(d.parentDraftId).summary:"",d.summary,taskDescription(d,byId.get(d.parentDraftId)),Objects.toString(d.assignee,""),Objects.toString(d.assigneeName,""),d.priority,!target(d),Objects.toString(d.startAt,""),Objects.toString(d.endAt,""),new ArrayList<>(d.dependencyDraftIds==null?Set.of():d.dependencyDraftIds))).toList();
        for(var task:tasks)if(task.fixed())MeetingService.require(!blank(task.startAt())&&!blank(task.endAt()),"수동 업무의 시작·종료 시각을 먼저 지정하거나 AI 방식으로 전환하세요: "+task.summary());
        var jira=service.jira().snapshot();
        // Validate selected identities against the project's assignable directory before estimating.
        if(jira.mode().equals("cloud"))for(var account:targets.stream().map(d->d.assignee).distinct().toList())
            MeetingService.require(jira.assignableUsers(snapshot.project(),"",account,0).users().stream().anyMatch(u->u.accountId().equals(account)),"현재 프로젝트에 할당할 수 없는 담당자가 있습니다. 담당자를 다시 선택하세요.");
        var catalog=priorityCatalog(jira,snapshot.project(),targets.get(0));
        var priorities=catalog.values();
        var ai=service.intelligence().snapshot();var assessments=ai.schedule(tasks,priorities);
        var expected=new HashSet<String>();targets.forEach(d->expected.add(d.id));var estimates=new LinkedHashMap<String,ScheduleAssessment>();
        var allowed=new HashSet<String>();priorities.forEach(p->allowed.add(p.id()));
        for(var a:assessments){MeetingService.require(expected.contains(a.draftId())&&estimates.put(a.draftId(),a)==null,"AI 일정 응답에 중복되거나 다른 업무가 포함되어 있습니다.");MeetingService.require(allowed.contains(a.priority())&&a.estimatedMinutes()>=15&&a.estimatedMinutes()<=19200&&a.reason()!=null&&!a.reason().isBlank()&&a.reason().length()<=4000,"AI 우선순위·예상 작업시간·판단 근거를 확인할 수 없습니다.");}
        MeetingService.require(estimates.keySet().equals(expected),"AI 일정 응답에서 누락된 업무가 있습니다. 다시 시도하세요.");
        var work=relevant.stream().map(d->{var a=estimates.get(d.id);var parent=byId.get(d.parentDraftId);boolean manualBound=parent!=null&&!"AI".equals(parent.scheduleMode);String notBefore=manualBound?Objects.toString(parent.startAt,""):"",deadline=manualBound?Objects.toString(parent.endAt,""):"";if(manualBound&&blank(deadline)&&!blank(parent.dueDate))deadline=java.time.LocalDate.parse(parent.dueDate).plusDays(1).atStartOfDay(ZoneId.of(snapshot.request().timezone())).toOffsetDateTime().toString();return new SchedulePlanner.Work(d.id,Objects.toString(d.assignee,""),a==null,Objects.toString(d.startAt,""),Objects.toString(d.endAt,""),a==null?0:a.estimatedMinutes(),a==null?d.priority:a.priority(),a==null?new ArrayList<>(d.dependencyDraftIds==null?Set.of():d.dependencyDraftIds):a.dependencyDraftIds(),notBefore,deadline);}).toList();
        synchronized(allocationLock){
            tx.executeWithoutResult(s->{
                var job=service.jobs().locked(jobId).orElseThrow();if(job.status.equals("CANCELLED"))return;
                service.lock(job.meetingId);job.stage="ALLOCATING_SCHEDULE";job.progress=70;service.jobs().save(job);
            });
            tx.executeWithoutResult(s->{
                var job=service.jobs().locked(jobId).orElseThrow();if(job.status.equals("CANCELLED"))return;
                var meeting=service.lock(job.meetingId);var current=service.drafts().findByMeetingId(meeting.id);
                var versions=new HashMap<String,Long>();snapshot.drafts().forEach(d->versions.put(d.id,d.version));
                MeetingService.require(current.size()==snapshot.drafts().size()&&current.stream().allMatch(d->Objects.equals(versions.get(d.id),d.version)),"분석 중 후보가 변경되었습니다. 최신 내용으로 다시 실행하세요.");
                var currentIds=new HashSet<>(byId.keySet());var reservations=new ArrayList<SchedulePlanner.Reservation>();
                var all=service.drafts().findAll();var allParents=new HashSet<String>();all.stream().filter(d->!d.status.equals("CONSOLIDATED")).forEach(d->{if(!blank(d.parentDraftId))allParents.add(d.parentDraftId);});
                for(var d:all)if(!currentIds.contains(d.id)&&!allParents.contains(d.id)&&!d.status.equals("CONSOLIDATED")&&!blank(d.assignee)&&!blank(d.startAt)&&!blank(d.endAt))
                    reservations.add(new SchedulePlanner.Reservation(d.assignee,OffsetDateTime.parse(d.startAt).toZonedDateTime(),OffsetDateTime.parse(d.endAt).toZonedDateTime()));
                var slots=SchedulePlanner.plan(work,reservations,snapshot.request(),priorities);
                var zone=ZoneId.of(snapshot.request().timezone());var nowById=new HashMap<String,Draft>();current.forEach(d->nowById.put(d.id,d));
                for(var a:assessments){var d=nowById.get(a.draftId());var slot=slots.get(d.id);d.startAt=slot.start().toOffsetDateTime().toString();d.endAt=slot.end().toOffsetDateTime().toString();d.dueDate=slot.end().withZoneSameInstant(zone).toLocalDate().toString();d.estimatedMinutes=a.estimatedMinutes();if(catalog.writable())d.priority=a.priority();d.dependencyDraftIds=new LinkedHashSet<>(a.dependencyDraftIds());d.scheduleReason=a.reason()+(catalog.writable()?"":"\nAI 일정 산정 우선순위: "+a.priority()+" (Jira 프로젝트 기본 우선순위 유지)");d.scheduleMode="AI";invalidate(d);service.drafts().save(d);}
                for(var id:parentIds){var parent=nowById.get(id);if(parent==null||!editable(parent))continue;var children=current.stream().filter(d->id.equals(d.parentDraftId)).toList();
                    if(("AI".equals(parent.scheduleMode)||(blank(parent.startAt)&&blank(parent.endAt)&&blank(parent.dueDate)))&&children.stream().allMatch(d->!blank(d.startAt)&&!blank(d.endAt))){
                        parent.startAt=children.stream().map(d->OffsetDateTime.parse(d.startAt)).min(Comparator.comparing(OffsetDateTime::toInstant)).orElseThrow().toString();parent.endAt=children.stream().map(d->OffsetDateTime.parse(d.endAt)).max(Comparator.comparing(OffsetDateTime::toInstant)).orElseThrow().toString();parent.dueDate=OffsetDateTime.parse(parent.endAt).atZoneSameInstant(zone).toLocalDate().toString();parent.estimatedMinutes=children.stream().mapToInt(d->d.estimatedMinutes==null?0:d.estimatedMinutes).sum();if(catalog.writable())parent.priority=priorities.stream().filter(p->children.stream().anyMatch(d->p.id().equals(d.priority))).map(SchedulePriority::id).findFirst().orElse(parent.priority);parent.scheduleMode="AI";parent.scheduleReason="하위업무의 시작·종료와 예상 작업시간을 집계했습니다. 상위업무는 담당자 시간에 중복 배정하지 않습니다.";invalidate(parent);service.drafts().save(parent);
                    }
                }
                job.status="COMPLETED";job.progress=100;service.jobs().save(job);meeting.status="REVIEW_PENDING";var content=service.content(meeting);content.scheduleSettings=snapshot.request();service.save(meeting,content);service.audits().save(new Audit(ai.mode().equals("mock")?"MOCK_SCHEDULE_APPLIED":"AI_SCHEDULE_APPLIED",meeting.id));
            });
        }
    }
    static void invalidate(Draft d){d.status="DRAFT";d.approvedTarget=null;d.publishTarget=null;d.error=null;}
    static String taskDescription(Draft d,Draft parent){String text=d.description.substring(0,Math.min(6000,d.description.length()));if(parent!=null)text+="\n상위업무 맥락·의존성:\n"+parent.description.substring(0,Math.min(1500,parent.description.length()));return text;}
    record PriorityCatalog(List<SchedulePriority> values,boolean writable){}
    static PriorityCatalog priorityCatalog(Adapters.Jira jira,String project,Draft sample){
        if(!jira.mode().equals("cloud"))return new PriorityCatalog(List.of(new SchedulePriority("Highest","Highest"),new SchedulePriority("High","High"),new SchedulePriority("Medium","Medium"),new SchedulePriority("Low","Low"),new SchedulePriority("Lowest","Lowest")),true);
        var probe=new Draft();probe.projectKey=project;probe.issueType=sample.issueType;probe.parentDraftId=sample.parentDraftId;probe.parent=blank(sample.parentDraftId)?"":"PENDING-1";
        var cloud=(CloudJira)jira;var type=cloud.resolveType(probe);var fields=jira.fields(project,type);
        for(var f:fields)if("priority".equals(f.get("id"))){
            @SuppressWarnings("unchecked") var values=(List<Map<String,Object>>)f.get("allowedValues");
            var result=(values==null?List.<Map<String,Object>>of():values).stream().map(v->new SchedulePriority(Objects.toString(v.get("id"),""),Objects.toString(v.get("name"),""))).toList();if(!result.isEmpty())return new PriorityCatalog(result,true);
        }
        return new PriorityCatalog(List.of(new SchedulePriority("Highest","Highest"),new SchedulePriority("High","High"),new SchedulePriority("Medium","Medium"),new SchedulePriority("Low","Low"),new SchedulePriority("Lowest","Lowest")),false);
    }
}
