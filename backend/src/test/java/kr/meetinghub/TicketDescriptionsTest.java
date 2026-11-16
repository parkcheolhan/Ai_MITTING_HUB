package kr.meetinghub;
import java.util.*;
import org.junit.jupiter.api.Test;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

class TicketDescriptionsTest {
    @Test void parentContainsOnlyActualTimeRangeAndChildHasNoEvidenceOrFooter(){
        var segments=List.of(new Segment("s1","화자",43200000,43230000,"원문",false),new Segment("s2","화자",47450000,47471000,"원문",false));
        var item=new Item("i","ACTION","업무",List.of(),List.of(),List.of(),List.of(),null,null,List.of(new Evidence("s1",43200000,"화자","원문",false),new Evidence("s2",47450000,"화자","원문",false)),1,false);
        String text="1. 배경 및 목적\n사용자가 작성한 내용\n\n6. 회의 근거\n회의: /meetings/m\n화자: 원문\n7. AI 검토 사항\n확인 필요\n\n※ AI가 정리한 초안 (codex-default)입니다. [제안]/[확인 필요]는 확정된 사실과 구분해 검토하세요. 승인 전 등록되지 않습니다.";
        var parent=TicketDescriptions.normalize(text,false,List.of(item),List.of("i"),segments);
        assertThat(parent).contains("6. 회의 근거\n12:00:00 ~ 13:11:11\n","사용자가 작성한 내용").doesNotContain("/meetings/","화자:","※ AI가");
        assertThat(TicketDescriptions.normalize(parent,false,List.of(item),List.of("i"),segments)).isEqualTo(parent);
        assertThat(TicketDescriptions.normalize(text,true,List.of(item),List.of("i"),segments)).contains("1. 작업목록","2. 완료조건","3. 제약사항","4. AI 검토사항").doesNotContain("배경 및 목적","회의 근거","/meetings/","화자:","※ AI가");
    }
    @Test void childKeepsOnlyFourSectionsAndListsTaskSpecificReviewNotes(){
        var proposal=new TicketProposal(List.of("i"),"파일 암호화","상위 업무에서 분리한 배경",List.of("파일 업로드·다운로드 암호화 처리"),List.of("작업 후: 암호화 파일의 정상 복호화 확인"),List.of("암복호화 검증 완료"),List.of("키 관리 정책 준수"),List.of("작업 전: 암호화 적용 구간과 키 관리 주체 확인"));
        String result=TicketDescriptions.childDescription(proposal);
        assertThat(result).startsWith("1. 작업목록\n- 파일 업로드·다운로드 암호화 처리").contains("2. 완료조건\n- 암복호화 검증 완료","3. 제약사항\n- 키 관리 정책 준수","4. AI 검토사항\n- 작업 후:","\n- 작업 전:").doesNotContain("배경","상위 업무","회의 근거");
        assertThat(TicketDescriptions.normalize(result,true,List.of(),List.of(),List.of())).isEqualTo(result);
    }
}
