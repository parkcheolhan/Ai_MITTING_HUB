package kr.meetinghub;

import java.net.URI;
import java.util.*;
import kr.meetinghub.Domain.*;

/** Storage XHTML generated from escaped text, never from uploaded HTML. */
final class ConfluenceDocument {
    static String escape(String text){return Objects.toString(text,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    static String link(String url,String label){try{var u=URI.create(Objects.toString(url,""));if("https".equals(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null)return "<a href=\""+escape(url)+"\">"+escape(label)+"</a>";}catch(Exception ignored){}return escape(label);}
    static String meetingUrl(String publicUrl,String meetingId){return publicUrl.replaceAll("/+$","")+"/#/meetings/"+meetingId+"?tab=minutes";}
    static String title(MeetingView meeting){String title=meeting.title()+" · "+meeting.createdAt().toString().substring(0,10);return title.length()>230?title.substring(0,230):title;}
    static String render(MeetingView m,String publicUrl){
        var c=m.content();var html=new StringBuilder("<p>").append(link(meetingUrl(publicUrl,m.id()),"모아에서 회의록 보기")).append("</p>");
        html.append("<p>회의 일시: ").append(escape(c.occurredAt)).append("<br />참석자: ").append(escape(String.join(", ",c.participants))).append("</p>");
        var minutes=c.minutes;
        if(minutes!=null){
            if(minutes.formatted!=null&&!minutes.formatted.isBlank())html.append(text(minutes.formatted));
            else{section(html,"회의 개요",minutes.overview);section(html,"주요 안건",minutes.summary);section(html,"논의 내용",minutes.discussion);section(html,"결정 사항",minutes.decisions);section(html,"검토 사항",minutes.openQuestions);section(html,"후속 조치",minutes.followUp);}
        }
        html.append("<h2>회의에서 생성된 Jira 티켓</h2><table><tbody><tr><th>업무</th><th>에픽</th><th>담당자</th><th>일정</th><th>등록 상태</th><th>Jira 티켓</th><th>상세</th></tr>");
        for(var d:m.drafts()){
            String epic=m.epics().stream().filter(e->Objects.equals(e.id(),d.epicId)||(!Objects.toString(d.epicKey,"").isBlank()&&Objects.equals(e.issueKey(),d.epicKey))).map(e->e.summary()).findFirst().orElse(Objects.toString(d.epicKey,""));
            String status=switch(d.status){case "PUBLISHED"->d.publishTarget!=null&&d.publishTarget.startsWith("mock")?"Mock 등록":"등록 완료";case "PUBLISHING"->"등록 중";case "UNKNOWN"->"등록 확인 필요";case "FAILED"->"등록 실패";default->"미등록";};
            String start=Objects.toString(d.startAt,""),end=Objects.toString(d.endAt,"");if(end.isBlank())end=Objects.toString(d.dueDate,"");String schedule=start.isBlank()?end:end.isBlank()?start:start+" ~ "+end;
            html.append("<tr>");for(String value:List.of(d.summary,epic,Objects.toString(d.assigneeName,""),schedule,status))html.append("<td>").append(escape(value)).append("</td>");
            html.append("<td>").append(d.issueKey==null||d.issueKey.isBlank()?"미등록":link(d.issueUrl,d.issueKey)).append("</td><td>")
                .append(link(publicUrl.replaceAll("/+$","")+"/#/meetings/"+m.id()+"/tickets?ticket="+d.id,"모아에서 상세 보기")).append("</td></tr>");
        }
        html.append("</tbody></table>");if(m.drafts().isEmpty())html.append("<p>아직 생성된 티켓이 없습니다.</p>");return html.toString();
    }
    static void section(StringBuilder out,String title,String value){out.append("<h2>").append(escape(title)).append("</h2>").append(text(value));}
    static String inline(String text){return escape(text).replaceAll("\\*\\*(.+?)\\*\\*","<strong>$1</strong>");}
    static String[] cells(String line){return line.strip().replaceAll("^\\||\\|$","").split("\\|",-1);}
    static void row(StringBuilder out,String line,boolean header){String tag=header?"th":"td";out.append("<tr>");for(String cell:cells(line))out.append('<').append(tag).append('>').append(inline(cell.strip())).append("</").append(tag).append('>');out.append("</tr>");}
    static String text(String text){
        var out=new StringBuilder();var lines=Objects.toString(text,"").split("\\R",-1);String list="";
        for(int i=0;i<lines.length;i++){
            String line=lines[i].strip();boolean bullet=line.matches("[-*+]\\s+.*"),numbered=line.matches("[0-9]+[.)]\\s+.*");String nextList=bullet?"ul":numbered?"ol":"";
            if(!list.equals(nextList)){if(!list.isEmpty())out.append("</").append(list).append('>');list=nextList;if(!list.isEmpty())out.append('<').append(list).append('>');}
            if(line.isBlank())continue;
            if(i+1<lines.length&&line.contains("|")&&lines[i+1].strip().matches("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?")){
                out.append("<table><tbody>");row(out,line,true);i++;while(i+1<lines.length&&!lines[i+1].isBlank()&&lines[i+1].contains("|"))row(out,lines[++i],false);out.append("</tbody></table>");continue;
            }
            var heading=java.util.regex.Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
            if(heading.matches()){int n=Math.min(heading.group(1).length()+1,6);out.append("<h").append(n).append(">").append(inline(heading.group(2))).append("</h").append(n).append('>');}
            else if(!list.isEmpty())out.append("<li>").append(inline(line.replaceFirst(bullet?"^[-*+]\\s+":"^[0-9]+[.)]\\s+",""))).append("</li>");
            else if(!line.matches("[-*_]{3,}"))out.append("<p>").append(inline(line)).append("</p>");
        }
        if(!list.isEmpty())out.append("</").append(list).append('>');return out.length()==0?"<p>기록 없음</p>":out.toString();
    }
}
