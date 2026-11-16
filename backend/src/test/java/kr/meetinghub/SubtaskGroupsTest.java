package kr.meetinghub;

import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

class SubtaskGroupsTest {
    @Test void newCandidatesGroupExecutionAndVerificationIntoLargerWorkPackages(){
        assertThat(SubtaskGroups.creationLimit(4,2)).isEqualTo(1);assertThat(SubtaskGroups.creationLimit(8,4)).isEqualTo(2);assertThat(SubtaskGroups.creationLimit(0,0)).isEqualTo(1);
    }
    @Test void assignsOneThirdAcrossParentsWithoutMixingSeparateParents(){
        var limits=SubtaskGroups.limits(List.of(7,7,7,7));assertThat(limits.stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
        assertThat(limits).allMatch(n->n>=1&&n<=3);
        assertThat(SubtaskGroups.limits(List.of(1,1,1))).containsExactly(1,1,1);
    }
    @Test void reducesNineCandidatesToThreeWithoutLosingWorkReviewsOrRecommendations(){
        var original=IntStream.range(0,9).mapToObj(i->new TicketProposal(List.of("i"),"[작업] 항목 "+i,"",List.of("작업 "+i),List.of(),List.of("완료조건 "+i),List.of("제약 "+i),List.of("검토 "+i),List.of(),i==8)).toList();
        var result=SubtaskGroups.reduce(original,3);assertThat(result).hasSize(3);
        assertThat(result.stream().flatMap(p->p.tasks().stream())).containsExactlyInAnyOrderElementsOf(original.stream().flatMap(p->p.tasks().stream()).toList());
        assertThat(result.stream().flatMap(p->p.reviewNotes().stream())).hasSize(9);
        assertThat(result.stream().flatMap(p->p.acceptanceCriteria().stream())).hasSize(9);
        assertThat(result.stream().flatMap(p->p.dependencies().stream())).hasSize(9);
        assertThat(result).anyMatch(TicketProposal::aiRecommended);
    }
}
