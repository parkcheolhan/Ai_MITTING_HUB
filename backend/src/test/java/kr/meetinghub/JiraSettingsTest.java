package kr.meetinghub;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import kr.meetinghub.Domain.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:settings;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","jira.mode=mock","jira.api-token=legacy-environment-unit-token","app.secret-key-file=./target/settings-test.key"})
@AutoConfigureMockMvc
class JiraSettingsTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JiraSettingsStore settings;
    @Autowired JiraSettingsRepository repository;@Autowired ConfiguredJira router;@Autowired MeetingService meetings;@Autowired Publisher publisher;
    @Autowired JiraConfig environment;@Autowired SecretCipher cipher;@Autowired Audits audits;
    @BeforeEach void clear(){repository.deleteAll();}
    JiraSettingsStore.Input cloud(String token){return new JiraSettingsStore.Input("cloud","https://unit.atlassian.net","unit@example.com",token,"");}
    @Test void apiEncryptsTokenAndNeverReturnsIt() throws Exception {
        String token="fake-settings-unit-secret";
        var response=mvc.perform(put("/api/settings/jira").contentType("application/json").content(json.writeValueAsString(cloud(token))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.mode").value("cloud")).andExpect(jsonPath("$.tokenConfigured").value(true))
            .andExpect(jsonPath("$.email").value("unit@example.com")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(token,"encryptedToken");
        var stored=repository.findById("jira").orElseThrow();assertThat(stored.encryptedToken).startsWith("v1.").doesNotContain(token);
        assertThat(settings.current().apiToken()).isEqualTo(token);
        var recreated=new JiraSettingsStore(repository,environment,cipher,audits);
        assertThat(recreated.current().apiToken()).isEqualTo(token);
        var read=mvc.perform(get("/api/settings")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(read).doesNotContain(token,"encryptedToken");
    }
    @Test void blankTokenRetainsExistingSecret() {
        settings.save(cloud("fake-unit-token"));String encrypted=repository.findById("jira").orElseThrow().encryptedToken;
        settings.save(cloud(""));assertThat(settings.current().apiToken()).isEqualTo("fake-unit-token");
        assertThat(repository.findById("jira").orElseThrow().encryptedToken).isEqualTo(encrypted);
    }
    @Test void configurationChangesApplyImmediatelyButExistingSnapshotStaysPinned() {
        assertThat(router.snapshot()).isInstanceOf(Adapters.MockJira.class);
        settings.save(cloud("fake-unit-token"));var captured=router.snapshot();assertThat(captured).isInstanceOf(CloudJira.class);
        settings.save(new JiraSettingsStore.Input("cloud","https://other.atlassian.net","other@example.com","fake-replacement-token",""));
        assertThat(router.target()).isEqualTo("cloud:https://other.atlassian.net");assertThat(captured.target()).isEqualTo("cloud:https://unit.atlassian.net");
        settings.save(new JiraSettingsStore.Input("mock","https://other.atlassian.net","other@example.com","",""));
        assertThat(router.snapshot()).isInstanceOf(Adapters.MockJira.class);
    }
    @Test void defaultProjectIsSavedAndReloaded() throws Exception {
        mvc.perform(put("/api/settings/jira/project").contentType("application/json").content("{\"projectKey\":\"DEMO\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.defaultProject").value("DEMO"));
        assertThat(settings.current().defaultProject()).isEqualTo("DEMO");
        mvc.perform(get("/api/settings")).andExpect(jsonPath("$.defaultProject").value("DEMO"));
    }
    @Test void deletingTokenDoesNotRestoreEnvironmentFallback() throws Exception {
        assertThat(settings.view().get("tokenConfigured")).isEqualTo(true);
        mvc.perform(delete("/api/settings/jira/token")).andExpect(status().isOk()).andExpect(jsonPath("$.tokenConfigured").value(false));
        assertThat(settings.current().apiToken()).isEmpty();assertThat(repository.findById("jira").orElseThrow().encryptedToken).isNull();
    }
    @Test void changingIdentityRequiresANewTokenAndLeavesPreviousSettingsIntact() {
        settings.save(cloud("fake-unit-token"));
        assertThatThrownBy(()->settings.save(new JiraSettingsStore.Input("cloud","https://other.atlassian.net","unit@example.com","",""))).hasMessageContaining("새 API 토큰");
        assertThat(settings.current().siteUrl()).isEqualTo("https://unit.atlassian.net");
    }
    @Test void rejectsUnsafeSiteAndInvalidProjectInput() throws Exception {
        var input=new JiraSettingsStore.Input("cloud","http://localhost:1234","unit@example.com","fake-unit-token","");
        mvc.perform(put("/api/settings/jira").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/jira/project").contentType("application/json").content("{\"projectKey\":\"bad key\"}")).andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }
    @Test void switchingFromMockToCloudRequiresFreshTicketApproval() {
        var m=meetings.create(new CreateMeeting("설정 변경 승인 검증","",List.of(),"DEMO","","환경 확인","TEXT","ko",false,"일반"));
        var d=meetings.addDraft(m.id(),new DraftInput("DEMO","Task","환경 확인","테스트 설명","","","",List.of(),"",List.of()));
        meetings.approve(List.of(d.id));settings.save(cloud("fake-unit-token"));
        assertThat(publisher.publish(d.id).status()).isEqualTo("BLOCKED");
        assertThat(meetings.drafts().findById(d.id).orElseThrow().publishTarget).isNull();
    }
    @Test void foreignWebsitesCannotChangeLocalCredentials() throws Exception {
        mvc.perform(put("/api/settings/jira").header("Origin","https://foreign.example").contentType("application/json").content(json.writeValueAsString(cloud("fake-unit-token"))))
            .andExpect(status().isForbidden());assertThat(repository.count()).isZero();
    }
}
