package kr.meetinghub;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:confirm-stages;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
@Import(AnalysisConfirmationTest.Config.class)
class AnalysisConfirmationTest {
    @TestConfiguration static class Config{@Bean @Primary CountingAi countingAi(){return new CountingAi();}}
    static class CountingAi extends Adapters.MockIntelligence{
        AtomicInteger reviews=new AtomicInteger(),minutes=new AtomicInteger(),analysis=new AtomicInteger();
        public List<Segment> reviewTranscript(String title,List<Segment> segments){reviews.incrementAndGet();return segments;}
        public Minutes minutes(String title,List<Segment> segments){minutes.incrementAndGet();return super.minutes(title,segments);}
        public List<Item> analyze(List<Segment> segments){analysis.incrementAndGet();return super.analyze(segments);}
    }
    @Autowired JobService worker;@Autowired MeetingService service;@Autowired CountingAi ai;
    @BeforeEach void reset(){ai.reviews.set(0);ai.minutes.set(0);ai.analysis.set(0);}
    MeetingView meeting(){return service.create(new CreateMeeting("단계 확인","",List.of(),"DEMO","","로그인 오류를 확인하고 수정합시다.","TEXT","ko",false,"일반"));}
    Job await(String id,String status,int next)throws Exception{for(int i=0;i<300;i++){var j=service.jobs().findById(id).orElseThrow();if(j.status.equals(status)&&j.nextStageIndex==next)return j;Thread.sleep(20);}throw new AssertionError("Wrong job status/index");}
    @Test void confirmationPersistsAtEveryBoundaryAndUsesEditsBeforeContinuing()throws Exception{
        var m=meeting();worker.setAdvanceMode(m.id(),"CONFIRM");var j=worker.start(m.id(),"PIPELINE");
        await(j.id,"WAITING_CONFIRMATION",1);assertThat(ai.reviews.get()).isZero();assertThat(service.get(m.id()).content().minutes).isNull();
        assertThatThrownBy(()->worker.start(m.id(),"PIPELINE")).hasMessageContaining("확인 대기");
        var s=m.content().segments.get(0);service.editTranscript(m.id(),new TranscriptTextInput(List.of(new TranscriptTextEdit(s.id(),s.text(),"저장한 문구를 검토하고 수정합시다."))));
        worker.continueJob(j.id,1);await(j.id,"WAITING_CONFIRMATION",2);assertThat(ai.reviews.get()).isEqualTo(1);assertThat(ai.minutes.get()).isZero();
        assertThatThrownBy(()->worker.continueJob(j.id,1)).hasMessageContaining("이미 다른 단계");
        worker.continueJob(j.id,2);await(j.id,"WAITING_CONFIRMATION",3);assertThat(service.get(m.id()).content().minutes.summary).contains("저장한 문구");assertThat(ai.analysis.get()).isZero();
        worker.continueJob(j.id,3);await(j.id,"WAITING_CONFIRMATION",4);assertThat(ai.analysis.get()).isEqualTo(1);
        worker.continueJob(j.id,4);await(j.id,"COMPLETED",5);
        assertThat(ai.reviews.get()).isEqualTo(1);assertThat(ai.minutes.get()).isEqualTo(1);
    }
    @Test void canSwitchPausedJobToAutoAndFinishRemainingStages()throws Exception{
        var m=meeting();worker.setAdvanceMode(m.id(),"CONFIRM");var j=worker.start(m.id(),"PIPELINE");await(j.id,"WAITING_CONFIRMATION",1);
        worker.setAdvanceMode(m.id(),"AUTO");assertThat(service.jobs().findById(j.id).orElseThrow().advanceMode).isEqualTo("AUTO");
        worker.continueJob(j.id,1);await(j.id,"COMPLETED",5);
    }
    @Test void waitingCanBeCancelledAndStaleSourceCannotGenerateTickets()throws Exception{
        var m=meeting();worker.setAdvanceMode(m.id(),"CONFIRM");var j=worker.start(m.id(),"ANALYZE");await(j.id,"WAITING_CONFIRMATION",1);
        var s=m.content().segments.get(0);service.editTranscript(m.id(),new TranscriptTextInput(List.of(new TranscriptTextEdit(s.id(),s.text(),"바뀐 분석 근거를 확인하세요."))));
        assertThatThrownBy(()->worker.continueJob(j.id,1)).hasMessageContaining("음성 기록이 변경");
        worker.cancel(j.id);assertThat(service.jobs().findById(j.id).orElseThrow().status).isEqualTo("CANCELLED");
        assertThatThrownBy(()->worker.continueJob(j.id,1)).hasMessageContaining("확인 대기");
    }
}
