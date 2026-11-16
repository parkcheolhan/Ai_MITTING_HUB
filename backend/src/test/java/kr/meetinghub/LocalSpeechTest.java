package kr.meetinghub;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

class LocalSpeechTest {
    final ObjectMapper json=new ObjectMapper();@TempDir Path directory;
    @Test void readsAnonymousSpeakerGroupsWithoutInventingNames()throws Exception{
        var segments=LocalSpeech.parse(json.readTree("{\"segments\":[{\"start\":0,\"end\":2,\"text\":\"발언\",\"speaker\":\"화자 2\"}]}"),0);
        assertThat(segments.get(0).speaker()).isEqualTo("화자 2");
    }
    @Test void existingTextRecordsBypassSpeechEngine(){
        var speech=new LocalSpeech(json,directory,(path,language,cancelled)->{throw new AssertionError("Should not transcribe supplied text");});
        var content=new Content();content.segments=TranscriptParser.parse("사용자가 제공한 음성 기록");
        assertThat(speech.transcribe(content)).isSameAs(content.segments);
    }
    @Test void audioProducesTimedSegmentsWithoutInventedSpeakerNames()throws Exception{
        Files.write(directory.resolve("recording.m4a"),new byte[]{1});
        var response=json.readTree("{\"segments\":[{\"start\":1.25,\"end\":3.5,\"text\":\"로그인 오류를 수정합니다.\"}]}");
        var speech=new LocalSpeech(json,directory,(path,language,cancelled)->{assertThat(path).isEqualTo(directory.resolve("recording.m4a"));return response;});
        var content=new Content();content.assets.add(new Asset("a","녹음.m4a","audio/mp4",1,"recording.m4a"));
        var segment=speech.transcribe(content).get(0);assertThat(segment.startMs()).isEqualTo(1250);assertThat(segment.endMs()).isEqualTo(3500);assertThat(segment.speaker()).isEqualTo("화자 미분리");assertThat(segment.estimatedTime()).isFalse();
    }
    @Test void rejectsUnsafeAssetPathsBeforeStartingTheWorker(){
        var calls=new AtomicInteger();var speech=new LocalSpeech(json,directory,(path,language,cancelled)->{calls.incrementAndGet();return null;});
        var content=new Content();content.assets.add(new Asset("a","recording.m4a","audio/mp4",1,"../outside.m4a"));
        assertThatThrownBy(()->speech.transcribe(content)).hasMessageContaining("경로");assertThat(calls.get()).isZero();
    }
    @Test void accAndAacFilesReachTheSpeechDecoder()throws Exception{
        var response=json.readTree("{\"segments\":[{\"start\":0,\"end\":1,\"text\":\"음성 기록\"}]}");
        for(String extension:List.of("acc","aac")){
            String key="recording."+extension;Files.write(directory.resolve(key),new byte[]{1});
            var speech=new LocalSpeech(json,directory,(path,language,cancelled)->{assertThat(path).isEqualTo(directory.resolve(key));return response;});
            var content=new Content();content.assets.add(new Asset("a",key,"audio/aac",1,key));
            assertThat(speech.transcribe(content)).hasSize(1);
        }
    }
    @Test void silenceAndInvalidTimestampsAreNotPresentedAsSuccessfulTranscripts()throws Exception{
        assertThatThrownBy(()->LocalSpeech.parse(json.readTree("{\"error\":\"NO_SPEECH\"}"),0)).hasMessageContaining("음성을 인식하지 못했습니다");
        assertThatThrownBy(()->LocalSpeech.parse(json.readTree("{\"segments\":[{\"start\":3,\"end\":1,\"text\":\"오류\"}]}"),0)).hasMessageContaining("시각");
    }
}
