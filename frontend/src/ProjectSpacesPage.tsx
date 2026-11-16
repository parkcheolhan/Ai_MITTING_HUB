import {useEffect,useState,type FormEvent} from "react";
import {Link,useSearchParams} from "react-router-dom";
import {Plus,Layers,ArrowRight} from "lucide-react";
import {api} from "./api";
import {useJiraSettings,type Project} from "./integrations";

export type ProjectSpace={key:string;name:string;description:string;meetingCount:number;createdAt:string};
export default function ProjectSpacesPage(){
  const jira=useJiraSettings();const [params]=useSearchParams();
  const [spaces,setSpaces]=useState<ProjectSpace[]>([]),[key,setKey]=useState(""),[name,setName]=useState(""),[description,setDescription]=useState("");
  const [editing,setEditing]=useState(false),[busy,setBusy]=useState(false),[loading,setLoading]=useState(true),[error,setError]=useState(""),[message,setMessage]=useState("");
  const [jiraProjects,setJiraProjects]=useState<Project[]>([]),[importing,setImporting]=useState(false),[jiraError,setJiraError]=useState("");
  const [savedKey,setSavedKey]=useState("");
  const returnTo=params.get("returnTo")==="new";
  async function load(){try{setSpaces(await api<ProjectSpace[]>("/projects"));}catch(e){setError((e as Error).message);}finally{setLoading(false);}}
  useEffect(()=>{void load();},[]);
  function reset(){setEditing(false);setKey("");setName("");setDescription("");setError("");setMessage("");}
  async function save(e:FormEvent){
    e.preventDefault();setBusy(true);setError("");setMessage("");
    try{await api(editing?`/projects/${key}`:"/projects",editing?"PUT":"POST",{key,name,description});setSavedKey(key);await load();setEditing(false);setKey("");setName("");setDescription("");setMessage(editing?"프로젝트 정보를 수정했습니다.":"프로젝트를 등록했습니다. 새 회의에서 선택할 수 있습니다.");}
    catch(e){setError((e as Error).message);}finally{setBusy(false);}
  }
  async function fetchJira(){setImporting(true);setJiraError("");try{setJiraProjects(await api<Project[]>("/integrations/jira/projects"));}catch(e){setJiraError((e as Error).message);}finally{setImporting(false);}}
  return <>
    <div className="page-heading"><div><span className="eyebrow">PROJECTS / SPACES</span><h1>프로젝트(스페이스) 관리</h1><p>회의를 묶을 프로젝트를 등록하고 관리하세요.</p></div><Link className="button" to={returnTo?"/meetings/new":"/meetings"}>{returnTo?"회의 등록으로 돌아가기":"회의 목록으로 돌아가기"}<ArrowRight size={16}/></Link></div>
    <div className="project-space-grid">
      <section className="panel padded">
        <div className="section-head"><h2><Plus size={18}/>{editing?"프로젝트 정보 수정":"프로젝트(스페이스) 등록"}</h2></div>
        <form onSubmit={save}><fieldset disabled={busy}><div className="form-grid">
          <label className="full">프로젝트 이름<input aria-label="프로젝트 이름" required maxLength={100} value={name} onChange={e=>setName(e.target.value)} placeholder="프로젝트 이름을 입력하세요"/></label>
          <label className="full">프로젝트 키<input aria-label="프로젝트 키" required pattern="[A-Z][A-Z0-9_]{0,19}" maxLength={20} disabled={editing} value={key} onChange={e=>setKey(e.target.value.toUpperCase())} placeholder="영문 대문자로 시작하는 고유 키"/></label>
          <label className="full">프로젝트 설명<textarea aria-label="프로젝트 설명" rows={4} maxLength={2000} value={description} onChange={e=>setDescription(e.target.value)}/></label>
        </div><div className="actions"><button type="submit" className="primary">{editing?"프로젝트 변경 저장":"프로젝트 등록"}</button>{editing&&<button type="button" onClick={reset}>수정 취소</button>}</div></fieldset></form>
        {error&&<div className="error" role="alert">{error}</div>}{message&&<p role="status" className="help">{message}</p>}
        {returnTo&&message&&<Link className="button primary" to={`/meetings/new?project=${encodeURIComponent(savedKey)}`}>등록한 프로젝트로 회의 작성<ArrowRight size={16}/></Link>}
      </section>
      <section className="panel padded"><div className="section-head"><h2>기존 Jira 프로젝트 가져오기</h2></div><p className="help">Jira에서 사용 중인 프로젝트를 선택하면 이름과 키를 채워 줍니다. 내용을 확인하고 프로젝트 등록을 누르세요.</p>
        {jira.mode==="cloud"&&jira.configured?<><button disabled={importing||busy} onClick={()=>void fetchJira()}>{importing?"불러오는 중…":"Jira 프로젝트 불러오기"}</button>{jiraProjects.length>0&&<label className="project-space-import">Jira 프로젝트<select aria-label="가져올 Jira 프로젝트" defaultValue="" onChange={e=>{const p=jiraProjects.find(p=>p.key===e.target.value);if(p){reset();setKey(p.key);setName(p.name);}}}><option value="">가져올 프로젝트 선택</option>{jiraProjects.map(p=><option key={p.key} value={p.key} disabled={spaces.some(s=>s.key===p.key)}>{p.key} · {p.name}{spaces.some(s=>s.key===p.key)?" · 등록됨":""}</option>)}</select></label>}</>:<p className="help"><Link to="/settings">설정에서 실제 Jira를 연결</Link>하면 프로젝트를 가져올 수 있습니다.</p>}
        {jiraError&&<p className="error" role="alert">{jiraError}</p>}
        <p className="help">이 화면은 회의 관리용 프로젝트를 저장합니다. 실제 Jira 티켓을 발급하려면 Jira에도 동일한 키의 프로젝트가 있어야 합니다.</p>
      </section>
    </div>
    <section className="panel padded project-space-list"><div className="section-head"><h2><Layers size={18}/>등록한 프로젝트 <span className="tag">{spaces.length}개</span></h2></div>
      {loading?<p className="help">프로젝트를 불러오고 있습니다.</p>:spaces.length===0?<div className="empty"><Layers size={28}/><p>등록한 프로젝트가 없습니다. 위에서 첫 프로젝트를 등록하세요.</p></div>:<div className="project-space-cards">{spaces.map(p=><article className="project-space-card" key={p.key}><span className="project-chip">{p.key}</span><h3>{p.name}</h3><p>{p.description||"설명 없음"}</p><small>등록된 회의 {p.meetingCount}건</small><div className="actions"><Link className="button" to={`/meetings?project=${encodeURIComponent(p.key)}`}>회의 보기</Link><button onClick={()=>{setEditing(true);setKey(p.key);setName(p.name);setDescription(p.description);setError("");setMessage("");window.scrollTo({top:0,behavior:"smooth"});}}>정보 수정</button><Link className="button" to={`/meetings/new?project=${encodeURIComponent(p.key)}`}>새 회의</Link></div></article>)}</div>}
    </section>
  </>;
}
