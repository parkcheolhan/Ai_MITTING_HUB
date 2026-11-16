package kr.meetinghub;

import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:scheduling;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
class SchedulingWorkflowTest {
    @TestConfiguration static class Configuration {@Bean @Primary PlanAi planAi(){return new PlanAi();}}
    static class PlanAi implements Adapters.Intelligence {
        volatile boolean cyclic,blocking;CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        public String mode(){return "openai";}public String model(){return "schedule-unit";}
        public Minutes minutes(String t,List<Segment> s){return new Minutes();}public List<Item> analyze(List<Segment> s){return List.of();}
        public List<ScheduleAssessment> schedule(List<ScheduleTask> tasks,List<SchedulePriority> p){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(blocking){entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}
            var first=tasks.stream().filter(t->t.summary().equals("구현")).findFirst().orElseThrow();var second=tasks.stream().filter(t->t.summary().equals("검증")).findFirst().orElseThrow();
            return tasks.stream().filter(t->!t.fixed()).map(t->new ScheduleAssessment(t.id(),120,"High",t.id().equals(second.id())?List.of(first.id()):cyclic?List.of(second.id()):List.of(),"구현 완료 후 검증을 진행합니다.")).toList();
        }
    }
    @Autowired MeetingService service;@Autowired JobService worker;@Autowired PlanAi ai;@Autowired TransactionTemplate tx;
    @BeforeEach void reset(){ai.cyclic=false;ai.blocking=false;ai.entered=new CountDownLatch(1);ai.release=new CountDownLatch(1);tx.executeWithoutResult(s->{service.jobs().deleteAll();service.drafts().deleteAll();service.audits().deleteAll();service.meetings().deleteAll();});}
    record Pair(String meetingId,Draft root,Draft a,Draft b){}
    Pair pair(){var m=service.create(new CreateMeeting("일정 검증","",List.of(),"TEST","","기록","TEXT","ko",false,"일반"));var root=service.addDraft(m.id(),input("상위", "", "mock-minsu"));var a=service.addDraft(m.id(),input("구현",root.id,"mock-minsu"));var b=service.addDraft(m.id(),input("검증",root.id,"mock-jieun"));return new Pair(m.id(),root,a,b);}
    DraftInput input(String title,String parent,String assignee){return new DraftInput("TEST",parent.isBlank()?"Task":"Sub-task",title,"작업 내용",assignee,"Medium","",List.of(),"",List.of(),parent);}
    ScheduleRequest request(){return new ScheduleRequest("2027-03-01T09:00:00+09:00","Asia/Seoul",9,18,List.of());}
    Job await(String id)throws Exception{for(int i=0;i<300;i++){var j=service.jobs().findById(id).orElseThrow();if(Set.of("COMPLETED","FAILED","CANCELLED").contains(j.status))return j;Thread.sleep(20);}throw new AssertionError("Job timeout");}
    @Test void schedulesCrossAssigneeDependenciesRollsUpRootAndRequiresReapproval()throws Exception{
        var pair=pair();service.approve(List.of(pair.root.id,pair.a.id,pair.b.id));var job=await(worker.schedule(pair.meetingId,request()).id);assertThat(job.status).isEqualTo("COMPLETED");
        var drafts=service.get(pair.meetingId).drafts();var a=drafts.stream().filter(d->d.id.equals(pair.a.id)).findFirst().orElseThrow();var b=drafts.stream().filter(d->d.id.equals(pair.b.id)).findFirst().orElseThrow();var root=drafts.stream().filter(d->d.id.equals(pair.root.id)).findFirst().orElseThrow();
        assertThat(OffsetDateTime.parse(b.startAt)).isEqualTo(OffsetDateTime.parse(a.endAt));assertThat(b.dependencyDraftIds).containsExactly(a.id);assertThat(root.startAt).isEqualTo(a.startAt);assertThat(root.endAt).isEqualTo(b.endAt);assertThat(root.estimatedMinutes).isEqualTo(240);assertThat(drafts).allMatch(d->d.status.equals("DRAFT")&&d.approvedTarget==null);
    }
    @Test void invalidCycleDoesNotPartiallySave()throws Exception{
        var pair=pair();ai.cyclic=true;var job=await(worker.schedule(pair.meetingId,request()).id);assertThat(job.status).isEqualTo("FAILED");assertThat(job.errorMessage).contains("순환");assertThat(service.get(pair.meetingId).drafts()).allMatch(d->d.startAt.isBlank());
    }
    @Test void cancellationDiscardsLateAiResult()throws Exception{
        var pair=pair();ai.blocking=true;var job=worker.schedule(pair.meetingId,request());assertThat(ai.entered.await(3,TimeUnit.SECONDS)).isTrue();worker.cancel(job.id);ai.release.countDown();Thread.sleep(150);assertThat(service.jobs().findById(job.id).orElseThrow().status).isEqualTo("CANCELLED");assertThat(service.get(pair.meetingId).drafts()).allMatch(d->d.startAt.isBlank());
    }
    @Test void preservesExplicitManualTimesAndSchedulesDependentWorkAfterThem()throws Exception{
        var pair=pair();var d=pair.a;
        service.update(d.id,new DraftInput("TEST",d.issueType,d.summary,d.description,d.assignee,d.priority,"2027-03-01",List.of(),"",List.of(),d.parentDraftId,"NEW","","김민수","2027-03-01T09:00:00+09:00","2027-03-01T11:00:00+09:00",120,"MANUAL",List.of()));
        assertThat(await(worker.schedule(pair.meetingId,request()).id).status).isEqualTo("COMPLETED");var fixed=service.drafts().findById(d.id).orElseThrow();var dependent=service.drafts().findById(pair.b.id).orElseThrow();
        assertThat(fixed.startAt).isEqualTo("2027-03-01T09:00:00+09:00");assertThat(fixed.endAt).isEqualTo("2027-03-01T11:00:00+09:00");assertThat(fixed.scheduleMode).isEqualTo("MANUAL");assertThat(OffsetDateTime.parse(dependent.startAt)).isEqualTo(OffsetDateTime.parse(fixed.endAt));
    }
    @Test void staleAiResultDoesNotOverwriteEditedCandidates()throws Exception{
        var pair=pair();ai.blocking=true;var job=worker.schedule(pair.meetingId,request());assertThat(ai.entered.await(3,TimeUnit.SECONDS)).isTrue();
        service.update(pair.a.id,input("수정된 구현",pair.root.id,"mock-minsu"));ai.release.countDown();var failed=await(job.id);assertThat(failed.status).isEqualTo("FAILED");assertThat(failed.errorMessage).contains("변경");assertThat(service.drafts().findById(pair.a.id).orElseThrow().summary).isEqualTo("수정된 구현");assertThat(service.get(pair.meetingId).drafts()).allMatch(d->d.startAt.isBlank());
    }
    @Test void bulkAssignmentInvalidatesAiSchedulesAndIsAtomicForPublishedChildren()throws Exception{
        var pair=pair();assertThat(await(worker.schedule(pair.meetingId,request()).id).status).isEqualTo("COMPLETED");
        var result=service.assignChildren(pair.root.id,new JiraUser("mock-jieun","이지은",true));assertThat(result).allMatch(d->d.assignee.equals("mock-jieun")&&d.startAt.isBlank()&&d.status.equals("DRAFT"));
        tx.executeWithoutResult(s->{var child=service.drafts().findById(pair.a.id).orElseThrow();child.status="PUBLISHED";child.issueKey="TEST-77";service.drafts().save(child);});
        assertThatThrownBy(()->service.assignChildren(pair.root.id,new JiraUser("mock-minsu","김민수",true))).hasMessageContaining("이미 등록");assertThat(service.drafts().findById(pair.root.id).orElseThrow().assignee).isEqualTo("mock-jieun");
    }
    @Test void cannotApproveUncomputedAiScheduleAndCannotChangeMeetingProject(){
        var pair=pair();tx.executeWithoutResult(s->{var a=service.drafts().findById(pair.a.id).orElseThrow();a.scheduleMode="AI";service.drafts().save(a);});
        assertThatThrownBy(()->service.approve(List.of(pair.a.id))).hasMessageContaining("AI 일정");
        assertThatThrownBy(()->service.update(pair.a.id,new DraftInput("OTHER","Task","수정","설명","","","",List.of(),"",List.of()))).hasMessageContaining("회의 프로젝트");
    }
    @Test void newTicketWithoutModeDefaultsToAiAndExistingIssueDoesNotRequireAiDates(){
        var p=pair();var d=service.addDraft(p.meetingId,new DraftInput("TEST","Task","새 업무","작업 내용","mock-minsu","Medium","",List.of(),"",List.of(),"","NEW","","김민수",null,null,null,null,null));
        assertThat(d.scheduleMode).isEqualTo("AI");
        var existing=new Draft();existing.registrationMode="EXISTING";assertThatCode(()->ScheduleDetails.ready(existing)).doesNotThrowAnyException();
    }
    @Test void savingEquivalentDatesAndFormattedContentKeepsCalculatedSchedule()throws Exception{
        var p=pair();assertThat(await(worker.schedule(p.meetingId,request()).id).status).isEqualTo("COMPLETED");
        var d=service.drafts().findById(p.a.id).orElseThrow();String start=d.startAt,end=d.endAt;
        service.update(d.id,new DraftInput("TEST",d.issueType,d.summary,d.description,d.assignee,d.priority,d.dueDate,new ArrayList<>(d.labels),d.parent,new ArrayList<>(d.analysisItemIds),d.parentDraftId,"NEW","",d.assigneeName,OffsetDateTime.parse(start).withOffsetSameInstant(ZoneOffset.UTC).toString(),OffsetDateTime.parse(end).withOffsetSameInstant(ZoneOffset.UTC).toString(),d.estimatedMinutes,"AI",new ArrayList<>(d.dependencyDraftIds)));
        var saved=service.drafts().findById(d.id).orElseThrow();assertThat(saved.startAt).isNotBlank();assertThat(saved.endAt).isNotBlank();
        service.approve(List.of(saved.id));
        service.applyChildSchedule(p.root.id,new ApplyChildSchedule("AI","","",""));
        assertThat(service.drafts().findById(saved.id).orElseThrow().startAt).isNotBlank();
    }
    @Test void appliesManualDeadlineToEveryChildAndRevokesApprovalsWithoutChangingAssignees(){
        var p=pair();service.approve(List.of(p.root.id,p.a.id,p.b.id));
        var group=service.applyChildSchedule(p.root.id,new ApplyChildSchedule("MANUAL","2027-03-01T09:00:00+09:00","2027-03-03T18:00:00+09:00",""));
        assertThat(group).hasSize(3).allSatisfy(d->{assertThat(d.startAt).isEqualTo("2027-03-01T09:00:00+09:00");assertThat(d.endAt).isEqualTo("2027-03-03T18:00:00+09:00");assertThat(d.dueDate).isEqualTo("2027-03-03");assertThat(d.scheduleMode).isEqualTo("MANUAL");assertThat(d.status).isEqualTo("DRAFT");assertThat(d.approvedTarget).isNull();});
        assertThat(service.drafts().findById(p.b.id).orElseThrow().assignee).isEqualTo("mock-jieun");
    }
    @Test void appliesAiModeWithoutCopyingDatesAndThenCalculatesEachChildIndependently()throws Exception{
        var p=pair();service.applyChildSchedule(p.root.id,new ApplyChildSchedule("MANUAL","2027-03-01T09:00:00+09:00","2027-03-03T18:00:00+09:00",""));
        var group=service.applyChildSchedule(p.root.id,new ApplyChildSchedule("AI","2027-03-01T09:00:00+09:00","2027-03-03T18:00:00+09:00","2027-03-03"));
        assertThat(group).allMatch(d->d.scheduleMode.equals("AI")&&d.startAt.isBlank()&&d.endAt.isBlank()&&d.dueDate.isBlank());
        assertThat(await(worker.schedule(p.meetingId,request()).id).status).isEqualTo("COMPLETED");
        var a=service.drafts().findById(p.a.id).orElseThrow();var b=service.drafts().findById(p.b.id).orElseThrow();assertThat(OffsetDateTime.parse(b.startAt)).isEqualTo(OffsetDateTime.parse(a.endAt));assertThat(a.startAt).isNotEqualTo(b.startAt);
    }
    @Test void bulkDeadlineRejectsPublishedChildAndInvalidIntervalsAtomically(){
        var p=pair();
        assertThatThrownBy(()->service.applyChildSchedule(p.root.id,new ApplyChildSchedule("MANUAL","2027-03-01T18:00:00+09:00","2027-03-01T09:00:00+09:00",""))).hasMessageContaining("이후");
        assertThat(service.get(p.meetingId).drafts()).allMatch(d->d.startAt.isBlank());
        tx.executeWithoutResult(s->{var child=service.drafts().findById(p.b.id).orElseThrow();child.status="PUBLISHED";child.issueKey="TEST-9";service.drafts().save(child);});
        assertThatThrownBy(()->service.applyChildSchedule(p.root.id,new ApplyChildSchedule("MANUAL","2027-03-01T09:00:00+09:00","2027-03-02T18:00:00+09:00",""))).hasMessageContaining("이미 등록");
        assertThat(service.get(p.meetingId).drafts()).allMatch(d->d.startAt.isBlank());
    }
}
