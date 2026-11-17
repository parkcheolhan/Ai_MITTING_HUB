import {useEffect,useRef,useState,type ReactNode,type DragEvent} from 'react';
import {api} from './api';
import {createPortal} from 'react-dom';
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
  const [menu,setMenu]=useState<{draft:Draft;x:number;y:number}|null>(null);
  const menuElement=useRef<HTMLDivElement>(null),menuButton=useRef<HTMLButtonElement>(null),menuOpener=useRef<HTMLElement|null>(null);
  const host=useRef<HTMLDivElement>(null),searchRequest=useRef(0),alive=useRef(true),dragId=useRef('');
  const lanes=useRef<HTMLDivElement>(null),scrollFrame=useRef(0),dragPoint=useRef<{x:number;y:number}|null>(null);
  const [scrollable,setScrollable]=useState({left:false,right:false});
  const locked=disabled||mutating;
  function stopDrag(){dragId.current='';dragPoint.current=null;if(scrollFrame.current)cancelAnimationFrame(scrollFrame.current);scrollFrame.current=0;setDragging('');setOver(null);}
  function measureScroll(){const el=lanes.current;if(el)setScrollable(previous=>{const next={left:el.scrollLeft>2,right:el.scrollLeft+el.clientWidth<el.scrollWidth-2};return previous.left===next.left&&previous.right===next.right?previous:next;});}
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;searchRequest.current++;if(scrollFrame.current)cancelAnimationFrame(scrollFrame.current);};},[]);
  useEffect(()=>{const el=lanes.current;if(!el)return;measureScroll();const observer=new ResizeObserver(measureScroll);observer.observe(el);return()=>observer.disconnect();},[epics.length]);
  useEffect(()=>{if(added){const box=host.current?.querySelector<HTMLElement>(`[data-epic-id="${added}"]`),area=lanes.current;if(box&&area){area.scrollLeft+=box.getBoundingClientRect().left-area.getBoundingClientRect().left-12;measureScroll();setAdded('');}}},[added,meeting.epics]);
  function pageColumns(direction:number){const el=lanes.current;if(el)el.scrollBy({left:direction*Math.max(280,el.clientWidth*.8),behavior:'smooth'});}
  function edgeSpeed(position:number,start:number,end:number,margin:number){return position<start+margin?-Math.ceil(18*Math.max(0,Math.min(1,(start+margin-position)/margin))):position>end-margin?Math.ceil(18*Math.max(0,Math.min(1,(position-end+margin)/margin))):0;}
  function autoScroll(){
    scrollFrame.current=0;const el=lanes.current,point=dragPoint.current;if(!el||!point||!dragId.current)return;
    const bounds=el.getBoundingClientRect();
    if(point.y>=bounds.top&&point.y<=bounds.bottom&&point.x>=bounds.left-16&&point.x<=bounds.right+16){
      el.scrollLeft+=edgeSpeed(point.x,bounds.left,bounds.right,64);
      for(const body of el.querySelectorAll<HTMLElement>('.epic-column-body')){const rect=body.getBoundingClientRect();if(point.x>=rect.left&&point.x<=rect.right&&point.y>=rect.top&&point.y<=rect.bottom)body.scrollTop+=edgeSpeed(point.y,rect.top,rect.bottom,52);}
    }
    scrollFrame.current=requestAnimationFrame(autoScroll);
  }
  function trackDrag(e:DragEvent){if(!dragId.current)return;dragPoint.current={x:e.clientX,y:e.clientY};if(!scrollFrame.current)scrollFrame.current=requestAnimationFrame(autoScroll);}
  function canMove(d:Draft){return !locked&&movableTicket(d);}
  function closeMenu(){setMenu(null);menuOpener.current?.focus();}
  useEffect(()=>{
    if(!menu)return;
    menuButton.current?.focus({preventScroll:true});
    const outside=(event:PointerEvent)=>{if(!menuElement.current?.contains(event.target as Node))setMenu(null);};
    const keyboard=(event:KeyboardEvent)=>{if(event.key==='Escape'){event.preventDefault();closeMenu();}};
    const dismiss=()=>setMenu(null);
    document.addEventListener('pointerdown',outside);document.addEventListener('keydown',keyboard);window.addEventListener('resize',dismiss);window.addEventListener('wheel',dismiss,{passive:true});window.addEventListener('touchmove',dismiss,{passive:true});
    return()=>{document.removeEventListener('pointerdown',outside);document.removeEventListener('keydown',keyboard);window.removeEventListener('resize',dismiss);window.removeEventListener('wheel',dismiss);window.removeEventListener('touchmove',dismiss);};
  },[menu]);
  function openMenu(draft:Draft,x:number,y:number,opener:HTMLElement){menuOpener.current=opener;setMenu({draft,x:Math.max(8,Math.min(x,innerWidth-208)),y:Math.max(8,Math.min(y,innerHeight-110))});}
  async function remove(draft:Draft){
    setMenu(null);
    await perform(async()=>{await api(`/ticket-drafts/${draft.id}?version=${draft.version}`,'DELETE');setNotice(`${draft.summary} 업무를 삭제했습니다.`);});
  }
  async function search(more=false){
    const request=++searchRequest.current,term=more?searchQuery:query.trim();setSearching(true);setError('');
    try{
      const data=await api<SearchPage>(`/integrations/jira/projects/${encodeURIComponent(meeting.content.projectKey)}/epics?query=${encodeURIComponent(term)}&nextPageToken=${encodeURIComponent(more?cursor:'')}`);
      if(request===searchRequest.current&&alive.current){setResults(previous=>more?[...new Map([...previous,...data.epics].map(e=>[e.key,e])).values()]:data.epics);setCursor(data.nextPageToken||'');setSearchQuery(term);setSearched(true);}
    }catch(e){if(request===searchRequest.current&&alive.current)setError((e as Error).message);}
    finally{if(request===searchRequest.current&&alive.current)setSearching(false);}
  }
  async function perform(action:()=>Promise<void>){
    stopDrag();
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
      setNotice('');
    });
  }
  function start(e:DragEvent,draft:Draft){
    if(!canMove(draft)){e.preventDefault();return;}
    e.dataTransfer.effectAllowed='move';e.dataTransfer.setData(dragType,JSON.stringify({meetingId:meeting.id,id:draft.id,version:draft.version}));dragId.current=draft.id;setDragging(draft.id);setNotice('');
  }
  function drop(e:DragEvent,epicId:string){
    e.preventDefault();stopDrag();
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
      <div className="epic-column-body" role="region" aria-label={`${epic?.summary||'미배치'} 업무 목록`} tabIndex={0}>
      {rows.map(d=><div key={d.id} className={'epic-task-group '+(dragging===d.id?'dragging':'')} draggable={canMove(d)} onDragStart={e=>start(e,d)} onDragEnd={stopDrag} onContextMenu={e=>{e.preventDefault();openMenu(d,e.clientX,e.clientY,e.currentTarget);}} onKeyDown={e=>{if(e.key==='ContextMenu'||(e.shiftKey&&e.key==='F10')){e.preventDefault();const rect=e.currentTarget.getBoundingClientRect();openMenu(d,rect.left+20,rect.top+30,e.currentTarget);}}} tabIndex={0} data-draft-id={d.id}>
        <div className="epic-placement"><span draggable={canMove(d)} aria-label={`${d.summary} 이동 핸들`} title="업무 이동">⠿</span><select aria-label={`${d.summary} 에픽 이동`} value={assignedEpicId(d,epics)} disabled={!canMove(d)} onChange={e=>void move(d,e.target.value)}><option value="">미배치</option>{epics.map(item=><option key={item.id} value={item.id} disabled={item.currentTarget===false||!['PENDING','FAILED','PUBLISHED'].includes(item.status)}>{item.issueKey?item.issueKey+' · ':''}{item.summary}</option>)}</select><button type="button" className="task-menu-trigger" aria-label={`${d.summary} 업무 메뉴`} aria-haspopup="menu" onClick={e=>{const rect=e.currentTarget.getBoundingClientRect();openMenu(d,rect.right-190,rect.bottom,e.currentTarget);}}>⋯</button></div>
        {renderRoot(d)}
      </div>)}
      {!rows.length&&<p className="epic-drop-placeholder">{epic?'이 에픽에 업무를 배치하세요.':'에픽에 배치하지 않은 업무가 없습니다.'}</p>}
      </div>
    </section>;
  }
  return <div className="epic-ticket-board" ref={host}>
    <div className="epic-board-toolbar"><div><strong>에픽별 티켓 배치</strong><p>업무를 원하는 에픽에 배치하세요.</p></div><div className="epic-board-controls"><button type="button" aria-label="이전 에픽 보기" disabled={!scrollable.left} onClick={()=>pageColumns(-1)}>←</button><button type="button" aria-label="다음 에픽 보기" disabled={!scrollable.right} onClick={()=>pageColumns(1)}>→</button><button type="button" disabled={locked} aria-expanded={lookup} onClick={()=>setLookup(v=>!v)}>에픽 조회·추가</button></div></div>
    {lookup&&<div className="epic-lookup"><form onSubmit={e=>{e.preventDefault();void search();}}><label>에픽 제목 또는 키<input value={query} maxLength={200} placeholder="제목 또는 PROJ-123" disabled={locked||searching} onChange={e=>setQuery(e.target.value)}/></label><button disabled={locked||searching}>{searching?'조회 중…':'에픽 조회'}</button></form>
      {searched&&<div className="epic-search-results" aria-label="에픽 조회 결과">{results.map(e=><div key={e.key}><div><strong>{e.summary}</strong><small>{e.key} · {e.status}</small></div><button type="button" disabled={locked||epics.some(item=>item.issueKey===e.key&&item.currentTarget!==false)} onClick={()=>void add({issueKey:e.key})}>{epics.some(item=>item.issueKey===e.key&&item.currentTarget!==false)?'추가됨':'박스 추가'}</button></div>)}{!results.length&&<p className="help">검색 조건에 맞는 에픽이 없습니다.</p>}{cursor&&<button type="button" disabled={locked||searching} onClick={()=>void search(true)}>에픽 더 보기</button>}</div>}
      <form className="epic-create" onSubmit={e=>{e.preventDefault();if(newTitle.trim())void add({summary:newTitle.trim()});}}><label>새 에픽 제목<input value={newTitle} maxLength={200} placeholder="새 에픽의 제목을 입력하세요" disabled={locked} onChange={e=>setNewTitle(e.target.value)}/></label><button disabled={locked||!newTitle.trim()}>새 에픽 박스 추가</button><p className="help">새 에픽은 배치한 티켓을 승인·등록할 때 Jira에 생성됩니다.</p></form>
    </div>}
    {error&&<p role="alert" className="error">{error}</p>}{notice&&<p role="status" className="epic-board-notice">{notice}</p>}
    <div ref={lanes} className="epic-board-lanes" role="region" aria-label="에픽 가로 배치 보드" tabIndex={0} onScroll={measureScroll} onDragOver={trackDrag} onDragLeave={e=>{if(!e.currentTarget.contains(e.relatedTarget as Node|null))dragPoint.current=null;}}>{box()}{epics.map(epic=>box(epic))}</div>
    {menu&&createPortal(<div ref={menuElement} role="menu" aria-label={`${menu.draft.summary} 업무 메뉴`} className="task-context-menu" style={{left:menu.x,top:menu.y}} onContextMenu={e=>e.preventDefault()}><button ref={menuButton} type="button" role="menuitem" disabled={locked||!['DRAFT','APPROVED','FAILED'].includes(menu.draft.status)||!!menu.draft.issueKey} onClick={()=>void remove(menu.draft)}>업무 삭제</button></div>,document.body)}
  </div>;
}
