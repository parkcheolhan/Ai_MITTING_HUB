import {useEffect,useState} from "react";
import {api} from "./api";
import {type Meeting,time} from "./types";

export default function ParticipantsEditor({meeting,disabled,reload,seek}:{meeting:Meeting;disabled:boolean;reload:()=>Promise<void>;seek:(ms:number,endMs?:number)=>void}){
  const initial=()=>Object.fromEntries(meeting.content.segments.map(s=>[s.id,meeting.content.participants.includes(s.speaker)?s.speaker:""]));
  const [names,setNames]=useState(meeting.content.participants),[assigned,setAssigned]=useState<Record<string,string>>(initial),[name,setName]=useState("");
  const [dirty,setDirty]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(""),[message,setMessage]=useState("");
  const signature=meeting.content.segments.map(s=>s.speaker).join("|");
  useEffect(()=>{if(!dirty){setNames(meeting.content.participants);setAssigned(initial());}},[meeting.id,meeting.content.participants.join("|"),signature,dirty]);
  const groups=[...new Set(meeting.content.segments.map(s=>meeting.content.speakerGroups?.[s.id]||s.speaker))];
  function add(){const value=name.trim();if(!value)return;if(!names.includes(value)){setNames(v=>[...v,value]);setDirty(true);}setName("");setMessage("");}
  function assign(ids:string[],value:string){setAssigned(prev=>({...prev,...Object.fromEntries(ids.map(id=>[id,value]))}));setDirty(true);setMessage("");}
  async function save(){setBusy(true);setError("");try{await api(`/meetings/${meeting.id}/participants`,"PUT",{participants:names,segmentSpeakers:assigned});await reload();setDirty(false);setMessage("참석자와 발언자 지정을 저장했습니다.");}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
  async function analyze(){setBusy(true);setError("");try{await api(`/meetings/${meeting.id}/speakers/analyze`,"POST");await reload();setMessage("음성별 화자 분석을 시작했습니다. 완료 후 이름을 지정하세요.");}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
  return <section className="participants-editor" aria-label="음성별 참석자 지정">
    <div className="section-head"><div><h3>참석자와 발언자 지정</h3><p className="help">음성을 화자별로 구분한 뒤 실제 참석자 이름을 연결하세요. 듣기만 한 참석자도 추가할 수 있습니다.</p></div>{meeting.content.assets.length===1&&<button disabled={disabled||busy||dirty||!meeting.content.segments.length||meeting.content.segments.some(s=>s.estimatedTime)} onClick={()=>void analyze()}>음성별 화자 분석</button>}</div>
    <div className="participant-add"><input aria-label="참석자 이름" placeholder="발언하지 않은 참석자도 추가하세요" maxLength={100} disabled={disabled||busy} value={name} onChange={e=>setName(e.target.value)} onKeyDown={e=>{if(e.key==="Enter"){e.preventDefault();add();}}}/><button disabled={disabled||busy||!name.trim()||names.length>=100} onClick={add}>참석자 추가</button></div>
    <div className="participant-chips">{names.map(n=><span key={n}>{n}<button aria-label={`${n} 참석자 제거`} disabled={disabled||busy} onClick={()=>{setNames(v=>v.filter(x=>x!==n));setAssigned(prev=>Object.fromEntries(Object.entries(prev).map(([id,x])=>[id,x===n?"":x])));setDirty(true);}}>×</button></span>)}</div>
    <p className="help">{names.length?`화자는 지정한 발언자 ${names.length}명 이내로 목소리를 묶어 매칭합니다. 참석자 수를 바꾼 뒤에는 음성별 화자 분석을 실행하세요.`:'참석자를 등록하면 지정한 인원 수 이내로 화자를 매칭합니다.'}</p>
    {groups.length>0&&<><p className="help">발언 묶음에 참석자 이름을 지정하거나 아래 ‘발언별 지정’에서 수정할 수 있습니다.</p><div className="speaker-groups">{groups.map(group=>{
      const segments=meeting.content.segments.filter(s=>(meeting.content.speakerGroups?.[s.id]||s.speaker)===group),values=[...new Set(segments.map(s=>assigned[s.id]||""))],value=values.length===1?values[0]:"__mixed";
      return <div className="speaker-group" key={group}><div><strong>{group}</strong><small>{segments.length}개 발언</small><p>{segments[0].text.slice(0,100)}</p><button onClick={()=>seek(segments[0].startMs,segments[0].endMs)}>목소리 듣기 · {time(segments[0].startMs)}</button></div><label>참석자 지정<select aria-label={`${group} 참석자 지정`} disabled={disabled||busy} value={value} onChange={e=>assign(segments.map(s=>s.id),e.target.value)}><option value="">미지정</option>{value==="__mixed"&&<option value="__mixed" disabled>발언별로 지정됨</option>}{names.map(n=><option key={n}>{n}</option>)}</select></label></div>;
    })}</div><details><summary>발언별 지정</summary><div className="speaker-segment-list">{meeting.content.segments.map(s=><div key={s.id}><button onClick={()=>seek(s.startMs,s.endMs)}>{time(s.startMs)}</button><p>{s.text}</p><select aria-label={`${time(s.startMs)} 발언자`} value={assigned[s.id]||""} disabled={disabled||busy} onChange={e=>assign([s.id],e.target.value)}><option value="">미지정</option>{names.map(n=><option key={n}>{n}</option>)}</select></div>)}</div></details></>}
    {error&&<p className="error" role="alert">{error}</p>}{message&&<p className="help" role="status">{message}</p>}
    <div className="form-actions"><button className="primary" disabled={disabled||busy||!dirty} onClick={()=>void save()}>{busy?"처리 중…":"참석자 지정 저장"}</button></div>
  </section>;
}
