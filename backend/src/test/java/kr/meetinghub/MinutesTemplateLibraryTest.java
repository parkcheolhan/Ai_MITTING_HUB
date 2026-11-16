package kr.meetinghub;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:template-library;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class MinutesTemplateLibraryTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired MinutesTemplateRepository repository;
    @Autowired MinutesTemplateLibrary library;@Autowired Meetings meetings;
    @BeforeEach void clear(){repository.deleteAll();}
    @Test void uploadPersistsInLibraryWithoutChangingAnyMeeting()throws Exception{
        long count=meetings.count();
        String template="# 팀 양식\n{{회의명}}\n{{요약}}\n{{결정사항}}";
        String result=mvc.perform(post("/api/minutes-templates").contentType("application/json").content(json.writeValueAsString(Map.of("name","팀.md","template","\uFEFF"+template))))
            .andExpect(status().isOk()).andExpect(jsonPath("name").value("팀.md")).andReturn().getResponse().getContentAsString();
        String id=json.readTree(result).path("id").asText();
        assertThat(repository.findById(id).orElseThrow().template).isEqualTo(template);
        mvc.perform(get("/api/minutes-templates")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].template").value(template));
        assertThat(meetings.count()).isEqualTo(count);
        var duplicate=library.create(new MinutesTemplateLibrary.Input("다른 파일명.md",template));
        assertThat(duplicate.id()).isEqualTo(id);assertThat(repository.count()).isEqualTo(1);
    }
    @Test void invalidUploadLeavesLibraryUnchanged()throws Exception{
        for(String template:new String[]{"일반","회의명 {{회의명}}","{{없는항목}} {{요약}}","{{요약}}"+"x".repeat(20000)})
            mvc.perform(post("/api/minutes-templates").contentType("application/json").content(json.writeValueAsString(Map.of("name","양식.md","template",template))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/minutes-templates").contentType("application/json").content(json.writeValueAsString(Map.of("name"," ","template","{{요약}}"))))
            .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }
}
