package kr.meetinghub;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.meetinghub.Domain.*;

/** Explicit meeting-scoped consolidation; originals are archived with nested backups, never deleted. */
@Service
class TicketConsolidation {
    final MeetingService service;
    TicketConsolidation(MeetingService service){this.service=service;}
    static String text(String s){return Objects.toString(s,"");}
    @Transactional public List<Draft> apply(String meetingId,CompactPlan plan){
        var meeting=service.lock(meetingId);service.editable(meeting);var content=service.content(meeting);
        var current=service.drafts().findByMeetingId(meetingId);var byId=new LinkedHashMap<String,Draft>();current.forEach(d->byId.put(d.id,d));
        MeetingService.require(byId.keySet().equals(plan.versions().keySet())&&current.stream().allMatch(d->Objects.equals(plan.versions().get(d.id),d.version)),"후보가 변경되었습니다. 최신 목록으로 통합 계획을 다시 작성하세요.");
        var used=new HashSet<String>();var replacements=new HashMap<String,String>();var backedUp=new HashSet<String>();
        for(var group:plan.groups()){
            MeetingService.require(!group.ids().isEmpty()&&group.summary()!=null&&!group.summary().isBlank()&&group.summary().length()<=200,"통합 후보와 업무명이 필요합니다.");
            for(var id:group.ids()){MeetingService.require(used.add(id)&&byId.containsKey(id),"중복되거나 다른 회의의 후보가 통합 계획에 포함되었습니다.");}
            var rows=group.ids().stream().map(byId::get).toList();
            MeetingService.require(rows.stream().allMatch(SchedulingService::editable),"미등록 새 이슈 후보만 통합할 수 있습니다.");
            boolean child=!text(rows.get(0).parentDraftId).isBlank();
            MeetingService.require(rows.stream().allMatch(d->!text(d.parentDraftId).isBlank()==child),"상위업무와 하위업무를 섞어 통합할 수 없습니다.");
            compatible(rows);
            for(int i=1;i<rows.size();i++)replacements.put(rows.get(i).id,rows.get(0).id);
        }
        for(var group:plan.groups()){
            var rows=group.ids().stream().map(byId::get).toList();if(text(rows.get(0).parentDraftId).isBlank())continue;
            var parents=rows.stream().map(d->resolve(d.parentDraftId,replacements)).distinct().toList();
            MeetingService.require(parents.size()==1,"하위업무는 같은 통합 상위업무 아래에서만 합칠 수 있습니다.");
            MeetingService.require(rows.stream().allMatch(d->service.children(d.id).isEmpty()),"여러 단계의 하위 계층은 통합할 수 없습니다.");
        }
        // Save every modified relationship before any mutation as well as each merged source.
        for(var d:current)if(used.contains(d.id)||replacements.containsKey(d.parentDraftId)||(d.dependencyDraftIds!=null&&d.dependencyDraftIds.stream().anyMatch(replacements::containsKey))||("AI".equals(d.scheduleMode)&&SchedulingService.editable(d)))backup(d,backedUp);
        for(var group:plan.groups()){
            var rows=group.ids().stream().map(byId::get).toList();var keep=rows.get(0);boolean child=!text(keep.parentDraftId).isBlank();
            String description=child?childDescription(rows):rootDescription(rows);
            var refs=new LinkedHashSet<String>();rows.forEach(d->refs.addAll(d.analysisItemIds));
            description=TicketDescriptions.normalize(description,child,service.allItems(content),refs,content.segments);
            MeetingService.require(description.length()<=30000,"통합 설명이 30,000자를 초과합니다. 내용을 지우지 않고 나누어 통합하세요.");
            var known=rows.stream().filter(d->!text(d.assignee).isBlank()).findFirst();if(known.isPresent()){keep.assignee=known.get().assignee;keep.assigneeName=known.get().assigneeName;}
            rows.stream().map(d->text(d.scheduleMode)).filter(s->!s.isBlank()).findFirst().ifPresent(mode->keep.scheduleMode=mode);
            keep.summary=child?SubtaskTitles.compact(group.summary()):group.summary();keep.description=description;keep.analysisItemIds=refs;keep.groupingVersion=2;
            var labels=new LinkedHashSet<String>();var deps=new LinkedHashSet<String>();int effort=0;
            for(var d:rows){labels.addAll(d.labels);if(d.dependencyDraftIds!=null)deps.addAll(d.dependencyDraftIds);effort+=d.estimatedMinutes==null?0:d.estimatedMinutes;}
            keep.labels=labels;keep.dependencyDraftIds=deps;keep.estimatedMinutes=effort;keep.duplicateSuspected=false;SchedulingService.invalidate(keep);service.drafts().save(keep);
            for(int i=1;i<rows.size();i++){var original=rows.get(i);original.status="CONSOLIDATED";original.consolidatedInto=keep.id;original.approvedTarget=null;service.drafts().save(original);service.audits().save(new Audit("TICKET_CONSOLIDATED_INTO_"+keep.id,original.id));}
        }
        var remaining=service.drafts().findByMeetingId(meetingId);
        for(var d:remaining){
            String parent=resolve(text(d.parentDraftId),replacements);var deps=new LinkedHashSet<String>();if(d.dependencyDraftIds!=null)for(var id:d.dependencyDraftIds){String dep=resolve(id,replacements);if(!dep.equals(d.id))deps.add(dep);}
            if(!parent.equals(text(d.parentDraftId))||!deps.equals(d.dependencyDraftIds)){
                MeetingService.require(SchedulingService.editable(d),"등록된 관련 업무의 계층·선행관계를 변경할 수 없습니다.");backup(d,backedUp);d.parentDraftId=parent;d.dependencyDraftIds=deps;SchedulingService.invalidate(d);service.drafts().save(d);
            }
        }
        validateDependencies(remaining);service.clearAiSchedules(meetingId);service.audits().save(new Audit("MEETING_TICKETS_CONSOLIDATED",meetingId));return service.drafts().findByMeetingId(meetingId);
    }
    static void compatible(List<Draft> rows){
        for(Function<Draft,String> field:List.<Function<Draft,String>>of(d->d.projectKey,d->d.issueType,d->d.priority,d->d.dueDate,d->d.startAt,d->d.endAt,d->d.parent,d->d.epicKey))
            MeetingService.require(rows.stream().map(d->text(field.apply(d))).distinct().count()==1,"프로젝트·유형·우선순위·기한·상위 Jira 이슈가 다른 업무는 통합할 수 없습니다.");
        MeetingService.require(rows.stream().map(d->text(d.assignee)).filter(s->!s.isBlank()).distinct().count()<=1,"담당자가 서로 다른 업무는 통합할 수 없습니다.");
        MeetingService.require(rows.stream().map(d->text(d.scheduleMode)).filter(s->!s.isBlank()).distinct().count()<=1,"직접 설정과 AI 일정 방식을 섞어 통합할 수 없습니다.");
        MeetingService.require(rows.stream().map(d->d.labels.contains("ai-recommended")).distinct().count()==1,"AI 추천과 확정 업무의 구분을 보존하려면 별도로 통합하세요.");
    }
    void backup(Draft d,Set<String> saved){
        if(!saved.add(d.id))return;
        try{String previous=text(d.consolidationBackup);d.consolidationBackup=service.mapper().writeValueAsString(Map.of("draft",d,"previousBackup",previous));}
        catch(Exception e){throw new IllegalStateException("통합 전 후보를 보관할 수 없습니다.");}
    }
    static String resolve(String id,Map<String,String> replacements){var seen=new HashSet<String>();while(replacements.containsKey(id)){MeetingService.require(seen.add(id),"순환된 통합 계획입니다.");id=replacements.get(id);}return id;}
    static List<String> entries(List<Draft> rows,int section){return rows.stream().flatMap(d->MeetingService.paragraphEntries(d.description,section).stream()).distinct().toList();}
    static String childDescription(List<Draft> rows){
        var normalized=rows.stream().map(d->{var copy=new Draft();copy.description=TicketDescriptions.childSections(d.description);return copy;}).toList();
        return TicketDescriptions.childDocument(entries(normalized,1),entries(normalized,2),entries(normalized,3),entries(normalized,4));
    }
    static String rootDescription(List<Draft> rows){
        var headings=new LinkedHashMap<Integer,String>();var sections=new LinkedHashMap<Integer,LinkedHashSet<String>>();
        var regex=Pattern.compile("(?m)^\\s*(?:#{1,6}\\s*)?(\\d+)[.)]\\s+([^\\r\\n]+)\\s*$");
        for(var d:rows){var matcher=regex.matcher(d.description);int number=1,start=0;boolean first=true;
            while(matcher.find()){
                String body=d.description.substring(start,matcher.start());if(!body.isBlank())sections.computeIfAbsent(number,k->new LinkedHashSet<>()).addAll(TicketDescriptions.lines(body));
                number=Integer.parseInt(matcher.group(1));headings.putIfAbsent(number,matcher.group(2));start=matcher.end();first=false;
            }
            String rest=d.description.substring(start);if(!rest.isBlank())sections.computeIfAbsent(number,k->new LinkedHashSet<>()).addAll(TicketDescriptions.lines(rest));
            if(first)headings.putIfAbsent(1,"배경 및 목적");
        }
        var out=new ArrayList<String>();for(var number:new TreeSet<>(sections.keySet()))out.add(number+". "+headings.getOrDefault(number,"배경 및 목적")+"\n"+TicketDescriptions.bullets(new ArrayList<>(sections.get(number)),""));return String.join("\n\n",out);
    }
    static void validateDependencies(List<Draft> rows){
        var deps=new LinkedHashMap<String,Set<String>>();rows.forEach(d->deps.put(d.id,new LinkedHashSet<>(d.dependencyDraftIds==null?Set.of():d.dependencyDraftIds)));
        var done=new HashSet<String>();while(done.size()<deps.size()){
            int before=done.size();deps.forEach((id,links)->{if(done.containsAll(links))done.add(id);});MeetingService.require(done.size()>before,"통합 후 순환되거나 잘못된 선행관계가 생깁니다. 통합 계획을 나누세요.");
        }
    }
}
