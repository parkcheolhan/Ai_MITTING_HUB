package kr.meetinghub;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:subtasks;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
class SubtaskWorkflowTest {
    @TestConfiguration static class Configuration {
        @Bean @Primary RecordingJira subtaskJira(){return new RecordingJira();}
    }
    static class RecordingJira implements Adapters.Jira {
        final List<Draft> calls=new ArrayList<>();final List<String> links=new ArrayList<>();final AtomicInteger sequence=new AtomicInteger();boolean fail,failLink;
        public String target(){return "subtask-test";}
        public Result linkExisting(Draft d){links.add(d.existingIssueKey);if(failLink)throw new IllegalArgumentException("기존 이슈를 찾을 수 없습니다.");return new Result(d.id,"PUBLISHED",d.existingIssueKey,"https://example.atlassian.net/browse/"+d.existingIssueKey,null);}
        public Result create(Draft draft){calls.add(draft);if(fail&&!draft.issueType.equals("Epic"))throw new CloudJira.Uncertain();String key="TEST-"+sequence.incrementAndGet();return new Result(draft.id,"PUBLISHED",key,"https://example.atlassian.net/browse/"+key,null);}
    }
    @Autowired MeetingService service;@Autowired Publisher publisher;@Autowired TransactionTemplate tx;@Autowired RecordingJira jira;
    List<Draft> approveManually(List<String> ids){
        // These hierarchy/publishing scenarios explicitly opt out of AI scheduling.
        tx.executeWithoutResult(s->{for(var id:ids){var d=service.drafts().findById(id).orElseThrow();d.scheduleMode="MANUAL";service.drafts().save(d);}});
        return service.approve(ids);
    }
    @BeforeEach void reset(){jira.calls.clear();jira.links.clear();jira.fail=false;jira.failLink=false;}
    @Test void consolidatesNineLegacyChildrenToThreeAndArchivesOriginalsWithoutDeletingThem(){
        var pair=fixture();var ids=new ArrayList<String>();
        for(int i=0;i<9;i++){
            var input=new DraftInput("TEST","Sub-task","[확인] [확인 필요] 점검 "+i,"1. 작업목록\n- 작업 "+i+"\n\n2. 완료조건\n- 완료 "+i+"\n\n3. 제약사항\n- 제약 "+i+"\n\n4. AI 검토사항\n- 검토 "+i,"","Medium","",List.of(),"",new ArrayList<>(pair.parent.analysisItemIds),pair.parent.id);
            ids.add(service.addDraft(pair.parent.meetingId,input).id);
        }
        service.consolidateSubtasks(pair.parent.meetingId,new Ids(ids));
        var visible=service.children(pair.parent.id).stream().filter(d->ids.contains(d.id)).toList();assertThat(visible).hasSize(3);
        String all=visible.stream().map(d->d.description).collect(java.util.stream.Collectors.joining("\n"));
        for(int i=0;i<9;i++)assertThat(all).contains("작업 "+i,"완료 "+i,"제약 "+i,"검토 "+i);
        assertThat(visible).allMatch(d->!d.summary.contains("[확인 필요]")&&!d.consolidationBackup.isBlank());
        var stored=ids.stream().map(id->service.drafts().findById(id).orElseThrow()).toList();assertThat(stored).hasSize(9);assertThat(stored.stream().filter(d->d.status.equals("CONSOLIDATED"))).hasSize(6);
        service.consolidateSubtasks(pair.parent.meetingId,new Ids(ids));assertThat(service.children(pair.parent.id).stream().filter(d->ids.contains(d.id))).hasSize(3);
    }
    @Test void compactsOnlySelectedChildTitlesAndPreservesDetails(){
        var pair=fixture();var original=pair.child.description;
        tx.executeWithoutResult(s->{var d=service.drafts().findById(pair.child.id).orElseThrow();d.summary="[작업] 파일 업로드·다운로드 암호화 처리에 대해 미리 협의하고 테스트를 요청한다.";service.drafts().save(d);});
        service.formatSubtaskTitles(pair.parent.meetingId,new Ids(List.of(pair.child.id)));
        var saved=service.drafts().findById(pair.child.id).orElseThrow();assertThat(saved.summary).isEqualTo("[작업] 파일 업로드·다운로드 암호화 처리");assertThat(saved.description).isEqualTo(original);
        assertThat(service.drafts().findById(pair.parent.id).orElseThrow().summary).isEqualTo(pair.parent.summary);
        assertThatThrownBy(()->service.formatSubtaskTitles(pair.parent.meetingId,new Ids(List.of(pair.parent.id)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void selectedMeetingFormattingPreservesOtherMeetingsAndRequiresReviewAgain(){
        var pair=fixture();var other=fixture();approveManually(List.of(pair.parent.id));
        tx.executeWithoutResult(s->{var d=service.drafts().findById(pair.parent.id).orElseThrow();d.description+="\n※ AI가 정리한 초안 (codex-default)입니다. 승인 전 등록되지 않습니다.";service.drafts().save(d);});
        service.formatDescriptions(pair.parent.meetingId,new Ids(List.of(pair.parent.id,pair.child.id)));
        var saved=service.drafts().findById(pair.parent.id).orElseThrow();assertThat(saved.description).doesNotContain("※ AI가");assertThat(saved.status).isEqualTo("DRAFT");
        assertThat(service.drafts().findById(other.parent.id).orElseThrow().description).isEqualTo(other.parent.description);
        assertThatThrownBy(()->service.formatDescriptions(pair.parent.meetingId,new Ids(List.of(other.parent.id)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fillsExistingMediumParagraphFromSmallParagraphsWithoutDuplicatesOrParentEdits(){
        var view=service.create(new CreateMeeting("단락 후보","",List.of(),"TEST","","로그인 오류 수정 작업을 진행합니다.","TEXT","ko",false,"일반"));
        var parent=tx.execute(s->{
            var m=service.lock(view.id());var c=service.content(m);c.items=new Adapters.MockIntelligence().analyze(c.segments);service.save(m,c);
            var d=new Draft();d.meetingId=m.id;d.projectKey="TEST";d.generationMode="codex";d.generationModel="test-model";
            d.summary="로그인 개선";d.status="APPROVED";d.analysisItemIds.add(c.items.get(0).id());
            d.description="1. 배경 및 목적\n로그인 개선\n\n2. 해야 하는 작업 목록\n☐ 오류 재현\n☐ 로그인 수정\n\n3. 확인해야 하는 항목\n☐ [제안] 세션 회귀 검증\n\n4. 완료 조건\n오류 미발생";
            return service.drafts().save(d);
        });
        service.structureCandidates(view.id());
        var children=service.children(parent.id);assertThat(children).hasSize(1);
        assertThat(children.get(0).description).contains("오류 재현","로그인 수정","세션 회귀 검증");
        assertThat(children).allMatch(d->d.status.equals("DRAFT"));
        assertThat(children.stream().filter(d->d.labels.contains("ai-recommended"))).hasSize(1);
        service.structureCandidates(view.id());assertThat(service.children(parent.id)).hasSize(1);
        var saved=service.drafts().findById(parent.id).orElseThrow();assertThat(saved.description).isEqualTo(parent.description);assertThat(saved.status).isEqualTo("APPROVED");
    }
    @Test void recommendationsAreTaggedWhileSourceTasksRemainUntaggedAndRegenerationDoesNotDuplicate(){
        var view=service.create(new CreateMeeting("추천 검증","",List.of(),"TEST","","로그인 오류 수정 작업을 진행합니다.","TEXT","ko",false,"일반"));
        var generated=tx.execute(s->{
            var m=service.lock(view.id());var c=service.content(m);c.items=new Adapters.MockIntelligence().analyze(c.segments);service.save(m,c);
            var ids=c.items.stream().map(Item::id).toList();
            var recommendation=new TicketProposal(ids,"세션 만료 회귀 검증","로그인 수정의 회귀 위험을 줄이기 위한 추가 추천",List.of("[제안] 세션 만료 검증"),List.of("결과 확인"),List.of("결과 기록"),List.of(),List.of("회의에서 확정한 업무가 아닌 추가 제안"),List.of(),true);
            var parent=new TicketProposal(ids,"로그인 오류 수정","원문 지시",List.of("로그인 오류 수정"),List.of("[제안] 세션 만료 검증"),List.of("수정 완료"),List.of(),List.of(),List.of(recommendation));
            var result=service.applyDraftProposals(m,c,List.of(parent),"openai","test-model",null,-1);
            assertThat(service.applyDraftProposals(m,c,List.of(parent),"openai","test-model",null,-1)).hasSize(result.size());
            return result;
        });
        var parent=generated.stream().filter(d->d.parentDraftId.isBlank()).findFirst().orElseThrow();
        assertThat(parent.labels).doesNotContain("ai-recommended");assertThat(generated).hasSize(2);
        var recommended=generated.stream().filter(d->d.labels.contains("ai-recommended")).toList();assertThat(recommended).hasSize(1);
        assertThat(recommended.get(0).description).contains("4. AI 검토사항", "세션 만료 검증").doesNotContain("배경 및 목적","회의 근거");
        assertThat(recommended.get(0).parentDraftId).isEqualTo(parent.id);assertThat(recommended.get(0).status).isEqualTo("DRAFT");
        assertThat(publisher.publish(recommended.get(0).id).status()).isEqualTo("BLOCKED");
    }
    record Pair(Draft parent,Draft child){}
    Pair fixture(){
        var view=service.create(new CreateMeeting("하위 업무 검토","",List.of(),"TEST","","[00:01:00] 참석자: 로그인 오류 수정 작업을 진행합시다.","TEXT","ko",false,"일반"));
        return tx.execute(s->{
            var m=service.lock(view.id());var c=service.content(m);c.items=new Adapters.MockIntelligence().analyze(c.segments);service.save(m,c);
            var item=c.items.get(0);var child=new TicketProposal(List.of(item.id()),"오류 재현과 수정","원문 기반 작업",List.of("오류 수정"),List.of("재현 확인"),List.of("오류 미발생"),List.of(),List.of());
            var parent=new TicketProposal(List.of(item.id()),"로그인 개선","원문 기반 작업",List.of("작업 통합"),List.of("완료 확인"),List.of("회귀 검증"),List.of(),List.of(),List.of(child));
            var drafts=service.applyDraftProposals(m,c,List.of(parent),"openai","test-model",null,-1);
            return new Pair(drafts.stream().filter(d->d.parentDraftId.isBlank()).findFirst().orElseThrow(),drafts.stream().filter(d->!d.parentDraftId.isBlank()).findFirst().orElseThrow());
        });
    }
    @Test void aiSubtasksKeepEvidenceAndPublishParentFirstRegardlessOfSelectionOrder(){
        var pair=fixture();assertThat(pair.child.parentDraftId).isEqualTo(pair.parent.id);assertThat(pair.child.analysisItemIds).isEqualTo(pair.parent.analysisItemIds);
        assertThat(pair.child.description).doesNotContain("회의 근거");
        approveManually(List.of(pair.child.id,pair.parent.id));
        var results=publisher.publishBatch(List.of(pair.child.id,pair.parent.id));
        assertThat(results).extracting(Result::status).containsExactly("PUBLISHED","PUBLISHED");
        assertThat(jira.calls).hasSize(3);assertThat(jira.calls.get(0).issueType).isEqualTo("Epic");
        assertThat(jira.calls.stream().filter(d->!d.issueType.equals("Epic"))).extracting(d->d.id).containsExactly(pair.parent.id,pair.child.id);
        var saved=service.drafts().findById(pair.child.id).orElseThrow();
        assertThat(saved.parent).isEqualTo(service.drafts().findById(pair.parent.id).orElseThrow().issueKey);
        publisher.publishBatch(List.of(pair.parent.id,pair.child.id));assertThat(jira.calls).hasSize(3);
    }
    @Test void childCannotImplicitlyPublishAnUnselectedOrUnapprovedParent(){
        var pair=fixture();approveManually(List.of(pair.child.id));
        assertThat(publisher.publish(pair.child.id).status()).isEqualTo("BLOCKED");assertThat(jira.calls).isEmpty();
        var results=publisher.publishBatch(List.of(pair.child.id,pair.parent.id));
        assertThat(results).allMatch(r->r.status().equals("BLOCKED"));assertThat(jira.calls).isEmpty();
    }
    @Test void linksExistingParentThenCreatesOnlyItsNewChild(){
        var pair=fixture();var p=pair.parent;
        service.update(p.id,new DraftInput(p.projectKey,p.issueType,p.summary,p.description,"","Medium","",List.of(),"",new ArrayList<>(p.analysisItemIds),"","EXISTING","test-42"));
        assertThat(publisher.publish(p.id).status()).isEqualTo("BLOCKED");assertThat(jira.links).isEmpty();
        approveManually(List.of(p.id,pair.child.id));
        assertThat(publisher.publishBatch(List.of(pair.child.id,p.id))).extracting(Result::status).containsExactly("PUBLISHED","PUBLISHED");
        assertThat(jira.links).containsExactly("TEST-42");assertThat(jira.calls).extracting(d->d.id).containsExactly(pair.child.id);
        assertThat(service.drafts().findById(pair.child.id).orElseThrow().parent).isEqualTo("TEST-42");
        publisher.publish(p.id);assertThat(jira.links).hasSize(1);
    }
    @Test void failedExistingLookupNeverFallsBackToCreatingANewIssue(){
        var p=fixture().parent;
        service.update(p.id,new DraftInput(p.projectKey,p.issueType,p.summary,p.description,"","Medium","",List.of(),"",new ArrayList<>(p.analysisItemIds),"","EXISTING","TEST-404"));
        assertThatThrownBy(()->service.split(p.id)).hasMessageContaining("기존 이슈 연결");
        assertThatThrownBy(()->service.merge(List.of(p.id,fixture().parent.id))).hasMessageContaining("기존 이슈 연결");
        approveManually(List.of(p.id));jira.failLink=true;
        assertThat(publisher.publish(p.id).status()).isEqualTo("FAILED");assertThat(jira.calls).isEmpty();
        assertThat(service.drafts().findById(p.id).orElseThrow().existingIssueKey).isEqualTo("TEST-404");
    }
    @Test void uncertainParentBlocksChildrenWithoutCreatingThem(){
        var pair=fixture();approveManually(List.of(pair.parent.id,pair.child.id));jira.fail=true;
        assertThat(publisher.publishBatch(List.of(pair.child.id,pair.parent.id))).extracting(Result::status).containsExactly("UNKNOWN","BLOCKED");
        assertThat(jira.calls).hasSize(2);
    }
    DraftInput input(Draft d,String project,String parentId){return new DraftInput(project,d.issueType,d.summary,d.description,"","Medium","",List.of(),"",new ArrayList<>(d.analysisItemIds),parentId);}
    @Test void parentCannotBeDeletedMergedSplitOrMovedAndChildCannotFormCycle(){
        var pair=fixture();
        assertThatThrownBy(()->service.deleteDraft(pair.parent.id)).hasMessageContaining("하위");
        assertThatThrownBy(()->service.update(pair.parent.id,input(pair.parent,"OTHER",""))).hasMessageContaining("프로젝트");
        assertThatThrownBy(()->service.update(pair.child.id,input(pair.child,"TEST",pair.child.id))).hasMessageContaining("자신");
        assertThatThrownBy(()->service.update(pair.parent.id,input(pair.parent,"TEST",pair.child.id))).hasMessageContaining("한 단계");
        assertThatThrownBy(()->service.merge(List.of(pair.parent.id,pair.child.id))).hasMessageContaining("병합");
        assertThatThrownBy(()->service.split(pair.parent.id)).hasMessageContaining("분할");
        service.children(pair.parent.id).forEach(d->service.deleteDraft(d.id));service.deleteDraft(pair.parent.id);assertThat(service.drafts().findById(pair.parent.id)).isEmpty();
    }
    @Test void sevenChecklistEntriesBecomeTwoGroupedChildrenWithoutLosingWork(){
        var view=service.create(new CreateMeeting("FOS 수신·파일 업로드","",List.of(),"TEST","","FOS 구성과 FS 준비 후 파일 업로드 테스트를 진행합니다.","TEXT","ko",false,"일반"));
        var tasks=List.of("[확인 필요] FOS 관련 미완료 구성과 수신 차단 원인을 특정하고 필요한 조치를 정한다.","[제안] 확인된 조치 범위에 따라 인사 정보 수신 연결을 진행한다.","FS 준비 후 파일 업로드 테스트를 수행한다.","[제안] 구성 조치 후 인사 정보 수신 결과를 확인한다.");
        var checks=List.of("[확인 필요] FOS와 FS가 동일한 구성 요소인지 미확정이다.","[확인 필요] 잔여 구성 항목, 조치 주체와 완료 시점은 미확정이다.","[확인 필요] 파일 업로드 대상·입력 파일·기대 결과를 확인한다.");
        var generated=tx.execute(s->{
            var m=service.lock(view.id());var c=service.content(m);c.items=new Adapters.MockIntelligence().analyze(c.segments);service.save(m,c);
            var ids=c.items.stream().map(Item::id).toList();
            var p=new TicketProposal(ids,"FOS 구성 및 인사 정보 수신 검증","원문 근거",tasks,checks,List.of("[제안] 결과 기록"),List.of(),List.of());
            var result=service.applyDraftProposals(m,c,List.of(p),"codex","test-model",null,-1);
            assertThat(service.applyDraftProposals(m,c,List.of(p),"codex","test-model",null,-1)).hasSize(3);
            return result;
        });
        var parent=generated.stream().filter(d->d.parentDraftId.isBlank()).findFirst().orElseThrow();
        var children=service.children(parent.id);assertThat(children).hasSize(2);
        assertThat(children).allSatisfy(child->{assertThat(child.issueType).isEqualTo("Sub-task");assertThat(child.parentDraftId).isEqualTo(parent.id);assertThat(child.description).contains("1. 작업목록","4. AI 검토사항").doesNotContain("상위 업무:","배경 및 목적","회의 근거");assertThat(child.status).isEqualTo("DRAFT");});
        String allDescriptions=children.stream().map(d->d.description).collect(java.util.stream.Collectors.joining("\n"));
        tasks.forEach(task->assertThat(allDescriptions).contains(TicketWording.normalize(task)));checks.forEach(check->assertThat(allDescriptions).contains(TicketWording.normalize(check)));
        assertThat(children.stream().map(d->d.description).toList()).anySatisfy(text->assertThat(text).contains("[확인 필요] FOS와 FS가 동일한 구성 요소인지 미확정임"));
        approveManually(generated.stream().map(d->d.id).toList());
        var reversed=new ArrayList<>(generated.stream().map(d->d.id).toList());Collections.reverse(reversed);
        assertThat(publisher.publishBatch(reversed)).allMatch(r->r.status().equals("PUBLISHED"));
        var saved=service.drafts().findById(parent.id).orElseThrow();
        assertThat(service.children(parent.id)).allSatisfy(child->assertThat(child.parent).isEqualTo(saved.issueKey));
        assertThat(jira.calls).hasSize(4);assertThat(jira.calls.get(0).issueType).isEqualTo("Epic");assertThat(jira.calls.get(1).id).isEqualTo(parent.id);
    }
    @Test void checklistExpansionSkipsDuplicatesAlreadyCoveredByAiChildren(){
        var child=new TicketProposal(List.of("id"),"세부 업무","근거",List.of("[제안] 연결 확인"),List.of("응답 확인"),List.of("결과 기록"),List.of(),List.of());
        var parent=new TicketProposal(List.of("id"),"상위 업무","근거",List.of("[제안] 연결 확인","추가 작업","추가 작업"),List.of("응답 확인","추가 검증"),List.of(),List.of(),List.of(),List.of(child));
        var rows=new ArrayList<>(parent.subtasks());var covered=new HashSet<String>();child.tasks().forEach(v->covered.add(MeetingService.checklistKey(v)));child.checks().forEach(v->covered.add(MeetingService.checklistKey(v)));
        MeetingService.addChecklistSubtasks(rows,covered,parent,parent.tasks(),false);MeetingService.addChecklistSubtasks(rows,covered,parent,parent.checks(),true);
        assertThat(rows).hasSize(3);
    }
}
