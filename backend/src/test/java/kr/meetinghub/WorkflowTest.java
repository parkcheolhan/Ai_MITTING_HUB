package kr.meetinghub;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@ActiveProfiles("demo")
class WorkflowTest {
    @org.springframework.boot.test.context.TestConfiguration static class Config {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        Adapters.Jira retryJira(){return new Adapters.MockJira(){@Override public Result create(Draft d){if(d.summary.equals("오류 시연"))throw new IllegalArgumentException("모의 일시적 등록 오류");return super.create(d);}};}
    }
    @Autowired MeetingService service;
    @Autowired Publisher publisher;
    @Autowired JobService worker;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    List<Draft> approveManually(List<String> ids){
        tx.executeWithoutResult(s->{for(var id:ids){var d=service.drafts().findById(id).orElseThrow();d.scheduleMode="MANUAL";service.drafts().save(d);}});
        return service.approve(ids);
    }

    MeetingView fixture() {
        var m=service.create(new CreateMeeting("테스트 회의","2026-10-25T10:00",List.of("참석자"),"DEMO","",
            "[00:12:34] 화자 2: QA 전에 환경부터 확인하겠습니다.\n[00:14:00] 화자 1: 로그인 오류 수정 작업을 진행합시다.","TEXT","ko",false,"일반"));
        var job=worker.start(m.id(),"PIPELINE");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) {
            var state=service.jobs().findById(job.id).orElseThrow();
            if(state.status.equals("COMPLETED"))return service.get(m.id());
            if(state.status.equals("FAILED"))throw new AssertionError(state.errorMessage);
            try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
        throw new AssertionError("작업 시간 초과");
    }
    Draft draft() {var m=fixture();return service.generateDrafts(m.id()).get(0);}
    DraftInput input(Draft d,String title,String project) {return new DraftInput(project,"Task",title,d.description,"","High","",List.of("meeting"),"",new ArrayList<>(d.analysisItemIds));}

    @Test void extractsItemsWithVerbatimEvidenceAndNoInventedOwnerOrDeadline() {
        var m=fixture();assertThat(m.content().items).hasSize(2);
        var item=m.content().items.get(0);
        assertThat(item.kind()).isEqualTo("CHECK");assertThat(item.evidence().get(0).startMs()).isEqualTo(754000);
        assertThat(item.evidence().get(0).quote()).isEqualTo(m.content().segments.get(0).text());
        assertThat(item.evidence().get(0).estimatedTime()).isFalse();
        assertThat(item.assigneeSuggestion()).isNull();assertThat(item.dueDateSuggestion()).isNull();
        assertThat(item.inferred()).isTrue();
    }
    @Test void editPersistsAndRevokesApproval() {
        var d=draft();approveManually(List.of(d.id));
        var saved=service.update(d.id,input(d,"수정한 제목","DEMO"));
        assertThat(saved.status).isEqualTo("DRAFT");
        var stored=service.drafts().findById(d.id).orElseThrow();assertThat(stored.summary).isEqualTo("수정한 제목");assertThat(stored.projectKey).isEqualTo("DEMO");
        assertThat(publisher.publish(d.id).status()).isEqualTo("BLOCKED");
    }
    @Test void blocksPublishBeforeApproval() {
        var d=draft();assertThat(publisher.publish(d.id).status()).isEqualTo("BLOCKED");
        assertThat(service.drafts().findById(d.id).orElseThrow().issueKey).isNull();
    }
    @Test void repeatedAndConcurrentPublishHasOneIssueKey() throws Exception {
        var d=draft();approveManually(List.of(d.id));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var results=pool.invokeAll(List.<Callable<Result>>of(()->publisher.publish(d.id),()->publisher.publish(d.id)));
            var a=results.get(0).get();var b=results.get(1).get();
            assertThat(List.of(a.status(),b.status())).contains("PUBLISHED");
            assertThat(List.of(a.status(),b.status())).allMatch(s->Set.of("PUBLISHED","SKIPPED","PUBLISHING").contains(s));
            String key=service.drafts().findById(d.id).orElseThrow().issueKey;
            assertThat(key).startsWith("MOCK-");assertThat(publisher.publish(d.id).issueKey()).isEqualTo(key);
        } finally {pool.shutdownNow();}
    }
    @Test void failureCanBeCorrectedAndRetriedWithoutDuplicates() {
        var d=draft();service.update(d.id,input(d,"오류 시연","DEMO"));approveManually(List.of(d.id));
        assertThat(publisher.publish(d.id).status()).isEqualTo("FAILED");
        service.update(d.id,input(d,"오류 수정","DEMO"));
        assertThat(publisher.publish(d.id).status()).isEqualTo("BLOCKED");
        approveManually(List.of(d.id));assertThat(publisher.publish(d.id).status()).isEqualTo("PUBLISHED");
        assertThat(publisher.publish(d.id).status()).isEqualTo("SKIPPED");
    }
    @Test void mergeSplitAndRegenerationPreserveEvidence() {
        var m=fixture();var original=service.generateDrafts(m.id());
        assertThat(service.generateDrafts(m.id())).hasSize(2);
        var merged=service.merge(original.stream().map(d->d.id).toList());assertThat(merged).hasSize(1);
        assertThat(merged.get(0).analysisItemIds).hasSize(2);
        var split=service.split(merged.get(0).id);assertThat(split).hasSize(2);
        assertThat(split).allSatisfy(d->{assertThat(d.status).isEqualTo("DRAFT");assertThat(d.description).contains("회의 근거");});
    }
    @Test void timestampWithoutSourceIsExplicitlyEstimated() {
        var segment=Adapters.parse("권한을 확인해주세요.").get(0);assertThat(segment.estimatedTime()).isTrue();
        assertThat(segment.speaker()).isEqualTo("화자 미상");
    }
    @Test void mockDoesNotPretendToTranscribeAudio() {
        var content=new Content();content.assets.add(new Asset("a","audio.mp3","audio/mpeg",100,"key"));
        assertThatThrownBy(()->new Adapters.MockSpeech().transcribe(content)).hasMessageContaining("실제 녹음을 전사하지 않습니다");
    }
}
