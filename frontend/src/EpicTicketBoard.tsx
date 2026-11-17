import {useEffect,useRef,useState,type ReactNode,type DragEvent} from 'react';
import {api} from './api';
import {statusName,type Draft,type Meeting,type MeetingEpic} from './types';

type JiraEpic={key:string;projectKey:string;summary:string;status:string;issueUrl:string|null};
type SearchPage={epics:JiraEpic[];nextPageToken:string};
const dragType='application/x-moa-ticket';
export function assignedEpicId(draft:Draft,epics:MeetingEpic[]){return epics.find(e=>draft.epicId?e.id===draft.epicId:!!draft.epicKey&&e.issueKey===draft.epicKey)?.id||'';}
export function movableTicket(draft:Draft){return ['DRAFT','APPROVED','FAILED'].includes(draft.status)&&draft.registrationMode!=='EXISTING'&&!draft.issueKey;}

export default function EpicTicketBoard({meeting,roots,renderRoot,disabled,onBusyChange,reload}:{meeting:Meeting;roots:Draft[];renderRoot:(draft:Draft)=>ReactNode;disabled:boolean;onBusyChange:(value:boolean)=>void;reload:()=>Promise<void>}){
  const epics=meeting.epics||[];
  const [lookup,setLookup]=useState(false),[query,setQuery]=useState(''),[searched,setSearched]=useState(false);
  const [results,setResults]=useState<JiraEpic[]>([]),[cursor,setCursor]=useState(''),[searchQuery,setSearchQuery]=useState('');
  const [searching,setSearching]=useState(false),[mutating,setMutating]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const [newTitle,setNewTitle]=useState(''),[dragging,setDragging]=useState(''),[over,setOver]=useState<string|null>(null),[added,setAdded]=useState('');
  const host=useRef<HTMLDivElement>(null),searchRequest=useRef(0),alive=useRef(true),dragId=useRef('');
  const locked=disabled||mutating;
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;searchRequest.current++;};},[]);
  useEffect(()=>{if(added){const box=host.current?.querySelector<HTMLElement>(`[data-epic-id="${added}"]`);if(box){box.scrollIntoView({block:'nearest',behavior:'instant'});setAdded('');}}},[added,meeting.epics]);
  function canMove(d:Draft){return !locked&&movableTicket(d)&&meeting.drafts.filter(child=>child.parentDraftId===d.id).every(movableTicket);}
  async function search(more=false){
    const request=++searchRequest.current,term=more?searchQuery:query.trim();setSearching(true);setError('');
    try{
      const data=await api<SearchPage>(`/integrations/jira/projects/${encodeURIComponent(meeting.content.projectKey)}/epics?query=${encodeURIComponent(term)}&nextPageToken=${encodeURIComponent(more?cursor:'')}`);
      if(request===searchRequest.current&&alive.current){setResults(previous=>more?[...new Map([...previous,...data.epics].map(e=>[e.key,e])).values()]:data.epics);setCursor(data.nextPageToken||'');setSearchQuery(term);setSearched(true);}
    }catch(e){if(request===searchRequest.current&&alive.current)setError((e as Error).message);}
    finally{if(request===searchRequest.current&&alive.current)setSearching(false);}
  }
  async function perform(action:()=>Promise<void>){
    setMutating(true);onBusyChange(true);setError('');setNotice('');
    try{await action();await reload();}
    catch(e){if(alive.current)setError((e as Error).message);}
    finally{if(alive.current){setMutating(false);onBusyChange(false);}}
  }
  async function add(input:{issueKey?:string;summary?:string}){
    await perform(async()=>{
      const result=await api<Meeting>(`/meetings/${meeting.id}/epics`,'POST',input);
      const epic=result.epics?.find(e=>input.issueKey?e.issueKey===input.issueKey:!epics.some(old=>old.id===e.id));
      if(epic)setAdded(epic.id);setNewTitle('');setLookup(false);setNotice('에픽 박스를 추가했습니다. 업무 티켓을 끌어다 놓으세요.');
    });
  }
  async function move(draft:Draft,epicId:string,version=draft.version){
    if(!canMove(draft)||assignedEpicId(draft,epics)===epicId)return;
    await perform(async()=>{
      await api(`/ticket-drafts/${draft.id}/epic`,'PUT',{epicId,version});
      setNotice(`${draft.summary} 업무와 하위업무를 ${epics.find(e=>e.id===epicId)?.summary||'미배치 목록'}${epicId?' 에픽으로':'으로'} 이동했습니다. 다시 승인한 뒤 등록하세요.`);
    });
  }
  function start(e:DragEvent,draft:Draft){
    if(!canMove(draft)){e.preventDefault();return;}
    e.dataTransfer.effectAllowed='move';e.dataTransfer.setData(dragType,JSON.stringify({meetingId:meeting.id,id:draft.id,version:draft.version}));dragId.current=draft.id;setDragging(draft.id);setNotice('');
  }
  function drop(e:DragEvent,epicId:string){
    e.preventDefault();dragId.current='';setOver(null);setDragging('');
    if(locked)return;
    try{const payload=JSON.parse(e.dataTransfer.getData(dragType));const draft=roots.find(d=>d.id===payload.id);if(payload.meetingId!==meeting.id||!draft||!Number.isInteger(payload.version))throw new Error('같은 회의의 업무 티켓을 이동하세요.');void move(draft,epicId,payload.version);}
    catch(err){setError((err as Error).message);}
  }
  function box(epic?:MeetingEpic){
    const id=epic?.id||'',rows=roots.filter(d=>assignedEpicId(d,epics)===id);
    const accepts=!locked&&(!epic||(epic.currentTarget!==false&&['PENDING','FAILED','PUBLISHED'].includes(epic.status)));
    return <section key={id||'unassigned'} data-epic-id={id} className={'epic-drop-box '+(!epic?'unassigned ':'')+(over===id?'drag-over':'')} aria-label={epic?`${epic.summary} 에픽 박스`:'미배치 업무 박스'} onDragOver={e=>{if(accepts&&dragId.current&&e.dataTransfer.types.includes(dragType)){e.preventDefault();e.dataTransfer.dropEffect='move';setOver(id);}}} onDragLeave={e=>{if(!e.currentTarget.contains(e.relatedTarget as Node|null))setOver(null);}} onDrop={e=>{if(accepts)drop(e,id);}}>
      <div className="epic-box-heading"><div><span className="eyebrow">{epic?'EPIC':'UNASSIGNED'} · 업무 {rows.length}개</span><h3>{epic?.summary||'미배치 업무'}</h3>{epic&&<p>{epic.issueUrl?<a href={epic.issueUrl} target="_blank" rel="noopener noreferrer">{epic.issueKey} ↗</a>:epic.issueKey||'등록 전 에픽'} · {epic.external?'기존 에픽':epic.status==='PENDING'?'등록 대기':statusName[epic.status]||'상태 확인'}</p>}</div></div>
      {epic?.currentTarget===false&&<p className="warning">현재 연결한 Jira 대상과 다릅니다. 에픽을 다시 조회하세요.</p>}
      {epic?.error&&<p className="error">{epic.error}</p>}
      {rows.map(d=><div key={d.id} className={'epic-task-group '+(dragging===d.id?'dragging':'')} draggable={canMove(d)} onDragStart={e=>start(e,d)} onDragEnd={()=>{dragId.current='';setDragging('');setOver(null);}} data-draft-id={d.id}>
        <div className="epic-placement"><span draggable={canMove(d)} aria-label={`${d.summary} 이동 핸들`} title="업무와 하위업무를 함께 이동합니다">⠿ {canMove(d)?'드래그해 이동':'이동 잠김'}</span><select aria-label={`${d.summary} 에픽 이동`} value={assignedEpicId(d,epics)} disabled={!canMove(d)} onChange={e=>void move(d,e.target.value)}><option value="">미배치</option>{epics.map(item=><option key={item.id} value={item.id} disabled={item.currentTarget===false||!['PENDING','FAILED','PUBLISHED'].includes(item.status)}>{item.issueKey?item.issueKey+' · ':''}{item.summary}</option>)}</select></div>
        {renderRoot(d)}
      </div>)}
      {!rows.length&&<p className="epic-drop-placeholder">{epic?'업무 티켓을 이곳에 끌어다 놓으세요.':'에픽에 배치하지 않은 업무가 없습니다.'}</p>}
    </section>;
  }
  return <div className="epic-ticket-board" ref={host}>
    <div className="epic-board-toolbar"><div><strong>에픽별 티켓 배치</strong><p>업무를 이동하면 하위업무도 함께 이동합니다.</p></div><button type="button" disabled={locked} aria-expanded={lookup} onClick={()=>setLookup(v=>!v)}>에픽 조회·추가</button></div>
    {lookup&&<div className="epic-lookup"><form onSubmit={e=>{e.preventDefault();void search();}}><label>에픽 제목 또는 키<input value={query} maxLength={200} placeholder="제목 또는 PROJ-123" disabled={locked||searching} onChange={e=>setQuery(e.target.value)}/></label><button disabled={locked||searching}>{searching?'조회 중…':'에픽 조회'}</button></form>
      {searched&&<div className="epic-search-results" aria-label="에픽 조회 결과">{results.map(e=><div key={e.key}><div><strong>{e.summary}</strong><small>{e.key} · {e.status}</small></div><button type="button" disabled={locked||epics.some(item=>item.issueKey===e.key&&item.currentTarget!==false)} onClick={()=>void add({issueKey:e.key})}>{epics.some(item=>item.issueKey===e.key&&item.currentTarget!==false)?'추가됨':'박스 추가'}</button></div>)}{!results.length&&<p className="help">검색 조건에 맞는 에픽이 없습니다.</p>}{cursor&&<button type="button" disabled={locked||searching} onClick={()=>void search(true)}>에픽 더 보기</button>}</div>}
      <form className="epic-create" onSubmit={e=>{e.preventDefault();if(newTitle.trim())void add({summary:newTitle.trim()});}}><label>새 에픽 제목<input value={newTitle} maxLength={200} placeholder="새 에픽의 제목을 입력하세요" disabled={locked} onChange={e=>setNewTitle(e.target.value)}/></label><button disabled={locked||!newTitle.trim()}>새 에픽 박스 추가</button><p className="help">새 에픽은 배치한 티켓을 승인·등록할 때 Jira에 생성됩니다.</p></form>
    </div>}
    {error&&<p role="alert" className="error">{error}</p>}{notice&&<p role="status" className="epic-board-notice">{notice}</p>}
    {box()}{epics.map(epic=>box(epic))}
  </div>;
}
