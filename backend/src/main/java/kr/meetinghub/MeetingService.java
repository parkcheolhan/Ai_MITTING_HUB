package kr.meetinghub;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import kr.meetinghub.Domain.*;

@Service @Transactional
public class MeetingService {
    final Meetings meetings; final Drafts drafts; final Jobs jobs; final Audits audits; final ObjectMapper json;
    final Adapters.Intelligence intelligence;
    final Adapters.Jira jira;
    final Epics epics;
    MeetingService(Meetings meetings,Drafts drafts,Jobs jobs,Audits audits,ObjectMapper json,Adapters.Intelligence intelligence,Adapters.Jira jira,Epics epics) {
        this.meetings=meetings; this.drafts=drafts; this.jobs=jobs; this.audits=audits; this.json=json; this.intelligence=intelligence;this.jira=jira;
        this.epics=epics;
    }
    public Meetings meetings() { return meetings; }
    public Drafts drafts() { return drafts; }
    public Jobs jobs() { return jobs; }
    public Audits audits() { return audits; }
    public Epics epics(){return epics;}
    public Adapters.Intelligence intelligence() { return intelligence; }
    public ObjectMapper mapper(){return json;}
    public Adapters.Jira jira(){return jira;}
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"대상을 찾을 수 없습니다."); }
    static void require(boolean ok,String message) { if(!ok) throw new IllegalArgumentException(message); }
    Meeting lock(String id) { return meetings.locked(id).orElseThrow(MeetingService::missing); }
    Content content(Meeting m) {
        try { var c=json.readValue(m.payload,Content.class);TimeReferences.clean(c.minutes);return c; }
        catch(Exception e) { throw new IllegalStateException("회의 데이터 읽기 실패",e); }
    }
    void save(Meeting m,Content c) {
        try { m.payload=json.writeValueAsString(c); meetings.save(m); }
        catch(Exception e) { throw new IllegalStateException("회의 저장 실패",e); }
    }
    public List<MeetingView> list() { return meetings.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList(); }
    public MeetingView get(String id) { return view(meetings.findById(id).orElseThrow(MeetingService::missing)); }
    MeetingView view(Meeting m) { return new MeetingView(m.id,m.title,m.status,m.createdAt,content(m),drafts.findByMeetingId(m.id),jobs.findByMeetingIdOrderByCreatedAtDesc(m.id),epics.findByMeetingId(m.id).stream().map(e->new EpicView(e.id,e.projectKey,e.summary,e.status,e.issueKey,e.issueUrl,e.error,e.contentStatus,e.contentError)).toList()); }
    public MeetingView create(CreateMeeting input) {
        MinutesTemplate.validate(input.template());
        var m=new Meeting(); m.title=input.title().trim(); var c=new Content();
        c.occurredAt=Objects.toString(input.occurredAt(),""); c.projectKey=Objects.toString(input.projectKey(),"DEMO");
        c.participants=input.participants()==null?List.of():input.participants(); c.memo=Objects.toString(input.memo(),"");
        c.source=Objects.toString(input.source(),"TEXT"); c.language=Objects.toString(input.language(),"ko");
        c.diarization=input.diarization(); c.template=Objects.toString(input.template(),"일반");
        if(input.transcript()!=null) c.segments=Adapters.parse(input.transcript());
        save(m,c); audits.save(new Audit("MEETING_CREATED",m.id)); return view(m);
    }
    public void addAsset(String id,Asset asset) {
        var m=lock(id); editable(m); var c=content(m); c.assets.add(asset); save(m,c);
        audits.save(new Audit("ASSET_UPLOADED",id));
    }
    public void replaceFailedSpeechAsset(String id,Asset asset){
        var m=lock(id);editable(m);var c=content(m);
        var latest=jobs.findByMeetingIdOrderByCreatedAtDesc(id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("음성 변환에 실패한 회의에서 파일을 교체하세요."));
        require("FAILED".equals(latest.status)&&"TRANSCRIBING".equals(latest.stage)&&c.segments.isEmpty(),"음성 변환에 실패하고 전사 결과가 없는 회의에서만 파일을 교체할 수 있습니다.");
        c.assets=new ArrayList<>(List.of(asset));c.source="MEDIA";c.speakerGroups.clear();save(m,c);
        audits.save(new Audit("FAILED_SPEECH_ASSET_REPLACED",id));
    }
    public MeetingView setTranscriptTiming(String id,TranscriptTiming input) {
        var m=lock(id);editable(m);var c=content(m);
        require(!c.segments.isEmpty(),"음성 기록이 없습니다.");
        require(input.startMs()>=0&&input.startMs()<=86400000&&input.intervalMs()>=1000&&input.intervalMs()<=14400000,"시작 시각과 구간 간격을 확인하세요.");
        require(input.startMs()+input.intervalMs()*c.segments.size()<=86400000,"전체 시간은 24시간 이내로 설정하세요.");
        var timings=new HashMap<String,Long>();var segments=new ArrayList<Segment>();
        for(int i=0;i<c.segments.size();i++){
            var s=c.segments.get(i);long start=input.startMs()+input.intervalMs()*i;
            timings.put(s.id(),start);segments.add(new Segment(s.id(),s.speaker(),start,start+input.intervalMs(),s.text(),true));
        }
        c.segments=segments;c.items=retimeItems(c.items,timings);c.archivedItems=retimeItems(c.archivedItems,timings);
        save(m,c);audits.save(new Audit("TRANSCRIPT_TIMING_UPDATED",id));return view(m);
    }
    public MeetingView setParticipants(String id,ParticipantsInput input){
        var m=lock(id);editable(m);var c=content(m);var names=input.participants().stream().map(String::strip).distinct().toList();
        boolean refreshDocument=c.minutes!=null&&MinutesTemplate.custom(c.template)&&Objects.equals(c.minutes.formatted,MinutesTemplate.render(m.title,c,c.minutes));
        require(names.size()<=100&&names.stream().allMatch(n->!n.isBlank()&&n.length()<=100),"참석자 이름을 확인하세요.");
        var valid=c.segments.stream().map(Segment::id).toList();require(valid.containsAll(input.segmentSpeakers().keySet()),"다른 회의의 발언에는 참석자를 지정할 수 없습니다.");
        require(input.segmentSpeakers().values().stream().allMatch(n->n!=null&&(n.isBlank()||names.contains(n))),"발언에 지정한 참석자를 먼저 추가하세요.");
        c.segments.forEach(s->c.speakerGroups.putIfAbsent(s.id(),s.speaker()));
        c.segments=c.segments.stream().map(s->{String name=input.segmentSpeakers().get(s.id());return name==null?s:new Segment(s.id(),name.isBlank()?c.speakerGroups.get(s.id()):name,s.startMs(),s.endMs(),s.text(),s.estimatedTime());}).toList();
        c.participants=names;syncEvidenceSpeakers(c);
        if(refreshDocument){c.minutes.formatted=MinutesTemplate.render(m.title,c,c.minutes);c.minutes.version++;}
        save(m,c);audits.save(new Audit("PARTICIPANTS_ASSIGNED",id));return view(m);
    }
    void syncEvidenceSpeakers(Content c){
        var names=new HashMap<String,String>();c.segments.forEach(s->names.put(s.id(),s.speaker()));
        c.items=relabelItems(c.items,names);c.archivedItems=relabelItems(c.archivedItems,names);
    }
    static List<Item> relabelItems(List<Item> items,Map<String,String> names){
        return items.stream().map(i->new Item(i.id(),i.kind(),i.title(),i.tasks(),i.checks(),i.acceptanceCriteria(),i.dependencies(),i.assigneeSuggestion(),i.dueDateSuggestion(),i.evidence().stream().map(e->new Evidence(e.segmentId(),e.startMs(),names.getOrDefault(e.segmentId(),e.speaker()),e.quote(),e.estimatedTime())).toList(),i.confidence(),i.inferred())).toList();
    }
    static List<Item> retimeItems(List<Item> items,Map<String,Long> timings){
        return items.stream().map(i->new Item(i.id(),i.kind(),i.title(),i.tasks(),i.checks(),i.acceptanceCriteria(),i.dependencies(),i.assigneeSuggestion(),i.dueDateSuggestion(),
            i.evidence().stream().map(e->timings.containsKey(e.segmentId())?new Evidence(e.segmentId(),timings.get(e.segmentId()),e.speaker(),e.quote(),true):e).toList(),i.confidence(),i.inferred())).toList();
    }
    void editable(Meeting m) {
        require(jobs.findByMeetingIdOrderByCreatedAtDesc(m.id).stream().noneMatch(j->Set.of("QUEUED","RUNNING").contains(j.status)),"분석 중에는 변경할 수 없습니다. 취소 후 다시 시도하세요.");
    }
    public Minutes saveMinutes(String id,Minutes minutes) {
        var m=lock(id); editable(m); var c=content(m); require(c.minutes!=null,"회의록을 먼저 생성하세요.");
        minutes.version=c.minutes.version+1;minutes.generationMode=c.minutes.generationMode;minutes.generationModel=c.minutes.generationModel;
        TimeReferences.clean(minutes);c.minutes=minutes; save(m,c); audits.save(new Audit("MINUTES_EDITED",id)); return minutes;
    }
    public MeetingView setMinutesTemplate(String id,MinutesTemplateInput input){
        var m=lock(id);editable(m);MinutesTemplate.validate(input.template());var c=content(m);
        c.template=input.template();
        if(c.minutes!=null){c.minutes.formatted=MinutesTemplate.render(m.title,c,c.minutes);c.minutes.version++;}
        save(m,c);audits.save(new Audit("MINUTES_TEMPLATE_APPLIED",id));return view(m);
    }
    public List<Draft> generateDrafts(String id) {
        // Fixture helper only. Real API generation is asynchronous and calls Intelligence.tickets.
        require(intelligence.mode().equals("mock"),"실제 AI 티켓은 비동기 생성 API를 사용하세요.");
        var m=lock(id);editable(m);var c=content(m);require(!c.items.isEmpty(),"분석된 업무가 없습니다.");
        var covered=new HashSet<String>();drafts.findByMeetingId(id).forEach(d->covered.addAll(d.analysisItemIds));
        var remaining=c.items.stream().filter(i->!covered.contains(i.id())).toList();
        return applyDraftProposals(m,c,new Adapters.MockIntelligence().tickets(m.title,c.minutes,c.segments,remaining,""),"mock","rules",null,-1);
    }
    public List<Item> allItems(Content c) {
        var items=new LinkedHashMap<String,Item>();c.archivedItems.forEach(i->items.put(i.id(),i));c.items.forEach(i->items.put(i.id(),i));return new ArrayList<>(items.values());
    }
    List<Draft> applyDraftProposals(Meeting m,Content c,List<TicketProposal> proposals,String mode,String model,String targetDraftId,long expectedVersion) {
        var existing=drafts.findByMeetingId(m.id);var all=allItems(c);var valid=all.stream().map(Item::id).toList();
        if(targetDraftId!=null) {
            require(proposals.size()==1,"한 티켓의 AI 검토 결과가 필요합니다.");
            var d=drafts.locked(targetDraftId).orElseThrow(MeetingService::missing);
            require(d.meetingId.equals(m.id)&&Set.of("DRAFT","APPROVED","FAILED").contains(d.status),"AI 정리할 수 없는 티켓 상태입니다.");
            require(d.version==expectedVersion,"AI 정리 중 초안이 변경되었습니다. 현재 편집을 보존했으니 다시 실행하세요.");
            var p=proposals.get(0);require(new HashSet<>(p.analysisItemIds()).equals(d.analysisItemIds),"AI 검토에서 연결 업무가 변경되었습니다.");
            require(p.subtasks().isEmpty()||(Objects.toString(d.parentDraftId,"").isBlank()&&d.parent.isBlank()&&children(d.id).isEmpty()),"기존 하위 티켓은 각각 검토하세요. 이미 계층이 있는 티켓에 하위를 다시 생성할 수 없습니다.");
            d.summary=Objects.toString(d.parentDraftId,"").isBlank()?TicketWording.normalize(TimeReferences.without(p.summary())):SubtaskTitles.compact(p.summary());d.description=TicketDescriptions.normalize(proposalDescription(m.id,p,all,mode,model,!Objects.toString(d.parentDraftId,"").isBlank()),!Objects.toString(d.parentDraftId,"").isBlank(),all,d.analysisItemIds,c.segments);d.status="DRAFT";d.approvedTarget=null;d.publishTarget=null;d.error=null;
            tagRecommendation(d,p,mode);d.generationMode=mode;d.generationModel=model;d.analysisVersion=c.analysisVersion;drafts.save(d);audits.save(new Audit("DRAFT_AI_REVIEWED",d.id));
            saveSubtasks(m,c,d,p,all,mode,model);
        } else {
            var covered=new HashSet<String>();existing.forEach(d->covered.addAll(d.analysisItemIds));
            for(var p:proposals) {
                require(!p.analysisItemIds().isEmpty()&&valid.containsAll(p.analysisItemIds()),"알 수 없는 업무가 티켓에 연결되었습니다.");
                if(p.analysisItemIds().stream().anyMatch(covered::contains))continue;
                var d=new Draft();d.meetingId=m.id;d.projectKey=c.projectKey;d.summary=TicketWording.normalize(TimeReferences.without(p.summary()));d.analysisItemIds.addAll(p.analysisItemIds());
                d.description=TicketDescriptions.normalize(proposalDescription(m.id,p,all,mode,model),false,all,d.analysisItemIds,c.segments);d.generationMode=mode;d.generationModel=model;d.analysisVersion=c.analysisVersion;
                tagRecommendation(d,p,mode);d.duplicateSuspected=existing.stream().anyMatch(old->similar(old.summary,d.summary));drafts.save(d);existing.add(d);covered.addAll(p.analysisItemIds());
                saveSubtasks(m,c,d,p,all,mode,model);
            }
            if(!mode.equals("mock"))fillParagraphChildren(m,c);
            audits.save(new Audit(!mode.equals("mock")?"AI_DRAFTS_GENERATED":"MOCK_DRAFTS_GENERATED",m.id));
        }
        return drafts.findByMeetingId(m.id);
    }
    List<Draft> children(String id){return drafts.findByParentDraftId(id);}
    public List<Draft> structureCandidates(String id){
        var m=lock(id);editable(m);var c=content(m);fillParagraphChildren(m,c);
        audits.save(new Audit("PARAGRAPH_CANDIDATES_STRUCTURED",id));return drafts.findByMeetingId(id);
    }
    public List<Draft> formatDescriptions(String meetingId,Ids input){
        var m=lock(meetingId);editable(m);var c=content(m);var all=allItems(c);
        for(var id:new TreeSet<>(input.ids())){
            var d=drafts.locked(id).orElseThrow(MeetingService::missing);
            require(d.meetingId.equals(meetingId)&&Set.of("DRAFT","APPROVED","FAILED").contains(d.status),"같은 회의의 미등록 후보만 정리할 수 있습니다.");
            String next=TicketDescriptions.normalize(d.description,!Objects.toString(d.parentDraftId,"").isBlank(),all,d.analysisItemIds,c.segments);
            String title=TicketWording.normalize(d.summary);
            if(!next.equals(d.description)||!title.equals(d.summary)){d.summary=title;d.description=next;d.status="DRAFT";d.approvedTarget=null;d.publishTarget=null;drafts.save(d);audits.save(new Audit("DRAFT_DESCRIPTION_FORMATTED",d.id));}
        }
        return drafts.findByMeetingId(meetingId);
    }
    public List<Draft> formatSubtaskTitles(String meetingId,Ids input){
        lock(meetingId);editable(meetings.findById(meetingId).orElseThrow(MeetingService::missing));
        for(var id:new TreeSet<>(input.ids())){
            var d=drafts.locked(id).orElseThrow(MeetingService::missing);
            require(d.meetingId.equals(meetingId)&&!Objects.toString(d.parentDraftId,"").isBlank()&&Set.of("DRAFT","APPROVED","FAILED").contains(d.status),"같은 회의의 미등록 하위업무만 제목을 정리할 수 있습니다.");
            String next=SubtaskTitles.compact(d.summary);
            if(!next.equals(d.summary)){d.summary=next;d.status="DRAFT";d.approvedTarget=null;drafts.save(d);audits.save(new Audit("SUBTASK_TITLE_COMPACTED",d.id));}
        }
        return drafts.findByMeetingId(meetingId);
    }
    public List<Draft> consolidateSubtasks(String meetingId,Ids input){
        var m=lock(meetingId);editable(m);var c=content(m);
        var buckets=new LinkedHashMap<List<String>,List<Draft>>();
        for(var id:new TreeSet<>(input.ids())){
            var d=drafts.locked(id).orElseThrow(MeetingService::missing);
            require(d.meetingId.equals(meetingId)&&!Objects.toString(d.parentDraftId,"").isBlank()&&Set.of("DRAFT","APPROVED","FAILED","CONSOLIDATED").contains(d.status)&&Objects.toString(d.issueKey,"").isBlank()&&!"EXISTING".equals(d.registrationMode),"같은 회의의 미등록 하위업무만 통합할 수 있습니다.");
            if(d.status.equals("CONSOLIDATED")||(d.groupingVersion!=null&&d.groupingVersion>=1))continue;
            var key=List.of(d.parentDraftId,d.projectKey,d.issueType,Objects.toString(d.assignee,""),Objects.toString(d.priority,""),Objects.toString(d.dueDate,""),Objects.toString(d.parent,""));
            buckets.computeIfAbsent(key,k->new ArrayList<>()).add(d);
        }
        var batches=new ArrayList<>(buckets.values());var limits=SubtaskGroups.limits(batches.stream().map(List::size).toList());
        for(int batch=0;batch<batches.size();batch++){
            var rows=batches.get(batch);
            var byProposal=new IdentityHashMap<TicketProposal,Draft>();var proposals=new ArrayList<TicketProposal>();
            for(var d:rows){String text=TicketDescriptions.normalize(d.description,true,allItems(c),d.analysisItemIds,c.segments);
                var p=new TicketProposal(new ArrayList<>(d.analysisItemIds),d.summary,"",paragraphEntries(text,1),List.of(),paragraphEntries(text,2),paragraphEntries(text,3),paragraphEntries(text,4),List.of(),d.labels.contains("ai-recommended"));
                proposals.add(p);byProposal.put(p,d);
            }
            for(var group:SubtaskGroups.groups(proposals,limits.get(batch))){
                var p=SubtaskGroups.combine(group);var keep=byProposal.get(group.get(0));
                String summary=SubtaskTitles.compact(p.summary()),description=TicketDescriptions.childDescription(p);
                require(description.length()<=30000,"통합할 설명이 너무 깁니다. 작업 내용을 줄이지 않고 통합하려면 후보를 나누어 선택하세요.");
                boolean changed=group.size()>1||!summary.equals(keep.summary)||!description.equals(keep.description);
                if(changed){try{keep.consolidationBackup=json.writeValueAsString(keep);}catch(Exception e){throw new IllegalStateException("통합 전 초안 보관 실패",e);}}
                keep.summary=summary;keep.description=description;keep.analysisItemIds=new LinkedHashSet<>(p.analysisItemIds());keep.groupingVersion=1;
                if(changed){keep.status="DRAFT";keep.approvedTarget=null;}
                for(var member:group)keep.labels.addAll(byProposal.get(member).labels);
                drafts.save(keep);for(int i=1;i<group.size();i++){var original=byProposal.get(group.get(i));original.status="CONSOLIDATED";original.consolidatedInto=keep.id;original.approvedTarget=null;drafts.save(original);audits.save(new Audit("SUBTASK_CONSOLIDATED_INTO_"+keep.id,original.id));}
            }
        }
        audits.save(new Audit("SUBTASKS_CONSOLIDATED",meetingId));return drafts.findByMeetingId(meetingId);
    }
    void fillParagraphChildren(Meeting m,Content c){
        var all=allItems(c);
        for(var parent:drafts.findByMeetingId(m.id)){
            if(!Objects.toString(parent.parentDraftId,"").isBlank()||!parent.parent.isBlank()||!Set.of("DRAFT","APPROVED","FAILED").contains(parent.status)||parent.analysisItemIds.isEmpty())continue;
            var linked=all.stream().filter(i->parent.analysisItemIds.contains(i.id())).toList();if(linked.isEmpty())continue;
            var tasks=paragraphEntries(parent.description,2);var checks=paragraphEntries(parent.description,3);
            if(tasks.isEmpty())tasks=linked.stream().flatMap(i->i.tasks().stream()).distinct().toList();
            if(checks.isEmpty())checks=linked.stream().flatMap(i->i.checks().stream()).distinct().toList();
            var proposal=new TicketProposal(new ArrayList<>(parent.analysisItemIds),parent.summary,"상위업무 업무의 하위업무 후보",tasks,checks,List.of(),List.of(),List.of(),List.of(),parent.labels.contains("ai-recommended"));
            saveSubtasks(m,c,parent,proposal,all,parent.generationMode,parent.generationModel,true);
        }
    }
    static List<String> paragraphEntries(String description,int section){
        if(description==null)return List.of();
        var heading=java.util.regex.Pattern.compile("(?m)^\\s*(?:#{1,6}\\s*)?(\\d+)[.)]\\s+.*$").matcher(description);
        int start=-1,end=description.length();
        while(heading.find()){
            if(start>=0){end=heading.start();break;}
            if(Integer.parseInt(heading.group(1))==section)start=heading.end();
        }
        if(start<0)return List.of();
        return description.substring(start,end).lines().map(String::strip).filter(s->!s.isBlank())
            .map(s->s.replaceFirst("^(?:☐|☑|[-*]\\s*\\[[ xX]]|[-*•])\\s*", ""))
            .filter(s->!s.isBlank()).distinct().limit(20).toList();
    }
    void saveSubtasks(Meeting m,Content c,Draft parent,TicketProposal proposal,List<Item> all,String mode,String model){
        saveSubtasks(m,c,parent,proposal,all,mode,model,false);
    }
    void saveSubtasks(Meeting m,Content c,Draft parent,TicketProposal proposal,List<Item> all,String mode,String model,boolean supplement){
        require(proposal.subtasks().size()<=8,"부모당 하위 티켓은 최대 8개입니다.");
        var proposals=new ArrayList<>(proposal.subtasks());
        // Expand newly generated AI parents only. Reviewing an existing hierarchy
        // must preserve its children and must never create grandchildren.
        if(!mode.equals("mock")&&Objects.toString(parent.parentDraftId,"").isBlank()&&parent.parent.isBlank()&&(supplement||children(parent.id).isEmpty())){
            var covered=new HashSet<String>();
            for(var existing:children(parent.id)){
                covered.add(checklistKey(existing.summary));
                boolean concise=existing.description.startsWith("1. 작업목록");
                paragraphEntries(existing.description,concise?1:2).forEach(v->covered.add(checklistKey(v)));
                paragraphEntries(existing.description,concise?4:3).forEach(v->covered.add(checklistKey(v)));
            }
            for(var p:proposals){covered.add(checklistKey(p.summary()));p.tasks().forEach(v->covered.add(checklistKey(v)));p.checks().forEach(v->covered.add(checklistKey(v)));}
            addChecklistSubtasks(proposals,covered,proposal,proposal.tasks(),false);
            addChecklistSubtasks(proposals,covered,proposal,proposal.checks(),true);
        }
        int limit=SubtaskGroups.creationLimit(proposal.tasks().size(),proposal.checks().size());
        if(!mode.equals("mock"))proposals=new ArrayList<>(SubtaskGroups.reduce(proposals,limit));
        for(var p:proposals){
            require(p.subtasks().isEmpty()&&!p.analysisItemIds().isEmpty()&&parent.analysisItemIds.containsAll(p.analysisItemIds()),"하위 티켓의 계층과 연결 근거를 확인하세요.");
            var child=new Draft();child.meetingId=m.id;child.projectKey=parent.projectKey;child.parentDraftId=parent.id;child.issueType="Sub-task";
            child.summary=SubtaskTitles.compact(p.summary());child.analysisItemIds.addAll(p.analysisItemIds());child.description=TicketDescriptions.normalize(proposalDescription(m.id,p,all,mode,model,true),true,all,child.analysisItemIds,c.segments);
            tagRecommendation(child,p,mode);child.generationMode=mode;child.generationModel=model;child.analysisVersion=c.analysisVersion;child.groupingVersion=1;drafts.save(child);
        }
    }
    static void tagRecommendation(Draft draft,TicketProposal proposal,String mode){
        if(!mode.equals("mock")&&proposal.aiRecommended())draft.labels.add("ai-recommended");
    }
    static String checklistKey(String value){return value.strip().replaceAll("\\[(?:AI 추천|제안|확인 필요|작업|확인)\\]\\s*", "").replaceAll("\\s+"," ");}
    static void addChecklistSubtasks(List<TicketProposal> children,Set<String> covered,TicketProposal parent,List<String> entries,boolean check){
        require(entries.size()<=20,"작업·확인 목록은 각각 최대 20개입니다.");
        for(int index=0;index<entries.size();index++){
            String entry=entries.get(index);
            String text=entry.strip();if(text.isBlank()||!covered.add(checklistKey(text)))continue;
            var titles=check?parent.checkTitles():parent.taskTitles();
            String title=(check?"[확인] ":"[작업] ")+SubtaskTitles.compact(index<titles.size()?titles.get(index):text);
            var reviews=check?parent.checkReviews():parent.taskReviews();
            var notes=index<reviews.size()?reviews.get(index):List.of("[제안] 작업 전: "+SubtaskTitles.compact(text)+"의 대상과 선행 조건 확인","[제안] 작업 후: "+SubtaskTitles.compact(text)+"의 처리 결과와 미해결 항목 확인");
            if(title.length()>200)title=title.substring(0,199)+"…";
            children.add(new TicketProposal(parent.analysisItemIds(),title,
                "상위 업무: "+parent.summary()+"\n상위 티켓의 "+(check?"확인해야 하는 항목":"해야 하는 작업 목록")+"에서 분리한 업무입니다.\n원문 항목: "+text,
                List.of(text),
                List.of(),
                List.of(check?"[제안] 확인 결과·미확정 사항·근거가 기록되어 상위 업무에서 검토할 수 있다.":"[제안] 항목의 수행 결과와 남은 조치가 기록되어 상위 업무에서 검토할 수 있다."),
                parent.dependencies(),notes,List.of(),parent.aiRecommended()||text.contains("[제안]")||text.contains("[AI 추천]")));
        }
    }
    static String proposalDescription(String meetingId,TicketProposal p,List<Item> items,String mode,String model) {
        return proposalDescription(meetingId,p,items,mode,model,false);
    }
    static String proposalDescription(String meetingId,TicketProposal p,List<Item> items,String mode,String model,boolean child) {
        var evidence=items.stream().filter(i->p.analysisItemIds().contains(i.id())).flatMap(i->i.evidence().stream()).distinct().toList();
        require(!evidence.isEmpty(),"티켓에 연결할 원문 근거가 없습니다.");
        if(child){String description=TicketDescriptions.childDescription(p);require(description.length()<=30000,"하위 후보 설명이 너무 깁니다.");return description;}
        String result=(p.aiRecommended()?"AI 추천 · 원문 업무를 바탕으로 AI가 제안한 후보입니다. 회의의 확정 지시와 구분해 검토하세요.\n\n":"")+"1. 배경 및 목적\n"+p.background()+"\n\n2. 해야 하는 작업 목록\n"+checklist(p.tasks())
            +"\n\n3. 확인해야 하는 항목\n"+checklist(p.checks())+"\n\n4. 완료 조건 (Acceptance Criteria)\n"+checklist(p.acceptanceCriteria())
            +"\n\n5. 의존성 및 제약사항\n"+(p.dependencies().isEmpty()?"명시된 의존성 없음. 필요한 경우 검토 후 추가하세요.":String.join("\n",p.dependencies()))
            +"\n담당자 및 기한은 사용자 확인 후 설정\n\n6. 회의 근거\n회의: /meetings/"+meetingId+"\n"
            +"__MEETING_EVIDENCE__"
            +"\n7. "+(!mode.equals("mock")?"AI":"Mock")+" 검토 사항\n"+(p.reviewNotes().isEmpty()?"원문, 작업 범위와 완료 조건을 사용자 검토해야 합니다.":String.join("\n",p.reviewNotes()));
        result=TicketDescriptions.normalize(TimeReferences.without(result),child,items,p.analysisItemIds(),List.of());
        require(result.length()<=30000,"AI 티켓 설명이 너무 깁니다. 업무를 나누거나 다시 정리하세요.");return result;
    }
    static boolean similar(String a,String b) {
        var left=new HashSet<>(Arrays.asList(a.split("\\s+"))); var right=new HashSet<>(Arrays.asList(b.split("\\s+")));
        var union=new HashSet<>(left); union.addAll(right); left.retainAll(right);
        return !union.isEmpty() && ((double)left.size()/union.size())>=0.45;
    }
    static String description(String meetingId,Item item) {
        String result=TimeReferences.without("1. 배경 및 목적\n"+item.title()+"\n\n2. 해야 하는 작업 목록\n"+checklist(item.tasks())
            +"\n\n3. 확인해야 하는 항목\n"+checklist(item.checks())+"\n\n4. 완료 조건 (Acceptance Criteria)\n"+checklist(item.acceptanceCriteria())
            +"\n\n5. 의존성 및 제약사항\n"+(item.dependencies().isEmpty()?"명시되지 않음":String.join("\n",item.dependencies()))
            +"\n담당자 및 기한: 미확정\n\n6. 회의 근거\n회의: /meetings/"+meetingId+"\n"
            +item.evidence().stream().map(e->"["+(e.estimatedTime()?"추정 ":"")+formatTime(e.startMs())+"] "+e.speaker()+": "+e.quote()).reduce("",(a,b)->a+b+"\n")
            +"\n※ Mock 규칙 기반 후보. [제안] 항목은 원문 사실과 구분하여 검토하세요.");
        return TicketDescriptions.normalize(result,false,List.of(item),List.of(item.id()),List.of());
    }
    static String checklist(List<String> items) { return items.stream().map(t->"☐ "+t).reduce("",(a,b)->a+b+"\n").stripTrailing(); }
    static String formatTime(long ms) { return String.format("%02d:%02d:%02d",ms/3600000,ms/60000%60,ms/1000%60); }
    public Draft update(String id,DraftInput in) {
        var d=drafts.locked(id).orElseThrow(MeetingService::missing);
        lock(d.meetingId);
        require(!Set.of("PUBLISHED","PUBLISHING","UNKNOWN","CONSOLIDATED").contains(d.status),"등록되었거나 통합 보관된 티켓은 수정할 수 없습니다.");
        var meeting=meetings.findById(d.meetingId).orElseThrow(MeetingService::missing);var c=content(meeting);
        require(c.projectKey.equals(in.projectKey()),"티켓 프로젝트는 회의 프로젝트로 고정됩니다.");
        String incomingDescription=TicketDescriptions.normalize(in.description(),!Objects.toString(d.parentDraftId,"").isBlank(),allItems(c),d.analysisItemIds,c.segments);
        String currentDescription=TicketDescriptions.normalize(d.description,!Objects.toString(d.parentDraftId,"").isBlank(),allItems(c),d.analysisItemIds,c.segments);
        boolean replan=!Objects.equals(d.assignee,Objects.toString(in.assignee(),""))||!Objects.equals(TicketWording.normalize(d.summary),TicketWording.normalize(in.summary()))||!Objects.equals(currentDescription,incomingDescription)||(in.startAt()!=null&&!sameTime(d.startAt,in.startAt()))||(in.endAt()!=null&&!sameTime(d.endAt,in.endAt()));
        apply(d,in); d.status="DRAFT";d.description=TicketDescriptions.normalize(d.description,!Objects.toString(d.parentDraftId,"").isBlank(),allItems(c),d.analysisItemIds,c.segments); d.error=null; d.publishTarget=null; d.approvedTarget=null;drafts.save(d);if(replan)clearAiSchedules(d.meetingId); audits.save(new Audit("DRAFT_EDITED",id)); return d;
    }
    static boolean sameTime(String a,String b){if(Objects.equals(Objects.toString(a,""),Objects.toString(b,"")))return true;try{return java.time.OffsetDateTime.parse(a).toInstant().equals(java.time.OffsetDateTime.parse(b).toInstant());}catch(Exception e){return false;}}
    void apply(Draft d,DraftInput in) {
        boolean clearEpic=!Objects.toString(d.epicKey,"").isBlank()&&(!d.projectKey.equals(in.projectKey())||("EXISTING".equals(in.registrationMode())&&!"EXISTING".equals(d.registrationMode)));
        String registration=in.registrationMode()==null?Objects.toString(d.registrationMode,"NEW"):in.registrationMode();
        String existingKey=in.existingIssueKey()==null?Objects.toString(d.existingIssueKey,""):in.existingIssueKey().strip().toUpperCase(Locale.ROOT);
        require(Set.of("NEW","EXISTING").contains(registration),"등록 방식은 새 이슈 또는 기존 이슈 연결을 선택하세요.");
        if(registration.equals("EXISTING"))require(existingKey.matches("[A-Z][A-Z0-9_]*-[0-9]+")&&existingKey.length()<=100,"연결할 Jira 이슈 키를 입력하세요.");
        d.registrationMode=registration;d.existingIssueKey=registration.equals("EXISTING")?existingKey:"";
        String parentId=in.parentDraftId()==null?Objects.toString(d.parentDraftId,""):in.parentDraftId().strip();
        if(!parentId.isBlank()){
            require(!parentId.equals(d.id),"자신을 부모로 지정할 수 없습니다.");
            var parent=drafts.findById(parentId).orElseThrow(MeetingService::missing);
            require(!Set.of("PUBLISHING","UNKNOWN").contains(parent.status),"부모의 등록 결과를 확인한 뒤 하위 티켓을 추가·수정하세요.");
            require(parent.meetingId.equals(d.meetingId)&&parent.projectKey.equals(in.projectKey()),"부모와 하위 티켓은 같은 회의·프로젝트여야 합니다.");
            require(Objects.toString(parent.parentDraftId,"").isBlank()&&(parent.parent.isBlank()||parent.parent.equals(parent.epicKey))&&children(d.id).isEmpty(),"하위 티켓은 한 단계만 생성할 수 있습니다.");
            require(Objects.toString(in.parent(),"").isBlank()||in.parent().equals(parent.issueKey),"연결된 부모 초안과 다른 Jira 이슈를 지정할 수 없습니다.");
        }
        if(!children(d.id).isEmpty())require(d.projectKey.equals(in.projectKey())&&parentId.isBlank()&&(Objects.toString(in.parent(),"").isBlank()||Objects.equals(in.parent(),d.epicKey)),"하위 티켓이 있는 부모의 프로젝트·계층은 변경할 수 없습니다.");
        require(in.projectKey().matches("[A-Z][A-Z0-9_]{0,19}"),"프로젝트 키 형식이 잘못되었습니다.");
        if(in.dueDate()!=null && !in.dueDate().isBlank()) java.time.LocalDate.parse(in.dueDate());
        d.projectKey=in.projectKey(); d.issueType=in.issueType(); d.summary=TicketWording.normalize(in.summary()); d.description=TicketWording.normalize(in.description());
        d.assignee=Objects.toString(in.assignee(),""); d.priority=Objects.toString(in.priority(),"Medium");
        d.dueDate=Objects.toString(in.dueDate(),""); d.parent=Objects.toString(in.parent(),"");
        if(parentId.isBlank()&&registration.equals("NEW"))d.parent=Objects.toString(d.epicKey,"");
        if(in.assigneeName()!=null)d.assigneeName=in.assigneeName();
        if(in.scheduleMode()!=null){require(Set.of("AI","MANUAL").contains(in.scheduleMode()),"기한 설정 방식을 확인하세요.");d.scheduleMode=in.scheduleMode();if(in.scheduleMode().equals("MANUAL"))d.scheduleReason="직접 설정한 일정입니다.";}
        if(in.startAt()!=null||in.endAt()!=null){
            String start=Objects.toString(in.startAt(),""),end=Objects.toString(in.endAt(),"");require(start.isBlank()==end.isBlank(),"시작과 종료 시각을 모두 입력하세요.");
            if(!start.isBlank()){
                java.time.OffsetDateTime begin,finish;try{begin=java.time.OffsetDateTime.parse(start);finish=java.time.OffsetDateTime.parse(end);}catch(Exception e){throw new IllegalArgumentException("시작·종료 시각 형식을 확인하세요.");}
                require(finish.isAfter(begin),"종료 시각은 시작 시각 이후여야 합니다.");d.dueDate=finish.toLocalDate().toString();
            }
            d.startAt=start;d.endAt=end;
        }
        if(in.estimatedMinutes()!=null){require(in.estimatedMinutes()>=0&&in.estimatedMinutes()<=19200,"예상 작업시간은 0~19,200분으로 입력하세요.");d.estimatedMinutes=in.estimatedMinutes();}
        if(in.dependencyDraftIds()!=null){
            require(in.dependencyDraftIds().size()<=200,"선행 업무가 너무 많습니다.");
            for(var depId:in.dependencyDraftIds()){var dep=drafts.findById(depId).orElseThrow(MeetingService::missing);require(!depId.equals(d.id)&&dep.meetingId.equals(d.meetingId),"같은 회의의 다른 업무만 선행 업무로 지정하세요.");}
            d.dependencyDraftIds=new LinkedHashSet<>(in.dependencyDraftIds());
        }
        if(clearEpic){if(d.parent.equals(d.epicKey))d.parent="";d.epicKey="";}
        d.parentDraftId=parentId;
        d.labels=new LinkedHashSet<>(in.labels()==null?List.of():in.labels());
        if(in.analysisItemIds()!=null) {
            var valid=allItems(content(meetings.findById(d.meetingId).orElseThrow(MeetingService::missing))).stream().map(Item::id).toList();
            require(valid.containsAll(in.analysisItemIds()),"다른 회의의 업무 항목은 연결할 수 없습니다.");
            d.analysisItemIds=new LinkedHashSet<>(in.analysisItemIds());
        }
    }
    public Draft addDraft(String meetingId,DraftInput input) {
        lock(meetingId); var d=new Draft(); d.meetingId=meetingId; apply(d,input);
        audits.save(new Audit("DRAFT_ADDED",d.id)); return drafts.save(d);
    }
    public void deleteDraft(String id) {
        var d=drafts.locked(id).orElseThrow(MeetingService::missing);
        lock(d.meetingId);
        require(!Set.of("PUBLISHED","PUBLISHING","UNKNOWN").contains(d.status),"등록된 티켓은 삭제할 수 없습니다.");
        require(children(id).isEmpty(),"하위 티켓을 먼저 삭제하세요.");
        drafts.delete(d);clearAiSchedules(d.meetingId); audits.save(new Audit("DRAFT_DELETED",id));
    }
    void clearAiSchedules(String meetingId){var all=drafts.findByMeetingId(meetingId);var ids=new HashSet<String>();all.forEach(d->ids.add(d.id));for(var d:all)if(SchedulingService.editable(d)){if(d.dependencyDraftIds!=null)d.dependencyDraftIds.removeIf(id->!ids.contains(id));if("AI".equals(d.scheduleMode)){d.startAt="";d.endAt="";d.dueDate="";d.estimatedMinutes=0;d.scheduleReason="업무 또는 담당자가 변경되어 AI 일정 재설정이 필요합니다.";SchedulingService.invalidate(d);}drafts.save(d);}}
    public List<Draft> assignChildren(String id,JiraUser user){
        var root=drafts.locked(id).orElseThrow(MeetingService::missing);lock(root.meetingId);
        require(Objects.toString(root.parentDraftId,"").isBlank()&&SchedulingService.editable(root),"미등록 상위업무에서 담당자를 일괄 적용하세요.");
        var group=new ArrayList<Draft>();group.add(root);group.addAll(children(id));
        require(group.stream().allMatch(SchedulingService::editable),"이미 등록되었거나 등록 중인 하위업무가 있습니다. 해당 Jira 업무는 직접 변경하세요.");
        if(group.stream().allMatch(d->user.accountId().equals(d.assignee)))return group;
        for(var d:group){d.assignee=user.accountId();d.assigneeName=user.displayName();SchedulingService.invalidate(d);drafts.save(d);}
        clearAiSchedules(root.meetingId);audits.save(new Audit("ASSIGNEE_APPLIED_TO_CHILDREN",id));return group;
    }
    public List<Draft> applyChildSchedule(String id,ApplyChildSchedule input){
        var root=drafts.locked(id).orElseThrow(MeetingService::missing);var meeting=lock(root.meetingId);editable(meeting);
        require(Objects.toString(root.parentDraftId,"").isBlank()&&SchedulingService.editable(root),"미등록 상위업무에서 기한을 일괄 적용하세요.");
        var group=new ArrayList<Draft>();group.add(root);group.addAll(children(id));require(group.size()>1,"적용할 하위업무가 없습니다.");
        require(group.stream().allMatch(SchedulingService::editable),"이미 등록되었거나 등록 중인 하위업무가 있어 기한을 일괄 변경할 수 없습니다.");
        require(Set.of("MANUAL","AI").contains(input.scheduleMode()),"기한 설정 방식을 확인하세요.");
        if("AI".equals(input.scheduleMode())&&group.stream().allMatch(d->"AI".equals(d.scheduleMode)))return group;
        String start=Objects.toString(input.startAt(),""),end=Objects.toString(input.endAt(),""),due=Objects.toString(input.dueDate(),"");
        if(input.scheduleMode().equals("MANUAL")){
            require(start.isBlank()==end.isBlank(),"시작과 종료 시각을 모두 지정하세요.");
            if(!start.isBlank()){
                java.time.OffsetDateTime begin,finish;try{begin=java.time.OffsetDateTime.parse(start);finish=java.time.OffsetDateTime.parse(end);}catch(Exception e){throw new IllegalArgumentException("시작·종료 시각 형식을 확인하세요.");}
                require(finish.isAfter(begin),"종료 시각은 시작 시각 이후여야 합니다.");due=finish.toLocalDate().toString();
            }else{require(!due.isBlank(),"하위에 적용할 시작·종료 일시를 입력하세요.");try{java.time.LocalDate.parse(due);}catch(Exception e){throw new IllegalArgumentException("기한 날짜를 확인하세요.");}}
        }else{start="";end="";due="";}
        for(var d:group){d.scheduleMode=input.scheduleMode();d.startAt=start;d.endAt=end;d.dueDate=due;if(input.scheduleMode().equals("AI"))d.estimatedMinutes=0;d.scheduleReason=input.scheduleMode().equals("AI")?"하위업무에 AI 방식을 적용했습니다. 업무 전체 AI 일정 자동 설정에서 업무별 기간을 계산하세요.":"상위업무의 직접 설정 기한을 함께 적용했습니다.";SchedulingService.invalidate(d);drafts.save(d);}
        clearAiSchedules(root.meetingId);audits.save(new Audit("SCHEDULE_APPLIED_TO_CHILDREN",id));return group;
    }
    public List<Draft> approve(List<String> ids) {
        String approvedTarget=jira.snapshot().target();
        var result=new ArrayList<Draft>();
        for(var id:new TreeSet<>(ids)) {
            var d=drafts.locked(id).orElseThrow(MeetingService::missing);
            require(Set.of("DRAFT","APPROVED","FAILED").contains(d.status),"승인할 수 없는 티켓입니다.");
            require(!d.summary.isBlank()&&!d.description.isBlank(),"제목과 설명이 필요합니다.");
            ScheduleDetails.ready(d);
            d.status="APPROVED"; d.approvedTarget=approvedTarget; result.add(drafts.save(d)); audits.save(new Audit("DRAFT_APPROVED",id));
        }
        return result;
    }
    public List<Draft> merge(List<String> ids) {
        require(new HashSet<>(ids).size()>=2,"병합할 후보를 두 개 이상 선택하세요.");
        var selected=new ArrayList<Draft>();
        for(String id:new TreeSet<>(ids)) selected.add(drafts.locked(id).orElseThrow(MeetingService::missing));
        var first=selected.get(0);
        lock(first.meetingId);
        require(selected.stream().noneMatch(d->"EXISTING".equals(d.registrationMode)),"기존 이슈 연결 후보는 병합할 수 없습니다. 새 이슈 발급으로 변경·저장한 뒤 병합하세요.");
        require(selected.stream().allMatch(d->d.meetingId.equals(first.meetingId)&&Set.of("DRAFT","APPROVED","FAILED").contains(d.status)),"같은 회의의 미등록 후보만 병합할 수 있습니다.");
        require(selected.stream().allMatch(d->Objects.toString(d.parentDraftId,"").isBlank()&&children(d.id).isEmpty()),"부모·하위 티켓은 병합할 수 없습니다.");
        var merged=new Draft(); merged.meetingId=first.meetingId; merged.projectKey=first.projectKey;
        merged.summary=first.summary+" 외 "+(selected.size()-1)+"건";
        merged.description=selected.stream().map(d->d.description).reduce("",(a,b)->a+b+"\n\n");
        selected.forEach(d->merged.analysisItemIds.addAll(d.analysisItemIds));
        drafts.save(merged); drafts.deleteAll(selected);clearAiSchedules(first.meetingId); audits.save(new Audit("DRAFTS_MERGED",merged.id));
        return drafts.findByMeetingId(first.meetingId);
    }
    public List<Draft> split(String id) {
        var d=drafts.locked(id).orElseThrow(MeetingService::missing);
        require(!"EXISTING".equals(d.registrationMode),"기존 이슈 연결 후보는 분할할 수 없습니다. 새 이슈 발급으로 변경·저장한 뒤 분할하세요.");
        require(Set.of("DRAFT","APPROVED","FAILED").contains(d.status),"미등록 후보만 분할할 수 있습니다.");
        require(Objects.toString(d.parentDraftId,"").isBlank()&&children(d.id).isEmpty(),"부모·하위 티켓은 분할할 수 없습니다.");
        var c=content(lock(d.meetingId)); var related=allItems(c).stream().filter(i->d.analysisItemIds.contains(i.id())).toList();
        require(related.size()>1,"여러 업무가 연결된 병합 후보만 분할할 수 있습니다. 단일 후보는 추가 기능으로 나누세요.");
        for(var item:related) {
            var child=new Draft(); child.meetingId=d.meetingId; child.projectKey=d.projectKey;
            child.summary=TicketWording.normalize(item.title()); child.description=TicketDescriptions.normalize(description(d.meetingId,item),false,allItems(c),List.of(item.id()),c.segments); child.analysisItemIds.add(item.id()); drafts.save(child);
        }
        drafts.delete(d);clearAiSchedules(d.meetingId); audits.save(new Audit("DRAFT_SPLIT",id)); return drafts.findByMeetingId(d.meetingId);
    }
}
