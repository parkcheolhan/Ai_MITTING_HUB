package kr.meetinghub;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

class SchedulePlannerTest {
    List<SchedulePriority> priorities=List.of(new SchedulePriority("High","높음"),new SchedulePriority("Medium","보통"));
    ScheduleRequest request(String start){return new ScheduleRequest(start,"Asia/Seoul",9,18,List.of());}
    SchedulePlanner.Work work(String id,String person,int minutes,String... deps){return new SchedulePlanner.Work(id,person,false,"","",minutes,"Medium",List.of(deps));}
    SchedulePlanner.Slot slot(String start,String end){return new SchedulePlanner.Slot(OffsetDateTime.parse(start).atZoneSameInstant(ZoneId.of("Asia/Seoul")),OffsetDateTime.parse(end).atZoneSameInstant(ZoneId.of("Asia/Seoul")));}
    @Test void serializesSamePersonButAllowsIndependentPeopleInParallel(){
        var result=SchedulePlanner.plan(List.of(work("a","minsu",60),work("b","minsu",60),work("c","jieun",60)),List.of(),request("2026-11-16T09:00:00+09:00"),priorities);
        assertThat(result.get("a").start()).isEqualTo(result.get("c").start());assertThat(result.get("b").start()).isEqualTo(result.get("a").end());
    }
    @Test void waitsForAnotherPersonsPredecessorAndSkipsLunch(){
        var result=SchedulePlanner.plan(List.of(work("a","minsu",180),work("b","jieun",120,"a")),List.of(),request("2026-11-16T09:00:00+09:00"),priorities);
        assertThat(result.get("b")).isEqualTo(slot("2026-11-16T13:00:00+09:00","2026-11-16T15:00:00+09:00"));
    }
    @Test void lunchIsExcludedFromEffortAndIndividualAvailability(){
        var request=new ScheduleRequest("2026-11-16T11:30:00+09:00","Asia/Seoul",9,18,List.of(new Availability("q","2026-11-16T12:15:00+09:00",12,18)));
        var result=SchedulePlanner.plan(List.of(work("a","p",60),work("b","q",60)),List.of(),request,priorities);
        assertThat(result.get("a")).isEqualTo(slot("2026-11-16T11:30:00+09:00","2026-11-16T13:30:00+09:00"));
        assertThat(result.get("b")).isEqualTo(slot("2026-11-16T13:00:00+09:00","2026-11-16T14:00:00+09:00"));
    }
    @Test void skipsWeekendAndFinishesRemainingWorkOnMonday(){
        var result=SchedulePlanner.plan(List.of(work("a","minsu",120)),List.of(),request("2026-11-13T17:00:00+09:00"),priorities);
        assertThat(result.get("a")).isEqualTo(slot("2026-11-13T17:00:00+09:00","2026-11-16T10:00:00+09:00"));
    }
    @Test void avoidsExistingReservationWithoutProducingOverlappingEnvelope(){
        var reserved=slot("2026-11-16T10:00:00+09:00","2026-11-16T11:00:00+09:00");
        var result=SchedulePlanner.plan(List.of(work("a","minsu",120)),List.of(new SchedulePlanner.Reservation("minsu",reserved.start(),reserved.end())),request("2026-11-16T09:00:00+09:00"),priorities);
        assertThat(result.get("a")).isEqualTo(slot("2026-11-16T11:00:00+09:00","2026-11-16T14:00:00+09:00"));
    }
    @Test void respectsIndividualAvailabilityAndWorkingHours(){
        var request=new ScheduleRequest("2026-11-16T09:00:00+09:00","Asia/Seoul",9,18,List.of(new Availability("jieun","2026-11-17T14:00:00+09:00",14,17)));
        var result=SchedulePlanner.plan(List.of(work("a","jieun",240)),List.of(),request,priorities);
        assertThat(result.get("a")).isEqualTo(slot("2026-11-17T14:00:00+09:00","2026-11-18T15:00:00+09:00"));
    }
    @Test void prioritizesReadyHighPriorityWork(){
        var high=new SchedulePlanner.Work("z","minsu",false,"","",60,"High",List.of());
        var result=SchedulePlanner.plan(List.of(work("a","minsu",60),high),List.of(),request("2026-11-16T09:00:00+09:00"),priorities);
        assertThat(result.get("z").start()).isBefore(result.get("a").start());
    }
    @Test void rejectsCyclesUnknownReferencesAndSelfDependencies(){
        for(var tasks:List.of(List.of(work("a","p",60,"b"),work("b","q",60,"a")),List.of(work("a","p",60,"foreign")),List.of(work("a","p",60,"a"))))
            assertThatThrownBy(()->SchedulePlanner.plan(tasks,List.of(),request("2026-11-16T09:00:00+09:00"),priorities)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void preservesManualSlotsAndValidatesItsDependencies(){
        var manual=new SchedulePlanner.Work("a","p",true,"2026-11-16T10:00:00+09:00","2026-11-16T11:00:00+09:00",0,"Medium",List.of());
        var result=SchedulePlanner.plan(List.of(manual,work("b","q",60,"a")),List.of(),request("2026-11-16T09:00:00+09:00"),priorities);
        assertThat(result.get("a")).isEqualTo(slot(manual.startAt(),manual.endAt()));assertThat(result.get("b").start()).isEqualTo(result.get("a").end());
    }
    @Test void respectsParentManualBoundsAndFailsWhenDeadlineIsInfeasible(){
        var constrained=new SchedulePlanner.Work("a","p",false,"","",120,"Medium",List.of(),"2026-11-17T09:00:00+09:00","2026-11-17T10:00:00+09:00");
        assertThatThrownBy(()->SchedulePlanner.plan(List.of(constrained),List.of(),request("2026-11-16T09:00:00+09:00"),priorities)).hasMessageContaining("수동 기한");
    }
}
