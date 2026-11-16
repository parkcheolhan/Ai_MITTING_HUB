package kr.meetinghub;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TicketWordingTest {
    @Test void removesDeclarativeEndingsAcrossAllTicketSections(){
        assertThat(TicketWording.normalize("확인한다\n- 파일을 구현한다.\n- 테스트를 요청합니다\n- 범위를 검토해야 한다\n- 전송하지 않는다\n- 결과가 없다"))
            .isEqualTo("확인\n- 파일을 구현\n- 테스트를 요청\n- 범위를 검토 필요\n- 전송하지 않음\n- 결과가 없음");
    }
    @Test void preservesIdentifiersConditionsAndAlreadyNominalText(){
        String text="[제안] 작업 전: 파일 암호화 확인\nhttps://example.com/확인한다/test\n테스트를 수행한다면 결과 검토\n12:00:00 ~ 13:11:11";
        assertThat(TicketWording.normalize(text)).isEqualTo(text);
        assertThat(TicketWording.normalize(TicketWording.normalize("검토한다"))).isEqualTo("검토");
    }
    @Test void formatsParentAndChildBodies(){
        assertThat(TicketDescriptions.normalize("1. 작업목록\n- 결과를 확인한다",true,List.of(),List.of(),List.of())).contains("결과를 확인").doesNotContain("한다");
        assertThat(TicketDescriptions.normalize("1. 배경 및 목적\n범위를 검토한다",false,List.of(),List.of(),List.of())).contains("범위를 검토").doesNotContain("한다");
    }
}
