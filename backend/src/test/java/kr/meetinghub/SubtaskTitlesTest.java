package kr.meetinghub;

import java.util.*;
import org.junit.jupiter.api.Test;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

class SubtaskTitlesTest {
    @Test void usesShortTaskNamesAndPreservesTags(){
        assertThat(SubtaskTitles.compact("[작업] 파일 업로드·다운로드 암호화 처리에 대해 미리 협의하고 테스트를 요청한다.")).isEqualTo("[작업] 파일 업로드·다운로드 암호화 처리");
        assertThat(SubtaskTitles.compact("[확인] 접근 권한을 확인해야 한다.")).isEqualTo("[확인] 접근 권한 확인");
        assertThat(SubtaskTitles.compact("[작업] [제안] 로그인 오류를 수정한다.")).isEqualTo("[작업] [제안] 로그인 오류 수정");
        assertThat(SubtaskTitles.compact("[작업] 파일 업로드·다운로드 암호화 처리")).isEqualTo("[작업] 파일 업로드·다운로드 암호화 처리");
        assertThat(SubtaskTitles.compact("[확인] 데이터에 대한 접근 권한 검토")).isEqualTo("[확인] 데이터에 대한 접근 권한 검토");
        assertThat(SubtaskTitles.compact("[확인] [확인 필요] 접근 권한 검토")).isEqualTo("[확인] 접근 권한 검토");
        assertThat(SubtaskTitles.compact("[확인 필요] 접근 권한 검토")).isEqualTo("[확인] 접근 권한 검토");
    }
    @Test void aiTitlesAreUsedWithoutReplacingTheFullTaskDescription(){
        String task="파일 업로드·다운로드 암호화 처리에 대해 미리 협의하고 테스트를 요청한다.";
        var parent=new TicketProposal(List.of("i"),"파일 보안","배경",List.of(task),List.of("결과를 확인한다."),List.of(),List.of(),List.of(),List.of(),false,List.of("파일 업로드·다운로드 암호화 처리"),List.of("처리 결과 확인"),List.of(List.of("작업 전: 암호화 키 관리 주체 확인","작업 후: 업로드 파일 정상 복호화 검증")),List.of(List.of("작업 후: 처리 결과 확인")));
        var children=new ArrayList<TicketProposal>();var covered=new HashSet<String>();
        MeetingService.addChecklistSubtasks(children,covered,parent,parent.tasks(),false);
        MeetingService.addChecklistSubtasks(children,covered,parent,parent.checks(),true);
        assertThat(children.get(0).summary()).isEqualTo("[작업] 파일 업로드·다운로드 암호화 처리");
        assertThat(children.get(0).tasks()).containsExactly(task);
        assertThat(children.get(0).reviewNotes()).containsExactly("작업 전: 암호화 키 관리 주체 확인","작업 후: 업로드 파일 정상 복호화 검증");
        assertThat(children.get(1).summary()).isEqualTo("[확인] 처리 결과 확인");
    }
}
