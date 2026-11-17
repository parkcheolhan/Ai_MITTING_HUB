import {type ReactNode} from 'react';
import {type Meeting, type Minutes} from "./types";

export const minuteSections = [["overview","회의 개요"],["summary","주요 안건"],["discussion","논의 내용"],["decisions","결정 사항"],["openQuestions","검토 사항"],["followUp","후속 조치"]] as const;
export function meetingDate(value:string){
  const match=/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(value);
  return match?`${match[1]}년 ${match[2]}월 ${match[3]}일 ${match[4]}:${match[5]}`:value||"미기재";
}
export function minutesText(meeting:Meeting,minutes:Minutes){return minutes.formatted||`# ${meeting.title}\n회의 일시: ${meeting.content.occurredAt||"미입력"}\n참석자: ${meeting.content.participants.join(", ")||"미입력"}\n\n`+minuteSections.map(([key,label])=>`## ${label}\n${minutes[key]||"기록 없음"}`).join("\n\n");}
export type MinutesField='formatted'|typeof minuteSections[number][0];
export type MinutesLine={field:MinutesField;index:number;raw:string;label:string};
export type MinutesLineRenderer=(line:MinutesLine,content:ReactNode)=>ReactNode;
function DocumentText({text,field,label,renderLine}:{text:string;field:MinutesField;label:string;renderLine?:MinutesLineRenderer}){
  return <>{text.split(/\r?\n/).map((line,i)=>{
    function wrap(content:ReactNode){return renderLine?renderLine({field,index:i,raw:line,label},content):content;}
    const heading=/^(#{1,6})\s+(.+)$/.exec(line);
    if(heading)return wrap(heading[1].length<=2?<h2 key={i}>{heading[2]}</h2>:<h3 key={i}>{heading[2]}</h3>);
    if(/^\s*[-*]\s+/.test(line))return wrap(<p className="document-list-item" key={i}>• {line.replace(/^\s*[-*]\s+/,"")}</p>);
    return wrap(line.trim()?<p key={i}>{line}</p>:<div className="document-spacer" key={i}/>);
  })}</>;
}
export default function MinutesDocument({meeting,minutes,renderLine,formattedMode}:{meeting:Meeting;minutes:Minutes;renderLine?:MinutesLineRenderer;formattedMode?:boolean}){
  return <article className="minutes-document" aria-label="회의록 문서">
    <div className="document-heading"><span>회의록</span><h1>{meeting.title}</h1><dl><div><dt>회의 일시</dt><dd>{meetingDate(meeting.content.occurredAt)}</dd></div><div><dt>참석자</dt><dd>{meeting.content.participants.join(", ")||"미기재"}</dd></div><div><dt>프로젝트</dt><dd>{meeting.content.projectKey}</dd></div></dl></div>
    {(formattedMode??!!minutes.formatted)?<div className="document-body"><DocumentText text={minutes.formatted||''} field="formatted" label="회의록 본문" renderLine={renderLine}/></div>:minuteSections.map(([key,label])=><section key={key}><h2>{label}</h2><div className="document-body"><DocumentText text={minutes[key]||''} field={key} label={label} renderLine={renderLine}/>{!minutes[key]&&!renderLine&&<p>기록 없음</p>}</div></section>)}
    <footer>회의록 · 제{minutes.version}차 작성본</footer>
  </article>;
}
