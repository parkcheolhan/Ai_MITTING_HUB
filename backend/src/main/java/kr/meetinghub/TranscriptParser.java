package kr.meetinghub;

import java.util.*;
import java.util.regex.*;
import kr.meetinghub.Domain.Segment;

/** Imports exported text without calling an undocumented CLOVA Note API. */
public final class TranscriptParser {
    private TranscriptParser() {}
    private static final String TIME="(?:(?:\\d{1,2}):)?\\d{1,2}:\\d{2}(?:[.,]\\d{1,3})?";
    private static final Pattern RANGE=Pattern.compile("^("+TIME+")\\s*-->\\s*("+TIME+").*$");
    private static final Pattern FIRST=Pattern.compile("^\\[?("+TIME+")\\]?\\s*(?:(.{1,40}?)[:：]\\s*)?(.*)$");
    private static final Pattern LAST=Pattern.compile("^(.{1,40}?)\\s+\\[?("+TIME+")\\]?\\s*$");
    private static final Pattern ONLY=Pattern.compile("^\\[?("+TIME+")\\]?$");
    public static List<Segment> parse(String input) {
        var result=new ArrayList<Segment>();
        var lines=input.replace("\uFEFF","").replace("\r\n","\n").split("\\n");
        boolean subtitles=input.contains("-->");Cue cue=null;String pendingSpeaker="화자 미상";
        for(int i=0;i<lines.length;i++) {
            String line=lines[i].strip();
            if(line.isBlank()||line.equals("WEBVTT")||line.startsWith("NOTE ")||(subtitles&&line.matches("\\d+")))continue;
            var range=RANGE.matcher(line);var first=FIRST.matcher(line);var last=LAST.matcher(line);
            if(range.matches()) {flush(result,cue);cue=new Cue(pendingSpeaker,ms(range.group(1)),ms(range.group(2)),false);continue;}
            if(first.matches()) {
                flush(result,cue);pendingSpeaker=first.group(2)==null?pendingSpeaker:first.group(2).strip();
                cue=new Cue(pendingSpeaker,ms(first.group(1)),-1,false);
                if(!first.group(3).isBlank())cue.text.add(first.group(3).strip());continue;
            }
            if(last.matches()) {flush(result,cue);pendingSpeaker=last.group(1).strip();cue=new Cue(pendingSpeaker,ms(last.group(2)),-1,false);continue;}
            int next=i+1;while(next<lines.length&&lines[next].isBlank())next++;
            if(line.length()<=40&&next<lines.length&&ONLY.matcher(lines[next].strip()).matches()) {
                flush(result,cue);cue=null;pendingSpeaker=line;continue;
            }
            if(cue!=null)cue.text.add(line);
            else {
                long start=result.isEmpty()?0:result.get(result.size()-1).endMs();
                var plain=new Cue("화자 미상",start,start+15000,true);plain.text.add(line);flush(result,plain);
            }
        }
        flush(result,cue);return result;
    }
    private static long ms(String value) {
        String[] secondsParts=value.replace(',','.').split("\\.");String[] parts=secondsParts[0].split(":");
        long total=0;for(String part:parts)total=total*60+Long.parseLong(part);
        MeetingService.require(Integer.parseInt(parts[parts.length-1])<60,"초는 0~59 범위로 입력하세요.");
        if(parts.length==3)MeetingService.require(Integer.parseInt(parts[1])<60,"분은 0~59 범위로 입력하세요.");
        long fraction=secondsParts.length==1?0:Long.parseLong((secondsParts[1]+"000").substring(0,3));return total*1000+fraction;
    }
    private static void flush(List<Segment> segments,Cue cue) {
        if(cue==null||cue.text.isEmpty())return;long end=cue.end<0?cue.start+15000:cue.end;
        MeetingService.require(end>=cue.start,"발언 종료 시간은 시작 시간 이후여야 합니다.");
        segments.add(new Segment(UUID.randomUUID().toString(),cue.speaker,cue.start,end,String.join("\n",cue.text),cue.estimated));
    }
    private static final class Cue {
        final String speaker;final long start,end;final boolean estimated;final List<String> text=new ArrayList<>();
        Cue(String speaker,long start,long end,boolean estimated){this.speaker=speaker;this.start=start;this.end=end;this.estimated=estimated;}
    }
}
