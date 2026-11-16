package kr.meetinghub;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:epic-workflow;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
class EpicWorkflowTest {
    @TestConfiguration static class Config {@Bean @Primary EpicJira epicJira(){return new EpicJira();}}
    static class EpicJira implements Adapters.Jira {
        final List<Draft> calls=new ArrayList<>();boolean uncertain,indexed,failTask;int sequence;
        public String target(){return "epic-test";}
        public Result create(Draft d){calls.add(d);if(d.issueType.equals("Epic")&&uncertain)throw new CloudJira.Uncertain();if(!d.issueType.equals("Epic")&&failTask)throw new IllegalArgumentException("작업 발급 실패");String key="TEST-"+(++sequence);return new Result(d.id,"PUBLISHED",key,"https://example.atlassian.net/browse/"+key,null);}
        public Optional<Result> find(Draft d){return indexed?Optional.of(new Result(d.id,"PUBLISHED","TEST-999","https://example.atlassian.net/browse/TEST-999",null)):Optional.empty();}
    }
    @Autowired MeetingService meetings;@Autowired Publisher publisher;@Autowired EpicJira jira;@Autowired Epics epics;
    @BeforeEach void reset(){jira.calls.clear();jira.uncertain=false;jira.indexed=false;jira.failTask=false;}
    String meeting(){return meetings.create(new CreateMeeting("에픽 우선 회의","",List.of(),"TEST","","실제 업무를 진행합니다.","TEXT","ko",false,"일반")).id();}
    Draft task(String meeting,String name){return meetings.addDraft(meeting,new DraftInput("TEST","Task",name,"작업 내용","","","",List.of(),"",List.of()));}
    @Test void createsEpicBeforeTasksAndReusesItAcrossPartialBatches(){
        String id=meeting();var first=task(id,"첫 업무");var second=task(id,"다음 업무");
        meetings.approve(List.of(first.id));assertThat(publisher.publishBatch(List.of(first.id)).get(0).status()).isEqualTo("PUBLISHED");
        var epic=epics.findByMeetingId(id).get(0);assertThat(epic.status).isEqualTo("PUBLISHED");
        assertThat(jira.calls).extracting(d->d.issueType).containsExactly("Epic","Task");
        assertThat(meetings.drafts().findById(first.id).orElseThrow().parent).isEqualTo(epic.issueKey);
        meetings.approve(List.of(second.id));publisher.publishBatch(List.of(second.id));
        assertThat(jira.calls).extracting(d->d.issueType).containsExactly("Epic","Task","Task");
        assertThat(meetings.drafts().findById(second.id).orElseThrow().parent).isEqualTo(epic.issueKey);
        publisher.publishBatch(List.of(first.id,second.id));assertThat(jira.calls).hasSize(3);assertThat(meetings.get(id).epics()).hasSize(1);
    }
    @Test void unapprovedSelectionCannotCreateAnEpic(){
        String id=meeting();var draft=task(id,"미승인 업무");
        assertThat(publisher.publishBatch(List.of(draft.id)).get(0).status()).isEqualTo("BLOCKED");assertThat(epics.findByMeetingId(id)).isEmpty();assertThat(jira.calls).isEmpty();
    }
    @Test void uncertainEpicBlocksTasksAndOnlyReconcilesUntilFound(){
        String id=meeting();var first=task(id,"업무 1");var second=task(id,"업무 2");meetings.approve(List.of(first.id,second.id));jira.uncertain=true;
        assertThat(publisher.publishBatch(List.of(first.id,second.id))).allMatch(r->r.status().equals("BLOCKED"));
        assertThat(jira.calls).hasSize(1);assertThat(epics.findByMeetingId(id).get(0).status).isEqualTo("UNKNOWN");
        publisher.publishBatch(List.of(first.id,second.id));assertThat(jira.calls).hasSize(1);
        jira.indexed=true;jira.uncertain=false;
        assertThat(publisher.publishBatch(List.of(first.id,second.id))).allMatch(r->r.status().equals("PUBLISHED"));
        assertThat(jira.calls).hasSize(3);assertThat(meetings.drafts().findById(first.id).orElseThrow().parent).isEqualTo("TEST-999");
    }
    @Test void taskFailureDoesNotRecreateAlreadyPublishedEpic(){
        String id=meeting();var draft=task(id,"재시도 업무");meetings.approve(List.of(draft.id));jira.failTask=true;
        assertThat(publisher.publishBatch(List.of(draft.id)).get(0).status()).isEqualTo("FAILED");
        jira.failTask=false;assertThat(publisher.publishBatch(List.of(draft.id)).get(0).status()).isEqualTo("PUBLISHED");
        assertThat(jira.calls.stream().filter(d->d.issueType.equals("Epic"))).hasSize(1);
    }
}
