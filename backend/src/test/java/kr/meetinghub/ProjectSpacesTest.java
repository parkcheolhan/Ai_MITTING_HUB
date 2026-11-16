package kr.meetinghub;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import kr.meetinghub.Domain.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:project-spaces;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc @Transactional
class ProjectSpacesTest {
    @Autowired MockMvc mvc;@Autowired ProjectSpaceRepository repository;@Autowired MeetingService meetings;@Autowired ObjectMapper json;
    String body(String key,String name,String description)throws Exception{return json.writeValueAsString(Map.of("key",key,"name",name,"description",description));}
    @Test void freshRegistryIsEmptyAndRegisteredProjectsPersist()throws Exception{
        mvc.perform(get("/api/projects")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/projects").contentType("application/json").content(body("team","팀 프로젝트","회의를 관리합니다.")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.key").value("TEAM")).andExpect(jsonPath("$.meetingCount").value(0));
        assertThat(repository.findById("TEAM")).hasValueSatisfying(p->assertThat(p.description).isEqualTo("회의를 관리합니다."));
        mvc.perform(get("/api/projects")).andExpect(jsonPath("$[0].name").value("팀 프로젝트"));
    }
    @Test void duplicateAndInvalidKeysCannotOverwriteAProject()throws Exception{
        mvc.perform(post("/api/projects").contentType("application/json").content(body("TEAM","기존 이름",""))).andExpect(status().isCreated());
        mvc.perform(post("/api/projects").contentType("application/json").content(body("team","덮어쓰기",""))).andExpect(status().isConflict());
        mvc.perform(post("/api/projects").contentType("application/json").content(body("bad key","이름",""))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects").contentType("application/json").content(body("OTHER","   ",""))).andExpect(status().isBadRequest());
        assertThat(repository.findById("TEAM").orElseThrow().name).isEqualTo("기존 이름");assertThat(repository.count()).isEqualTo(1);
    }
    @Test void changingNameKeepsKeyAndExistingMeetingAssociation()throws Exception{
        mvc.perform(post("/api/projects").contentType("application/json").content(body("TEAM","기존 이름",""))).andExpect(status().isCreated());
        var meeting=meetings.create(new CreateMeeting("실제 회의","",List.of(),"TEAM","","실제 전사문","TEXT","ko",false,"일반"));
        mvc.perform(put("/api/projects/TEAM").contentType("application/json").content(body("TEAM","새 이름","설명 수정")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("새 이름")).andExpect(jsonPath("$.meetingCount").value(1));
        mvc.perform(put("/api/projects/TEAM").contentType("application/json").content(body("OTHER","이름",""))).andExpect(status().isBadRequest());
        assertThat(meetings.get(meeting.id()).content().projectKey).isEqualTo("TEAM");
    }
}
