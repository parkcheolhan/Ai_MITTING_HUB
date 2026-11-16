package kr.meetinghub;

import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ai-jobs;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
@Import(AiJobWorkflowTest.Config.class)
class AiJobWorkflowTest {
    @Autowired MeetingService service;@Autowired JobService worker;@Autowired ScenarioAi ai;
    @TestConfiguration static class Config {@Bean @Primary ScenarioAi scenarioAi(){return new ScenarioAi();}}
    static class ScenarioAi implements Adapters.Intelligence {
        final ObjectMapper json=new ObjectMapper();
        volatile String blocking="";volatile boolean invalidEvidence,variant,noWork;
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        void reset(){blocking="";invalidEvidence=false;variant=false;noWork=false;entered=new CountDownLatch(1);release=new CountDownLatch(1);}
        public String mode(){return "openai";}public String model(){return "unit-ai-model";}
        OpenAiIntelligence delegate(){return new OpenAiIntelligence(new AiConfig("openai",model(),"fake-workflow-secret"),json,(key,body)->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var request=json.readTree(body);String name=request.path("text").path("format").path("name").asText();
            var input=json.readTree(request.path("input").asText());
            if(name.equals(blocking)){entered.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new AssertionError("AI test latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}}
            Object output;
            if(name.equals("meeting_minutes"))output=Map.of("overview","환경 검증 회의","summary","접근 권한과 로그인 오류를 논의했습니다.","discussion","운영 권한 및 로그인 검증 논의","decisions","명시된 결정 없음","openQuestions","검증 기한 미확정","followUp","환경 검증과 로그인 오류 수정");
            else if(name.equals("meeting_actions")){
                var rows=new ArrayList<Object>();
                for(var segment:input.path("transcript")){
                    if(noWork)continue;
                    String quote=segment.path("text").asText();if(variant)quote=quote.substring(0,Math.min(4,quote.length()));if(invalidEvidence)quote="원문에 없는 가짜 근거";
                    var row=new LinkedHashMap<String,Object>();row.put("kind","ACTION");row.put("title","AI가 해석한 업무 "+rows.size());row.put("tasks",List.of("운영 API 응답과 접근 권한 점검"));
                    row.put("checks",List.of("[제안] 환경별 권한 차이를 검증"));row.put("acceptanceCriteria",List.of("[제안] 결과 기록"));row.put("dependencies",List.of());
                    row.put("assigneeSuggestion",null);row.put("dueDateSuggestion",null);row.put("confidence",0.8);row.put("inferred",true);
                    row.put("evidence",List.of(Map.of("segmentId",segment.path("id").asText(),"quote",quote)));rows.add(row);
                }
                output=Map.of("items",rows);
            }else if(name.equals("jira_ticket_review")){
                var ids=new ArrayList<String>();input.path("analysisItems").forEach(i->ids.add(i.path("id").asText()));
                String title=input.path("existingDraft").asText().isBlank()?"AI가 정리한 환경 검증":"AI가 재검토한 환경 검증";
                output=Map.of("tickets",List.of(Map.of("analysisItemIds",ids,"summary",title,"background","환경 점검 발언을 실행 가능한 검증 업무로 정리합니다.",
                    "tasks",List.of("운영 API 응답과 접근 권한 점검"),"checks",List.of("[제안] 개발·운영 접근 차이 검증"),"acceptanceCriteria",List.of("[제안] 검증 결과 로그 첨부"),
                    "dependencies",List.of(),"reviewNotes",List.of("담당자와 기한은 미확정입니다."))));
            }else throw new AssertionError("Unknown operation "+name);
            return OpenAiIntelligenceTest.reply(json,output);
        });}
        public Minutes minutes(String title,List<Segment> segments){return delegate().minutes(title,segments);}
        public List<Item> analyze(List<Segment> segments){return delegate().analyze(segments);}
        public List<TicketProposal> tickets(String title,Minutes minutes,List<Segment> segments,List<Item> items,String existing){return delegate().tickets(title,minutes,segments,items,existing);}
    }
    @BeforeEach void reset(){ai.reset();}
    @Test void assignsVoicesAndKeepsListeningParticipantsAndEvidence(){
        var m=analyzed();var original=m.content().segments;
        var mappings=new HashMap<String,String>();original.forEach(s->mappings.put(s.id(),"김민수"));
        var updated=service.setParticipants(m.id(),new ParticipantsInput(List.of("김민수","참관인"),mappings));
        assertThat(updated.content().participants).containsExactly("김민수","참관인");
        assertThat(updated.content().segments).extracting(Segment::id).containsExactlyElementsOf(original.stream().map(Segment::id).toList());
        assertThat(updated.content().segments).allMatch(s->s.speaker().equals("김민수"));
        assertThat(updated.content().items).allSatisfy(i->assertThat(i.evidence()).allMatch(e->e.speaker().equals("김민수")));
        assertThat(updated.content().speakerGroups.get(original.get(0).id())).isEqualTo(original.get(0).speaker());
        assertThatThrownBy(()->service.setParticipants(m.id(),new ParticipantsInput(List.of("김민수"),Map.of("foreign-segment","김민수")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.setParticipants(m.id(),new ParticipantsInput(List.of("김민수"),Map.of(original.get(0).id(),"없는참석자")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void existingMinutesCanUseUploadedTemplateAndKeepItForRegeneration(){
        var m=analyzed();int version=m.content().minutes.version;
        var updated=service.setMinutesTemplate(m.id(),new MinutesTemplateInput("# 사내 회의록\n{{회의명}}\n## 결정 사항\n{{결정사항}}\n{{요약}}"));
        assertThat(updated.content().minutes.formatted).contains("# 사내 회의록",m.title(),updated.content().minutes.summary).doesNotContain("{{");
        assertThat(updated.content().minutes.version).isEqualTo(version+1);assertThat(updated.drafts()).hasSize(m.drafts().size());
        assertThatThrownBy(()->service.setMinutesTemplate(m.id(),new MinutesTemplateInput("잘못된 양식"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(m.id()).content().template).isEqualTo(updated.content().template);
        complete(worker.start(m.id(),"MINUTES"));assertThat(service.get(m.id()).content().minutes.formatted).startsWith("# 사내 회의록");
        var basic=service.setMinutesTemplate(m.id(),new MinutesTemplateInput("일반"));assertThat(basic.content().minutes.formatted).isEmpty();
    }
    @Test void noWorkCompletesWithoutTickets(){
        ai.noWork=true;var m=created();complete(worker.start(m.id(),"ANALYZE"));
        assertThat(service.get(m.id()).content().items).isEmpty();assertThat(service.get(m.id()).drafts()).isEmpty();
    }
    @Test void cancellingAutomaticTicketStageDoesNotSaveLateCandidates()throws Exception{
        var m=created();ai.blocking="jira_ticket_review";var job=worker.start(m.id(),"ANALYZE");
        try{assertThat(ai.entered.await(5,TimeUnit.SECONDS)).isTrue();worker.cancel(job.id);}
        finally{ai.release.countDown();}
        assertThat(terminal(job).status).isEqualTo("CANCELLED");assertThat(service.get(m.id()).drafts()).isEmpty();
    }
    @Test void analysisAutomaticallyRunsAiTicketDesignAndCreatesHierarchyWithoutAnotherRequest(){
        var m=created();complete(worker.start(m.id(),"ANALYZE"));
        var result=service.get(m.id());assertThat(result.drafts()).hasSize(2);
        var parent=result.drafts().stream().filter(d->d.parentDraftId.isBlank()).findFirst().orElseThrow();
        assertThat(parent.summary).isEqualTo("AI가 정리한 환경 검증");
        assertThat(service.children(parent.id)).hasSize(1);
        assertThat(result.drafts()).allMatch(d->d.status.equals("DRAFT")&&d.issueKey==null);
        complete(worker.start(m.id(),"ANALYZE"));assertThat(service.get(m.id()).drafts()).hasSize(2);
    }
    @Test void batchTimingUpdatesTranscriptAndEvidenceWithoutChangingText() {
        var m=analyzed();var original=m.content().segments;
        var updated=service.setTranscriptTiming(m.id(),new TranscriptTiming(60000,30000));
        assertThat(updated.content().segments).extracting(Segment::startMs).containsExactly(60000L,90000L);
        assertThat(updated.content().segments).extracting(Segment::text).containsExactlyElementsOf(original.stream().map(Segment::text).toList());
        assertThat(updated.content().segments).allMatch(Segment::estimatedTime);
        assertThat(updated.content().items.get(0).evidence().get(0).startMs()).isEqualTo(60000);
        assertThat(updated.content().items.get(0).evidence().get(0).estimatedTime()).isTrue();
        assertThatThrownBy(()->service.setTranscriptTiming(m.id(),new TranscriptTiming(86399000,30000))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(m.id()).content().segments).extracting(Segment::startMs).containsExactly(60000L,90000L);
    }
    @Test void uploadedTemplateSurvivesGenerationEditingAndRegeneration() {
        var m=service.create(new CreateMeeting("양식 회의","2026-11-08T10:00",List.of("이지은"),"DEMO","메모",
            "이지은: 운영 접근 권한을 확인하겠습니다.","TEXT","ko",false,"# {{회의명}}\n{{참석자}}\n## 결정\n{{결정사항}}\n## 요약\n{{요약}}"));
        complete(worker.start(m.id(),"PIPELINE"));
        var minutes=service.get(m.id()).content().minutes;
        assertThat(minutes.formatted).startsWith("# 양식 회의\n이지은\n## 결정\n").contains("접근 권한과 로그인 오류를 논의했습니다.").doesNotContain("{{");
        minutes.formatted="사용자가 수정한 회사 회의록";service.saveMinutes(m.id(),minutes);
        assertThat(service.get(m.id()).content().minutes.formatted).isEqualTo("사용자가 수정한 회사 회의록");
        complete(worker.start(m.id(),"MINUTES"));
        assertThat(service.get(m.id()).content().minutes.formatted).startsWith("# 양식 회의");
        assertThat(service.get(m.id()).content().minutes.version).isEqualTo(3);
    }
    Job terminal(Job job){
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<end){var state=service.jobs().findById(job.id).orElseThrow();if(Set.of("COMPLETED","FAILED","CANCELLED").contains(state.status))return state;
            try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
        throw new AssertionError("job timeout");
    }
    void complete(Job job){var state=terminal(job);assertThat(state.status).as("job error: %s",state.errorMessage).isEqualTo("COMPLETED");}
    MeetingView created(){return service.create(new CreateMeeting("AI 처리 검증","",List.of("이지은"),"DEMO","",
        "[00:12:34] 이지은: QA 전에 운영 접근 권한을 확인하겠습니다.\n[00:14:00] 김민수: 로그인 오류 수정 작업을 진행합시다.","TEXT","ko",false,"일반"));}
    MeetingView analyzed(){var m=created();complete(worker.start(m.id(),"PIPELINE"));return service.get(m.id());}
    Draft draft(MeetingView m){complete(worker.start(m.id(),"TICKETS"));return service.get(m.id()).drafts().get(0);}
    DraftInput edit(Draft d,String summary,String description){return new DraftInput(d.projectKey,"Task",summary,description,"account-123","High","2026-11-01",List.of("reviewed"),"",new ArrayList<>(d.analysisItemIds));}
    @Test void aiWritesAnalysisAndJiraSectionsThenReviewRevokesApproval() {
        var m=analyzed();assertThat(m.content().analysisMode).isEqualTo("openai");assertThat(m.content().minutes.generationMode).isEqualTo("openai");
        assertThat(m.content().items).hasSize(2);var d=draft(m);
        assertThat(d.generationMode).isEqualTo("openai");assertThat(d.description).contains("개발·운영 접근 차이 검증","검증 결과 로그 첨부","AI 검토 사항").doesNotContain("[00:12:34]");
        assertThat(d.status).isEqualTo("DRAFT");assertThat(d.issueKey).isNull();
        service.update(d.id,edit(d,"사용자 초안","사용자가 편집한 설명"));service.approve(List.of(d.id));
        complete(worker.review(d.id));var reviewed=service.drafts().findById(d.id).orElseThrow();
        assertThat(reviewed.status).isEqualTo("DRAFT");assertThat(reviewed.approvedTarget).isNull();
        assertThat(reviewed.summary).isEqualTo("AI가 재검토한 환경 검증");assertThat(reviewed.projectKey).isEqualTo(d.projectKey);
        assertThat(reviewed.assignee).isEqualTo("account-123");assertThat(reviewed.dueDate).isEqualTo("2026-11-01");assertThat(reviewed.labels).contains("reviewed");
        complete(worker.start(m.id(),"TICKETS"));assertThat(service.get(m.id()).drafts()).hasSize(2);
    }
    @Test void invalidAiEvidenceFailsWithoutReplacingPreviousAnalysis() {
        var m=analyzed();ai.invalidEvidence=true;var job=terminal(worker.start(m.id(),"ANALYZE"));
        assertThat(job.status).isEqualTo("FAILED");assertThat(job.errorMessage).contains("원문과 일치하지");
        assertThat(service.get(m.id()).content().items).isEqualTo(m.content().items);
    }
    @Test void cancellingDoesNotWaitForRemoteModelOrApplyLateResults() throws Exception {
        var m=created();ai.blocking="meeting_actions";var job=worker.start(m.id(),"ANALYZE");
        try {assertThat(ai.entered.await(5,TimeUnit.SECONDS)).isTrue();long before=System.nanoTime();worker.cancel(job.id);
            assertThat(System.nanoTime()-before).isLessThan(TimeUnit.SECONDS.toNanos(2));assertThat(service.get(m.id()).content().items).isEmpty();}
        finally{ai.release.countDown();}
        assertThat(terminal(job).status).isEqualTo("CANCELLED");
    }
    @Test void concurrentUserEditsArePreservedInsteadOfOverwrittenByAi() throws Exception {
        var m=analyzed();var d=draft(m);ai.blocking="jira_ticket_review";var job=worker.review(d.id);
        String[] expected={""};
        try{assertThat(ai.entered.await(5,TimeUnit.SECONDS)).isTrue();expected[0]=service.update(d.id,edit(d,"동시 편집 제목","보존해야 하는 사용자 설명")).description;}
        finally{ai.release.countDown();}
        assertThat(terminal(job).status).isEqualTo("FAILED");
        var saved=service.drafts().findById(d.id).orElseThrow();assertThat(saved.summary).isEqualTo("동시 편집 제목");assertThat(saved.description).isEqualTo(expected[0]);
    }
    @Test void oldTicketEvidenceSurvivesReanalysisAndCanBeReviewedAgain() {
        var m=analyzed();var d=draft(m);var ids=new HashSet<>(d.analysisItemIds);ai.variant=true;complete(worker.start(m.id(),"ANALYZE"));
        var after=service.get(m.id());assertThat(after.content().archivedItems.stream().map(Item::id)).containsAll(ids);
        complete(worker.review(d.id));assertThat(service.drafts().findById(d.id).orElseThrow().analysisItemIds).isEqualTo(ids);
    }
}
