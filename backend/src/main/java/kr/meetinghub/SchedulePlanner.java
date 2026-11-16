package kr.meetinghub;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import kr.meetinghub.Domain.*;

/** Validated dependency graph + deterministic resource-constrained working-time placement. */
final class SchedulePlanner {
    record Work(String id,String assignee,boolean fixed,String startAt,String endAt,int minutes,String priority,List<String> dependencies,String notBefore,String deadline) {
        Work(String id,String assignee,boolean fixed,String startAt,String endAt,int minutes,String priority,List<String> dependencies){this(id,assignee,fixed,startAt,endAt,minutes,priority,dependencies,"","");}
    }
    record Slot(ZonedDateTime start,ZonedDateTime end) {}
    record Reservation(String assignee,ZonedDateTime start,ZonedDateTime end) {}
    static ScheduleRequest validate(ScheduleRequest in){
        MeetingService.require(in!=null,"일정 기준이 필요합니다.");
        try {ZoneId.of(in.timezone());OffsetDateTime.parse(in.startAt());}catch(Exception e){throw new IllegalArgumentException("일정 시작 시각과 시간대를 확인하세요.");}
        hours(in.workStartHour(),in.workEndHour());
        MeetingService.require(in.availability()==null||in.availability().size()<=200,"담당자 시간 설정은 200명 이하로 입력하세요.");
        var ids=new HashSet<String>();
        for(var a:in.availability()==null?List.<Availability>of():in.availability()){
            MeetingService.require(a.assignee()!=null&&!a.assignee().isBlank()&&ids.add(a.assignee()),"담당자 시간 설정이 중복되었거나 비어 있습니다.");hours(a.workStartHour(),a.workEndHour());
            if(a.availableAt()!=null&&!a.availableAt().isBlank())try{OffsetDateTime.parse(a.availableAt());}catch(Exception e){throw new IllegalArgumentException("담당자의 착수 가능 시각을 확인하세요.");}
        }
        return in;
    }
    static void hours(int start,int end){MeetingService.require(start>=0&&end<=24&&start<end&&!(start==12&&end==13),"근무 시작·종료 시각을 확인하세요. 12~13시는 점심시간입니다.");}
    static Map<String,Slot> plan(List<Work> work,List<Reservation> existing,ScheduleRequest input,List<SchedulePriority> priorities){
        validate(input);var zone=ZoneId.of(input.timezone());var base=OffsetDateTime.parse(input.startAt()).atZoneSameInstant(zone);
        var byId=new LinkedHashMap<String,Work>();work.forEach(w->{MeetingService.require(byId.put(w.id(),w)==null,"중복된 업무 ID입니다.");});
        for(var w:work){
            MeetingService.require(w.dependencies()!=null&&!w.dependencies().contains(w.id())&&byId.keySet().containsAll(w.dependencies()),"일정 선행 업무가 잘못되었습니다.");
            if(!w.fixed())MeetingService.require(!w.assignee().isBlank()&&w.minutes()>=15&&w.minutes()<=19200,"담당자와 예상 작업시간(15~19,200분)을 확인하세요.");
        }
        var busy=new HashMap<String,List<Slot>>();
        for(var r:existing)if(!r.assignee().isBlank())busy.computeIfAbsent(r.assignee(),k->new ArrayList<>()).add(new Slot(r.start().withZoneSameInstant(zone),r.end().withZoneSameInstant(zone)));
        var fixed=new HashMap<String,Slot>();
        for(var w:work)if(w.fixed()){
            Slot slot;try{slot=new Slot(OffsetDateTime.parse(w.startAt()).atZoneSameInstant(zone),OffsetDateTime.parse(w.endAt()).atZoneSameInstant(zone));}catch(Exception e){throw new IllegalArgumentException("수동·기등록 업무의 시작·종료 시각을 먼저 지정하세요. 또는 AI 일정 대상으로 전환하세요.");}
            MeetingService.require(slot.end().isAfter(slot.start()),"고정 일정의 종료는 시작 이후여야 합니다.");fixed.put(w.id(),slot);
            if(!w.assignee().isBlank())busy.computeIfAbsent(w.assignee(),k->new ArrayList<>()).add(slot);
        }
        var calendars=new HashMap<String,Availability>();for(var a:input.availability()==null?List.<Availability>of():input.availability())calendars.put(a.assignee(),a);
        var ranks=new HashMap<String,Integer>();for(int i=0;i<priorities.size();i++)ranks.put(priorities.get(i).id(),i);
        var result=new LinkedHashMap<String,Slot>();var pending=new ArrayList<>(work);
        while(!pending.isEmpty()){
            var ready=pending.stream().filter(w->result.keySet().containsAll(w.dependencies())).sorted(Comparator.comparingInt((Work w)->ranks.getOrDefault(w.priority(),999)).thenComparing(Work::id)).toList();
            MeetingService.require(!ready.isEmpty(),"업무 사이에 순환 의존성이 있습니다. 선행 업무를 수정한 뒤 다시 분석하세요.");
            var w=ready.get(0);ZonedDateTime after=base;
            for(var dep:w.dependencies())if(result.get(dep).end().isAfter(after))after=result.get(dep).end();
            Slot slot;
            if(w.fixed()){
                slot=fixed.get(w.id());for(var dep:w.dependencies())MeetingService.require(!slot.start().isBefore(result.get(dep).end()),"수동 일정이 선행 업무 종료보다 빠릅니다. 수동 일정을 조정하세요.");
            }else{
                if(w.notBefore()!=null&&!w.notBefore().isBlank()){var bound=OffsetDateTime.parse(w.notBefore()).atZoneSameInstant(zone);if(bound.isAfter(after))after=bound;}
                var calendar=calendars.get(w.assignee());int begin=calendar==null?input.workStartHour():calendar.workStartHour(),end=calendar==null?input.workEndHour():calendar.workEndHour();
                if(calendar!=null&&calendar.availableAt()!=null&&!calendar.availableAt().isBlank()){
                    var available=OffsetDateTime.parse(calendar.availableAt()).atZoneSameInstant(zone);if(available.isAfter(after))after=available;
                }
                slot=allocate(after,w.minutes(),begin,end,busy.computeIfAbsent(w.assignee(),k->new ArrayList<>()));busy.get(w.assignee()).add(slot);
                if(w.deadline()!=null&&!w.deadline().isBlank())MeetingService.require(!slot.end().isAfter(OffsetDateTime.parse(w.deadline()).atZoneSameInstant(zone)),"하위업무가 상위업무의 수동 기한 안에 끝나지 않습니다. 기한·담당자 시간을 조정하세요.");
            }
            result.put(w.id(),slot);pending.remove(w);
        }
        return result;
    }
    static Slot allocate(ZonedDateTime cursor,int minutes,int begin,int end,List<Slot> busy){
        ZonedDateTime start=null;int remaining=minutes;
        cursor=cursor.truncatedTo(ChronoUnit.MINUTES).equals(cursor)?cursor:cursor.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        for(int i=0;i<20000;i++){
            cursor=working(cursor,begin,end);var date=cursor.toLocalDate();
            var limit=at(date,end,cursor.getZone());if(cursor.getHour()<12&&end>12)limit=at(date,12,cursor.getZone());
            Slot occupied=null;for(var b:busy)if(!cursor.isBefore(b.start())&&cursor.isBefore(b.end())){if(occupied==null||b.end().isAfter(occupied.end()))occupied=b;}
            if(occupied!=null){cursor=occupied.end();start=null;remaining=minutes;continue;}
            for(var b:busy)if(b.start().isAfter(cursor)&&b.start().isBefore(limit))limit=b.start();
            long available=ChronoUnit.MINUTES.between(cursor,limit);
            if(available<=0){cursor=limit.plusMinutes(1);continue;}
            if(start==null)start=cursor;
            int used=(int)Math.min(available,remaining);cursor=cursor.plusMinutes(used);remaining-=used;
            if(remaining==0)return new Slot(start,cursor);
        }
        throw new IllegalArgumentException("계획 가능한 기간을 초과했습니다. 기존 일정과 담당자 시간을 확인하세요.");
    }
    static ZonedDateTime working(ZonedDateTime t,int begin,int end){
        for(int i=0;i<14;i++){
            var day=t.getDayOfWeek();if(day==DayOfWeek.SATURDAY||day==DayOfWeek.SUNDAY){t=at(t.toLocalDate().plusDays(1),begin,t.getZone());continue;}
            if(t.isBefore(at(t.toLocalDate(),begin,t.getZone())))t=at(t.toLocalDate(),begin,t.getZone());
            if(!t.isBefore(at(t.toLocalDate(),end,t.getZone()))){t=at(t.toLocalDate().plusDays(1),begin,t.getZone());continue;}
            if(t.getHour()==12&&end>12){t=at(t.toLocalDate(),13,t.getZone());continue;}
            return t;
        }
        throw new IllegalArgumentException("근무시간을 확인하세요.");
    }
    static ZonedDateTime at(LocalDate day,int hour,ZoneId zone){return day.atStartOfDay(zone).plusHours(hour);}
}
