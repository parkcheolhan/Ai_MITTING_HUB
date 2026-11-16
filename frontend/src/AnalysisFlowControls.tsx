import {useState} from 'react';
import {api} from './api';
import {activeJob,type Meeting} from './types';
import {stageLabels,jobStages} from './JobSteps';

export default function AnalysisFlowControls({meeting,reload}:{meeting:Meeting;reload:()=>Promise<void>}){
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const running=activeJob(meeting),waiting=meeting.jobs.find(j=>j.status==='WAITING_CONFIRMATION');
  const mode=waiting?.advanceMode||meeting.content.analysisAdvanceMode||'AUTO';
  async function change(value:string){setBusy(true);setError('');try{await api(`/meetings/${meeting.id}/analysis-flow`,'PUT',{mode:value});await reload();}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
  async function act(action:'continue'|'cancel'){if(!waiting)return;setBusy(true);setError('');try{await api(`/jobs/${waiting.id}/${action}`,'POST',action==='continue'?{expectedStageIndex:waiting.nextStageIndex}:undefined);await reload();}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
  const next=waiting?jobStages(waiting.type)[waiting.nextStageIndex||0]:undefined;
  return <section className="analysis-flow-controls panel padded" aria-label="AI 단계 진행 설정"><div><h3>AI 단계 진행 방식</h3><label>분석 진행 방식<select aria-label="분석 진행 방식" disabled={busy||!!running} value={mode} onChange={e=>void change(e.target.value)}><option value="AUTO">자동으로 다음 단계 진행</option><option value="CONFIRM">각 단계 결과 확인 후 진행</option></select></label><p className="help">회의 자료·업무 분석에 적용합니다. 확인 모드에서는 결과를 검토·수정한 뒤 다음 단계를 직접 시작합니다.</p></div>{waiting&&<div className="analysis-stage-confirmation"><strong>직전 단계 완료 · 다음 단계 확인 대기</strong><p>현재 결과를 확인하세요. 다음 단계: {next?(stageLabels[next]||next):'분석'}</p><div className="actions"><button className="primary" disabled={busy} onClick={()=>void act('continue')}>확인 후 다음 단계 진행</button><button disabled={busy} onClick={()=>void act('cancel')}>대기 작업 취소</button></div></div>}{error&&<p className="error" role="alert">{error}</p>}</section>;
}
