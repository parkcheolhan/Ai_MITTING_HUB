import {useEffect,useRef,useState} from "react";
import {api} from "./api";
import {time, type Meeting} from "./types";
import ParticipantsEditor from "./ParticipantsEditor";

export default function TranscriptView({meeting, disabled, reload, seek, playbackMs=-1}: {meeting: Meeting; disabled: boolean; reload: () => Promise<void>; seek: (ms: number) => void; playbackMs?:number}) {
  const [start,setStart]=useState("00:00:00");
  const [interval,setInterval]=useState(30);
  const [query,setQuery]=useState("");
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  const [saved,setSaved]=useState(false);
  const [showTimes,setShowTimes]=useState(false);
  const [follow,setFollow]=useState(true);
  const documentRef=useRef<HTMLDivElement>(null);
  const segments=meeting.content.segments.filter(s=>(s.text+" "+s.speaker).toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const active=meeting.content.segments.filter(s=>!s.estimatedTime&&s.startMs<=playbackMs&&playbackMs<s.endMs).reduce<(typeof meeting.content.segments)[number]|undefined>((latest,s)=>!latest||s.startMs>latest.startMs?s:latest,undefined);
  useEffect(()=>{
    if(!follow||!active)return;
    const container=documentRef.current;
    const row=container?.querySelector<HTMLElement>('[data-active="true"]');
    if(!container||!row)return;
    const bounds=container.getBoundingClientRect(),rect=row.getBoundingClientRect();
    const top=bounds.top+container.clientTop+12;
    // Anchor each new utterance at the reader's top, without scrolling the page.
    if(Math.abs(rect.top-top)>1){
      container.scrollTo({top:container.scrollTop+rect.top-top,behavior:"instant"});
    }
  },[active?.id,follow,query]);
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
      <div className="transcript-reader-toolbar"><div><h3>전체 음성 기록</h3><span className="help">{segments.length} / {meeting.content.segments.length}개 발언</span></div><label className="transcript-search">발언 검색<input aria-label="발언 검색" placeholder="발언 내용 또는 화자 이름" value={query} onChange={e=>setQuery(e.target.value)} /></label></div>
      <div className="transcript-playback"><span>{active?`재생 중 · ${active.speaker} · ${time(active.startMs)}`:"발언을 선택하면 해당 구간을 재생합니다."}</span><label><input type="checkbox" checked={follow} onChange={e=>setFollow(e.target.checked)}/>재생 발언 따라가기</label></div>
      <div className="transcript-document" ref={documentRef}>{segments.map(s=><div className="transcript-utterance" key={s.id} data-active={active?.id===s.id} aria-current={active?.id===s.id?"true":undefined}><button onClick={()=>seek(s.startMs)} aria-label={`${s.speaker} ${time(s.startMs)} 재생`}><strong>{s.speaker}</strong><span>{time(s.startMs)}{s.estimatedTime?" · 추정":""}</span></button><p>{s.text}</p></div>)}{!segments.length&&<p className="empty">{query?"검색 결과가 없습니다.":"아직 음성 기록이 없습니다."}</p>}</div>
    </section>
    <ParticipantsEditor meeting={meeting} disabled={disabled||busy} reload={reload} seek={seek}/>
    <section className="transcript-timing" aria-label="시간 일괄 설정">
      <h3>시간 일괄 설정</h3>
      <p className="help">전체 기록을 확인한 뒤 모든 발언의 시간을 한 번에 배정합니다. 시작 시각부터 같은 간격으로 설정하며, 실제 발언 시각이 아닌 ‘추정 시간’으로 표시합니다.</p>
      <div className="form-grid">
        <label>시작 시각 (HH:MM:SS)<input aria-label="기록 시작 시각" value={start} disabled={disabled||busy} onChange={e=>{setStart(e.target.value);setSaved(false);}} /></label>
        <label>발언 구간 간격 (초)<input type="number" min={1} max={14400} step={1} value={interval} disabled={disabled||busy} onChange={e=>{setInterval(Number(e.target.value));setSaved(false);}} /></label>
      </div>
      <p className="help">검색 여부와 관계없이 전체 {meeting.content.segments.length}개 발언에 적용됩니다. 기존 시간은 교체됩니다. 이미 작성된 Jira 설명은 자동 변경하지 않습니다.</p>
      <button disabled={disabled||busy||!meeting.content.segments.length} onClick={()=>void apply()}>{busy?"시간 저장 중…":"전체 시간 적용"}</button>
      {error&&<p className="error" role="alert">{error}</p>}{saved&&<p role="status">전체 발언 시간을 저장했습니다.</p>}
      <label><input type="checkbox" checked={showTimes} onChange={e=>setShowTimes(e.target.checked)} />전체 발언 시간 표시</label>
      {showTimes&&segments.map(s=><div className="segment" key={s.id}><button className="timestamp" onClick={()=>seek(s.startMs)}>{time(s.startMs)}{s.estimatedTime&&<small>추정 시간</small>}</button><div><strong>{s.speaker}</strong><p>{s.text}</p><small>종료 {time(s.endMs)}</small></div></div>)}
    </section>
  </>;
}
