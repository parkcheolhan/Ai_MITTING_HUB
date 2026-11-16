package kr.meetinghub;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:transcript-edits;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","ai.mode=mock","jira.mode=mock"})
@AutoConfigureMockMvc
class TranscriptEditingTest {
    @Autowired MeetingService service;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    MeetingView meeting(){return service.create(new CreateMeeting("기록 수정","",List.of(),"DEMO","","이번 주에는 주간복 때 초연을 봤으면 좋겠는데","TEXT","ko",false,"일반"));}
    @Test void editApiPreservesIdentityTimingAndOriginalAndRejectsStaleSave()throws Exception{
        var m=meeting();var original=m.content().segments.get(0);String text="이번 주에는 주간보고 때 초안을 봤으면 좋겠는데";
        var input=new TranscriptTextInput(List.of(new TranscriptTextEdit(original.id(),original.text(),text)));
        mvc.perform(put("/api/meetings/"+m.id()+"/transcript").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andExpect(jsonPath("$.content.transcriptRevision").value(1));
        var result=service.get(m.id()).content();assertThat(result.segments.get(0)).isEqualTo(new Segment(original.id(),original.speaker(),original.startMs(),original.endMs(),text,original.estimatedTime()));
        assertThat(result.originalTranscriptText.get(original.id())).isEqualTo(original.text());
        assertThatThrownBy(()->service.editTranscript(m.id(),input)).hasMessageContaining("다른 작업");
        service.editTranscript(m.id(),new TranscriptTextInput(List.of(new TranscriptTextEdit(original.id(),text,text+"."))));
        assertThat(service.get(m.id()).content().originalTranscriptText.get(original.id())).isEqualTo(original.text());
    }
    @Test void invalidAndForeignEditsCannotPartiallyApply(){
        var m=meeting();var s=m.content().segments.get(0);
        for(var bad:List.of(List.of(new TranscriptTextEdit("foreign",s.text(),"외부 발언")),List.of(new TranscriptTextEdit(s.id(),s.text()," ")),List.of(new TranscriptTextEdit(s.id(),s.text(),"수정"),new TranscriptTextEdit(s.id(),s.text(),"중복")),List.of(new TranscriptTextEdit(s.id(),s.text(),"수정"),new TranscriptTextEdit("foreign","","외부")))){
            assertThatThrownBy(()->service.editTranscript(m.id(),new TranscriptTextInput(bad))).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.get(m.id()).content().segments).containsExactly(s);
        }
    }
    @Test void runningJobsBlockTextChanges(){
        var m=meeting();var s=m.content().segments.get(0);var job=new Job();job.meetingId=m.id();job.status="RUNNING";service.jobs().save(job);
        assertThatThrownBy(()->service.editTranscript(m.id(),new TranscriptTextInput(List.of(new TranscriptTextEdit(s.id(),s.text(),"수정"))))).hasMessageContaining("분석 중");
    }
    @Test void correctionCannotChangeSpeakerOrTiming(){
        var c=new Content();var s=new Segment("s","화자 1",100,900,"원문",false);c.segments=List.of(s);
        assertThatThrownBy(()->MeetingService.applyTranscriptText(c,List.of(new Segment("s","화자 2",100,900,"교정",false)))).hasMessageContaining("내용만");
        assertThat(c.segments).containsExactly(s);
    }
}
