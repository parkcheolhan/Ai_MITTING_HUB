package kr.meetinghub;

import java.util.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

class TranscriptReviewTest {
    final ObjectMapper json=new ObjectMapper();
    final AiConfig config=new AiConfig("openai","unit-model","fake-test-key");
    final Segment original=new Segment("s1","화자 1",1000,7000,"이번 주에는 자막 입힌 걸로 주간복 때 초연을 한 번 봤으면 좋겠는데",false);
    Map<String,Object> edit(String id,String before,String after){return Map.of("segmentId",id,"originalText",before,"text",after,"reason","앞뒤 맥락상 회의와 문서 초안에 대한 발언");}
    @Test void correctsContextWithoutChangingSourceIdentityOrSpeechIntent(){
        String corrected="이번 주에는 자막 입힌 걸로 주간보고 때 초안을 한 번 봤으면 좋겠는데";
        var ai=new OpenAiIntelligence(config,json,(key,body)->{
            var request=json.readTree(body);assertThat(request.path("instructions").asText()).contains("어말어미","이름·회사","추측으로 완성하지","일괄 치환하지");
            assertThat(request.path("text").path("format").path("name").asText()).isEqualTo("transcript_review");
            return OpenAiIntelligenceTest.reply(json,Map.of("edits",List.of(edit(original.id(),original.text(),corrected))));
        });
        assertThat(ai.reviewTranscript("자막 초안 검토",List.of(original))).containsExactly(new Segment(original.id(),original.speaker(),1000,7000,corrected,false));
    }
    @Test void rejectsFabricatedIdsStaleOriginalsRepeatedEditsAndChangedNumbers(){
        var numbered=new Segment("s","김민수",0,1000,"3개를 확인했으면 좋겠습니다.",false);
        for(var edits:List.of(List.of(edit("unknown",numbered.text(),"수정")),List.of(edit("s","다른 원문","수정")),List.of(edit("s",numbered.text(),"4개를 확인했으면 좋겠습니다.")),List.of(edit("s",numbered.text(),numbered.text()),edit("s",numbered.text(),numbered.text())))){
            var ai=new OpenAiIntelligence(config,json,(key,body)->OpenAiIntelligenceTest.reply(json,Map.of("edits",edits)));
            assertThatThrownBy(()->ai.reviewTranscript("검토",List.of(numbered))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void batchesLongTranscriptsAndStopsCallingAfterCancellation(){
        var source=java.util.stream.IntStream.range(0,61).mapToObj(i->new Segment("s"+i,"화자 1",i*1000,(i+1)*1000,"검토할 발언",false)).toList();
        var calls=new AtomicInteger();
        var ai=new OpenAiIntelligence(config,json,(key,body)->{calls.incrementAndGet();return OpenAiIntelligenceTest.reply(json,Map.of("edits",List.of()));});
        assertThat(ai.reviewTranscript("긴 회의",source)).isEqualTo(source);assertThat(calls.get()).isEqualTo(3);
        calls.set(0);assertThatThrownBy(()->ai.reviewTranscript("긴 회의",source,()->calls.get()>0)).hasMessageContaining("취소");assertThat(calls.get()).isEqualTo(1);
    }
}
