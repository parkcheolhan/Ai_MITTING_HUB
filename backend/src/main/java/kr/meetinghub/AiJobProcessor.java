package kr.meetinghub;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import kr.meetinghub.Domain.*;

/** Remote model calls never hold a database transaction or block job cancellation. */
@Service
public class AiJobProcessor {
    final MeetingService service;final Jobs jobs;final TransactionTemplate tx;final Adapters.SpeechToText speech;
    final SchedulingService scheduling;
    @org.springframework.beans.factory.annotation.Autowired ConfluencePublisher confluence;
    AiJobProcessor(MeetingService service,Jobs jobs,TransactionTemplate tx,Adapters.SpeechToText speech,SchedulingService scheduling){this.service=service;this.jobs=jobs;this.tx=tx;this.speech=speech;this.scheduling=scheduling;}
    record Input(Meeting meeting,Content content,Draft draft,List<Item> selected,boolean hierarchyExists) {}
    static List<String> stages(String type){
        return switch(type){
            case "TRANSCRIBE"->List.of("TRANSCRIBING","REVIEWING_TRANSCRIPT");case "MINUTES"->List.of("GENERATING_MINUTES");
            case "TRANSCRIPT_REVIEW"->List.of("REVIEWING_TRANSCRIPT");case "SPEAKERS"->List.of("IDENTIFYING_SPEAKERS");
            case "ANALYZE"->List.of("ANALYZING","GENERATING_TICKETS");case "TICKETS","REVIEW"->List.of("GENERATING_TICKETS");
            default->List.of("TRANSCRIBING","REVIEWING_TRANSCRIPT","GENERATING_MINUTES","ANALYZING","GENERATING_TICKETS");
        };
    }
    public void run(String id){
        try {
            var initial=jobs.findById(id).orElseThrow();
            if(!initial.status.equals("QUEUED"))return;
            if(initial.type.equals("SCHEDULE")){scheduling.run(id);return;}
            var ai=initial.type.equals("SPEAKERS")?new Adapters.MockIntelligence():service.intelligence().snapshot();
            var stages=stages(initial.type);
            for(int index=initial.nextStageIndex;index<stages.size();index++){
                String stage=stages.get(index);int progress=10+index*80/stages.size();
                var input=tx.execute(s->{
                    var j=jobs.locked(id).orElseThrow();if(j.status.equals("CANCELLED"))return null;
                    j.status="RUNNING";j.stage=stage;j.progress=progress;jobs.save(j);
                    var m=service.lock(j.meetingId);m.status=stage;service.meetings().save(m);var c=service.content(m);
                    if(!stage.equals("TRANSCRIBING"))MeetingService.require(!c.segments.isEmpty(),"전사문이 없습니다.");
                    Draft draft=null;List<Item> selected=c.items;
                    if(stage.equals("GENERATING_TICKETS")){
                        if(j.targetDraftId!=null){
                            draft=service.drafts().findById(j.targetDraftId).orElseThrow(MeetingService::missing);
                            MeetingService.require(Set.of("DRAFT","APPROVED","FAILED").contains(draft.status),"미등록 초안만 AI 정리할 수 있습니다.");
                            var ids=new HashSet<>(draft.analysisItemIds);selected=service.allItems(c).stream().filter(i->ids.contains(i.id())).toList();
                            MeetingService.require(!selected.isEmpty()&&selected.size()==ids.size(),"원문에 연결된 업무가 있는 초안만 AI 정리할 수 있습니다.");
                        }else{
                            var covered=new HashSet<String>();service.drafts().findByMeetingId(m.id).forEach(d->covered.addAll(d.analysisItemIds));
                            selected=c.items.stream().filter(i->!covered.contains(i.id())).toList();
                        }
                    }
                    return new Input(m,c,draft,selected,draft!=null&&(!Objects.toString(draft.parentDraftId,"").isBlank()||!draft.parent.isBlank()||!service.children(draft.id).isEmpty()));
                });
                if(input==null)return;
                Object computed=switch(stage){
                    case "TRANSCRIBING"->speech.transcribe(input.content(),()->jobs.findById(id).map(j->j.status.equals("CANCELLED")).orElse(true));
                    case "IDENTIFYING_SPEAKERS"->speech.identifySpeakers(input.content(),()->jobs.findById(id).map(j->j.status.equals("CANCELLED")).orElse(true));
                    case "REVIEWING_TRANSCRIPT"->ai.reviewTranscript(input.meeting().title,input.content().segments,()->jobs.findById(id).map(j->j.status.equals("CANCELLED")).orElse(true));
                    case "GENERATING_MINUTES"->ai.minutes(input.meeting().title,input.content().segments);
                    case "ANALYZING"->ai.analyze(input.content().segments);
                    case "GENERATING_TICKETS"->input.selected().isEmpty()?List.<TicketProposal>of():ai.tickets(input.meeting().title,input.content().minutes,input.content().segments,input.selected(),
                        input.draft()==null?"":"하위업무를 생성하지 말고 subtasks는 반드시 빈 목록으로 반환하세요. 세부 내용은 작업의 체크리스트에 작성하세요.\n기존 초안 제목: "+input.draft().summary+"\n기존 설명:\n"+input.draft().description);
                    default->throw new IllegalArgumentException("알 수 없는 분석 단계입니다.");
                };
                boolean waiting=persist(id,stage,input,computed,ai,index+1,stages.size());
                if(Set.of("GENERATING_MINUTES","GENERATING_TICKETS").contains(stage)&&jobs.findById(id).map(j->!j.status.equals("CANCELLED")).orElse(false))confluence.autoSync(input.meeting().id);
                if(waiting)return;
            }
            tx.executeWithoutResult(s->{
                var j=jobs.locked(id).orElseThrow();if(j.status.equals("CANCELLED"))return;
                j.status="COMPLETED";j.progress=100;jobs.save(j);var m=service.lock(j.meetingId);m.status="REVIEW_PENDING";service.meetings().save(m);
                service.audits().save(new Audit("JOB_COMPLETED",id));
            });
        }catch(Exception e){fail(id,e instanceof IllegalArgumentException?e.getMessage():"AI 처리에 실패했습니다. 결과를 덮어쓰지 않았습니다. 입력 및 설정을 확인하세요.");}
    }
    @SuppressWarnings("unchecked")
    boolean persist(String id,String stage,Input input,Object computed,Adapters.Intelligence ai,int nextStage,int totalStages){
        return Boolean.TRUE.equals(tx.execute(s->{
            var j=jobs.locked(id).orElseThrow();if(j.status.equals("CANCELLED"))return true;
            if(input.draft()!=null)service.drafts().locked(input.draft().id).orElseThrow(MeetingService::missing);
            var m=service.lock(j.meetingId);var c=service.content(m);
            switch(stage){
                case "REVIEWING_TRANSCRIPT"->{
                    MeetingService.require(c.segments.equals(input.content().segments),"교정 중 음성 기록이 변경되어 적용을 중단했습니다.");
                    MeetingService.applyTranscriptText(c,(List<Segment>)computed);
                    c.transcriptReviewRevision=c.transcriptRevision;c.transcriptReviewMode=ai.mode();
                    c.transcriptReviewedSegments=ai.mode().equals("mock")?0:c.segments.size();
                }
                case "TRANSCRIBING"->{c.segments=(List<Segment>)computed;c.segments.forEach(segment->c.speakerGroups.putIfAbsent(segment.id(),segment.speaker()));c.diarization=c.segments.stream().anyMatch(segment->segment.speaker().matches(".*화자 [0-9]+"));}
                case "IDENTIFYING_SPEAKERS"->{c.segments=(List<Segment>)computed;c.diarization=true;c.speakerGroups.clear();c.segments.forEach(segment->c.speakerGroups.put(segment.id(),segment.speaker()));service.syncEvidenceSpeakers(c);}
                case "GENERATING_MINUTES"->{
                    var minutes=(Minutes)computed;minutes.version=c.minutes==null?1:c.minutes.version+1;
                    minutes.generationMode=ai.mode();minutes.generationModel=ai.model();
                    TimeReferences.clean(minutes);minutes.formatted=MinutesTemplate.render(m.title,c,minutes);c.minutes=minutes;
                }
                case "ANALYZING"->{
                    var previous=service.allItems(c);var next=(List<Item>)computed;
                    var reused=new HashSet<String>();
                    var stable=next.stream().map(item->{
                        var old=previous.stream().filter(i->!reused.contains(i.id())&&i.kind().equals(item.kind())&&new HashSet<>(i.evidence()).equals(new HashSet<>(item.evidence()))).findFirst();
                        old.ifPresent(i->reused.add(i.id()));
                        return old.map(i->new Item(i.id(),item.kind(),item.title(),item.tasks(),item.checks(),item.acceptanceCriteria(),item.dependencies(),item.assigneeSuggestion(),item.dueDateSuggestion(),item.evidence(),item.confidence(),item.inferred())).orElse(item);
                    }).toList();
                    var referenced=new HashSet<String>();service.drafts().findByMeetingId(m.id).forEach(d->referenced.addAll(d.analysisItemIds));
                    var currentIds=new HashSet<String>();stable.forEach(i->currentIds.add(i.id()));
                    c.archivedItems=previous.stream().filter(i->referenced.contains(i.id())&&!currentIds.contains(i.id())).toList();
                    c.items=stable;c.analysisMode=ai.mode();c.analysisModel=ai.model();c.analysisVersion++;
                }
                case "GENERATING_TICKETS"->service.applyFlatDraftProposals(m,c,(List<TicketProposal>)computed,ai.mode(),ai.model(),input.draft()==null?null:input.draft().id,input.draft()==null?-1:input.draft().version);
                default->throw new IllegalArgumentException("알 수 없는 분석 단계입니다.");
            }
            j.nextStageIndex=nextStage;j.pausedTranscriptRevision=c.transcriptRevision;
            boolean waiting="CONFIRM".equals(j.advanceMode)&&nextStage<totalStages;
            if(waiting){j.status="WAITING_CONFIRMATION";m.status="WAITING_CONFIRMATION";service.audits().save(new Audit("JOB_STAGE_WAITING",id));}
            jobs.save(j);service.save(m,c);service.audits().save(new Audit(!ai.mode().equals("mock")?"AI_"+stage:"MOCK_"+stage,m.id));
            return waiting;
        }));
    }
    void fail(String id,String message){
        tx.executeWithoutResult(s->{
            var j=jobs.locked(id).orElseThrow();if(j.status.equals("CANCELLED"))return;
            j.status="FAILED";j.errorMessage=message;jobs.save(j);var m=service.lock(j.meetingId);m.status="FAILED";service.meetings().save(m);
        });
    }
}
