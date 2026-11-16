package kr.meetinghub;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:compact;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
class TicketConsolidationTest {
    @Autowired MeetingService service;@Autowired TicketConsolidation consolidation;@Autowired TransactionTemplate tx;
    String meeting(){return service.create(new CreateMeeting("주제 통합","",List.of(),"TEST","","기록","TEXT","ko",false,"일반")).id();}
    Draft draft(String meeting,String title,String parent,String owner,String marker){
        String description=parent.isBlank()?"1. 배경 및 목적\n배경 "+marker+"\n\n2. 해야 하는 작업 목록\n- 작업 "+marker+"\n\n3. 확인해야 하는 항목\n- 확인 "+marker+"\n\n4. 완료 조건\n- 완료 "+marker+"\n\n5. 의존성 및 제약사항\n- 제약 "+marker+"\n\n7. AI 검토 사항\n- 검토 "+marker:TicketDescriptions.childDocument(List.of("작업 "+marker),List.of("완료 "+marker),List.of("제약 "+marker),List.of("검토 "+marker));
        return service.addDraft(meeting,new DraftInput("TEST",parent.isBlank()?"Task":"Sub-task",title,description,owner,"Medium","",List.of(),"",List.of(),parent));
    }
    CompactPlan plan(String meeting,CompactGroup... groups){var versions=new LinkedHashMap<String,Long>();service.get(meeting).drafts().forEach(d->versions.put(d.id,d.version));return new CompactPlan(List.of(groups),versions);}
    @Test void mergesParentsAndChildrenPreservingAllWorkAndOriginalHistory(){
        String m=meeting(),other=meeting();Draft a=draft(m,"파일 보안 협의","","","a");var b=draft(m,"파일 보안 구현","","person-a","b");var ca=draft(m,"사전 확인",a.id,"","ca");var cb=draft(m,"구현 검증",b.id,"person-a","cb");var untouched=draft(other,"다른 회의","","","other");
        tx.executeWithoutResult(s->{for(var id:List.of(a.id,ca.id,b.id,cb.id)){var d=service.drafts().findById(id).orElseThrow();d.scheduleMode=(id.equals(a.id)||id.equals(ca.id))?null:"AI";if(id.equals(a.id))d.consolidationBackup="prior-backup";service.drafts().save(d);}});
        var input=plan(m,new CompactGroup(List.of(a.id,b.id),"파일 보안 개선"),new CompactGroup(List.of(ca.id,cb.id),"[작업] 암호화 구현 및 검증"));
        var rows=consolidation.apply(m,input);assertThat(rows).hasSize(2);var parent=rows.stream().filter(d->d.id.equals(a.id)).findFirst().orElseThrow();var child=rows.stream().filter(d->d.id.equals(ca.id)).findFirst().orElseThrow();
        assertThat(parent.description).contains("작업 a","작업 b","확인 a","확인 b","검토 a","검토 b");assertThat(parent.assignee).isEqualTo("person-a");assertThat(parent.scheduleMode).isEqualTo("AI");assertThat(parent.consolidationBackup).contains("prior-backup");assertThat(child.description).contains("작업 ca","작업 cb","완료 ca","완료 cb","제약 ca","제약 cb","검토 ca","검토 cb");assertThat(child.parentDraftId).isEqualTo(a.id);assertThat(child.assignee).isEqualTo("person-a");
        assertThat(service.drafts().findById(b.id).orElseThrow().status).isEqualTo("CONSOLIDATED");assertThat(service.drafts().findById(cb.id).orElseThrow().consolidatedInto).isEqualTo(ca.id);assertThat(service.get(other).drafts()).hasSize(1);assertThat(service.drafts().findById(untouched.id).orElseThrow().description).contains("other");
        assertThatThrownBy(()->consolidation.apply(m,input)).hasMessageContaining("후보가 변경");
    }
    @Test void rejectsConflictingOwnersAndStaleVersionsWithoutPartialChanges(){
        String m=meeting();Draft a=draft(m,"업무 a","","person-a","a"),b=draft(m,"업무 b","","person-b","b");
        var input=plan(m,new CompactGroup(List.of(a.id,b.id),"통합"));assertThatThrownBy(()->consolidation.apply(m,input)).hasMessageContaining("담당자");assertThat(service.get(m).drafts()).hasSize(2);
        var stale=plan(m,new CompactGroup(List.of(a.id),"변경"));service.update(a.id,new DraftInput("TEST","Task","사용자 수정",a.description,"person-a","Medium","",List.of(),"",List.of()));
        assertThatThrownBy(()->consolidation.apply(m,stale)).hasMessageContaining("후보가 변경");assertThat(service.drafts().findById(a.id).orElseThrow().summary).isEqualTo("사용자 수정");
    }
    @Test void publishedChildPreventsReparentingAndRollsBackParentMerge(){
        String m=meeting();Draft a=draft(m,"업무 a","","","a"),b=draft(m,"업무 b","","","b");var child=draft(m,"등록된 하위",b.id,"","c");
        tx.executeWithoutResult(s->{var d=service.drafts().findById(child.id).orElseThrow();d.status="PUBLISHED";d.issueKey="TEST-7";service.drafts().save(d);});
        var input=plan(m,new CompactGroup(List.of(a.id,b.id),"통합"));assertThatThrownBy(()->consolidation.apply(m,input)).hasMessageContaining("등록된 관련 업무");assertThat(service.get(m).drafts()).hasSize(3);assertThat(service.drafts().findById(b.id).orElseThrow().status).isEqualTo("DRAFT");
    }
    @Test void dependencyCycleCreatedByMergeRollsBackAllChanges(){
        String m=meeting();Draft a=draft(m,"업무 a","","","a"),b=draft(m,"업무 b","","","b"),c=draft(m,"업무 c","","","c");
        tx.executeWithoutResult(s->{Draft da=service.drafts().findById(a.id).orElseThrow(),dc=service.drafts().findById(c.id).orElseThrow();da.dependencyDraftIds.add(c.id);dc.dependencyDraftIds.add(b.id);service.drafts().save(da);service.drafts().save(dc);});
        assertThatThrownBy(()->consolidation.apply(m,plan(m,new CompactGroup(List.of(a.id,b.id),"통합")))).hasMessageContaining("순환");assertThat(service.get(m).drafts()).hasSize(3);
    }
}
