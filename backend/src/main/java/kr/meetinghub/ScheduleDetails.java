package kr.meetinghub;

import java.time.OffsetDateTime;
import java.util.*;
import kr.meetinghub.Domain.*;

final class ScheduleDetails {
    static boolean present(Draft d){return d.startAt!=null&&!d.startAt.isBlank()&&d.endAt!=null&&!d.endAt.isBlank();}
    static void ready(Draft d){
        if("EXISTING".equals(d.registrationMode))return;
        MeetingService.require(!"AI".equals(d.scheduleMode)||present(d),"AI 일정이 설정되지 않았거나 변경되었습니다. AI 일정을 다시 설정한 뒤 승인하세요.");
        if(present(d))MeetingService.require(OffsetDateTime.parse(d.endAt).isAfter(OffsetDateTime.parse(d.startAt)),"종료 시각은 시작 이후여야 합니다.");
    }
    static String description(Draft d){
        if(!present(d))return d.description;
        return d.description+"\n\n작업 일정\n- 설정 방식: "+("AI".equals(d.scheduleMode)?"AI 추정 일정":"직접 설정")+"\n- 담당자: "+Objects.toString(d.assigneeName,d.assignee)+"\n- 시작: "+d.startAt+"\n- 종료: "+d.endAt+"\n- 예상 작업시간: "+Objects.toString(d.estimatedMinutes,"0")+"분\n"+(d.scheduleReason==null||d.scheduleReason.isBlank()?"":"- 일정 판단: "+d.scheduleReason+"\n")+(d.dependencyDraftIds==null||d.dependencyDraftIds.isEmpty()?"":"- 선행 업무 후보 ID: "+String.join(", ",d.dependencyDraftIds));
    }
}
