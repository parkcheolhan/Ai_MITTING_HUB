import {type Meeting} from "./types";

export const stageLabels: Record<string,string> = {TRANSCRIBING:"음성 기록 준비",REVIEWING_TRANSCRIPT:"AI 음성 기록 문맥 교정",GENERATING_MINUTES:"회의록 작성",ANALYZING:"분석",GENERATING_TICKETS:"티켓구성",IDENTIFYING_SPEAKERS:"음성별 화자 매칭",ESTIMATING_SCHEDULE:"작업량·우선순위·선행관계 분석",ALLOCATING_SCHEDULE:"담당자별 일정 배정"};
export const jobStages=(type:string)=>type==="SCHEDULE"?["ESTIMATING_SCHEDULE","ALLOCATING_SCHEDULE"]:type==="SPEAKERS"?["IDENTIFYING_SPEAKERS"]:type==="TRANSCRIPT_REVIEW"?["REVIEWING_TRANSCRIPT"]:type==="TRANSCRIBE"?["TRANSCRIBING","REVIEWING_TRANSCRIPT"]:type==="MINUTES"?["GENERATING_MINUTES"]:type==="ANALYZE"?["ANALYZING","GENERATING_TICKETS"]:["TICKETS","REVIEW"].includes(type)?["GENERATING_TICKETS"]:["TRANSCRIBING","REVIEWING_TRANSCRIPT","GENERATING_MINUTES","ANALYZING","GENERATING_TICKETS"];
export default function JobSteps({meeting}: {meeting: Meeting}) {
  const job=meeting.jobs[0];
  if(!job)return null;
  const stages=jobStages(job.type),waiting=job.status==='WAITING_CONFIRMATION';
  const stage=job.stage||meeting.status;
  const position=stages.indexOf(stage);
  const current=waiting?(job.nextStageIndex||0):position>=0?position:Math.max(0,Math.min(stages.length-1,Math.round((job.progress-10)*stages.length/80)));
  const completed=job.status==="COMPLETED";
  const active=["QUEUED","RUNNING"].includes(job.status);
  function state(i:number){
    if(completed||i<current)return "완료";
    if(waiting)return i===current?'확인 대기':'대기';
    if(i>current||job.status==="QUEUED")return "대기";
    return job.status==="FAILED"?"실패":job.status==="CANCELLED"?"취소됨":"진행 중";
  }
  return <section className="job-steps" aria-label="AI 처리 단계">
    <div className="job-steps-heading"><strong>{completed?"AI 처리 완료":waiting?'다음 단계 확인 대기':job.status==="QUEUED"?"AI 처리 대기":`전체 ${stages.length}단계 중 ${current+1}단계`}</strong><span>{completed?`${stages.length}/${stages.length} 완료`:waiting?'결과 확인 후 다음 단계를 시작하세요.':active?"완료되면 자동 갱신됩니다.":job.status==="FAILED"?"실패한 단계에서 오류를 확인하세요.":"작업이 취소되었습니다."}</span></div>
    <ol>{stages.map((s,i)=><li key={s} className={state(i)==="완료"?"done":state(i)==="진행 중"?"running":state(i)==="실패"?"failed":"waiting"} aria-current={!completed&&i===current?"step":undefined}><span className="job-step-number">{i+1}</span><div><strong>{stageLabels[s]||s}</strong><small>{state(i)}</small></div></li>)}</ol>
    {completed&&stages.includes("GENERATING_TICKETS")&&<p>업무 후보 {meeting.drafts.length}개 · Jira 검토에서 내용을 확인하세요.</p>}
  </section>;
}
