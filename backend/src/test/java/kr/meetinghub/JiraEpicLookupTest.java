package kr.meetinghub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JiraEpicLookupTest {
    final ObjectMapper json=new ObjectMapper();
    JiraConfig config(){return new JiraConfig("cloud","https://example.atlassian.net","test@example.com","synthetic","","TEST");}
    String issue(String project,int level){return "{\"key\":\"TEST-42\",\"fields\":{\"project\":{\"key\":\""+project+"\"},\"issuetype\":{\"name\":\"기획\",\"hierarchyLevel\":"+level+"},\"summary\":\"서비스 개선\",\"status\":{\"name\":\"진행 중\"}}}";}
    @Test void searchesOnlyProjectEpicsWithEscapedQueryAndCursor(){
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->{
            assertThat(method).isEqualTo("GET");
            if(uri.getPath().endsWith("/issuetypes"))return new CloudJira.Reply(200,"{\"isLast\":true,\"issueTypes\":[{\"id\":\"100\",\"name\":\"기획\",\"hierarchyLevel\":1},{\"id\":\"101\",\"name\":\"작업\",\"hierarchyLevel\":0}]}");
            assertThat(uri.getPath()).endsWith("/search/jql");assertThat(java.net.URLDecoder.decode(uri.getRawQuery(),java.nio.charset.StandardCharsets.UTF_8)).contains("project = TEST AND issuetype in (100)","summary ~ \"서비스 OR project OTHER\"","nextPageToken=cursor/+==");
            return new CloudJira.Reply(200,"{\"issues\":["+issue("TEST",1)+"],\"nextPageToken\":\"next\"}");
        });
        var page=jira.epics("TEST","서비스\" OR project=OTHER","cursor/+==");assertThat(page.epics()).hasSize(1);assertThat(page.epics().get(0).key()).isEqualTo("TEST-42");assertThat(page.nextPageToken()).isEqualTo("next");
    }
    @Test void verifiesExistingEpicTypeAndProjectBeforeAdding(){
        for(String response:new String[]{issue("OTHER",1),issue("TEST",0)}){
            var jira=new CloudJira(config(),json,(method,uri,auth,body)->new CloudJira.Reply(200,response));
            assertThatThrownBy(()->jira.epic("TEST","TEST-42")).isInstanceOf(IllegalArgumentException.class);
        }
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->{assertThat(method).isEqualTo("GET");return new CloudJira.Reply(200,issue("TEST",1));});
        assertThat(jira.epic("TEST","TEST-42").summary()).isEqualTo("서비스 개선");
        assertThatThrownBy(()->jira.epic("TEST","TEST-42/other")).isInstanceOf(IllegalArgumentException.class);
    }
}
