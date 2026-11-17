package kr.meetinghub;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import kr.meetinghub.Domain.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:flat-tickets;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class FlatTicketsTest {
    @Autowired MeetingService service;@Autowired FlatTickets flat;@Autowired EpicBoard board;@Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired TransactionTemplate tx;
    String meeting(){return service.create(new CreateMeeting("독립 업무 검증","",List.of(),"DEMO","","[00:00:00] 담당자: 접근 권한을 확인합니다.","TEXT","ko",false,"일반")).id();}
    Draft task(String m,String title,String parent){return service.addDraft(m,new DraftInput("DEMO",parent.isBlank()?"Task":"Sub-task",title,"보존할 업무 내용","","","",List.of(),"",List.of(),parent));}
    Draft fresh(Draft d){return service.drafts().findById(d.id).orElseThrow();}
    @Test void promotesEditableChildrenAndPreservesTheirContentsAndEpicPlacement()throws Exception{
        String m=meeting();var root=task(m,"주요 업무","");var child=task(m,"기존 세부 업무",root.id);
        String epic=board.add(m,new AddEpic(null,"업무 에픽")).epics().get(0).id();board.move(root.id,new MoveEpic(epic,fresh(root).version));
        String description=fresh(child).description;flat.prepare(m);
        assertThat(service.get(m).drafts()).hasSize(2).allSatisfy(d->{assertThat(d.parentDraftId).isEmpty();assertThat(d.issueType).isEqualTo("Task");assertThat(d.epicId).isEqualTo(epic);});
        assertThat(fresh(child).description).isEqualTo(description);assertThat(fresh(child).flatHierarchyBackup).contains(root.id);
        assertThat(json.readTree(json.writeValueAsString(fresh(child))).has("flatHierarchyBackup")).isFalse();
        long version=fresh(child).version;flat.prepare(m);assertThat(fresh(child).version).isEqualTo(version);
        board.move(child.id,new MoveEpic("",fresh(child).version));assertThat(fresh(root).epicId).isEqualTo(epic);assertThat(fresh(child).epicId).isEmpty();
    }
    @Test void leavesPublishedAndUncertainJiraHistoryUntouched(){
        String m=meeting();var root=task(m,"이전 업무","");var child=task(m,"이미 발급된 업무",root.id);
        tx.executeWithoutResult(s->{var d=service.drafts().findById(child.id).orElseThrow();d.status="PUBLISHED";d.issueKey="DEMO-42";service.drafts().save(d);});
        flat.prepare(m);assertThat(fresh(child).parentDraftId).isEqualTo(root.id);assertThat(fresh(child).issueType).isEqualTo("Sub-task");assertThat(fresh(child).issueKey).isEqualTo("DEMO-42");
        assertThatThrownBy(()->flat.delete(child.id,fresh(child).version)).hasMessageContaining("삭제할 수 없습니다");
    }
    @Test void publicApiFixesTypeToTaskAndRejectsChildAssignments()throws Exception{
        String m=meeting();var input=new DraftInput("DEMO","Bug","작업 고정","작업 내용","","","",List.of(),"",List.of());
        mvc.perform(post("/api/meetings/"+m+"/ticket-drafts/manual").contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.issueType").value("Task")).andExpect(jsonPath("$.parentDraftId").value(""));
        var root=service.get(m).drafts().get(0);
        mvc.perform(post("/api/meetings/"+m+"/ticket-drafts/manual").contentType("application/json").content(json.writeValueAsString(new DraftInput("DEMO","Sub-task","금지된 하위","내용","","","",List.of(),"",List.of(),root.id))))
            .andExpect(status().isBadRequest());
    }
    @Test void deletionChecksVersionAndRemovesOnlyTheSelectedIndependentTask()throws Exception{
        String m=meeting();var root=task(m,"보존할 업무","");var child=task(m,"삭제할 업무",root.id);flat.prepare(m);long version=fresh(child).version;
        mvc.perform(delete("/api/ticket-drafts/"+child.id).param("version",String.valueOf(version+1))).andExpect(status().isBadRequest());assertThat(service.drafts().existsById(child.id)).isTrue();
        mvc.perform(delete("/api/ticket-drafts/"+child.id).param("version",String.valueOf(version))).andExpect(status().isOk());assertThat(service.drafts().existsById(child.id)).isFalse();assertThat(service.drafts().existsById(root.id)).isTrue();
    }
    @Test void flatAiPersistenceKeepsDetailsWithoutGeneratingSubtasks(){
        String m=meeting();
        tx.executeWithoutResult(s->{
            var meeting=service.lock(m);var c=service.content(meeting);var segment=c.segments.get(0);
            var item=new Item("analysis","ACTION","접근 권한 확인",List.of("계정 확인"),List.of("결과 검증"),List.of("검증 내역 기록"),List.of(),null,null,List.of(new Evidence(segment.id(),0,segment.speaker(),segment.text(),true)),0.9,false);c.items=List.of(item);service.save(meeting,c);
            var child=new TicketProposal(List.of(item.id()),"검증", "세부 검증",List.of("운영 환경 검증"),List.of("로그 확인"),List.of("로그 기록"),List.of(),List.of());
            var proposal=new TicketProposal(List.of(item.id()),"접근 권한 확인","배경",List.of("계정 확인"),List.of("결과 검증"),List.of("완료 내역"),List.of(),List.of(),List.of(child));
            service.applyFlatDraftProposals(meeting,c,List.of(proposal),"openai","synthetic",null,-1);
        });
        assertThat(service.get(m).drafts()).singleElement().satisfies(d->{assertThat(d.parentDraftId).isEmpty();assertThat(d.issueType).isEqualTo("Task");assertThat(d.description).contains("운영 환경 검증","로그 확인");});
    }
}
