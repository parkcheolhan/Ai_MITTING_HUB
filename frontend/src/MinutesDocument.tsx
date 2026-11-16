import {type Meeting, type Minutes} from "./types";

export const minuteSections = [["overview","회의 개요"],["summary","주요 안건"],["discussion","논의 내용"],["decisions","결정 사항"],["openQuestions","검토 사항"],["followUp","후속 조치"]] as const;
export function meetingDate(value:string){
  const match=/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(value);
  return match?`${match[1]}년 ${match[2]}월 ${match[3]}일 ${match[4]}:${match[5]}`:value||"미기재";
}
export function minutesText(meeting:Meeting,minutes:Minutes){return minutes.formatted||`# ${meeting.title}\n회의 일시: ${meeting.content.occurredAt||"미입력"}\n참석자: ${meeting.content.participants.join(", ")||"미입력"}\n\n`+minuteSections.map(([key,label])=>`## ${label}\n${minutes[key]||"기록 없음"}`).join("\n\n");}
function DocumentText({text}:{text:string}){
  return <>{text.split(/\r?\n/).map((line,i)=>{
    const heading=/^(#{1,6})\s+(.+)$/.exec(line);
    if(heading)return heading[1].length<=2?<h2 key={i}>{heading[2]}</h2>:<h3 key={i}>{heading[2]}</h3>;
    if(/^\s*[-*]\s+/.test(line))return <p className="document-list-item" key={i}>• {line.replace(/^\s*[-*]\s+/,"")}</p>;
    return line.trim()?<p key={i}>{line}</p>:<div className="document-spacer" key={i}/>;
  })}</>;
}
export default function MinutesDocument({meeting,minutes}:{meeting:Meeting;minutes:Minutes}){
  return <article className="minutes-document" aria-label="회의록 문서">
    <div className="document-heading"><span>회의록</span><h1>{meeting.title}</h1><dl><div><dt>회의 일시</dt><dd>{meetingDate(meeting.content.occurredAt)}</dd></div><div><dt>참석자</dt><dd>{meeting.content.participants.join(", ")||"미기재"}</dd></div><div><dt>프로젝트</dt><dd>{meeting.content.projectKey}</dd></div></dl></div>
    {minutes.formatted?<div className="document-body"><DocumentText text={minutes.formatted}/></div>:minuteSections.map(([key,label])=><section key={key}><h2>{label}</h2><div className="document-body"><DocumentText text={minutes[key]||"기록 없음"}/></div></section>)}
    <footer>회의록 · 제{minutes.version}차 작성본</footer>
  </article>;
}
