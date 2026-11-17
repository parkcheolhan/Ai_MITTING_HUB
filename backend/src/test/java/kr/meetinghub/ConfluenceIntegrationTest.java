package kr.meetinghub;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.*;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:confluence;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","jira.mode=mock","app.secret-key-file=./target/confluence-test.key"})
@AutoConfigureMockMvc
class ConfluenceIntegrationTest {
    @TestConfiguration static class Config {@Bean @Primary FakeConfluence fakeConfluence(ObjectMapper json){return new FakeConfluence(json);}}
    static class FakeConfluence extends ConfluenceClient {
        final Map<String,Page> remote=new LinkedHashMap<>();int creates,updates;boolean uncertain,loseCreate,failUpdate;String lastHtml="";int lastVersion;
        FakeConfluence(ObjectMapper json){super(json,r->{throw new AssertionError("Real HTTP must never be used");});}
        @Override JsonNode request(ConfluenceSettings.Config c,String method,String path,Object body){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(path.startsWith("/spaces/1/pages"))return json.valueToTree(Map.of("results",remote.values().stream().filter(p->p.spaceId().equals("1")).map(this::node).toList()));
            if(path.equals("/spaces/1"))return json.valueToTree(Map.of("id","1","name","제품 개발","key","DEV"));
            if(path.startsWith("/spaces?"))return json.valueToTree(Map.of("results",List.of(Map.of("id","1","name","제품 개발","key","DEV"))));
            if(path.equals("/pages/10?body-format=storage"))return node(new Page("10","회의록 모음","1","",1,""));
            if(path.equals("/pages/20?body-format=storage"))return node(new Page("20","다른 스페이스","2","",1,""));
            if(method.equals("GET")&&path.startsWith("/pages/")){var p=remote.get(path.substring(7).split("\\?")[0]);if(p==null)throw new RemoteFailure("찾을 수 없음",false);return node(p);}
            var n=json.valueToTree(body);
            if(method.equals("POST")){
                creates++;String id=""+(100+creates);lastHtml=n.path("body").path("value").asText();var p=new Page(id,n.path("title").asText(),n.path("spaceId").asText(),n.path("parentId").asText(""),1,lastHtml);
                if(!loseCreate)remote.put(id,p);if(uncertain)throw new RemoteFailure("응답 유실",true);return node(p);
            }
            if(method.equals("PUT")){
                if(failUpdate)throw new RemoteFailure("문서 버전 충돌",false);updates++;String id=n.path("id").asText();var old=remote.get(id);lastVersion=n.path("version").path("number").asInt();assertThat(lastVersion).isEqualTo(old.version()+1);
                lastHtml=n.path("body").path("value").asText();var p=new Page(id,old.title(),old.spaceId(),old.parentId(),lastVersion,lastHtml);remote.put(id,p);return node(p);
            }
            throw new AssertionError(method+" "+path);
        }
        JsonNode node(Page p){var map=new LinkedHashMap<String,Object>();map.put("id",p.id());map.put("title",p.title());map.put("spaceId",p.spaceId());map.put("parentId",p.parentId());map.put("version",Map.of("number",p.version()));map.put("body",Map.of("storage",Map.of("value",p.body())));return json.valueToTree(map);}
    }
    @Autowired ConfluenceSettings settings;@Autowired ConfluenceSettingsRepository configs;@Autowired ConfluencePublications publications;@Autowired ConfluencePublisher publisher;@Autowired FakeConfluence client;
    @Autowired MeetingService meetings;@Autowired TransactionTemplate tx;@Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JiraSettingsStore jira;@Autowired JiraSettingsRepository jiraRows;
    ConfluenceSettings.Connection connection(String token){return new ConfluenceSettings.Connection(true,false,"https://unit.atlassian.net","unit@example.com",token,"");}
    @BeforeEach void reset(){jiraRows.deleteAll();configs.deleteAll();publications.deleteAll();client.remote.clear();client.creates=client.updates=0;client.uncertain=client.loseCreate=client.failUpdate=false;settings.save(connection("fake-confluence-secret"));destination(true,"10");}
    void destination(boolean automatic,String parent){var c=settings.current();settings.destination(new ConfluenceSettings.Destination("1",parent,automatic),c,new ConfluenceClient.Space("1","제품 개발","DEV"),parent.isBlank()?null:new ConfluenceClient.Page(parent,"회의록 모음","1","",1,""));}
    String meeting(){var id=meetings.create(new CreateMeeting("제품 회의 "+UUID.randomUUID(),"2026-11-17T10:00",List.of("김민수"),"TEST","","회의 내용입니다.","TEXT","ko",false,"일반")).id();var m=new Minutes();m.overview="<script>alert('unsafe')</script> & 결정";m.summary="배포 검토";m.discussion="의견을 모읍니다.";m.decisions="다음 주 배포";m.openQuestions="없음";m.followUp="테스트";tx.executeWithoutResult(s->{var meeting=meetings.lock(id);var content=meetings.content(meeting);content.minutes=m;meetings.save(meeting,content);});return id;}
    Draft task(String id){return meetings.addDraft(id,new DraftInput("TEST","Task","보안 검토 <업무>","내용","","","",List.of(),"",List.of()));}
    @Test void tokenIsEncryptedRetainedAndRedacted()throws Exception{
        var stored=configs.findById("confluence").orElseThrow();assertThat(stored.encryptedToken).startsWith("v1.").doesNotContain("fake-confluence-secret");
        settings.save(connection(""));assertThat(settings.current().credentials().apiToken()).isEqualTo("fake-confluence-secret");
        var response=mvc.perform(get("/api/settings/confluence")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();assertThat(response).doesNotContain("fake-confluence-secret","encryptedToken");
        assertThat(connection("fake-secret").toString()).doesNotContain("fake-secret");assertThat(settings.current().toString()).doesNotContain("fake-confluence-secret");
    }
    @Test void rejectsForeignOriginsAndParentsFromAnotherSpace()throws Exception{
        assertThatThrownBy(()->settings.save(new ConfluenceSettings.Connection(true,false,"https://evil.example","unit@example.com","fake",""))).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(put("/api/settings/confluence/destination").contentType("application/json").content("{\"spaceId\":\"1\",\"parentPageId\":\"20\",\"automatic\":true}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/confluence/destination").contentType("application/json").content("{\"spaceId\":\"1\",\"parentPageId\":\"https://evil.example/wiki/pages/10\"}")).andExpect(status().isBadRequest());
        assertThat(settings.current().parentPageId()).isEqualTo("10");
    }
    @Test void acceptsSameSiteParentUrlAndValidatesDestination()throws Exception{
        mvc.perform(put("/api/settings/confluence/destination").contentType("application/json").content("{\"spaceId\":\"1\",\"parentPageId\":\"https://unit.atlassian.net/wiki/spaces/DEV/pages/10/Minutes\",\"automatic\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.parentPageId").value("10"));
    }
    @Test void createsEscapedMinutesAndClickableTicketTableThenUpdatesSamePage(){
        String id=meeting();var draft=task(id);tx.executeWithoutResult(s->{var d=meetings.drafts().findById(draft.id).orElseThrow();d.status="PUBLISHED";d.issueKey="TEST-42";d.issueUrl="https://unit.atlassian.net/browse/TEST-42";d.assigneeName="김민수";meetings.drafts().save(d);});
        var first=publisher.publish(id);assertThat(first.status()).isEqualTo("PUBLISHED");assertThat(first.pageUrl()).isEqualTo("https://unit.atlassian.net/wiki/pages/viewpage.action?pageId=101");
        assertThat(client.lastHtml).contains("&lt;script&gt;","회의에서 생성된 Jira 티켓","<table>","https://unit.atlassian.net/browse/TEST-42","/tickets?ticket="+draft.id,"김민수").doesNotContain("<script>");
        var minutes=meetings.get(id).content().minutes;minutes.decisions="새로운 결정";meetings.saveMinutes(id,minutes);assertThat(publisher.publish(id).status()).isEqualTo("PUBLISHED");assertThat(client.creates).isEqualTo(1);assertThat(client.updates).isEqualTo(1);assertThat(client.lastVersion).isEqualTo(2);assertThat(client.lastHtml).contains("새로운 결정");
    }
    @Test void ambiguousPostReconcilesWithoutDuplicateCreation(){String id=meeting();client.uncertain=true;assertThat(publisher.publish(id).status()).isEqualTo("UNKNOWN");client.uncertain=false;assertThat(publisher.publish(id).status()).isEqualTo("PUBLISHED");assertThat(client.creates).isEqualTo(1);assertThat(client.updates).isEqualTo(1);}
    @Test void unknownMissingPageNeverBlindlyPostsAgain(){String id=meeting();client.uncertain=true;client.loseCreate=true;assertThat(publisher.publish(id).status()).isEqualTo("UNKNOWN");client.uncertain=false;assertThat(publisher.publish(id).status()).isEqualTo("UNKNOWN");assertThat(client.creates).isEqualTo(1);}
    @Test void foreignPageWithSameTitleIsNeverOverwritten(){String id=meeting();var m=meetings.get(id);client.remote.put("999",new ConfluenceClient.Page("999",ConfluenceDocument.title(m),"1","10",1,"<p>다른 문서</p>"));assertThat(publisher.publish(id).status()).isEqualTo("FAILED");assertThat(client.creates+client.updates).isZero();}
    @Test void retryAfterUpdateConflictReusesDurablePageId(){String id=meeting();publisher.publish(id);client.failUpdate=true;assertThat(publisher.publish(id).status()).isEqualTo("FAILED");client.failUpdate=false;assertThat(publisher.publish(id).status()).isEqualTo("PUBLISHED");assertThat(client.creates).isEqualTo(1);}
    @Test void topLevelPagesCanBeUpdated(){destination(true,"");String id=meeting();publisher.publish(id);assertThat(publisher.publish(id).status()).isEqualTo("PUBLISHED");assertThat(client.creates).isEqualTo(1);}
    @Test void autoSyncHonorsSwitchAndSuccessfulMinutesSaveSurvivesConfluenceFailure()throws Exception{
        String id=meeting();destination(false,"10");var m=meetings.get(id).content().minutes;
        mvc.perform(put("/api/meetings/"+id+"/minutes").contentType("application/json").content(json.writeValueAsString(m))).andExpect(status().isOk());assertThat(client.creates).isZero();
        destination(true,"10");client.uncertain=true;m.decisions="저장된 결정";
        mvc.perform(put("/api/meetings/"+id+"/minutes").contentType("application/json").content(json.writeValueAsString(m))).andExpect(status().isOk());assertThat(meetings.get(id).content().minutes.decisions).isEqualTo("저장된 결정");assertThat(publisher.view(id).status()).isEqualTo("UNKNOWN");
    }
    @Test void jiraPublicationRefreshesTicketTableInSameConfluencePage()throws Exception{
        String id=meeting();var d=task(id);publisher.publish(id);meetings.approve(List.of(d.id));
        mvc.perform(post("/api/ticket-drafts/publish").contentType("application/json").content(json.writeValueAsString(Map.of("ids",List.of(d.id))))).andExpect(status().isOk());
        assertThat(meetings.get(id).drafts().get(0).status).isEqualTo("PUBLISHED");assertThat(client.creates).isEqualTo(1);assertThat(client.updates).isEqualTo(1);assertThat(client.lastHtml).contains("MOCK-","Mock 등록");
    }
    @Test void jiraCredentialsAreResolvedOnlyOnServerAndSiteChangeInvalidatesDestination(){
        jira.save(new JiraSettingsStore.Input("cloud","https://jira.atlassian.net","jira@example.com","fake-jira-token",""));settings.save(new ConfluenceSettings.Connection(true,true,"","","",""));assertThat(settings.current().credentials().apiToken()).isEqualTo("fake-jira-token");assertThat(settings.current().configured()).isFalse();assertThat(json.valueToTree(settings.view()).toString()).doesNotContain("fake-jira-token");
    }
    @Test void inlineMinutesSaveRefreshesTheExistingConfluenceDocument()throws Exception{
        String id=meeting();publisher.publish(id);var minutes=meetings.get(id).content().minutes;
        mvc.perform(patch("/api/meetings/"+id+"/minutes/line").contentType("application/json").content(json.writeValueAsString(new MinutesLineInput("decisions",0,minutes.decisions,"문서에서 바로 수정한 결정",minutes.version)))).andExpect(status().isOk());
        assertThat(client.creates).isEqualTo(1);assertThat(client.updates).isEqualTo(1);assertThat(client.lastHtml).contains("문서에서 바로 수정한 결정");
    }
}
