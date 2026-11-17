import {useEffect,useState,type FormEvent} from 'react';
import {Link,useLocation} from 'react-router-dom';
import {ExternalLink,Settings} from 'lucide-react';
import {api} from './api';
import type {Meeting} from './types';

type SettingsView={enabled:boolean;useJiraCredentials:boolean;siteUrl:string;email:string;cloudId:string;tokenConfigured:boolean;connected:boolean;configured:boolean;spaceId:string;spaceName:string;parentPageId:string;parentTitle:string;automatic:boolean};
type Space={id:string;name:string;key:string};type Page={id:string;title:string};type Listing<T>={results:T[];nextCursor:string};
type Publication={configured:boolean;automatic:boolean;status:string;pageUrl:string;error:string;syncedAt:string;spaceName:string;parentTitle:string};
const empty={enabled:false,useJiraCredentials:true,siteUrl:'',email:'',apiToken:'',cloudId:''};
export function ConfluenceSettingsCard(){
  const location=useLocation();
  const [view,setView]=useState<SettingsView|null>(null),[form,setForm]=useState(empty),[dirty,setDirty]=useState(false);
  const [space,setSpace]=useState(''),[parent,setParent]=useState(''),[automatic,setAutomatic]=useState(true);
  const [spaces,setSpaces]=useState<Space[]>([]),[spaceCursor,setSpaceCursor]=useState(''),[pages,setPages]=useState<Page[]>([]),[pageCursor,setPageCursor]=useState(''),[query,setQuery]=useState('');
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState('');
  function loaded(v:SettingsView){setView(v);setForm({enabled:v.enabled,useJiraCredentials:v.useJiraCredentials,siteUrl:v.siteUrl,email:v.email,apiToken:'',cloudId:v.cloudId});setSpace(v.spaceId);setParent(v.parentPageId);setAutomatic(v.automatic);setDirty(false);}
  useEffect(()=>{let live=true;api<SettingsView>('/settings/confluence').then(v=>{if(live)loaded(v);}).catch(e=>{if(live)setError(e.message);});return()=>{live=false;};},[]);
  useEffect(()=>{if(view&&new URLSearchParams(location.search).get('section')==='minutes')document.getElementById('minutes-settings')?.scrollIntoView({block:'start'});},[!!view,location.search]);
  function change<K extends keyof typeof form>(key:K,value:(typeof form)[K]){setForm(f=>({...f,[key]:value}));setDirty(true);setMessage('');}
  async function run(action:()=>Promise<void>){setBusy(true);setError('');setMessage('');try{await action();}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
  async function getSpaces(cursor=''){const result=await api<Listing<Space>>(`/integrations/confluence/spaces?cursor=${encodeURIComponent(cursor)}`);setSpaces(old=>cursor?[...old,...result.results.filter(s=>!old.some(o=>o.id===s.id))]:result.results);setSpaceCursor(result.nextCursor);}
  function connection(e:FormEvent){e.preventDefault();void run(async()=>{const v=await api<SettingsView>('/settings/confluence/connection','PUT',form);loaded(v);setSpaces([]);setPages([]);if(v.connected){await getSpaces();setMessage('연결 설정을 저장하고 스페이스를 불러왔습니다. 업로드 위치를 선택한 뒤 저장하세요.');}else setMessage('Confluence 연결을 해제했습니다.');});}
  function destination(e:FormEvent){e.preventDefault();void run(async()=>{const v=await api<SettingsView>('/settings/confluence/destination','PUT',{spaceId:space,parentPageId:parent,automatic});loaded(v);setMessage('회의록 업로드 위치를 저장했습니다. 기존 회의록은 회의록 화면에서 업로드할 수 있습니다.');window.dispatchEvent(new Event('moa-confluence-settings'));});}
  function getPages(cursor=''){void run(async()=>{const result=await api<Listing<Page>>(`/integrations/confluence/spaces/${space}/pages?title=${encodeURIComponent(query)}&cursor=${encodeURIComponent(cursor)}`);setPages(old=>cursor?[...old,...result.results.filter(p=>!old.some(o=>o.id===p.id))]:result.results);setPageCursor(result.nextCursor);if(!result.results.length)setMessage('조회된 페이지가 없습니다. 페이지 ID 또는 URL을 직접 입력할 수도 있습니다.');});}
  return <section id="minutes-settings" className="panel padded confluence-settings"><div className="section-head"><div><span className="eyebrow">CONFLUENCE CLOUD</span><h2>회의록 설정</h2><p>회의록과 생성된 Jira 티켓 목록을 Confluence 문서로 관리합니다.</p></div><span className="badge">{view?.configured?'업로드 위치 지정됨':'설정 필요'}</span></div>
    {error&&<div className="error" role="alert">{error}</div>}{message&&<p className="help" role="status">{message}</p>}
    <div className="confluence-settings-grid"><form onSubmit={connection}><h3>Confluence 연결</h3><fieldset disabled={busy||!view}>
      <label className="check"><input type="checkbox" checked={form.enabled} onChange={e=>change('enabled',e.target.checked)}/>Confluence 회의록 업로드 사용</label>
      <label className="check"><input type="checkbox" checked={form.useJiraCredentials} onChange={e=>change('useJiraCredentials',e.target.checked)}/>저장된 Jira Cloud 계정 사용</label>
      {form.useJiraCredentials?<p className="help">Jira에 저장한 사이트·이메일·API 토큰으로 연결합니다. 해당 계정과 토큰에 Confluence 읽기·페이지 쓰기 권한이 있어야 합니다.</p>:<>
        <label>Confluence 사이트 URL<input type="url" required={form.enabled} placeholder="https://team.atlassian.net" value={form.siteUrl} onChange={e=>change('siteUrl',e.target.value)}/></label>
        <label>계정 이메일<input type="email" required={form.enabled} autoComplete="off" value={form.email} onChange={e=>change('email',e.target.value)}/></label>
        <label>API 토큰<input type="password" autoComplete="new-password" placeholder={!view?.useJiraCredentials&&view?.tokenConfigured?'저장됨 · 변경할 때만 입력':'API 토큰 입력'} value={form.apiToken} onChange={e=>change('apiToken',e.target.value)}/></label>
        <label>Cloud ID <span className="help">권한 범위가 있는 API 토큰 사용 시 입력</span><input value={form.cloudId} onChange={e=>change('cloudId',e.target.value)}/></label>
      </>}
      <div className="actions"><button type="submit" className="primary">{busy?'처리 중…':'연결 설정 저장'}</button><button type="button" disabled={!view?.connected||dirty} onClick={()=>void run(()=>getSpaces())}>연결 테스트 · 스페이스 조회</button></div>
    </fieldset></form>
    <form onSubmit={destination}><h3>회의록 업로드 위치</h3><fieldset disabled={busy||!view?.connected||dirty}>
      <label>스페이스<select aria-label="스페이스" required value={space} onChange={e=>{setSpace(e.target.value);setParent('');setPages([]);setPageCursor('');setQuery('');}}><option value="">스페이스 선택</option>{space&&!spaces.some(s=>s.id===space)&&<option value={space}>{view?.spaceName||space}</option>}{spaces.map(s=><option key={s.id} value={s.id}>{s.name} ({s.key})</option>)}</select></label>
      {spaceCursor&&<button type="button" onClick={()=>void run(()=>getSpaces(spaceCursor))}>스페이스 더 보기</button>}
      <label>상위 페이지 ID 또는 URL <span className="help">비워 두면 스페이스 최상위에 업로드</span><input value={parent} onChange={e=>setParent(e.target.value)} placeholder="페이지 ID 또는 https://…/pages/…"/></label>
      {parent===view?.parentPageId&&view?.parentTitle&&<p className="help">선택된 상위 페이지: {view.parentTitle}</p>}
      <div className="confluence-page-search"><label>상위 페이지 찾기<input value={query} onChange={e=>setQuery(e.target.value)} placeholder="전체 페이지 제목 입력 · 비우면 목록 조회"/></label><button type="button" disabled={!space} onClick={()=>getPages()}>페이지 조회</button></div>
      {!!pages.length&&<label>조회된 상위 페이지<select aria-label="조회된 상위 페이지" value={pages.some(p=>p.id===parent)?parent:''} onChange={e=>setParent(e.target.value)}><option value="">페이지 선택</option>{pages.map(p=><option value={p.id} key={p.id}>{p.title}</option>)}</select></label>}
      {pageCursor&&<button type="button" onClick={()=>getPages(pageCursor)}>페이지 더 보기</button>}
      <label className="check"><input type="checkbox" checked={automatic} onChange={e=>setAutomatic(e.target.checked)}/>회의록 저장·생성 및 Jira 티켓 등록 후 자동 동기화</label>
      <p className="help">같은 업로드 위치에서는 문서를 갱신합니다. 위치를 변경하면 새 위치에 문서를 생성합니다.</p><button type="submit" className="primary" disabled={!space}>업로드 위치 저장</button>
    </fieldset>{dirty&&<p className="help">변경한 연결 설정을 먼저 저장하세요.</p>}{!view?.connected&&<p className="help">Confluence 연결 설정을 저장한 뒤 업로드 위치를 지정하세요.</p>}</form></div>
  </section>;
}

export function ConfluenceMinutes({meeting,disabled}:{meeting:Meeting;disabled:boolean}){
  const [view,setView]=useState<Publication|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const signature=meeting.drafts.map(d=>`${d.id}:${d.status}:${d.issueKey}`).join('|');
  useEffect(()=>{let live=true;function refresh(){api<Publication>(`/meetings/${meeting.id}/confluence`).then(v=>{if(live){setView(v);setError('');}}).catch(e=>{if(live)setError(e.message);});}refresh();window.addEventListener('moa-confluence-settings',refresh);return()=>{live=false;window.removeEventListener('moa-confluence-settings',refresh);};},[meeting.id,meeting.content.minutes?.version,signature]);
  async function publish(){setBusy(true);setError('');try{setView(await api<Publication>(`/meetings/${meeting.id}/confluence/publish`,'POST'));}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
  return <section className="confluence-minutes" aria-label="Confluence 회의록 업로드"><div><strong>Confluence 회의록</strong><p>{view?.configured?`${view.spaceName}${view.parentTitle?' / '+view.parentTitle:' / 최상위'} · ${view.automatic?'자동 동기화':'수동 업로드'}`:'회의록 설정에서 업로드할 스페이스와 상위 페이지를 지정하세요.'}</p>{view?.syncedAt&&<p>최근 동기화: {new Date(view.syncedAt).toLocaleString('ko-KR')}</p>}{view?.status==='PUBLISHED'&&<span className="badge">업로드 완료</span>}{view?.status==='UNKNOWN'&&<span className="badge">업로드 결과 확인 필요</span>}</div><div className="actions"><Link to="/settings?section=minutes"><Settings size={15}/>회의록 설정</Link>{view?.pageUrl&&<a href={view.pageUrl} target="_blank" rel="noopener noreferrer"><ExternalLink size={15}/>Confluence에서 보기</a>}<button className="primary" disabled={disabled||busy||!meeting.content.minutes||!view?.configured} onClick={()=>void publish()}>{busy?'업로드 중…':view?.pageUrl?'Confluence 동기화':'Confluence에 업로드'}</button></div>{(error||view?.error)&&<div className="error" role="alert">{error||view?.error}</div>}</section>;
}
