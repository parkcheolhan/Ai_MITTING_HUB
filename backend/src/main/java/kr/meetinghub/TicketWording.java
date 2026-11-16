package kr.meetinghub;

import java.util.Objects;

/** Nominal endings for ticket prose only; meeting minutes and source records stay verbatim. */
final class TicketWording {
    static String normalize(String text) {
        String value=Objects.toString(text,"");
        String end="[.!。]*(?=[\\t ]*(?:\\r?\\n|$)|[\\t ]+)";
        value=value.replaceAll("\\s*해야\\s*(?:한다|합니다)"+end," 필요");
        value=value.replaceAll("하지\\s*(?:않는다|않습니다)"+end,"하지 않음");
        value=value.replaceAll("(?:한다|합니다|하였다|했습니다|했다|하다)"+end,"");
        value=value.replaceAll("된다"+end,"됨").replaceAll("있다"+end,"있음").replaceAll("없다"+end,"없음");
        value=value.replaceAll("(?:이다|입니다)"+end,"임");
        return value;
    }
}
