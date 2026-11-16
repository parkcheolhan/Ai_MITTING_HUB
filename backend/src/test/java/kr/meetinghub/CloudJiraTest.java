package kr.meetinghub;

import java.util.*;
import java.net.URI;
import java.io.IOException;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

class CloudJiraTest {
    final ObjectMapper json=new ObjectMapper();
    JiraConfig config(){return new JiraConfig("cloud","https://example.atlassian.net","tester@example.com","unit-test-token","","TEST");}
    Draft draft(){var d=new Draft();d.scheduleMode="MANUAL";d.projectKey="TEST";d.issueType="10001";d.summary="한국어 테스트";d.description="해야 하는 작업\n☐ 환경 점검\n\n회의 근거 00:12:34";d.priority="";return d;}
    @Test void searchesProjectAssignableUsersAndExcludesInactiveAccounts(){
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->{
            assertThat(uri.getPath()).endsWith("/user/assignable/search");assertThat(uri.getQuery()).contains("project=TEST","query=민수","startAt=0");
            return new CloudJira.Reply(200,"[{\"accountId\":\"account-1\",\"displayName\":\"김민수\",\"active\":true},{\"accountId\":\"inactive\",\"displayName\":\"퇴사\",\"active\":false}]");
        });
        assertThat(jira.assignableUsers("TEST","민수","",0).users()).containsExactly(new JiraUser("account-1","김민수",true));
    }
    @Test void publishesExactScheduleInDescriptionAndSupportedDateTimeFields()throws Exception{
        var body=new AtomicReference<String>();var d=draft();d.startAt="2027-03-01T09:00:00+09:00";d.endAt="2027-03-01T18:00:00+09:00";d.dueDate="2027-03-01";d.scheduleMode="AI";d.estimatedMinutes=480;
        var jira=new CloudJira(config(),json,(method,uri,auth,payload)->{
            if(method.equals("POST")){body.set(payload);return new CloudJira.Reply(201,"{\"key\":\"TEST-201\"}");}
            if(uri.getPath().endsWith("/10001"))return new CloudJira.Reply(200,metadata().replace("\"fields\":[","\"fields\":[{\"fieldId\":\"timetracking\",\"name\":\"작업시간\"},{\"fieldId\":\"duedate\",\"name\":\"기한\"},{\"fieldId\":\"customfield_10015\",\"name\":\"Start date\",\"schema\":{\"type\":\"date\"}},{\"fieldId\":\"customfield_10016\",\"name\":\"종료 일시\",\"schema\":{\"type\":\"datetime\"}},"));
            return reads(uri);
        });
        jira.create(d);var fields=json.readTree(body.get()).path("fields");assertThat(fields.path("description").toString()).contains("2027-03-01T09:00:00+09:00","2027-03-01T18:00:00+09:00");assertThat(fields.path("timetracking").path("originalEstimate").asText()).isEqualTo("480m");assertThat(fields.path("customfield_10015").asText()).isEqualTo("2027-03-01");assertThat(fields.path("customfield_10016").asText()).isEqualTo("2027-03-01T18:00:00.000+0900");
    }
    @Test void keepsScheduleInDescriptionWhenJiraHasNoDateFields()throws Exception{
        var body=new AtomicReference<String>();var d=draft();d.startAt="2027-03-01T09:00:00+09:00";d.endAt="2027-03-01T10:00:00+09:00";d.dueDate="2027-03-01";d.scheduleMode="AI";
        var jira=new CloudJira(config(),json,(method,uri,auth,payload)->{if(method.equals("POST")){body.set(payload);return new CloudJira.Reply(201,"{\"key\":\"TEST-202\"}");}return reads(uri);});
        jira.create(d);var fields=json.readTree(body.get()).path("fields");assertThat(fields.path("description").toString()).contains("2027-03-01T09:00:00+09:00");assertThat(fields.has("duedate")).isFalse();
    }
    @Test void resolvesLocalizedSubtaskTypeAndSendsParentKey()throws Exception{
        var body=new AtomicReference<String>();var d=draft();d.parentDraftId="local-parent";d.parent="TEST-100";d.issueType="Sub-task";
        var jira=new CloudJira(config(),json,(method,uri,auth,payload)->{
            if(method.equals("POST")){body.set(payload);return new CloudJira.Reply(201,"{\"key\":\"TEST-101\"}");}
            if(uri.getPath().endsWith("/issuetypes"))return new CloudJira.Reply(200,"{\"issueTypes\":[{\"id\":\"10002\",\"name\":\"하위 작업\",\"subtask\":true}],\"isLast\":true}");
            if(uri.getPath().endsWith("/10002"))return new CloudJira.Reply(200,metadata().replace("\"fields\":[","\"fields\":[{\"fieldId\":\"parent\",\"name\":\"상위\",\"required\":true},"));
            return reads(uri);
        });
        jira.create(d);var fields=json.readTree(body.get()).path("fields");assertThat(fields.path("parent").path("key").asText()).isEqualTo("TEST-100");assertThat(fields.path("issuetype").path("id").asText()).isEqualTo("10002");
        d.issueType="10001";assertThatThrownBy(()->jira.resolveType(d)).hasMessageContaining("하위 작업 유형");
    }
    String metadata(){return """
        {"fields":[{"fieldId":"summary","name":"요약","required":true},
        {"fieldId":"project","name":"프로젝트","required":true},
        {"fieldId":"issuetype","name":"유형","required":true},
        {"fieldId":"description","name":"설명","required":false},
        {"fieldId":"labels","name":"라벨","required":false}],"isLast":true}
        """;}
    CloudJira.Reply reads(URI uri){
        if(uri.getQuery()!=null&&uri.getQuery().equals("fields=status"))return new CloudJira.Reply(200,"{\"fields\":{\"status\":{\"id\":\"100\",\"name\":\"To Do\",\"statusCategory\":{\"key\":\"new\"}}}}");
        if(uri.getPath().endsWith("/search/jql"))return new CloudJira.Reply(200,"{\"issues\":[]}");
        if(uri.getPath().endsWith("/issuetypes"))return new CloudJira.Reply(200,"{\"issueTypes\":[{\"id\":\"10001\",\"name\":\"작업\"}],\"isLast\":true}");
        return new CloudJira.Reply(200,metadata());
    }
    @Test void sendsAuthenticatedAdfWithMetadataTypeAndStableMarker() throws Exception {
        var body=new AtomicReference<String>();var d=draft();
        var jira=new CloudJira(config(),json,(method,uri,auth,payload)->{
            assertThat(auth).startsWith("Basic ");assertThat(uri.getHost()).isEqualTo("example.atlassian.net");
            if(method.equals("POST")){body.set(payload);return new CloudJira.Reply(201,"{\"key\":\"TEST-1\"}");}return reads(uri);
        });
        var result=jira.create(d);assertThat(result.issueUrl()).isEqualTo("https://example.atlassian.net/browse/TEST-1");
        var fields=json.readTree(body.get()).path("fields");assertThat(fields.path("issuetype").path("id").asText()).isEqualTo("10001");
        assertThat(fields.path("description").path("type").asText()).isEqualTo("doc");
        assertThat(fields.path("labels").toString()).contains("meetinghub-"+d.id);
        assertThat(fields.has("assignee")).isFalse();assertThat(fields.has("duedate")).isFalse();
    }
    @Test void existingMarkerReturnsIssueWithoutCreatingAnother() {
        var posts=new AtomicInteger();
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->{if(method.equals("POST"))posts.incrementAndGet();if(uri.getQuery()!=null&&uri.getQuery().equals("fields=status"))return reads(uri);return new CloudJira.Reply(200,"{\"issues\":[{\"key\":\"TEST-42\"}]}");});
        assertThat(jira.create(draft()).issueKey()).isEqualTo("TEST-42");assertThat(posts.get()).isZero();
    }
    @Test void defaultTaskResolvesToLocalizedTaskRatherThanFirstEpic(){
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->new CloudJira.Reply(200,"{\"issueTypes\":[{\"id\":\"10001\",\"name\":\"에픽\"},{\"id\":\"10003\",\"name\":\"작업\",\"subtask\":false}],\"isLast\":true}"));
        var d=draft();d.issueType="Task";assertThat(jira.resolveType(d)).isEqualTo("10003");
    }
    @Test void missingProjectErrorIdentifiesProjectWithoutLeakingRemoteBody(){
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->new CloudJira.Reply(404,"private-account-data"));
        assertThatThrownBy(()->jira.issueTypes("DEMO")).hasMessageContaining("DEMO").hasMessageContaining("조회 권한").hasMessageNotContaining("private-account-data");
        assertThatThrownBy(()->jira.fields("TEST","10003")).hasMessageContaining("TEST").hasMessageContaining("10003");
    }
    @Test void linkingAnExistingIssueOnlyReadsAndValidatesProjectAndParent(){
        var methods=new ArrayList<String>();
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->{methods.add(method);assertThat(uri.getPath()).endsWith("/issue/TEST-42");return new CloudJira.Reply(200,"{\"key\":\"TEST-42\",\"fields\":{\"project\":{\"key\":\"TEST\"},\"issuetype\":{\"subtask\":false}}}");});
        var d=draft();d.existingIssueKey="TEST-42";assertThat(jira.linkExisting(d).issueKey()).isEqualTo("TEST-42");
        d.projectKey="OTHER";assertThatThrownBy(()->jira.linkExisting(d)).hasMessageContaining("프로젝트");
        d.projectKey="TEST";d.parent="TEST-10";assertThatThrownBy(()->jira.linkExisting(d)).hasMessageContaining("부모");
        assertThat(methods).containsOnly("GET");
    }
    @Test void existingSubtaskMustBelongToTheChosenParent(){
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->new CloudJira.Reply(200,"{\"key\":\"TEST-42\",\"fields\":{\"project\":{\"key\":\"TEST\"},\"issuetype\":{\"subtask\":true},\"parent\":{\"key\":\"TEST-10\"}}}"));
        var d=draft();d.existingIssueKey="TEST-42";d.parent="TEST-11";assertThatThrownBy(()->jira.linkExisting(d)).hasMessageContaining("부모");
        d.parent="TEST-10";assertThat(jira.linkExisting(d).issueKey()).isEqualTo("TEST-42");
    }
    @Test void createsEpicByHierarchyAndLinksStandardTaskUsingParentField()throws Exception{
        var bodies=new ArrayList<JsonNode>();
        var jira=new CloudJira(config(),json,(method,uri,auth,payload)->{
            if(method.equals("POST")){bodies.add(json.readTree(payload));return new CloudJira.Reply(201,"{\"key\":\"TEST-"+bodies.size()+"\"}");}
            if(uri.getPath().endsWith("/issuetypes"))return new CloudJira.Reply(200,"{\"issueTypes\":[{\"id\":\"10001\",\"name\":\"큰 업무\",\"hierarchyLevel\":1},{\"id\":\"10003\",\"name\":\"작업\",\"hierarchyLevel\":0}],\"isLast\":true}");
            if(uri.getPath().endsWith("/10001"))return new CloudJira.Reply(200,metadata().replace("\"fields\":[","\"fields\":[{\"fieldId\":\"customfield_10011\",\"name\":\"에픽 이름\",\"required\":true,\"schema\":{\"custom\":\"com.pyxis.greenhopper.jira:gh-epic-label\"}},"));
            if(uri.getPath().endsWith("/10003"))return new CloudJira.Reply(200,metadata().replace("\"fields\":[","\"fields\":[{\"fieldId\":\"parent\",\"name\":\"상위\",\"required\":true},"));
            return reads(uri);
        });
        var epic=draft();epic.issueType="Epic";var result=jira.create(epic);
        var task=draft();task.issueType="Task";task.parent=result.issueKey();jira.create(task);
        assertThat(bodies.get(0).path("fields").path("issuetype").path("id").asText()).isEqualTo("10001");
        assertThat(bodies.get(0).path("fields").path("customfield_10011").asText()).isEqualTo(epic.summary);
        assertThat(bodies.get(1).path("fields").path("parent").path("key").asText()).isEqualTo("TEST-1");
    }
    @Test void timeoutAfterPostIsUncertainAndNotADefiniteFailure() {
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->{if(method.equals("POST"))throw new IOException("secret-network-detail");return reads(uri);});
        assertThatThrownBy(()->jira.create(draft())).isInstanceOf(CloudJira.Uncertain.class).hasMessageNotContaining("secret-network-detail");
    }
    @Test void refusesUnsupportedRequiredFieldBeforeAnyPost() {
        var posts=new AtomicInteger();
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->{
            if(method.equals("POST"))posts.incrementAndGet();
            if(uri.getPath().endsWith("/10001"))return new CloudJira.Reply(200,metadata().replace("\"fields\":[","\"fields\":[{\"fieldId\":\"customfield_123\",\"name\":\"팀\",\"required\":true},"));
            return reads(uri);
        });
        assertThatThrownBy(()->jira.create(draft())).hasMessageContaining("추가 필수 필드");assertThat(posts.get()).isZero();
    }
    @Test void authFailuresDoNotExposeRemoteBodyOrCredentials() {
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->new CloudJira.Reply(401,"unit-test-token private-data"));
        assertThatThrownBy(jira::test).hasMessageContaining("401").hasMessageNotContaining("unit-test-token").hasMessageNotContaining("private-data");
        assertThat(config().toString()).doesNotContain("unit-test-token","tester@example.com");
    }
    @Test void scopedTokenUsesCloudGatewayAndUnsafeSitesAreRejected() {
        var c=new JiraConfig("cloud","https://example.atlassian.net","e","t","cloud-123","TEST");
        assertThat(c.apiBase().toString()).isEqualTo("https://api.atlassian.com/ex/jira/cloud-123/rest/api/3");
        for(String site:List.of("http://example.atlassian.net","https://evil.test","https://example.atlassian.net@evil.test","https://example.atlassian.net/path"))
            assertThatThrownBy(()->new JiraConfig("cloud",site,"e","t","","TEST").site()).isInstanceOf(IllegalArgumentException.class);
    }
}
