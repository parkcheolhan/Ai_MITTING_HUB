package kr.meetinghub;

import java.util.*;
import java.util.regex.Pattern;
import kr.meetinghub.Domain.*;

/** Plain text templates use explicit slots; meeting text is never interpreted as a template. */
final class MinutesTemplate {
    static final Set<String> KEYS=Set.of("회의명","회의일시","참석자","메모","개요","요약","논의사항","결정사항","미결사항","후속조치");
    static final Pattern SLOT=Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    static boolean custom(String template){return template!=null&&!template.isBlank()&&!template.equals("일반");}
    static void validate(String template){
        if(!custom(template))return;
        MeetingService.require(template.length()<=20000,"회의록 양식은 20,000자 이하로 등록하세요.");
        var matcher=SLOT.matcher(template);boolean content=false;
        while(matcher.find()){
            String key=matcher.group(1).strip();
            MeetingService.require(KEYS.contains(key),"지원하지 않는 양식 항목: "+key);
            if(Set.of("개요","요약","논의사항","결정사항","미결사항","후속조치").contains(key))content=true;
        }
        MeetingService.require(content,"양식에 {{요약}}, {{논의사항}} 등 회의 내용 항목을 하나 이상 넣어주세요.");
    }
    static String render(String title,Content c,Minutes m){
        if(!custom(c.template))return "";
        validate(c.template);
        var values=new HashMap<String,String>();
        values.put("회의명",title);values.put("회의일시",c.occurredAt);values.put("참석자",String.join(", ",c.participants));values.put("메모",c.memo);
        values.put("개요",m.overview);values.put("요약",m.summary);values.put("논의사항",m.discussion);
        values.put("결정사항",m.decisions);values.put("미결사항",m.openQuestions);values.put("후속조치",m.followUp);
        var matcher=SLOT.matcher(c.template);var result=new StringBuilder();
        while(matcher.find()){
            String value=values.get(matcher.group(1).strip());
            matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(value==null||value.isBlank()?"기록 없음":value));
        }
        matcher.appendTail(result);return result.toString();
    }
}
