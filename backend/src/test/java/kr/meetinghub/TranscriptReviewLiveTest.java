package kr.meetinghub;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in only. Uses the app's existing CLI login and synthetic text, never meeting data. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_TRANSCRIPT_REVIEW",matches="true")
class TranscriptReviewLiveTest {
    @Test void reviewsTheProvidedExampleWithActualConfiguredAi(){
        var source=List.of(new Segment("context","화자 1",0,2000,"다음 주 주간보고 자료로 사용할 자막 영상 초안을 먼저 검토합시다.",false),new Segment("example","화자 1",2000,7000,"이번 주에는 자막 입힌 걸로 주간복 때 초연을 한 번 봤으면 좋겠는데",false));
        var ai=new CodexIntelligence(new AiConfig("codex","codex-default",""),new ObjectMapper());
        var result=ai.reviewTranscript("주간보고 자막 영상 초안 검토",source);
        assertThat(result.get(1).text()).contains("주간보고","초안","봤으면 좋겠는데");
        assertThat(result.get(1).id()).isEqualTo("example");assertThat(result.get(1).startMs()).isEqualTo(2000);
        System.out.println("Synthetic transcript correction: "+result.get(1).text());
    }
    @Test void reviewsEveryConversationalTurnAndKeepsQuestionAndReplyTone(){
        var source=List.of(new Segment("first","화자 1",0,2000,"소리가 들리는다",false),new Segment("reply","화자 2",2000,4000,"네 잘 들려요",false),new Segment("next","화자 1",4000,6000,"그럼 화면 공유할게요",false));
        var ai=new CodexIntelligence(new AiConfig("codex","codex-default",""),new ObjectMapper());
        var result=ai.reviewTranscript("온라인 회의 시작 · 소리 연결 확인",source);
        assertThat(result).hasSize(source.size());assertThat(result.get(0).text()).contains("들리는데?");
        assertThat(result.get(1).text()).contains("들려요").doesNotContain("들린다");
        System.out.println("Synthetic dialogue correction: "+result.get(0).text()+" / "+result.get(1).text());
    }
    @Test void resolvesMisheardDeicticUsingNeighbouringTurnsWithoutReplacingGenuineLanguageNames(){
        var ai=new CodexIntelligence(new AiConfig("codex","codex-default",""),new ObjectMapper());
        var scene=List.of(new Segment("a","화자 1",0,2000,"방금 틀어본 영상 다시 확인해 봐.",false),new Segment("b","화자 1",2000,4000,"한국 말풍선에 글자 없었지?",false),new Segment("c","화자 2",4000,6000,"응, 방금 재생한 장면에는 글자가 안 보였어.",false));
        var corrected=ai.reviewTranscript("방금 재생한 영상 시안 확인",scene);
        assertThat(corrected.get(1).text()).contains("방금 말풍선에 글자 없었지?");
        var language=List.of(new Segment("a","화자 1",0,2000,"한국어 버전과 영어 버전 말풍선 글자를 비교해 봅시다.",false),new Segment("b","화자 2",2000,4000,"한국 말풍선에 글자 없었지?",false));
        assertThat(ai.reviewTranscript("한국어와 영어 버전 비교",language).get(1).text()).contains("한국").doesNotContain("방금");
        System.out.println("Synthetic deictic correction: "+corrected.get(1).text());
    }
}
