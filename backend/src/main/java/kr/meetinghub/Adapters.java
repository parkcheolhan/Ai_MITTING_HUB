package kr.meetinghub;

import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import kr.meetinghub.Domain.*;

public final class Adapters {
    private Adapters() {}
    public interface SpeechToText { List<Segment> transcribe(Content content);default List<Segment> transcribe(Content content,java.util.function.BooleanSupplier cancelled){return transcribe(content);} default List<Segment> identifySpeakers(Content content,java.util.function.BooleanSupplier cancelled){throw new IllegalArgumentException("이 음성 엔진은 화자 분석을 지원하지 않습니다.");} }
    public interface Intelligence {
        default List<Segment> reviewTranscript(String title,List<Segment> segments){return segments;}
        default List<Segment> reviewTranscript(String title,List<Segment> segments,java.util.function.BooleanSupplier cancelled){return reviewTranscript(title,segments);}
        Minutes minutes(String title,List<Segment> segments);
        List<Item> analyze(List<Segment> segments);
        default Intelligence snapshot(){return this;}
        default String mode(){return "mock";}
        default String model(){return "rules";}
        default List<ScheduleAssessment> schedule(List<ScheduleTask> tasks,List<SchedulePriority> priorities){throw new IllegalArgumentException("이 AI 연결은 일정 분석을 지원하지 않습니다.");}
        default List<TicketProposal> tickets(String title,Minutes minutes,List<Segment> segments,List<Item> items,String reviewContext) {
            return items.stream().map(i->new TicketProposal(List.of(i.id()),i.title(),i.title(),i.tasks(),i.checks(),i.acceptanceCriteria(),i.dependencies(),List.of("Mock 규칙 기반 초안입니다. 원문과 제안 항목을 검토하세요."))).toList();
        }
    }
    public interface Jira {
        default Jira snapshot() { return this; }
        Result create(Draft draft);
        default void updateDescription(String key,String description) {}
        default boolean attachmentExists(String key,String filename,long size) { return false; }
        default void attach(String key,java.nio.file.Path file,String filename,String mediaType) {}
        default Optional<Result> find(Draft draft) { return Optional.empty(); }
        default Result linkExisting(Draft draft){throw new IllegalArgumentException("이 Jira 연결은 기존 이슈 연결을 지원하지 않습니다.");}
        default String mode() { return "mock"; }
        default String target() { return "mock"; }
        default Map<String,Object> test() { return Map.of("mode","mock","connected",false,"message","Mock 어댑터 정상. 실제 Jira 연결은 설정에서 안내를 확인하세요."); }
        default List<Map<String,String>> projects() { return List.of(Map.of("key","DEMO","name","제품 개발 (Mock)"),Map.of("key","PLATFORM","name","플랫폼 (Mock)")); }
        default List<Map<String,String>> issueTypes(String project) { return List.of(Map.of("id","Task","name","Task"),Map.of("id","Bug","name","Bug"),Map.of("id","Story","name","Story"),Map.of("id","Epic","name","Epic","hierarchyLevel","1"),Map.of("id","Sub-task","name","Sub-task","subtask","true")); }
        default List<Map<String,Object>> fields(String project,String type) { return List.of(); }
        default JiraUserPage assignableUsers(String project,String query,String accountId,int startAt){
            var users=List.of(new JiraUser("mock-minsu","김민수 (Mock)",true),new JiraUser("mock-jieun","이지은 (Mock)",true),new JiraUser("mock-seojun","박서준 (Mock)",true)).stream().filter(u->(accountId==null||accountId.isBlank()||u.accountId().equals(accountId))&&(query==null||(u.displayName()+u.accountId()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))).toList();
            return new JiraUserPage(startAt==0?users:List.of(),false,startAt+users.size());
        }
    }

    public static List<Segment> parse(String text) {
        return TranscriptParser.parse(text);
    }

    public static class MockSpeech implements SpeechToText {
        public List<Segment> transcribe(Content content) {
            if (!content.segments.isEmpty()) return content.segments;
            if (!content.assets.isEmpty()) throw new IllegalArgumentException("이 앱은 실제 녹음을 전사하지 않습니다. 클로바노트 등에서 받은 전사 텍스트를 입력하세요.");
            throw new IllegalArgumentException("전사 텍스트 또는 파일을 등록하세요.");
        }
    }
    public static class MockIntelligence implements Intelligence {
        public List<ScheduleAssessment> schedule(List<ScheduleTask> tasks,List<SchedulePriority> priorities){return tasks.stream().filter(t->!t.fixed()).map(t->new ScheduleAssessment(t.id(),60,priorities.get(Math.min(2,priorities.size()-1)).id(),t.dependencyDraftIds(),"Mock 시연: 60분 예상. 실제 AI 추정이 아닙니다.")).toList();}
        public Minutes minutes(String title, List<Segment> segments) {
            var m=new Minutes(); m.overview=title+" · Mock 규칙 기반 요약 (AI 분석 아님)";
            m.summary=segments.stream().limit(3).map(Segment::text).collect(Collectors.joining("\n"));
            m.discussion=segments.stream().map(s->s.speaker()+": "+s.text()).collect(Collectors.joining("\n"));
            m.decisions=select(segments,"결정|확정|합의"); m.openQuestions=select(segments,"확인|미정|권한|검토");
            m.followUp=select(segments,"진행|구현|개발|테스트|점검|확인|작성"); return m;
        }
        private String select(List<Segment> segments,String regex) {
            return segments.stream().filter(s->Pattern.compile(regex).matcher(s.text()).find()).map(Segment::text)
                .collect(Collectors.joining("\n"));
        }
        public List<Item> analyze(List<Segment> segments) {
            var items=new ArrayList<Item>();
            for (var s:segments) {
                if (!Pattern.compile("확인|검토|구현|개발|테스트|점검|작성|진행|준비|수정|배포").matcher(s.text()).find()) continue;
                boolean check=Pattern.compile("확인|검토|점검").matcher(s.text()).find();
                items.add(new Item(UUID.randomUUID().toString(),check?"CHECK":"ACTION",
                    s.text().substring(0,Math.min(90,s.text().length())), List.of(s.text()),
                    List.of("[제안] 원문 요구사항 충족 여부를 검토"),List.of("[제안] 처리 결과 및 검증 근거 기록"),List.of(),
                    null,null,List.of(new Evidence(s.id(),s.startMs(),s.speaker(),s.text(),s.estimatedTime())),0.55,true));
            }
            return items;
        }
    }
    public static class MockJira implements Jira {
        public Result linkExisting(Draft draft){MeetingService.require(draft.existingIssueKey.matches("[A-Z][A-Z0-9_]*-[0-9]+")&&draft.existingIssueKey.startsWith(draft.projectKey+"-"),"Mock 연결 이슈 키와 프로젝트를 확인하세요.");return new Result(draft.id,"PUBLISHED",draft.existingIssueKey,null,null);}
        public Result create(Draft draft) {
            if (draft.projectKey.equals("FAIL")) throw new IllegalArgumentException("모의 오류: 프로젝트 권한 없음. 프로젝트를 DEMO로 수정 후 다시 승인하세요.");
            String key = "MOCK-"+draft.id.substring(0,8).toUpperCase(Locale.ROOT);
            return new Result(draft.id,"PUBLISHED",key,null,null);
        }
    }
    // STT/media extraction is external. Real model calls are in OpenAiIntelligence.
    // Jira and AI modes are independently configured through their settings stores.
}
