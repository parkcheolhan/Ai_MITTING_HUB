package kr.meetinghub;

import java.util.List;
import org.junit.jupiter.api.Test;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

class TimeReferencesTest {
    @Test void removesMultipleMillisecondRangesWithoutRemovingReviewLabelsOrSchedule(){
        String text="수신 연결 확인 [01:48.890~01:58.090, 03:22.540~03:39.660]\n[확인 필요] FOS 구성\n[제안] 14:00 회의, 2026-10-26 기한";
        assertThat(TimeReferences.without(text)).doesNotContain("01:48","03:22").contains("수신 연결 확인","[확인 필요] FOS 구성","[제안] 14:00 회의, 2026-10-26 기한");
        assertThat(TimeReferences.without("논의 [00:01:48.890–00:01:58.090] 및 결정 [추정 00:03:22]")).isEqualTo("논의  및 결정");
    }
    @Test void cleansEveryMinutesSectionAndEpicDescription(){
        var m=new Minutes();String text="회의 내용 [01:48.890~01:58.090, 03:22.540~03:39.660]";
        m.overview=text;m.summary=text;m.discussion=text;m.decisions=text;m.openQuestions=text;m.followUp=text;
        TimeReferences.clean(m);
        assertThat(List.of(m.overview,m.summary,m.discussion,m.decisions,m.openQuestions,m.followUp)).allSatisfy(value->assertThat(value).isEqualTo("회의 내용"));
        var c=new Content();c.minutes=m;assertThat(EpicContents.description("회의",c)).doesNotContain("01:48","03:22");
    }
    @Test void parentHasNoTimeCitationsAndChildEvidenceUsesWholeSecondsOnly(){
        var evidence=new Evidence("s",108890,"참석자","수신 차단 원인을 확인한다. [01:48.890~01:58.090]",false);
        var item=new Item("i","ACTION","수신 연결",List.of(),List.of(),List.of(),List.of(),null,null,List.of(evidence),0.9,true);
        var p=new TicketProposal(List.of("i"),"수신 연결","배경 [01:48.890~01:58.090]",List.of("[확인 필요] 원인 조사 [03:22.540~03:39.660]"),List.of("결과 확인"),List.of("결과 기록"),List.of(),List.of());
        String parent=MeetingService.proposalDescription("m",p,List.of(item),"codex","test-model");
        String child=MeetingService.proposalDescription("m",p,List.of(item),"codex","test-model",true);
        assertThat(parent).contains("00:01:48 ~ 00:01:48","[확인 필요]").doesNotContain("참석자:","※ AI가 정리한 초안",".890");
        assertThat(child).doesNotContain("회의 근거","00:01:48","참석자:","※ AI가 정리한 초안",".890",".540");
        assertThat(MeetingService.formatTime(3_661_999)).isEqualTo("01:01:01");
    }
}
