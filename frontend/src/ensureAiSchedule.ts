import {api} from './api';
import {offsetDateTime,localDateTime} from './SchedulePanel';
import type {Meeting} from './types';

export async function ensureAiSchedule(meeting:Meeting,ids:string[],progress:(message:string)=>void){
  const pending=meeting.drafts.filter(d=>ids.includes(d.id)&&d.registrationMode!=='EXISTING'&&d.scheduleMode==='AI'&&(!d.startAt||!d.endAt));
  if(!pending.length)return;
  const parentIds=new Set(meeting.drafts.map(d=>d.parentDraftId).filter(Boolean));
  const unassigned=meeting.drafts.filter(d=>!parentIds.has(d.id)&&['DRAFT','APPROVED','FAILED'].includes(d.status)&&d.registrationMode!=='EXISTING'&&!d.issueKey&&(d.scheduleMode==='AI'||(!d.startAt&&!d.endAt&&!d.dueDate))&&!d.assignee);
  if(unassigned.length)throw new Error(`AI 일정 산정을 위해 담당자를 먼저 지정하세요: ${unassigned.slice(0,3).map(d=>d.summary).join(', ')}${unassigned.length>3?' 외':''}`);
  const saved=meeting.content.scheduleSettings;
  const tomorrow=new Date();tomorrow.setDate(tomorrow.getDate()+1);tomorrow.setHours(9,0,0,0);
  progress('AI 일정 산정 중입니다. 완료 후 선택한 티켓을 승인합니다.');
  const job=await api<{id:string}>(`/meetings/${meeting.id}/schedule`,'POST',saved??{startAt:offsetDateTime(localDateTime(tomorrow.toISOString())),timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,workStartHour:9,workEndHour:18,availability:[]});
  for(let attempt=0;attempt<600;attempt++){
    const latest=await api<Meeting>(`/meetings/${meeting.id}`);
    const current=latest.jobs.find(j=>j.id===job.id);
    if(current?.status==='COMPLETED')return;
    if(current?.status==='FAILED'||current?.status==='CANCELLED')throw new Error(current.errorMessage||'AI 일정 산정이 중단되었습니다. 담당자별 AI 일정 설정을 확인하세요.');
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
  throw new Error('AI 일정 산정이 진행 중입니다. 완료 후 선택 승인을 다시 실행하세요.');
}
