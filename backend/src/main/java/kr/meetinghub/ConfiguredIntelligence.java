package kr.meetinghub;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import kr.meetinghub.Domain.*;

@Component
public class ConfiguredIntelligence implements Adapters.Intelligence {
    final AiSettingsStore settings;final ObjectMapper json;
    public ConfiguredIntelligence(AiSettingsStore settings,ObjectMapper json){this.settings=settings;this.json=json;}
    @Override public Adapters.Intelligence snapshot(){var config=settings.current();return config.mode().equals("mock")?new Adapters.MockIntelligence():config.mode().equals("codex")?new CodexIntelligence(config,json):new OpenAiIntelligence(config,json);}
    @Override public String mode(){return settings.view().get("mode").toString();}
    @Override public String model(){return settings.view().get("model").toString();}
    @Override public List<ScheduleAssessment> schedule(List<ScheduleTask> tasks,List<SchedulePriority> priorities){return snapshot().schedule(tasks,priorities);}
    @Override public Minutes minutes(String title,List<Segment> segments){return snapshot().minutes(title,segments);}
    @Override public List<Item> analyze(List<Segment> segments){return snapshot().analyze(segments);}
    @Override public List<TicketProposal> tickets(String title,Minutes minutes,List<Segment> segments,List<Item> items,String reviewContext){return snapshot().tickets(title,minutes,segments,items,reviewContext);}
    public Map<String,Object> test(){var ai=snapshot();if(ai instanceof OpenAiIntelligence real)return real.test();return Map.of("mode","mock","connected",false,"message","Mock 분석 모드입니다. 실제 AI 호출을 사용하려면 OpenAI를 선택하세요.");}
}
