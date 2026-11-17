import {useEffect,useId,useRef,useState} from 'react';
import {api} from './api';
export type JiraUser={accountId:string;displayName:string;active:boolean};
type Page={users:JiraUser[];hasMore:boolean;nextStartAt:number};

export default function AssigneeSelect({project,value,name,disabled,onChange}:{project:string;value:string;name?:string;disabled:boolean;onChange:(user:JiraUser|null)=>void}){
  const id=useId(),[open,setOpen]=useState(false),[query,setQuery]=useState(''),[users,setUsers]=useState<JiraUser[]>([]),[loading,setLoading]=useState(false),[error,setError]=useState(''),[next,setNext]=useState(0),[more,setMore]=useState(false),[index,setIndex]=useState(-1),[resolved,setResolved]=useState(''),[retry,setRetry]=useState(0);
  const generation=useRef(0);
  useEffect(()=>{let active=true;setResolved('');if(value&&!name)api<Page>(`/integrations/jira/projects/${project}/assignees?accountId=${encodeURIComponent(value)}`).then(p=>{if(active)setResolved(p.users?.find(u=>u.accountId===value)?.displayName||'');}).catch(()=>{});return()=>{active=false;};},[project,value,name]);
  useEffect(()=>{generation.current++;if(!open)return;let active=true;setLoading(true);setError('');setUsers([]);setIndex(-1);const timer=setTimeout(()=>{api<Page>(`/integrations/jira/projects/${project}/assignees?query=${encodeURIComponent(query)}&startAt=0`).then(p=>{if(active){setUsers(p.users||[]);setMore(!!p.hasMore);setNext(p.nextStartAt||0);}}).catch(e=>{if(active)setError(e.message);}).finally(()=>{if(active)setLoading(false);});},250);return()=>{active=false;clearTimeout(timer);};},[open,query,project,retry]);
  useEffect(()=>{if(disabled)setOpen(false);},[disabled]);
  async function loadMore(){const token=generation.current;setLoading(true);setError('');try{const p=await api<Page>(`/integrations/jira/projects/${project}/assignees?query=${encodeURIComponent(query)}&startAt=${next}`);if(token!==generation.current)return;setUsers(prev=>[...new Map([...prev,...p.users].map(u=>[u.accountId,u])).values()]);setNext(p.nextStartAt);setMore(p.hasMore);}catch(e){if(token===generation.current)setError((e as Error).message);}finally{if(token===generation.current)setLoading(false);}}
  function choose(user:JiraUser){onChange(user);setOpen(false);setQuery('');}
  return <div className="assignee-select" onBlur={e=>{if(!e.currentTarget.contains(e.relatedTarget))setOpen(false);}}>
    <label htmlFor={id}>담당자</label>
    <div className="assignee-input"><input id={id} role="combobox" aria-label="담당자 검색" aria-expanded={open} aria-controls={`${id}-list`} aria-autocomplete="list" aria-activedescendant={index>=0?`${id}-option-${index}`:undefined} disabled={disabled} placeholder="프로젝트 담당자 검색·선택" value={open?query:(name||resolved||(value?'현재 담당자 · '+value:''))} onFocus={()=>{setOpen(true);setQuery('');}} onChange={e=>{setQuery(e.target.value);setOpen(true);}} onKeyDown={e=>{
      if(e.key==='Escape'&&open){e.preventDefault();e.stopPropagation();setOpen(false);return;}
      if(e.key==='ArrowDown'||e.key==='ArrowUp'){e.preventDefault();setOpen(true);setIndex(i=>users.length?(e.key==='ArrowDown'?(i+1)%users.length:(i-1+users.length)%users.length):-1);}
      if(e.key==='Enter'&&open){e.preventDefault();if(index>=0&&users[index])choose(users[index]);}
    }}/>{value&&<button type="button" aria-label="담당자 지정 해제" disabled={disabled} onClick={()=>{onChange(null);setOpen(false);}}>×</button>}</div>
    {open&&<div className="assignee-options"><div role="listbox" id={`${id}-list`} aria-label="프로젝트 담당자 목록">{users.map((u,i)=><button type="button" role="option" id={`${id}-option-${i}`} aria-selected={value===u.accountId} className={index===i?'keyboard-selected':''} key={u.accountId} onMouseDown={e=>e.preventDefault()} onClick={()=>choose(u)}><span className="assignee-avatar">{u.displayName.slice(0,1)}</span><span>{u.displayName}<small>{value===u.accountId?'현재 담당자':'프로젝트에 할당 가능'}</small></span></button>)}</div>{loading&&<p role="status">담당자를 불러오는 중…</p>}{error&&<div role="alert"><p>{error}</p><button type="button" onClick={()=>setRetry(n=>n+1)}>다시 조회</button></div>}{!loading&&!error&&!users.length&&<p>검색 결과가 없습니다.</p>}{more&&!loading&&<button type="button" onClick={()=>void loadMore()}>담당자 더 보기</button>}</div>}
  </div>;
}
