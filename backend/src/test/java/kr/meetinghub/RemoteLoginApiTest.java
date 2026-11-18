package kr.meetinghub;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:remote-login;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","app.remote.token=synthetic-internal-token-32-characters-only","app.remote.allowed-origins=https://unit.github.io","app.remote.login-id=unit-admin","app.remote.login-password=unit-password"})
@ActiveProfiles("remote") @AutoConfigureMockMvc
class RemoteLoginApiTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @Test void idLoginAuthorizesApiAndSettingsAndLogoutRevokesTheSession()throws Exception{
        var result=mvc.perform(post("/api/auth/login").header("Origin","https://unit.github.io").contentType("application/json").content("{\"id\":\"unit-admin\",\"token\":\"unit-password\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.user").value("unit-admin")).andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("unit-password","synthetic-internal-token");String token=json.readTree(result).path("token").asText();assertThat(token.length()).isGreaterThanOrEqualTo(32);
        mvc.perform(get("/api/meetings").header("Origin","https://unit.github.io").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(put("/api/settings/confluence/connection").header("Origin","https://unit.github.io").header("Sec-Fetch-Site","cross-site").header("Authorization","Bearer "+token).contentType("application/json").content("{\"enabled\":false,\"useJiraCredentials\":true}")).andExpect(status().isOk());
        mvc.perform(delete("/api/auth/session").header("Authorization","Bearer "+token)).andExpect(status().isNoContent());mvc.perform(get("/api/meetings").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void rejectsWrongCredentialsAndForeignOriginsAndProtectsOtherAuthRoutes()throws Exception{
        mvc.perform(post("/api/auth/login").header("Origin","https://foreign.example").contentType("application/json").content("{\"id\":\"unit-admin\",\"token\":\"unit-password\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"id\":\"unit-admin\",\"token\":\"wrong\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());mvc.perform(get("/api/meetings")).andExpect(status().isUnauthorized());
    }
}
