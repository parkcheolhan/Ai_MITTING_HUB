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
}
