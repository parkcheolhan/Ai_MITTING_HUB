package kr.meetinghub;

import java.util.regex.Pattern;
import kr.meetinghub.Domain.Minutes;

/** Removes transcript citations while preserving dates, appointment times and review labels. */
final class TimeReferences {
    private TimeReferences(){}
    private static final String TIME="(?:\\d{1,3}:)?\\d{1,2}:\\d{2}(?:[.,]\\d+)?";
    private static final String RANGE=TIME+"(?:\\s*[~～–—-]\\s*"+TIME+")?";
    private static final Pattern CITATION=Pattern.compile("\\[(?:추정\\s*)?"+RANGE+"(?:\\s*,\\s*"+RANGE+")*\\]");
    static String without(String text){return text==null?"":CITATION.matcher(text).replaceAll("").replaceAll("[ \\t]+(?=\\r?$)","").strip();}
    static void clean(Minutes minutes){
        if(minutes==null)return;
        minutes.overview=without(minutes.overview);minutes.summary=without(minutes.summary);minutes.discussion=without(minutes.discussion);
        minutes.decisions=without(minutes.decisions);minutes.openQuestions=without(minutes.openQuestions);minutes.followUp=without(minutes.followUp);
    }
}
