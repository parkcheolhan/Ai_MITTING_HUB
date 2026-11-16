import {useEffect,useRef,useState} from "react";
import {api} from "./api";
import {time, type Meeting} from "./types";
import ParticipantsEditor from "./ParticipantsEditor";
import {groupConsecutiveSegments} from './transcriptGroups';
import {useAiSettings} from './ai';
import {standalone} from './standalone';

export default function TranscriptView({meeting, disabled, reload, seek, playbackMs=-1}: {meeting: Meeting; disabled: boolean; reload: () => Promise<void>; seek: (ms: number) => void; playbackMs?:number}) {
  const [start,setStart]=useState("00:00:00");
  const [interval,setInterval]=useState(30);
  const [query,setQuery]=useState("");
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  const [saved,setSaved]=useState(false);
  const [showTimes,setShowTimes]=useState(false);
  const [follow,setFollow]=useState(true);
  const ai=useAiSettings();
  const [editing,setEditing]=useState(false),[texts,setTexts]=useState<Record<string,string>>({}),[baseline,setBaseline]=useState<Record<string,string>>({}),[message,setMessage]=useState('');
  const [editingGroup,setEditingGroup]=useState<string|null>(null);
  const documentRef=useRef<HTMLDivElement>(null);
  const groups=groupConsecutiveSegments(meeting.content.segments).filter(g=>(g.speaker+' '+g.segments.map(s=>texts[s.id]??s.text).join(' ')).toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const segments=groups.flatMap(g=>g.segments);
  const dirty=Object.keys(texts).some(id=>texts[id]!==baseline[id]);
  function beginEdit(groupId?:string){const original=Object.fromEntries(meeting.content.segments.map(s=>[s.id,s.text]));setBaseline(original);setTexts(original);setEditingGroup(groupId||null);setEditing(true);setError('');setMessage('');}
  function cancelEdit(){setEditing(false);setEditingGroup(null);setTexts({});setError('');}
  async function saveText(){
    setBusy(true);setError('');setMessage('');
    try{
      const edits=Object.entries(texts).filter(([id,text])=>text!==baseline[id]).map(([id,text])=>({id,originalText:baseline[id],text}));
      if(edits.some(e=>!e.text.trim()))throw new Error('발언 내용은 비워 둘 수 없습니다.');
      await api(`/meetings/${meeting.id}/transcript`,'PUT',{edits});
      setEditing(false);setTexts({});await reload();setMessage('음성 기록을 저장했습니다. 기존 회의록·업무·Jira 내용은 자동 변경되지 않으므로 필요하면 다시 분석하세요.');
    }catch(e){setError((e as Error).message);}finally{setBusy(false);}
  }
  async function reviewText(){setBusy(true);setError('');setMessage('');try{await api(`/meetings/${meeting.id}/transcript/review`,'POST');await reload();setMessage('AI 문맥 교정을 시작했습니다. 완료 후 교정 전 원문과 비교해 확인하세요.');}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
  const active=meeting.content.segments.filter(s=>!s.estimatedTime&&s.startMs<=playbackMs&&playbackMs<s.endMs).reduce<(typeof meeting.content.segments)[number]|undefined>((latest,s)=>!latest||s.startMs>latest.startMs?s:latest,undefined);
  useEffect(()=>{
    if(!follow||!active||editing)return;
    const container=documentRef.current;
    let row=container?.querySelector<HTMLElement>('[data-active="true"]');
    if(!container||!row)return;
    if(row.offsetHeight>container.clientHeight*.8)row=row.querySelector<HTMLElement>('[data-playback-active="true"]')||row;
    const bounds=container.getBoundingClientRect(),rect=row.getBoundingClientRect();
    const top=bounds.top+container.clientTop+12;
    // Anchor each new utterance at the reader's top, without scrolling the page.
    if(Math.abs(rect.top-top)>1){
      container.scrollTo({top:container.scrollTop+rect.top-top,behavior:"instant"});
    }
  },[active?.id,follow,query,editing]);
  async function apply(){
    setError("");setSaved(false);
    if(!/^\d{2}:\d{2}:\d{2}$/.test(start)) {setError("시작 시각을 HH:MM:SS 형식으로 입력하세요.");return;}
    const [h,m,s]=start.split(":").map(Number);
    if(h>23||m>59||s>59||!Number.isInteger(interval)||interval<1||interval>14400){setError("시작 시각과 구간 간격(1~14,400초)을 확인하세요.");return;}
    setBusy(true);
    try{await api(`/meetings/${meeting.id}/transcript/timing`,"PUT",{startMs:(h*3600+m*60+s)*1000,intervalMs:interval*1000});await reload();setShowTimes(true);setSaved(true);}
    catch(e){setError((e as Error).message);}finally{setBusy(false);}
  }
  return <>
    <section className="transcript-reader" aria-label="전체 음성 기록">
      <div className="transcript-reader-toolbar"><div><h3>전체 음성 기록</h3><span className="help">{groups.length}개 발언 묶음 · {segments.length} / {meeting.content.segments.length}개 시간 구간</span></div><label className="transcript-search">발언 검색<input aria-label="발언 검색" placeholder="발언 내용 또는 화자 이름" value={query} onChange={e=>setQuery(e.target.value)} /></label></div>
      <div className="transcript-edit-toolbar"><div className="actions">{editing?<><button className="primary" disabled={disabled||busy||!dirty} onClick={()=>void saveText()}>{busy?'저장 중…':'음성 기록 저장'}</button><button disabled={busy} onClick={cancelEdit}>편집 취소</button></>:<><button disabled={disabled||busy||!meeting.content.segments.length} onClick={()=>beginEdit()}>음성 기록 편집</button>{!standalone&&<button disabled={disabled||busy||!ai.configured||ai.mode==='mock'||!meeting.content.segments.length} onClick={()=>void reviewText()}>AI 문맥 교정</button>}</>}</div><p className="help">{editing?'같은 화자의 발언을 묶어 표시합니다. 재생 시간과 근거 연결을 보존하도록 구간별 내용을 수정하세요. 검색으로 숨겨진 구간의 수정도 함께 저장됩니다.':'연속된 같은 화자의 발언은 함께 표시합니다. 각 발언의 문구 수정으로 바로 수정·적용할 수 있습니다.'}</p>{error&&<p className="error" role="alert">{error}</p>}{message&&<p role="status">{message}</p>}{!!meeting.content.transcriptRevision&&<p className="help">음성 기록이 수정되었습니다. 기존 회의록·업무·Jira 내용은 자동으로 변경되지 않습니다.</p>}</div>
      <p className="transcript-review-coverage">{meeting.content.transcriptReviewRevision===meeting.content.transcriptRevision&&['openai','codex'].includes(meeting.content.transcriptReviewMode||'')&&meeting.content.transcriptReviewedSegments===meeting.content.segments.length?`AI 대화체 검토 완료 · 전체 ${meeting.content.transcriptReviewedSegments} / ${meeting.content.segments.length}구간 검토`:'AI 문맥 교정은 모든 발언의 질문·응답·어말어미를 앞뒤 대화와 함께 검토합니다.'}</p>
      <div className="transcript-playback"><span>{active?`재생 중 · ${active.speaker} · ${time(active.startMs)}`:"발언을 선택하면 해당 구간을 재생합니다."}</span><label><input type="checkbox" checked={follow} onChange={e=>setFollow(e.target.checked)}/>재생 발언 따라가기</label></div>
      <div className="transcript-document" ref={documentRef}>{groups.map(g=>{
        const first=g.segments[0],playing=g.segments.some(s=>s.id===active?.id);
        const changed=g.segments.filter(s=>meeting.content.originalTranscriptText?.[s.id]!==undefined&&meeting.content.originalTranscriptText[s.id]!==s.text);
        return <div className="transcript-utterance" key={g.id} data-active={playing} aria-current={playing?'true':undefined}>
          <div className="transcript-utterance-heading"><button onClick={()=>seek(first.startMs)} aria-label={`${g.speaker} ${time(first.startMs)} 재생`}><strong>{g.speaker}</strong><span>{time(first.startMs)}{g.segments.some(s=>s.estimatedTime)?' · 추정':''}</span></button><button disabled={disabled||busy||editing} onClick={()=>beginEdit(g.id)} aria-label={`${time(first.startMs)} 문구 수정`}>문구 수정</button></div>
          {g.segments.some(s=>s.speakerAssignment==='ESTIMATED')&&<small className="speaker-estimated">음성 정보가 부족한 구간 포함 · 화자 매칭 추정</small>}
          {editing&&(!editingGroup||editingGroup===g.id)?g.segments.map(s=><label className="transcript-text-editor" key={s.id}>{time(s.startMs)} 발언 내용<textarea aria-label={`${time(s.startMs)} 발언 내용`} rows={3} value={texts[s.id]??s.text} disabled={disabled||busy} maxLength={200000} onChange={e=>setTexts(t=>({...t,[s.id]:e.target.value}))}/></label>):<p>{g.segments.map(s=><span key={s.id}><button className="transcript-text-fragment" data-playback-active={active?.id===s.id} onClick={()=>seek(s.startMs)} aria-label={`${time(s.startMs)} 발언 재생`}>{s.text}</button>{' '}</span>)}</p>}
          {editing&&editingGroup===g.id&&<div className="actions transcript-inline-actions"><button className="primary" disabled={disabled||busy||!dirty} onClick={()=>void saveText()}>수정 문구 적용</button><button disabled={busy} onClick={cancelEdit}>문구 수정 취소</button></div>}
          {!!changed.length&&<details className="transcript-original"><summary>처음 수정 전 원문 보기</summary>{changed.map(s=><p key={s.id}>{time(s.startMs)} · {meeting.content.originalTranscriptText?.[s.id]}</p>)}</details>}
        </div>;
      })}{!segments.length&&<p className="empty">{query?"검색 결과가 없습니다.":"아직 음성 기록이 없습니다."}</p>}</div>
    </section>
    <ParticipantsEditor meeting={meeting} disabled={disabled||busy||editing} reload={reload} seek={seek}/>
    <section className="transcript-timing" aria-label="시간 일괄 설정">
      <h3>시간 일괄 설정</h3>
      <p className="help">전체 기록을 확인한 뒤 모든 발언의 시간을 한 번에 배정합니다. 시작 시각부터 같은 간격으로 설정하며, 실제 발언 시각이 아닌 ‘추정 시간’으로 표시합니다.</p>
      <div className="form-grid">
        <label>시작 시각 (HH:MM:SS)<input aria-label="기록 시작 시각" value={start} disabled={disabled||busy} onChange={e=>{setStart(e.target.value);setSaved(false);}} /></label>
        <label>발언 구간 간격 (초)<input type="number" min={1} max={14400} step={1} value={interval} disabled={disabled||busy} onChange={e=>{setInterval(Number(e.target.value));setSaved(false);}} /></label>
      </div>
      <p className="help">검색 여부와 관계없이 전체 {meeting.content.segments.length}개 발언에 적용됩니다. 기존 시간은 교체됩니다. 이미 작성된 Jira 설명은 자동 변경하지 않습니다.</p>
      <button disabled={disabled||busy||editing||!meeting.content.segments.length} onClick={()=>void apply()}>{busy?"시간 저장 중…":"전체 시간 적용"}</button>
      {error&&<p className="error" role="alert">{error}</p>}{saved&&<p role="status">전체 발언 시간을 저장했습니다.</p>}
      <label><input type="checkbox" checked={showTimes} onChange={e=>setShowTimes(e.target.checked)} />전체 발언 시간 표시</label>
      {showTimes&&segments.map(s=><div className="segment" key={s.id}><button className="timestamp" onClick={()=>seek(s.startMs)}>{time(s.startMs)}{s.estimatedTime&&<small>추정 시간</small>}</button><div><strong>{s.speaker}</strong><p>{s.text}</p><small>종료 {time(s.endMs)}</small></div></div>)}
    </section>
  </>;
}
