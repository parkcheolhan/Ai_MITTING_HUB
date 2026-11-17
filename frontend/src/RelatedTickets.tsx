import {useEffect,useRef,useState} from 'react';
import {assignedEpicId} from './EpicTicketBoard';
import {statusName,type Meeting} from './types';

export default function RelatedTickets({meeting,currentId,onSelect,disabled}:{meeting:Meeting;currentId:string;onSelect:(id:string)=>void;disabled:boolean}){
  const [query,setQuery]=useState('');const list=useRef<HTMLDivElement>(null);
  const current=meeting.drafts.find(d=>d.id===currentId),epicId=current?assignedEpicId(current,meeting.epics||[]):'';
  const epic=meeting.epics?.find(e=>e.id===epicId),related=meeting.drafts.filter(d=>assignedEpicId(d,meeting.epics||[])===epicId);
  const visible=related.filter(d=>(d.summary+' '+(d.issueKey||'')).toLocaleLowerCase().includes(query.toLocaleLowerCase()));
  useEffect(()=>{setQuery('');},[epicId,meeting.id]);
  useEffect(()=>{list.current?.querySelector<HTMLElement>('[aria-current="true"]')?.scrollIntoView({block:'nearest',inline:'nearest'});},[currentId,visible.length]);
  return <aside className="ticket-related-list" aria-label="같은 에픽 업무 목록"><div className="ticket-related-heading"><span className="eyebrow">{epic?.issueKey||'EPIC'}</span><h3 title={epic?.summary||'미배치 업무'}>{epic?.summary||'미배치 업무'}</h3><p>같은 에픽 업무 {related.length}개</p><input aria-label="같은 에픽 업무 검색" placeholder="업무 제목·이슈 키 검색" value={query} onChange={e=>setQuery(e.target.value)}/></div>
    <div className="ticket-related-items" ref={list}>{visible.map(d=><button type="button" key={d.id} aria-current={d.id===currentId?'true':undefined} disabled={disabled} onClick={()=>onSelect(d.id)}><strong>{d.summary}</strong><span>{d.issueKey||'미등록'} · {statusName[d.status]||d.status}</span></button>)}{!visible.length&&<p className="help">검색 조건에 맞는 업무가 없습니다.</p>}</div>
  </aside>;
}
