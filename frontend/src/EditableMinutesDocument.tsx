import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {api} from './api';
import MinutesDocument,{type MinutesLine} from './MinutesDocument';
import type {Meeting,Minutes} from './types';

type Edit=MinutesLine&{version:number;baseline:string;prefix:string;text:string;finish:boolean};
const fieldText=(minutes:Minutes,field:MinutesLine['field'])=>minutes[field]||'';
const rawText=(edit:Edit)=>edit.text?edit.prefix+edit.text:'';

export default function EditableMinutesDocument({meeting,minutes,disabled,onSaved,onEditingChange}:{meeting:Meeting;minutes:Minutes;disabled:boolean;onSaved:(minutes:Minutes)=>void;onEditingChange:(editing:boolean)=>void}){
  const [document,setDocument]=useState(minutes),[edit,setEdit]=useState<Edit|null>(null),[status,setStatus]=useState(''),[error,setError]=useState(''),[retrying,setRetrying]=useState(false),[saving,setSaving]=useState(false);
  const documentRef=useRef(minutes),editRef=useRef<Edit|null>(null),timer=useRef<ReturnType<typeof setTimeout>|null>(null),inflight=useRef(false),alive=useRef(true),nextLine=useRef<MinutesLine|null>(null),textarea=useRef<HTMLTextAreaElement>(null);
  const callbacks=useRef({onSaved,onEditingChange});callbacks.current={onSaved,onEditingChange};
  const composing=useRef(false);
  function update(value:Edit|null){editRef.current=value;if(alive.current)setEdit(value);}
  function clearTimer(){if(timer.current)clearTimeout(timer.current);timer.current=null;}
  function accept(value:Minutes){documentRef.current=value;if(alive.current){setDocument(value);callbacks.current.onSaved(value);}}
  useEffect(()=>{if(minutes.version>documentRef.current.version){documentRef.current=minutes;setDocument(minutes);}},[minutes]);
  useEffect(()=>{callbacks.current.onEditingChange(!!edit);},[!!edit]);
  useEffect(()=>{
    alive.current=true;
    function leave(e:BeforeUnloadEvent){const current=editRef.current;if(inflight.current||current&&rawText(current)!==current.raw){e.preventDefault();e.returnValue='';}}
    window.addEventListener('beforeunload',leave);
    return()=>{alive.current=false;clearTimer();if(editRef.current)void flush(true);window.removeEventListener('beforeunload',leave);callbacks.current.onEditingChange(false);};
  },[]);
  useLayoutEffect(()=>{const el=textarea.current;if(!el)return;el.style.height='auto';el.style.height=Math.max(44,el.scrollHeight+2)+'px';},[edit?.text,edit?.field,edit?.index]);
  useLayoutEffect(()=>{const el=textarea.current;if(el){el.focus();el.setSelectionRange(el.value.length,el.value.length);}},[edit?.field,edit?.index]);
  function open(line:MinutesLine){
    const current=documentRef.current,raw=fieldText(current,line.field).split(/\r?\n/)[line.index];if(raw===undefined)return;
    const prefix=/^(#{1,6}\s+|\s*[-*]\s+)/.exec(raw)?.[0]||'';
    clearTimer();setError('');setStatus('');update({...line,raw,version:current.version,baseline:fieldText(current,line.field),prefix,text:raw.slice(prefix.length),finish:false});
  }
  function close(){
    if(!alive.current){nextLine.current=null;update(null);return;}
    clearTimer();const previous=editRef.current;update(null);setError('');
    const queued=nextLine.current;nextLine.current=null;
    if(queued)open(queued);
    else if(previous)requestAnimationFrame(()=>window.document.querySelector<HTMLElement>(`[data-minutes-line="${previous.field}:${previous.index}"]`)?.focus({preventScroll:true}));
  }
  async function flush(finish=false){
    clearTimer();let current=editRef.current;if(!current)return;
    if(finish){current={...current,finish:true};update(current);}
    if(inflight.current||composing.current)return;
    if(rawText(current)===current.raw){if(alive.current)setStatus(previous=>previous==='자동 저장됨'?'자동 저장됨':'변경사항 없음');if(current.finish)close();return;}
    const sent={...current};inflight.current=true;if(alive.current){setSaving(true);setStatus('자동 저장 중…');setError('');}let success=false;
    try{
      const result=await api<Minutes>(`/meetings/${meeting.id}/minutes/line`,'PATCH',{field:sent.field,lineIndex:sent.index,originalText:sent.raw,text:rawText(sent),expectedVersion:sent.version});
      const latest=editRef.current;
      if(latest){const raw=fieldText(result,sent.field).split(/\r?\n/)[sent.index]??'';update({...latest,raw,version:result.version,baseline:fieldText(result,sent.field)});}
      accept(result);if(alive.current)setStatus('자동 저장됨');success=true;
    }catch(e){if(alive.current){nextLine.current=null;setStatus('저장되지 않음');setError((e as Error).message);}}
    finally{
      inflight.current=false;if(alive.current)setSaving(false);const latest=editRef.current;if(success&&latest){if(rawText(latest)!==latest.raw)timer.current=setTimeout(()=>void flush(),0);else if(latest.finish)close();}
    }
  }
  function begin(line:MinutesLine){
    if(disabled)return;const current=editRef.current;
    if(!current){open(line);return;}if(current.field===line.field&&current.index===line.index){textarea.current?.focus();return;}
    nextLine.current=line;void flush(true);
  }
  function change(text:string){const current=editRef.current;if(!current)return;update({...current,text:text.replace(/[\r\n]+/g,' '),finish:false});setError('');setStatus('자동 저장 대기');clearTimer();if(!composing.current)timer.current=setTimeout(()=>void flush(),900);}
  async function retry(){
    clearTimer();setRetrying(true);setError('');
    try{
      const latest=await api<Meeting>(`/meetings/${meeting.id}`),minutes=latest.content.minutes,current=editRef.current;if(!minutes||!current)return;
      const text=fieldText(minutes,current.field),line=text.split(/\r?\n/)[current.index];
      // A lost response may already have saved this exact change. Otherwise only rebase an unchanged field.
      if(line===rawText(current)){
        update({...current,raw:line,baseline:text,version:minutes.version});accept(minutes);setStatus('자동 저장됨');if(current.finish)close();
      }else if(text===current.baseline){update({...current,version:minutes.version});accept(minutes);await flush();}
      else{accept(minutes);throw new Error('다른 곳에서 해당 항목이 변경되었습니다. 입력한 문구는 유지했습니다. 필요한 문구를 복사한 뒤 편집 취소로 최신 내용을 확인하세요.');}
    }catch(e){setError((e as Error).message);setStatus('저장되지 않음');}finally{setRetrying(false);}
  }
  function cancel(){if(inflight.current)return;nextLine.current=null;clearTimer();setStatus('');close();}
  return <><p className="minutes-inline-help">본문의 줄을 더블클릭하면 수정할 수 있습니다. 입력을 멈추거나 다른 곳을 클릭하면 자동 저장됩니다.</p>
    {!edit&&status&&<p className="minutes-inline-status" role="status">{status}</p>}
    <MinutesDocument meeting={meeting} minutes={edit?{...document,[edit.field]:edit.baseline}:document} formattedMode={edit?.field==='formatted'||!!document.formatted} renderLine={(line,content)=>{
      const key=`${line.field}:${line.index}`,active=edit?.field===line.field&&edit.index===line.index;
      if(active&&edit)return <div key={key} className="minutes-line-editor" data-minutes-line={key} onBlur={e=>{if(!e.currentTarget.contains(e.relatedTarget as Node))void flush(true);}}>
        <textarea ref={textarea} aria-label={`${line.label} ${line.index+1}행 수정`} rows={1} maxLength={200000} value={edit.text} disabled={disabled||retrying}
          onCompositionStart={()=>{composing.current=true;clearTimer();}} onCompositionEnd={()=>{composing.current=false;clearTimer();timer.current=setTimeout(()=>void flush(),900);}}
          onChange={e=>change(e.target.value)} onKeyDown={e=>{if(e.nativeEvent.isComposing||e.keyCode===229)return;if(e.key==='Enter'){e.preventDefault();void flush(true);}else if(e.key==='Escape'){e.preventDefault();e.stopPropagation();cancel();}}}/>
        <div className="minutes-line-tools"><span role="status">{status||'Enter 또는 다른 곳 클릭으로 저장'}</span><div><button type="button" disabled={disabled||retrying} onClick={()=>void flush(true)}>완료</button><button type="button" disabled={saving||retrying} onClick={cancel}>편집 취소</button></div></div>
        {error&&<div className="minutes-line-error" role="alert"><span>{error}</span><button type="button" disabled={saving||retrying||disabled} onClick={()=>void retry()}>{retrying?'확인 중…':'다시 저장'}</button></div>}
      </div>;
      return <div key={key} data-minutes-line={key} className={`minutes-document-line${disabled?'':' editable'}${!line.raw?' empty':''}${!line.raw&&line.field==='formatted'?' template-gap':''}`} tabIndex={disabled?undefined:0} title={disabled?undefined:'더블클릭하여 수정'} onDoubleClick={()=>begin(line)} onKeyDown={e=>{if(!e.nativeEvent.isComposing&&(e.key==='Enter'||e.key==='F2')){e.preventDefault();begin(line);}}}>{content}{!line.raw&&<span className="minutes-empty-line">내용을 입력하세요</span>}</div>;
    }}/>
  </>;
}
