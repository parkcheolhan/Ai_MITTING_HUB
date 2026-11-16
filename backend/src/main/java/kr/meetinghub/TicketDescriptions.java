package kr.meetinghub;

import java.util.*;
import kr.meetinghub.Domain.*;

final class TicketDescriptions {
    static String normalize(String text,boolean child,List<Item> items,Collection<String> ids,List<Segment> segments){
        String result=Objects.toString(text,"").replaceAll("(?m)^※ (?:AI가 정리한 초안|Mock 규칙 기반 초안|Mock 규칙 기반 후보)[^\\r\\n]*(?:\\r?\\n)?","");
        if(child)return childSections(TimeReferences.without(result));
        var evidence=items.stream().filter(i->ids.contains(i.id())).flatMap(i->i.evidence().stream()).toList();
        String section="";
        if(!child&&!evidence.isEmpty()){
            var linked=new HashSet<String>();evidence.forEach(e->linked.add(e.segmentId()));
            long start=evidence.stream().mapToLong(Evidence::startMs).min().orElse(0);
            long end=segments.stream().filter(s->linked.contains(s.id())).mapToLong(s->Math.max(s.startMs(),s.endMs())).max().orElseGet(()->evidence.stream().mapToLong(Evidence::startMs).max().orElse(start));
            section="6. 회의 근거\n"+MeetingService.formatTime(start)+" ~ "+MeetingService.formatTime(Math.max(start,end))+"\n\n";
        }
        var pattern=java.util.regex.Pattern.compile("(?ms)^6\\. 회의 근거[^\\r\\n]*\\r?\\n.*?(?=^\\d+\\. |\\z)");
        var matcher=pattern.matcher(result);
        if(matcher.find())result=matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(section));
        else if(!section.isEmpty())result=result.stripTrailing()+"\n\n"+section;
        if(child)result=result.replaceFirst("(?m)^7\\. (AI|Mock) 검토 사항", "6. $1 검토 사항");
        return TicketWording.normalize(result.stripTrailing());
    }
    static String childDescription(TicketProposal p){
        var reviews=new ArrayList<String>();reviews.addAll(p.checks());reviews.addAll(p.reviewNotes());
        return TimeReferences.without(childDocument(p.tasks(),p.acceptanceCriteria(),p.dependencies(),reviews));
    }
    static String childSections(String text){
        var heading=java.util.regex.Pattern.compile("(?m)^\\d+\\.\\s*(배경 및 목적|해야 하는 작업 목록|작업목록|확인해야 하는 항목|완료 조건[^\\r\\n]*|완료조건|의존성 및 제약사항|제약사항|(?:AI|Mock) 검토\\s*사항|회의 근거)\\s*$").matcher(text);
        var sections=new LinkedHashMap<String,String>();String key=null;int start=0;
        while(heading.find()){if(key!=null)sections.put(key,text.substring(start,heading.start()).strip());key=heading.group(1);start=heading.end();}
        if(key!=null)sections.put(key,text.substring(start).strip());
        var tasks=new ArrayList<String>();var criteria=new ArrayList<String>();var constraints=new ArrayList<String>();var reviews=new ArrayList<String>();
        for(var entry:sections.entrySet()){
            String name=entry.getKey().replace(" ","");var rows=lines(entry.getValue());
            if(name.equals("작업목록")||name.equals("해야하는작업목록"))tasks.addAll(rows);
            else if(name.startsWith("완료조건"))criteria.addAll(rows);
            else if(name.equals("의존성및제약사항")||name.equals("제약사항"))constraints.addAll(rows);
            else if(name.equals("확인해야하는항목")||name.endsWith("검토사항"))reviews.addAll(rows);
        }
        if(sections.isEmpty())tasks.addAll(lines(text));
        return childDocument(tasks,criteria,constraints,reviews);
    }
    static List<String> lines(String text){return text.lines().map(String::strip).filter(s->!s.isBlank()).map(s->s.replaceFirst("^(?:☐|☑|[-*]\\s*\\[[ xX]]|[-*•])\\s*","")).toList();}
    static String childDocument(List<String> tasks,List<String> criteria,List<String> constraints,List<String> reviews){
        var notes=reviews.stream().filter(s->!s.contains("표시는 확정 사실")&&!s.contains("원문, 작업 범위와 완료 조건")&&!s.contains("해당 항목의 수행 결과와 근거")&&!s.contains("원문과 범위를 검토하고 승인")&&!s.contains("Mock 규칙 기반 초안")).distinct().toList();
        if(notes.isEmpty()&&!tasks.isEmpty()){String task=SubtaskTitles.compact(tasks.get(0));notes=List.of("[제안] 작업 전: "+task+"의 대상과 선행 조건 확인","[제안] 작업 후: "+task+"의 처리 결과와 미해결 항목 확인");}
        var limits=constraints.stream().filter(s->!s.equals("담당자 및 기한은 사용자 확인 후 설정")).map(s->s.startsWith("명시된 의존성 없음")?"명시된 제약사항 없음":s).distinct().toList();
        return TicketWording.normalize("1. 작업목록\n"+bullets(tasks,"작업 내용 확인 필요")+"\n\n2. 완료조건\n"+bullets(criteria,"완료 기준 확인 필요")+"\n\n3. 제약사항\n"+bullets(limits,"명시된 제약사항 없음")+"\n\n4. AI 검토사항\n"+bullets(notes,"작업 전후 추가 확인사항 검토 필요"));
    }
    static String bullets(List<String> rows,String empty){return (rows.isEmpty()?List.of(empty):rows).stream().filter(s->!s.isBlank()).map(s->"- "+s.strip()).collect(java.util.stream.Collectors.joining("\n"));}
}
