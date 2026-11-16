package kr.meetinghub;

import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JiraBoardStatusTest {
    Domain.Result result(){return new Domain.Result("draft","PUBLISHED","TEST-1","https://example.atlassian.net/browse/TEST-1",null);}
    String status(String id,String name,String category){return "{\"fields\":{\"status\":{\"id\":\""+id+"\",\"name\":\""+name+"\",\"statusCategory\":{\"key\":\""+category+"\"}}}}";}
    String transition(){return "{\"transitions\":[{\"id\":\"21\",\"to\":{\"id\":\"100\",\"name\":\"해야 할 일\",\"statusCategory\":{\"key\":\"new\"}}}]}";}
    @Test void transitionsAndVerifiesEveryCreatedIssueType() {
        for(String type: new String[]{"Epic","Task","Sub-task"}) {
            var fixture=new CloudJiraTest();var moved=new AtomicBoolean();var posts=new AtomicInteger();
            var jira=new CloudJira(fixture.config(),fixture.json,(method,uri,auth,body)->{
                if(uri.getPath().endsWith("/transitions")) {
                    if(method.equals("GET"))return new CloudJira.Reply(200,transition());
                    assertThat(body).contains("\"id\":\"21\"");moved.set(true);posts.incrementAndGet();return new CloudJira.Reply(204,"");
                }
                return new CloudJira.Reply(200,moved.get()?status("100","해야 할 일","new"):status("200","진행 중","indeterminate"));
            });
            assertThat(jira.readyForBoard(result()).status()).as(type).isEqualTo("PUBLISHED");assertThat(posts.get()).isEqualTo(1);
        }
    }
    @Test void alreadyTodoDoesNotWrite() {
        var fixture=new CloudJiraTest();var jira=new CloudJira(fixture.config(),fixture.json,(method,uri,auth,body)->{
            assertThat(method).isEqualTo("GET");assertThat(uri.getQuery()).isEqualTo("fields=status");return new CloudJira.Reply(200,status("100","To Do","new"));
        });assertThat(jira.readyForBoard(result())).isEqualTo(result());
    }
    @Test void unavailableTransitionIsNotReportedAsSuccess() {
        var fixture=new CloudJiraTest();var jira=new CloudJira(fixture.config(),fixture.json,(method,uri,auth,body)->new CloudJira.Reply(200,uri.getPath().endsWith("/transitions")?"{\"transitions\":[]}":status("200","진행 중","indeterminate")));
        assertThatThrownBy(()->jira.readyForBoard(result())).isInstanceOf(CloudJira.Uncertain.class).hasMessageContaining("TEST-1","워크플로","기존 이슈");
    }
    @Test void ignoredTransitionIsNotReportedAsSuccess() {
        var fixture=new CloudJiraTest();var jira=new CloudJira(fixture.config(),fixture.json,(method,uri,auth,body)->new CloudJira.Reply(200,uri.getPath().endsWith("/transitions")?transition():status("200","진행 중","indeterminate")));
        assertThatThrownBy(()->jira.readyForBoard(result())).isInstanceOf(CloudJira.Uncertain.class).hasMessageContaining("전환 결과");
    }
}
