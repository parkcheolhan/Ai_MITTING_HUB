package kr.meetinghub;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:speech-jobs;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
class SpeechJobWorkflowTest {
    @TestConfiguration static class Config {@Bean @Primary TestSpeech testSpeech(){return new TestSpeech();}}
    static class TestSpeech implements Adapters.SpeechToText {
        volatile boolean block,fail;volatile List<String> received=List.of();CountDownLatch entered=new CountDownLatch(1);
        public List<Segment> transcribe(Content content){return transcribe(content,()->false);}
        public List<Segment> transcribe(Content content,BooleanSupplier cancelled){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();
            received=content.assets.stream().map(Asset::storageKey).toList();if(fail)throw new IllegalArgumentException("음성 변환 실패");
            while(block&&!cancelled.getAsBoolean()){try{Thread.sleep(10);}catch(InterruptedException e){throw new IllegalArgumentException("cancelled");}}
            return List.of(new Segment("voice-1","화자 미분리",1000,5000,"로그인 오류 수정 작업을 진행합시다.",false));
        }
    }
    @Autowired MeetingService meetings;@Autowired JobService jobs;@Autowired TestSpeech speech;
    @BeforeEach void reset(){speech.block=false;speech.fail=false;speech.received=List.of();speech.entered=new CountDownLatch(1);}
    void await(String id,String status)throws Exception{for(int i=0;i<250;i++){if(meetings.jobs().findById(id).orElseThrow().status.equals(status))return;Thread.sleep(20);}throw new AssertionError("Job did not reach "+status);}
    @Test void failedTranscriptionCanReplaceSourceAndRetryWithoutProcessingOldFile()throws Exception{
        var m=audio();speech.fail=true;var job=jobs.start(m.id(),"TRANSCRIBE");await(job.id,"FAILED");
        var replacement=new Asset("new","replacement.acc","audio/aac",100,"replacement.acc");
        meetings.replaceFailedSpeechAsset(m.id(),replacement);assertThat(meetings.get(m.id()).content().assets).containsExactly(replacement);
        speech.fail=false;var retry=jobs.start(m.id(),"TRANSCRIBE");await(retry.id,"COMPLETED");
        assertThat(speech.received).containsExactly("replacement.acc");assertThat(meetings.get(m.id()).content().segments).hasSize(1);
        assertThatThrownBy(()->meetings.replaceFailedSpeechAsset(m.id(),replacement)).hasMessageContaining("실패");
    }
    @Test void sourceCannotBeReplacedBeforeFailureOrWhileProcessing()throws Exception{
        var m=audio();var replacement=new Asset("new","new.wav","audio/wav",100,"new.wav");
        assertThatThrownBy(()->meetings.replaceFailedSpeechAsset(m.id(),replacement)).hasMessageContaining("실패");
        speech.block=true;var job=jobs.start(m.id(),"TRANSCRIBE");assertThat(speech.entered.await(3,TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(()->meetings.replaceFailedSpeechAsset(m.id(),replacement)).hasMessageContaining("분석 중");
        jobs.cancel(job.id);assertThat(meetings.get(m.id()).content().assets.get(0).storageKey()).isEqualTo("recording.m4a");
    }
    MeetingView audio(){var m=meetings.create(new CreateMeeting("음성 파일 분석","",List.of(),"DEMO","","","MEDIA","ko",false,"일반"));meetings.addAsset(m.id(),new Asset("a","recording.m4a","audio/mp4",100,"recording.m4a"));return m;}
    @Test void audioOnlyRunsTranscriptionThenMinutesAndActionAnalysis()throws Exception{
        var m=audio();var job=jobs.start(m.id(),"PIPELINE");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline&&!meetings.jobs().findById(job.id).orElseThrow().status.equals("COMPLETED"))Thread.sleep(20);
        assertThat(meetings.jobs().findById(job.id).orElseThrow().status).isEqualTo("COMPLETED");var result=meetings.get(m.id());
        assertThat(result.content().segments.get(0).startMs()).isEqualTo(1000);assertThat(result.content().minutes).isNotNull();assertThat(result.content().items).isNotEmpty();
    }
    @Test void cancelledSpeechDoesNotOverwriteMeetingWithLateResults()throws Exception{
        speech.block=true;var m=audio();var job=jobs.start(m.id(),"PIPELINE");assertThat(speech.entered.await(3,TimeUnit.SECONDS)).isTrue();jobs.cancel(job.id);
        Thread.sleep(100);assertThat(meetings.get(m.id()).content().segments).isEmpty();assertThat(meetings.jobs().findById(job.id).orElseThrow().status).isEqualTo("CANCELLED");
    }
}
