package kr.meetinghub;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:review-workflow;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
@Import(TranscriptReviewWorkflowTest.Config.class)
class TranscriptReviewWorkflowTest {
    @TestConfiguration static class Config{@Bean @Primary ReviewAi reviewAi(){return new ReviewAi();}}
    static class ReviewAi extends Adapters.MockIntelligence {
        volatile boolean fail,block;CountDownLatch entered,release;
        public List<Segment> reviewTranscript(String title,List<Segment> segments){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();
            if(block)try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalArgumentException("중단");}
            if(fail)throw new IllegalArgumentException("교정 연결 실패");
            return segments.stream().map(s->new Segment(s.id(),s.speaker(),s.startMs(),s.endMs(),s.text().replace("주간복","주간보고").replace("초연","초안"),s.estimatedTime())).toList();
        }
    }
    @Autowired MeetingService service;@Autowired JobService worker;@Autowired ReviewAi ai;
    @BeforeEach void reset(){ai.fail=false;ai.block=false;ai.entered=new CountDownLatch(1);ai.release=new CountDownLatch(1);}
    MeetingView meeting(){return service.create(new CreateMeeting("검토 회의","",List.of(),"DEMO","","주간복 때 초연 확인을 진행하겠습니다.","TEXT","ko",false,"일반"));}
    void await(String id,String status)throws Exception{for(int i=0;i<250;i++){if(service.jobs().findById(id).orElseThrow().status.equals(status))return;Thread.sleep(20);}throw new AssertionError("Job did not reach "+status);}
    @Test void pipelineUsesCorrectedTranscriptBeforeMinutesAndAnalysis()throws Exception{
        var m=meeting();var before=m.content().segments.get(0);var j=worker.start(m.id(),"PIPELINE");await(j.id,"COMPLETED");
        var c=service.get(m.id()).content();assertThat(c.segments.get(0).text()).contains("주간보고","초안");assertThat(c.originalTranscriptText.get(before.id())).isEqualTo(before.text());assertThat(c.minutes.summary).contains("주간보고","초안");assertThat(c.items).isNotEmpty();
    }
    @Test void failedReviewRetainsRawTranscriptAndDoesNotGenerateMinutes()throws Exception{
        ai.fail=true;var m=meeting();var j=worker.start(m.id(),"PIPELINE");await(j.id,"FAILED");var result=service.get(m.id());
        assertThat(result.content().segments).isEqualTo(m.content().segments);assertThat(result.content().minutes).isNull();assertThat(result.jobs().get(0).stage).isEqualTo("REVIEWING_TRANSCRIPT");
    }
    @Test void cancelledReviewCannotApplyLateResultsAndBlocksConcurrentEdits()throws Exception{
        ai.block=true;var m=meeting();var before=m.content().segments.get(0);var j=worker.start(m.id(),"TRANSCRIPT_REVIEW");assertThat(ai.entered.await(3,TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(()->service.editTranscript(m.id(),new TranscriptTextInput(List.of(new TranscriptTextEdit(before.id(),before.text(),"수정"))))).hasMessageContaining("분석 중");
        worker.cancel(j.id);ai.release.countDown();Thread.sleep(150);assertThat(service.get(m.id()).content().segments).isEqualTo(m.content().segments);assertThat(service.get(m.id()).jobs().get(0).status).isEqualTo("CANCELLED");
    }
}
