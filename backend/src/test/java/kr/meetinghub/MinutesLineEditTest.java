package kr.meetinghub;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:minutes-lines;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","jira.mode=mock"})
@AutoConfigureMockMvc
class MinutesLineEditTest {
    @Autowired MeetingService service;@Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired TransactionTemplate tx;
    String meeting(){var id=service.create(new CreateMeeting("줄 편집 검증","2026-11-17T10:00",List.of("참석자"),"DEMO","","회의 원문입니다.","TEXT","ko",false,"일반")).id();tx.executeWithoutResult(s->{var m=service.lock(id);var c=service.content(m);c.minutes=new Minutes();c.minutes.discussion="첫 번째 줄\n두 번째 줄\n마지막 줄";c.minutes.summary="보존할 요약";c.minutes.generationMode="codex";c.minutes.generationModel="unit";service.save(m,c);});return id;}
    MinutesLineInput line(String field,int index,String old,String text,int version){return new MinutesLineInput(field,index,old,text,version);}
    org.springframework.test.web.servlet.ResultActions patch(String id,MinutesLineInput input)throws Exception{return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/meetings/"+id+"/minutes/line").contentType("application/json").content(json.writeValueAsString(input)));}
    @Test void editsOnlyTheSelectedLineAndPreservesOtherContent()throws Exception{
        String id=meeting();var before=service.get(id);
        patch(id,line("discussion",1,"두 번째 줄","대화에 맞게 수정한 문구입니다.",1)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        var after=service.get(id);assertThat(after.content().minutes.discussion).isEqualTo("첫 번째 줄\n대화에 맞게 수정한 문구입니다.\n마지막 줄");assertThat(after.content().minutes.summary).isEqualTo("보존할 요약");assertThat(after.content().minutes.generationMode).isEqualTo("codex");assertThat(after.content().segments).isEqualTo(before.content().segments);assertThat(after.content().participants).isEqualTo(before.content().participants);
    }
    @Test void editsUploadedTemplateLineWithoutRebuildingTheTemplate()throws Exception{
        String id=meeting();tx.executeWithoutResult(s->{var m=service.lock(id);var c=service.content(m);c.minutes.formatted="# 회사 양식\r\n- 기존 결정\r\n후속 조치";service.save(m,c);});
        patch(id,line("formatted",1,"- 기존 결정","- 새로운 결정",1)).andExpect(status().isOk());assertThat(service.get(id).content().minutes.formatted).isEqualTo("# 회사 양식\n- 새로운 결정\n후속 조치");assertThat(service.get(id).content().minutes.discussion).startsWith("첫 번째 줄");
    }
    @Test void staleVersionAndWrongOriginalCannotOverwriteAnotherEdit()throws Exception{
        String id=meeting();patch(id,line("discussion",1,"두 번째 줄","최신 문구",1)).andExpect(status().isOk());
        patch(id,line("discussion",1,"두 번째 줄","오래된 화면의 문구",1)).andExpect(status().isConflict());patch(id,line("discussion",1,"두 번째 줄","잘못된 원문",2)).andExpect(status().isConflict());assertThat(service.get(id).content().minutes.discussion).contains("최신 문구");assertThat(service.get(id).content().minutes.version).isEqualTo(2);
    }
    @Test void emptyTextCanClearAndRefillOneLineAndNoOpDoesNotIncrementVersion()throws Exception{
        String id=meeting();patch(id,line("discussion",1,"두 번째 줄","두 번째 줄",1)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        patch(id,line("discussion",1,"두 번째 줄","",1)).andExpect(status().isOk());assertThat(service.get(id).content().minutes.discussion).isEqualTo("첫 번째 줄\n\n마지막 줄");
        patch(id,line("discussion",1,"","다시 입력",2)).andExpect(status().isOk());assertThat(service.get(id).content().minutes.discussion).isEqualTo("첫 번째 줄\n다시 입력\n마지막 줄");
    }
    @Test void rejectsUnknownFieldsInvalidIndexesAndMultiLineWrites()throws Exception{
        String id=meeting();patch(id,line("generationMode",0,"codex","manual",1)).andExpect(status().isBadRequest());patch(id,line("discussion",-1,"","본문",1)).andExpect(status().isBadRequest());patch(id,line("discussion",100,"","본문",1)).andExpect(status().isConflict());patch(id,line("discussion",1,"두 번째 줄","여러\n줄",1)).andExpect(status().isBadRequest());assertThat(service.get(id).content().minutes.version).isEqualTo(1);
    }
    @Test void activeAnalysisCannotBeOverwrittenByInlineEditing()throws Exception{
        String id=meeting();var job=new Job();job.meetingId=id;job.status="RUNNING";service.jobs().saveAndFlush(job);
        patch(id,line("discussion",1,"두 번째 줄","변경",1)).andExpect(status().isBadRequest());assertThat(service.get(id).content().minutes.discussion).contains("두 번째 줄");
    }
}
