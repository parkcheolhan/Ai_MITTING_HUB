package kr.meetinghub;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:epic-board;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
@Import(EpicBoardTest.Config.class)
@AutoConfigureMockMvc
class EpicBoardTest {
    @TestConfiguration static class Config {@Bean @Primary BoardJira boardJira(){return new BoardJira();}}
    static class BoardJira implements Adapters.Jira {
        String target="board-test";int sequence,descriptionUpdates;final List<Draft> calls=new ArrayList<>();
        public String target(){return target;}
        public Result create(Draft d){calls.add(d);return new Result(d.id,"PUBLISHED","TEST-"+(++sequence),null,null);}
        public void updateDescription(String key,String description){descriptionUpdates++;}
        public JiraEpic epic(String project,String key){MeetingService.require(project.equals("TEST")&&key.equals("TEST-100"),"에픽을 찾을 수 없습니다.");return new JiraEpic(key,project,"기존 에픽","진행 중",null);}
    }
    @Autowired MeetingService service;@Autowired EpicBoard board;@Autowired Publisher publisher;@Autowired BoardJira jira;
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @BeforeEach void reset(){jira.calls.clear();jira.sequence=0;jira.descriptionUpdates=0;jira.target="board-test";}
    String meeting(){return service.create(new CreateMeeting("에픽 배치 검증","",List.of(),"TEST","","업무를 확인합니다.","TEXT","ko",false,"일반")).id();}
    Draft task(String m,String name,String parent){return service.addDraft(m,new DraftInput("TEST",parent.isBlank()?"Task":"Sub-task",name,"작업 범위를 검토합니다.","","","",List.of(),"",List.of(),parent));}
    Draft fresh(Draft d){return service.get(d.meetingId).drafts().stream().filter(x->x.id.equals(d.id)).findFirst().orElseThrow();}
    String add(String m,String title){return board.add(m,new AddEpic(null,title)).epics().stream().filter(e->e.summary().equals(title)).findFirst().orElseThrow().id();}
    @Test void movesTaskAndChildrenAtomicallyAndPublishesUnderTheChosenEpic(){
        String m=meeting();var root=task(m,"상위업무","");var child=task(m,"하위업무",root.id);String first=add(m,"첫 에픽"),second=add(m,"두 번째 에픽");
        service.approve(List.of(root.id,child.id));long old=fresh(root).version;
        var moved=board.move(root.id,new MoveEpic(first,old));assertThat(moved).hasSize(2).allSatisfy(d->{assertThat(d.epicId).isEqualTo(first);assertThat(d.status).isEqualTo("APPROVED");assertThat(d.approvedTarget).isEqualTo(jira.target());});
        assertThat(fresh(child).parentDraftId).isEqualTo(root.id);assertThat(jira.calls).isEmpty();
        assertThatThrownBy(()->board.move(root.id,new MoveEpic(second,old))).hasMessageContaining("변경되었습니다");assertThat(fresh(root).epicId).isEqualTo(first);
        board.move(root.id,new MoveEpic(second,fresh(root).version));
        assertThat(publisher.publishBatch(List.of(child.id,root.id))).allMatch(r->r.status().equals("PUBLISHED"));
        assertThat(jira.calls).extracting(d->d.issueType).containsExactly("Epic","Task","Sub-task");assertThat(jira.calls.get(0).summary).isEqualTo("두 번째 에픽");
        assertThat(fresh(root).parent).isEqualTo("TEST-1");assertThat(fresh(child).parent).isEqualTo("TEST-2");assertThat(fresh(child).epicKey).isEqualTo("TEST-1");
        assertThat(service.epics().findById(first).orElseThrow().status).isEqualTo("PENDING");
    }
    @Test void existingEpicIsReusedWithoutChangingItsDescriptionOrCreatingAnotherEpic(){
        String m=meeting();var root=task(m,"연결할 업무","");
        var selected=board.add(m,new AddEpic("TEST-100",null));String epic=selected.epics().get(0).id();
        assertThat(board.add(m,new AddEpic("TEST-100",null)).epics()).hasSize(1);
        board.move(root.id,new MoveEpic(epic,fresh(root).version));
        assertThat(publisher.publishBatch(List.of(root.id)).get(0).status()).isEqualTo("PUBLISHED");
        assertThat(jira.calls).extracting(d->d.issueType).containsExactly("Task");assertThat(fresh(root).parent).isEqualTo("TEST-100");assertThat(jira.descriptionUpdates).isZero();
        publisher.epicPublisher.contents.sync(epic,jira);assertThat(jira.descriptionUpdates).isZero();
    }
    @Test void rejectsWrongMeetingWrongTargetChildrenAndRegisteredGroups(){
        String m=meeting(),other=meeting();var root=task(m,"상위","");var child=task(m,"하위",root.id);String epic=add(m,"같은 회의"),foreign=add(other,"다른 회의");
        assertThatThrownBy(()->board.move(root.id,new MoveEpic(foreign,fresh(root).version))).hasMessageContaining("같은 회의");
        assertThatThrownBy(()->board.move(child.id,new MoveEpic(epic,fresh(child).version))).hasMessageContaining("상위업무");
        jira.target="changed";assertThatThrownBy(()->board.move(root.id,new MoveEpic(epic,fresh(root).version))).hasMessageContaining("Jira 대상");jira.target="board-test";
        var saved=service.drafts().findById(child.id).orElseThrow();saved.status="UNKNOWN";service.drafts().saveAndFlush(saved);
        assertThatThrownBy(()->board.move(root.id,new MoveEpic(epic,fresh(root).version))).hasMessageContaining("이동할 수 없습니다");assertThat(fresh(root).epicId).isEmpty();
    }
    @Test void unassignedBoardTicketsAreBlockedAndRemovalClearsParentAndApproval(){
        String m=meeting();var root=task(m,"미배치","");String epic=add(m,"배치 대상");service.approve(List.of(root.id));
        assertThat(publisher.publishBatch(List.of(root.id)).get(0).status()).isEqualTo("BLOCKED");assertThat(jira.calls).isEmpty();
        board.move(root.id,new MoveEpic(epic,fresh(root).version));board.move(root.id,new MoveEpic("",fresh(root).version));
        assertThat(fresh(root).epicId).isEmpty();assertThat(fresh(root).parent).isEmpty();assertThat(fresh(root).status).isEqualTo("DRAFT");
        assertThat(fresh(root).approvedTarget).isNull();
    }
    @Test void differentEpicBoxesInTheSameBatchUseDifferentParents(){
        String m=meeting();var a=task(m,"업무 A","");var b=task(m,"업무 B","");String x=add(m,"에픽 A"),y=add(m,"에픽 B");
        board.move(a.id,new MoveEpic(x,fresh(a).version));board.move(b.id,new MoveEpic(y,fresh(b).version));
        assertThat(publisher.publishBatch(List.of(a.id,b.id))).allMatch(r->r.status().equals("PUBLISHED"));assertThat(fresh(a).parent).isNotEqualTo(fresh(b).parent);
        assertThat(jira.calls).extracting(d->d.issueType).containsExactly("Epic","Task","Epic","Task");
    }
    @Test void apiLooksUpAddsAndAssignsWithoutPublishing()throws Exception{
        String m=meeting();var d=task(m,"API 배치","");
        mvc.perform(get("/api/integrations/jira/projects/TEST/epics").param("query","개선")).andExpect(status().isOk()).andExpect(jsonPath("$.epics[0].key").value("TEST-100"));
        String body=mvc.perform(post("/api/meetings/"+m+"/epics").contentType("application/json").content("{\"issueKey\":\"TEST-100\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.epics[0].external").value(true)).andReturn().getResponse().getContentAsString();
        String epic=json.readTree(body).path("epics").get(0).path("id").asText();
        mvc.perform(put("/api/ticket-drafts/"+d.id+"/epic").contentType("application/json").content(json.writeValueAsString(Map.of("epicId",epic,"version",fresh(d).version))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].epicId").value(epic)).andExpect(jsonPath("$[0].status").value("APPROVED")).andExpect(jsonPath("$[0].approvedTarget").value(jira.target()));
        assertThat(jira.calls).isEmpty();
    }
    @Test void moveApprovesFailedDraftForCurrentTargetAndRegistrationStillChecksSchedule(){
        String m=meeting();var root=task(m,"일정 미완료 업무","");String epic=add(m,"배치 대상");
        var saved=service.drafts().findById(root.id).orElseThrow();saved.status="FAILED";saved.approvedTarget="previous-target";saved.publishTarget="previous-target";saved.error="이전 오류";saved.scheduleMode="AI";service.drafts().saveAndFlush(saved);
        board.move(root.id,new MoveEpic(epic,fresh(root).version));
        var moved=fresh(root);assertThat(moved.status).isEqualTo("APPROVED");assertThat(moved.approvedTarget).isEqualTo(jira.target());assertThat(moved.publishTarget).isNull();assertThat(moved.error).isNull();assertThat(jira.calls).isEmpty();
        assertThat(publisher.publishBatch(List.of(root.id)).get(0).status()).isEqualTo("BLOCKED");assertThat(jira.calls).noneMatch(d->d.issueType.equals("Task"));
    }
}
