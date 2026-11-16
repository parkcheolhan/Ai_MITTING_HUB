package kr.meetinghub;

import java.util.*;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix="jira")
public record JiraConfig(String mode,String siteUrl,String email,String apiToken,String cloudId,String defaultProject) {
    public JiraConfig {
        mode=Objects.toString(mode,"mock"); siteUrl=Objects.toString(siteUrl,"").strip().replaceAll("/+$","");
        email=Objects.toString(email,"").strip(); apiToken=Objects.toString(apiToken,"").strip();
        cloudId=Objects.toString(cloudId,"").strip(); defaultProject=Objects.toString(defaultProject,"").strip();
    }
    // Never include credential values in generated record diagnostics.
    @Override public String toString() { return "JiraConfig[mode="+mode+", credentials=REDACTED]"; }
    public List<String> missing() {
        var result=new ArrayList<String>();
        if(siteUrl.isBlank())result.add("JIRA_SITE_URL"); if(email.isBlank())result.add("JIRA_EMAIL");
        if(apiToken.isBlank())result.add("JIRA_API_TOKEN"); return result;
    }
    public URI site() {
        MeetingService.require(!siteUrl.isBlank(),"JIRA_SITE_URL을 설정하세요.");
        URI uri;
        try { uri=URI.create(siteUrl); } catch(Exception e) { throw new IllegalArgumentException("Jira 사이트 URL 형식이 잘못되었습니다."); }
        MeetingService.require("https".equals(uri.getScheme())&&uri.getHost()!=null
            &&uri.getHost().endsWith(".atlassian.net")&&uri.getUserInfo()==null&&uri.getPort()==-1
            &&(uri.getPath()==null||uri.getPath().isEmpty())&&uri.getQuery()==null&&uri.getFragment()==null,
            "Jira Cloud 사이트 주소는 https://팀이름.atlassian.net 형식으로 입력하세요.");
        return uri;
    }
    public URI apiBase() {
        site();
        if(cloudId.isBlank())return URI.create(siteUrl+"/rest/api/3");
        MeetingService.require(cloudId.matches("[a-zA-Z0-9-]{1,100}"),"JIRA_CLOUD_ID 형식이 잘못되었습니다.");
        return URI.create("https://api.atlassian.com/ex/jira/"+cloudId+"/rest/api/3");
    }
}

@Configuration @EnableConfigurationProperties(JiraConfig.class)
class JiraConfiguration {}
