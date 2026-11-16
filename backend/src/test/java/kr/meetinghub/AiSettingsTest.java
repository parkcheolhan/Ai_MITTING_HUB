package kr.meetinghub;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ai-settings;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","ai.mode=openai","ai.api-key=","app.secret-key-file=./target/ai-settings-test.key"})
@AutoConfigureMockMvc
class AiSettingsTest {
    @Autowired MockMvc mvc;@Autowired AiSettingsStore settings;@Autowired AiSettingsRepository repository;@Autowired ConfiguredIntelligence intelligence;
    @BeforeEach void clear(){repository.deleteAll();}
    @Test void realAiIsNotSilentlyReplacedWhenUnconfigured() {
        assertThat(settings.view().get("mode")).isEqualTo("openai");assertThat(settings.view().get("configured")).isEqualTo(false);
        assertThat(intelligence.snapshot()).isInstanceOf(OpenAiIntelligence.class);
    }
    @Test void apiKeyIsEncryptedAndNeverReturned() throws Exception {
        var response=mvc.perform(put("/api/settings/ai").contentType("application/json").content("{\"mode\":\"openai\",\"model\":\"gpt-4.1-mini\",\"apiKey\":\"fake-ui-ai-secret\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.keyConfigured").value(true)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("fake-ui-ai-secret","encryptedKey");
        assertThat(repository.findById("ai").orElseThrow().encryptedKey).startsWith("v1.").doesNotContain("fake-ui-ai-secret");
        assertThat(settings.current().apiKey()).isEqualTo("fake-ui-ai-secret");
        var read=mvc.perform(get("/api/settings/ai")).andReturn().getResponse().getContentAsString();assertThat(read).doesNotContain("fake-ui-ai-secret","encryptedKey");
    }
    @Test void blankKeyIsRetainedAndDeletionPersists() throws Exception {
        settings.save(new AiSettingsStore.Input("openai","gpt-4.1-mini","fake-ai-unit-secret"));
        settings.save(new AiSettingsStore.Input("openai","gpt-4.1-mini",""));assertThat(settings.current().apiKey()).isEqualTo("fake-ai-unit-secret");
        mvc.perform(delete("/api/settings/ai/key")).andExpect(status().isOk()).andExpect(jsonPath("$.keyConfigured").value(false));
        assertThat(settings.current().apiKey()).isEmpty();assertThat(settings.view().get("configured")).isEqualTo(false);
    }
    @Test void mockModeMustBeExplicitAndConfigurationChangesAreImmediate() {
        settings.save(new AiSettingsStore.Input("mock","gpt-4.1-mini",""));assertThat(intelligence.snapshot()).isInstanceOf(Adapters.MockIntelligence.class);
        settings.save(new AiSettingsStore.Input("openai","gpt-4.1-mini","fake-ai-secret"));assertThat(intelligence.snapshot()).isInstanceOf(OpenAiIntelligence.class);
    }
    @Test void codexUsesSavedCliAuthenticationWithoutApiKey() throws Exception {
        mvc.perform(put("/api/settings/ai").contentType("application/json").content("{\"mode\":\"codex\",\"model\":\"codex-default\",\"apiKey\":\"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.keyConfigured").value(false));
        assertThat(intelligence.snapshot()).isInstanceOf(CodexIntelligence.class);
        assertThat(intelligence.snapshot().mode()).isEqualTo("codex");
    }
    @Test void switchingToCodexPreservesExistingApiKey() {
        settings.save(new AiSettingsStore.Input("openai","gpt-4.1-mini","fake-preserved"));
        settings.save(new AiSettingsStore.Input("codex","codex-default",""));
        settings.save(new AiSettingsStore.Input("openai","gpt-4.1-mini",""));
        assertThat(settings.current().apiKey()).isEqualTo("fake-preserved");
    }
    @Test void invalidModelAndMissingKeyAreRejected() throws Exception {
        mvc.perform(put("/api/settings/ai").contentType("application/json").content("{\"mode\":\"openai\",\"model\":\"gpt-4.1-mini\",\"apiKey\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/ai").contentType("application/json").content("{\"mode\":\"openai\",\"model\":\"https://untrusted.example\",\"apiKey\":\"fake\"}")).andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }
    @Test void foreignWebsiteCannotSetAiKey() throws Exception {
        mvc.perform(post("/api/settings/ai/codex/login").header("Origin","https://foreign.example")).andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/ai").header("Origin","https://foreign.example").contentType("application/json").content("{\"mode\":\"openai\",\"model\":\"gpt-4.1-mini\",\"apiKey\":\"fake\"}")).andExpect(status().isForbidden());
    }
}
