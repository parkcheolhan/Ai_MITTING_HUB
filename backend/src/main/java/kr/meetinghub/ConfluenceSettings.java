package kr.meetinghub;

import java.util.*;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Credentials stay on the local server; all views exclude the token. */
@Service @Transactional
public class ConfluenceSettings {
    final ConfluenceSettingsRepository rows;final JiraSettingsStore jira;final SecretCipher cipher;
    ConfluenceSettings(ConfluenceSettingsRepository rows,JiraSettingsStore jira,SecretCipher cipher){this.rows=rows;this.jira=jira;this.cipher=cipher;}
    public record Connection(boolean enabled,boolean useJiraCredentials,@Size(max=300) String siteUrl,@Email @Size(max=254) String email,@Size(max=8192) String apiToken,@Size(max=100) String cloudId){
        @Override public String toString(){return "ConfluenceConnection[credentials=REDACTED]";}
    }
    public record Destination(@NotBlank @Pattern(regexp="[0-9]+") String spaceId,@Size(max=500) String parentPageId,boolean automatic){}
    public record Config(boolean enabled,JiraConfig credentials,String spaceId,String spaceName,String parentPageId,String parentTitle,boolean automatic){
        @Override public String toString(){return "ConfluenceConfig[credentials=REDACTED]";}
        boolean connected(){return enabled&&credentials.mode().equals("cloud")&&credentials.missing().isEmpty();}
        boolean configured(){return connected()&&!spaceId.isBlank();}
        String target(){return credentials.siteUrl()+"|"+credentials.cloudId()+"|"+spaceId+"|"+parentPageId;}
    }
    SavedConfluenceSettings seed(){return rows.findById("confluence").orElseGet(SavedConfluenceSettings::new);}
    public Config current(){return config(true);}
    Config config(boolean includeSecret){
        var s=seed();JiraConfig c;
        if(s.useJiraCredentials){if(includeSecret)c=jira.current();else{var v=jira.view();c=new JiraConfig((String)v.get("mode"),(String)v.get("siteUrl"),(String)v.get("email"),Boolean.TRUE.equals(v.get("tokenConfigured"))?"configured":"",(String)v.get("cloudId"),"");}}
        else c=new JiraConfig("cloud",s.siteUrl,s.email,s.encryptedToken==null?"":includeSecret?cipher.decrypt(s.encryptedToken,"confluence-token"):"configured",s.cloudId,"");
        // A destination belongs to the account/site against which it was validated.
        boolean same=s.destinationSite.equals(c.siteUrl()+"|"+c.cloudId());
        return new Config(s.enabled,c,same?s.spaceId:"",same?s.spaceName:"",same?s.parentPageId:"",same?s.parentTitle:"",s.automatic);
    }
    public Map<String,Object> view(){
        var s=seed();var c=config(false);var result=new LinkedHashMap<String,Object>();
        result.put("enabled",s.enabled);result.put("useJiraCredentials",s.useJiraCredentials);result.put("siteUrl",c.credentials.siteUrl());result.put("email",c.credentials.email());result.put("cloudId",c.credentials.cloudId());
        result.put("tokenConfigured",!c.credentials.apiToken().isBlank());result.put("connected",c.connected());result.put("configured",c.configured());result.put("spaceId",c.spaceId);result.put("spaceName",c.spaceName);
        result.put("parentPageId",c.parentPageId);result.put("parentTitle",c.parentTitle);result.put("automatic",c.automatic);return result;
    }
    public Map<String,Object> save(Connection input){
        var s=seed();var next=new JiraConfig("cloud",input.siteUrl,input.email,"",input.cloudId,"");String token=Objects.toString(input.apiToken,"").strip();
        if(input.enabled){
            var check=input.useJiraCredentials?jira.current():new JiraConfig("cloud",next.siteUrl(),next.email(),token.isBlank()&&s.encryptedToken!=null?cipher.decrypt(s.encryptedToken,"confluence-token"):token,next.cloudId(),"");
            MeetingService.require(check.mode().equals("cloud")&&check.missing().isEmpty(),"Confluence 사이트·이메일·API 토큰을 설정하세요. Jira 계정 사용 시 Jira Cloud 연결을 먼저 설정하세요.");check.apiBase();
            if(!input.useJiraCredentials)MeetingService.require((next.siteUrl().equals(s.siteUrl)&&next.email().equals(s.email))||!token.isBlank(),"사이트 또는 계정을 변경할 때는 새 API 토큰을 입력하세요.");
        }
        s.enabled=input.enabled;s.useJiraCredentials=input.useJiraCredentials;
        if(!input.useJiraCredentials){s.siteUrl=next.siteUrl();s.email=next.email();s.cloudId=next.cloudId();if(!token.isBlank())s.encryptedToken=cipher.encrypt(token,"confluence-token");}
        rows.saveAndFlush(s);return view();
    }
    public Map<String,Object> destination(Destination input,Config expected,ConfluenceClient.Space space,ConfluenceClient.Page parent){
        MeetingService.require(current().credentials.equals(expected.credentials),"연결 설정이 변경되었습니다. 업로드 위치를 다시 선택하세요.");
        var s=seed();s.spaceId=space.id();s.spaceName=space.name();s.parentPageId=parent==null?"":parent.id();s.parentTitle=parent==null?"":parent.title();s.automatic=input.automatic;
        s.destinationSite=expected.credentials.siteUrl()+"|"+expected.credentials.cloudId();rows.saveAndFlush(s);return view();
    }
}
@Entity @Table(name="confluence_settings")
class SavedConfluenceSettings {
    @Id public String id="confluence";
    public boolean enabled=false,useJiraCredentials=true,automatic=true;
    public String siteUrl="",email="",cloudId="",spaceId="",spaceName="",parentPageId="",parentTitle="",destinationSite="";
    @Column(length=20000) public String encryptedToken;
    @Version public long version;
}
interface ConfluenceSettingsRepository extends JpaRepository<SavedConfluenceSettings,String>{}
