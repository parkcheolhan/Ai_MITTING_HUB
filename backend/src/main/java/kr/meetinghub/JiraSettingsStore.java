package kr.meetinghub;

import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.meetinghub.Domain.Audit;

@Service @Transactional
public class JiraSettingsStore {
    public record Input(@NotBlank @Pattern(regexp="mock|cloud") String mode,
        @Size(max=300) String siteUrl,@Email @Size(max=254) String email,
        @Size(max=8192) String apiToken,@Size(max=100) String cloudId) {
        @Override public String toString(){return "JiraSettingsInput[credentials=REDACTED]";}
    }
    public record ProjectInput(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,19}") String projectKey) {}
    final JiraSettingsRepository repository;final JiraConfig environment;final SecretCipher cipher;final Audits audits;
    public JiraSettingsStore(JiraSettingsRepository repository,JiraConfig environment,SecretCipher cipher,Audits audits) {
        this.repository=repository;this.environment=environment;this.cipher=cipher;this.audits=audits;
    }
    public JiraConfig current() {
        return repository.findById("jira").map(s->new JiraConfig(s.mode,s.siteUrl,s.email,
            s.encryptedToken==null||s.mode.equals("mock")?"":cipher.decrypt(s.encryptedToken),s.cloudId,s.defaultProject)).orElse(environment);
    }
    private SavedJiraSettings seed() {
        return repository.findById("jira").orElseGet(()->{
            var s=new SavedJiraSettings();s.mode=environment.mode();s.siteUrl=environment.siteUrl();s.email=environment.email();
            s.cloudId=environment.cloudId();s.defaultProject=environment.defaultProject();return s;
        });
    }
    private boolean hasToken(SavedJiraSettings s,boolean saved) {return s.encryptedToken!=null||(!saved&&!environment.apiToken().isBlank());}
    private void retainEnvironmentToken(SavedJiraSettings s,boolean saved) {
        if(!saved&&!environment.apiToken().isBlank())s.encryptedToken=cipher.encrypt(environment.apiToken());
    }
    public Map<String,Object> view() {
        boolean saved=repository.existsById("jira");var s=seed();boolean token=hasToken(s,saved);
        var missing=new ArrayList<String>();
        if(s.siteUrl.isBlank())missing.add("사이트 URL");if(s.email.isBlank())missing.add("계정 이메일");if(!token)missing.add("API 토큰");
        Map<String,Object> result=new LinkedHashMap<>();result.put("mode",s.mode);result.put("siteUrl",s.siteUrl);
        result.put("email",s.email);result.put("cloudId",s.cloudId);result.put("defaultProject",s.defaultProject);
        result.put("configured",s.mode.equals("cloud")&&missing.isEmpty());result.put("missing",missing);
        result.put("tokenConfigured",token);result.put("configurationSource",saved?"database":"environment");
        result.put("stt","전사 텍스트 가져오기");result.put("authentication",false);return result;
    }
    public Map<String,Object> save(Input input) {
        boolean saved=repository.existsById("jira");var s=seed();
        var next=new JiraConfig(input.mode(),input.siteUrl(),input.email(),"",input.cloudId(),s.defaultProject);
        String token=Objects.toString(input.apiToken(),"").strip();
        boolean identityChanged=!next.siteUrl().equals(s.siteUrl)||!next.email().equals(s.email);
        if(next.mode().equals("cloud")) {
            next.apiBase();MeetingService.require(!next.email().isBlank(),"Jira 계정 이메일을 입력하세요.");
            MeetingService.require(!token.isBlank()||hasToken(s,saved),"API 토큰을 입력하세요.");
            MeetingService.require(!identityChanged||!token.isBlank(),"사이트 또는 계정을 변경할 때는 새 API 토큰을 입력하세요.");
        }
        if(!token.isBlank())s.encryptedToken=cipher.encrypt(token);else retainEnvironmentToken(s,saved);
        if(!next.siteUrl().equals(s.siteUrl))s.defaultProject="";
        s.mode=next.mode();s.siteUrl=next.siteUrl();s.email=next.email();s.cloudId=next.cloudId();repository.saveAndFlush(s);
        audits.save(new Audit("JIRA_SETTINGS_UPDATED","jira"));return view();
    }
    public Map<String,Object> saveProject(String key) {
        CloudJira.projectKey(key);boolean saved=repository.existsById("jira");var s=seed();
        retainEnvironmentToken(s,saved);s.defaultProject=key;repository.saveAndFlush(s);
        audits.save(new Audit("JIRA_DEFAULT_PROJECT_UPDATED","jira"));return view();
    }
    public Map<String,Object> removeToken() {
        var s=seed();s.encryptedToken=null;repository.saveAndFlush(s);
        audits.save(new Audit("JIRA_TOKEN_REMOVED","jira"));return view();
    }
}
