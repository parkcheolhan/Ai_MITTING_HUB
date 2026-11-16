package kr.meetinghub;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import kr.meetinghub.Domain.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:jira-integration;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","jira.site-url=https://example.atlassian.net","jira.email=unit@example.com","jira.api-token=never-expose-this-token"})
@AutoConfigureMockMvc @Import(JiraIntegrationTest.Config.class)
class JiraIntegrationTest {
    @Autowired MockMvc mvc;@Autowired JiraIntegration integration;@Autowired TestJira jira;
    @TestConfiguration static class Config {@Bean @Primary TestJira integrationJira(){return new TestJira();}}
    static class TestJira implements Adapters.Jira {
        final AtomicInteger creates=new AtomicInteger();
        public String mode(){return "cloud";}
        public String target(){return "cloud:https://example.atlassian.net";}
        public java.util.Map<String,Object> test(){return java.util.Map.of("connected",true,"message","test user");}
        public Result create(Draft d){creates.incrementAndGet();return new Result(d.id,"PUBLISHED",d.projectKey+"-1","https://example.atlassian.net/browse/"+d.projectKey+"-1",null);}
    }
    @Test void testIssueRequiresExplicitApproval() throws Exception {
        int before=jira.creates.get();
        mvc.perform(post("/api/integrations/jira/test-issue").contentType("application/json").content("{\"projectKey\":\"BLOCK\",\"issueType\":\"10001\",\"approved\":false}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("실제 테스트 이슈 생성 동의가 필요합니다."));
        assertThat(jira.creates.get()).isEqualTo(before);
    }
    @Test void sameProjectReusesOnePersistedTestIssue() {
        int before=jira.creates.get();
        var first=integration.testIssue("ONE","10001",true);var second=integration.testIssue("ONE","10001",true);
        assertThat(first.status()).isEqualTo("PUBLISHED");assertThat(second.status()).isEqualTo("SKIPPED");
        assertThat(first.draftId()).isEqualTo(second.draftId());assertThat(jira.creates.get()).isEqualTo(before+1);
    }
    @Test void settingsReturnEditableEmailButNeverTheToken() throws Exception {
        var body=mvc.perform(get("/api/settings")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("tokenConfigured","unit@example.com").doesNotContain("never-expose-this-token");
    }
}
