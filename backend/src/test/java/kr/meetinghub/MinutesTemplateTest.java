package kr.meetinghub;

import java.util.List;
import org.junit.jupiter.api.Test;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

class MinutesTemplateTest {
    @Test void preservesLayoutAndReplacesOnlyTemplateSlots(){
        var c=new Content();c.template="# {{회의명}}\n참석: {{ 참석자 }}\n결정\n{{결정사항}}\n요약\n{{요약}}\n{{메모}}";
        c.participants=List.of("김민수","이지은");c.memo="메모";
        var m=new Minutes();m.summary="가격 $5, 경로 C:\\work, {{결정사항}}";
        assertThat(MinutesTemplate.render("주간 회의",c,m)).isEqualTo("# 주간 회의\n참석: 김민수, 이지은\n결정\n기록 없음\n요약\n가격 $5, 경로 C:\\work, {{결정사항}}\n메모");
    }
    @Test void rejectsUnsupportedMissingAndOversizedSlots(){
        assertThatThrownBy(()->MinutesTemplate.validate("{{요약}} {{없는항목}}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->MinutesTemplate.validate("회의록 {{회의명}}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->MinutesTemplate.validate("{{요약}}"+"x".repeat(20000))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->MinutesTemplate.validate("일반")).doesNotThrowAnyException();
    }
    @Test void epicUsesEditedTemplateDocument(){
        var c=new Content();c.minutes=new Minutes();c.minutes.formatted="회사 양식\n편집한 결정 사항";
        assertThat(EpicContents.description("회의",c)).contains(c.minutes.formatted).doesNotContain("논의 내용");
    }
}
