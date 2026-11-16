package kr.meetinghub;

import java.util.*;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.meetinghub.Domain.Audit;

@Service @Transactional
public class AiSettingsStore {
    public record Input(@NotBlank @Pattern(regexp="openai|codex|mock") String mode,
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") String model,@Size(max=8192) String apiKey) {
        @Override public String toString(){return "AiSettingsInput[apiKey=REDACTED]";}
    }
    final AiSettingsRepository repository;final AiConfig environment;final SecretCipher cipher;final Audits audits;
    public AiSettingsStore(AiSettingsRepository repository,AiConfig environment,SecretCipher cipher,Audits audits){this.repository=repository;this.environment=environment;this.cipher=cipher;this.audits=audits;}
    public AiConfig current(){return repository.findById("ai").map(s->new AiConfig(s.mode,s.model,s.encryptedKey==null||s.mode.equals("mock")?"":cipher.decrypt(s.encryptedKey,"ai-api-key"))).orElse(environment);}
    private SavedAiSettings seed(){return repository.findById("ai").orElseGet(()->{var s=new SavedAiSettings();s.mode=environment.mode();s.model=environment.model();return s;});}
    public Map<String,Object> view(){
        boolean saved=repository.existsById("ai");var s=seed();boolean key=s.encryptedKey!=null||(!saved&&!environment.apiKey().isBlank());
        return Map.of("mode",s.mode,"model",s.model,"keyConfigured",key,"configured",!s.mode.equals("openai")||key,"configurationSource",saved?"database":"environment");
    }
    public Map<String,Object> save(Input input){
        boolean saved=repository.existsById("ai");var s=seed();String key=Objects.toString(input.apiKey(),"").strip();
        if(!key.isBlank())s.encryptedKey=cipher.encrypt(key,"ai-api-key");
        else if(!saved&&!environment.apiKey().isBlank())s.encryptedKey=cipher.encrypt(environment.apiKey(),"ai-api-key");
        MeetingService.require(!input.mode().equals("openai")||s.encryptedKey!=null,"OpenAI API 키를 입력하세요.");
        s.mode=input.mode();s.model=input.model();repository.saveAndFlush(s);audits.save(new Audit("AI_SETTINGS_UPDATED","ai"));return view();
    }
    public Map<String,Object> removeKey(){var s=seed();s.encryptedKey=null;repository.saveAndFlush(s);audits.save(new Audit("AI_KEY_REMOVED","ai"));return view();}
}
@Entity @Table(name="ai_settings")
class SavedAiSettings {
    @Id public String id="ai";
    public String mode="openai",model="gpt-4.1-mini";
    @Column(columnDefinition="text") public String encryptedKey;
    @Version public long version;
}
interface AiSettingsRepository extends JpaRepository<SavedAiSettings,String> {}
