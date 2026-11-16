package kr.meetinghub;

/** Compact fallback for older AI results; new responses provide separate noun-phrase titles. */
final class SubtaskTitles {
    static String compact(String input){
        String title=TimeReferences.without(input).strip();
        String tags="";
        while(title.matches("^\\[[^]]+]\\s*.*")){
            int end=title.indexOf(']');tags+=title.substring(0,end+1)+" ";title=title.substring(end+1).strip();
        }
        if(tags.contains("[확인]")||tags.contains("[작업]"))tags=tags.replace("[확인 필요] ","");
        else tags=tags.replace("[확인 필요]","[확인]");
        String original=title;
        title=title.replaceFirst("(?:에 대해|에 대하여)\\s+.*(?:한다|합니다)[.!。]?$","");
        title=title.replaceFirst("\\s*(?:해야\\s*(?:한다|합니다)|하기로\\s*(?:한다|합니다)|한다|합니다|하다)[.!。]?$","");
        title=title.replaceFirst("(?:을|를)\\s+(?=(?:확인|검토|구현|수정|개발|처리|협의|테스트|요청|준비|수행|진행|점검|설정|작성|공유|적용|검증|업로드|다운로드))"," ");
        title=title.replaceFirst("[.!。]+$","").strip();
        if(title.isBlank())title=original;
        String result=TicketWording.normalize(tags+title);return result.length()>200?result.substring(0,199)+"…":result;
    }
}
