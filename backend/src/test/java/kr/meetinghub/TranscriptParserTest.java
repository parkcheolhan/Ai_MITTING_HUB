package kr.meetinghub;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TranscriptParserTest {
    @Test void importsClovaSpeakerAndTimestampHeadersAndMultilineSpeech() {
        var segments=TranscriptParser.parse("\uFEFF참석자 1 00:12\n환경을 확인하겠습니다.\n접근 권한도 점검하겠습니다.\n\n참석자 2 01:05\n테스트를 진행합시다.");
        assertThat(segments).hasSize(2);assertThat(segments.get(0).speaker()).isEqualTo("참석자 1");
        assertThat(segments.get(0).startMs()).isEqualTo(12000);assertThat(segments.get(0).text()).contains("\n접근 권한");
        assertThat(segments.get(1).startMs()).isEqualTo(65000);assertThat(segments.get(0).estimatedTime()).isFalse();
    }
    @Test void importsSeparateSpeakerAndTimeLines() {
        var segments=TranscriptParser.parse("김민수\n00:12:34\n권한을 확인하겠습니다.\n이지은\n00:13:20\n테스트 진행하겠습니다.");
        assertThat(segments).hasSize(2);assertThat(segments.get(0).speaker()).isEqualTo("김민수");
        assertThat(segments.get(0).startMs()).isEqualTo(754000);assertThat(segments.get(1).speaker()).isEqualTo("이지은");
    }
    @Test void preservesSrtStartEndAndFractionalSeconds() {
        var segments=TranscriptParser.parse("1\n00:00:12,345 --> 00:00:18,100\n배포 전에 확인합시다.\n\n2\n00:00:20,000 --> 00:00:24,000\n검토가 필요합니다.");
        assertThat(segments).hasSize(2);assertThat(segments.get(0).startMs()).isEqualTo(12345);assertThat(segments.get(0).endMs()).isEqualTo(18100);
    }
    @Test void importsVttAndPlainTextWithoutInventingSourceTimes() {
        var vtt=TranscriptParser.parse("WEBVTT\n\n00:01.500 --> 00:03.000\n환경 확인");assertThat(vtt.get(0).startMs()).isEqualTo(1500);
        var plain=TranscriptParser.parse("환경 확인\n테스트 진행");assertThat(plain).hasSize(2);assertThat(plain).allMatch(s->s.estimatedTime());
    }
    @Test void refusesInvalidTimestamp() {
        assertThatThrownBy(()->TranscriptParser.parse("[00:99] 화자: 확인하세요.")).hasMessageContaining("초는");
    }
}
