import NewMeeting from "./MeetingForm";
import AuthenticatedVideo from './AuthenticatedVideo';
import AnalysisFlowControls from './AnalysisFlowControls';
import {standalone} from './standalone';
import StandaloneNotice from './StandaloneNotice';
import SetupTutorial, {tutorialNeeded} from "./SetupTutorial";
import JobSteps from "./JobSteps";
import MinutesDocument, { minutesText } from "./MinutesDocument";
import TranscriptView from "./TranscriptView";
import SpeechRetryUpload from "./SpeechRetryUpload";
import AssigneeSelect, {type JiraUser} from "./AssigneeSelect";
import AutoSizeTextarea from "./AutoSizeTextarea";
import SchedulePanel, {displayDateTime,localDateTime,offsetDateTime} from "./SchedulePanel";
import {ensureAiSchedule} from "./ensureAiSchedule";
import { MinutesTemplateDialog, downloadText } from "./MinutesTemplate";
import SettingsPage from "./JiraSettingsPage";
import ProjectSpacesPage, {type ProjectSpace} from "./ProjectSpacesPage";
import { useAiSettings } from "./ai";
import {
  useJiraSettings,
  type IssueType,
  type Project,
  type JiraField,
} from "./integrations";
import {
  useState,
  useEffect,
  useCallback,
  useRef,
  type ReactNode,
} from "react";
import {
  NavLink,
  Link,
  Routes,
  Route,
  useNavigate,
  useParams,
  useLocation,
  Navigate,
} from "react-router-dom";
import {
  AudioLines,
  LayoutDashboard,
  CalendarDays,
  Layers,
  Settings,
  Plus,
  ArrowUpRight,
  ArrowRight,
  Search,
  ChevronRight,
  Check,
  Clock,
  FileText,
  ShieldCheck,
  GitBranch,
  RefreshCw,
  Link2,
  CheckCheck,
  AlertCircle,
} from "lucide-react";
import { api } from "./api";
import {
  type Meeting,
  type Draft,
  type Minutes,
  type Item,
  statusName,
  time,
  activeJob,
} from "./types";

function Badge({ status }: { status: string }) {
  return (
    <span className={"badge " + status.toLowerCase()}>
      <i />
      {statusName[status] || status}
    </span>
  );
}
function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="empty">
      <FileText size={30} />
      <p>{children}</p>
    </div>
  );
}
function useRemote<T>(path: string, initial: T, poll = false) {
  const [data, setData] = useState(initial),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true);
  const reload = useCallback(async () => {
    try {
      setData(await api<T>(path));
      setError("");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [path]);
  useEffect(() => {
    setLoading(true);
    void reload();
    if (poll) {
      const id = setInterval(reload, 1500);
      return () => clearInterval(id);
    }
  }, [reload, poll]);
  return { data, error, loading, reload };
}
function ErrorBox({ error }: { error: string }) {
  return error ? (
    <div role="alert" className="error">
      <AlertCircle size={16} />
      {error}
    </div>
  ) : null;
}
function PageHeading({
  eyebrow,
  title,
  description,
  children,
}: {
  eyebrow: string;
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <span className="eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <div>{children}</div>
    </div>
  );
}

export default function App() {
  const [tutorialOpen,setTutorialOpen]=useState(()=>!standalone&&tutorialNeeded());
  const jiraSettings = useJiraSettings();
  const aiSetup=useAiSettings();
  const setupRequired=!!jiraSettings.ready&&!!aiSetup.ready&&(!jiraSettings.configured||jiraSettings.mode==="mock"||!aiSetup.configured||aiSetup.mode==="mock");
  const location = useLocation(),
    navigate = useNavigate();
  const [search, setSearch] = useState("");
  const title = location.pathname.includes("settings")
    ? "설정"
    : location.pathname.includes("tickets")
      ? "JIRA 티켓 관리"
      : location.pathname.includes("meetings")
        ? "회의 관리"
        : "대시보드";
  return (
    <div className="app">
      <aside className="sidebar">
        <Link to="/dashboard" className="brand">
          <span>
            <AudioLines size={25} />
          </span>
          모아<span className="brand-dot">.</span>
        </Link>
        <Link to="/settings" className="workspace" aria-label="워크스페이스 설정">
          <span className="workspace-icon">M</span>
          <div>
            우리 팀 워크스페이스<small>Meeting workspace</small>
          </div>
        </Link>
        <span className="nav-label">WORKSPACE</span>
        <nav>
          {[
            [LayoutDashboard, "대시보드", "/dashboard"],
            [CalendarDays, "회의 관리", "/meetings"],
            [Settings, "설정", "/settings"],
          ].map(([Icon, label, path]) => {
            const I = Icon as typeof Settings;
            return (
              <NavLink
                key={String(path)}
                to={String(path)}
                className={({ isActive }) => (isActive ? "active" : "")}
              >
                <I size={19} />
                {String(label)}
                {path === "/meetings" && <span className="nav-mark" />}
              </NavLink>
            );
          })}
        </nav>
        <div className="sidebar-bottom">
          {setupRequired&&<button className="setup-guide-trigger" onClick={()=>setTutorialOpen(true)}>설정 가이드</button>}
          <div className="demo-label">
            <span className="pulse" />
            {jiraSettings.mode === "cloud" ? "JIRA CLOUD" : "MOCK WORKSPACE"}
          </div>
          <p>
            아이디어가 실행이 되는 곳.
            <br />
            회의의 다음 단계를 모아보세요.
          </p>
          <div className="profile">
            <span className="avatar">나</span>
            <div>
              로컬 데모 사용자<small>개인 워크스페이스</small>
            </div>
          </div>
        </div>
      </aside>
      <div className="main">
        <header>
          <div className="breadcrumb">
            워크스페이스
            <ChevronRight size={14} />
            <strong>{title}</strong>
          </div>
          <form
            className="header-search"
            onSubmit={(e) => {
              e.preventDefault();
              navigate("/meetings?q=" + encodeURIComponent(search));
            }}
          >
            <Search size={16} />
            <input
              aria-label="회의 검색"
              placeholder="회의 검색..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <kbd>↵</kbd>
          </form>
          {jiraSettings.mode === "cloud" && jiraSettings.configured && /^https:\/\/[a-zA-Z0-9-]+\.atlassian\.net\/?$/.test(jiraSettings.siteUrl) && (
            <a className="button jira-home-link" aria-label="Jira 홈페이지 열기" title="Jira 홈페이지 열기" href={jiraSettings.siteUrl} target="_blank" rel="noopener noreferrer">
              <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true" fill="#1868db"><path d="M12 2 2 12l10 10 10-10L12 2Zm0 6 4 4-4 4-4-4 4-4Z"/></svg>
            </a>
          )}
          <span className="avatar small">나</span>
        </header>
        <main>
          {standalone&&<StandaloneNotice/>}
          {tutorialOpen&&setupRequired&&<SetupTutorial close={()=>setTutorialOpen(false)}/>}
          <Routes>
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/meetings" element={<MeetingsPage />} />
            <Route path="/meetings/new" element={<NewMeeting />} />
            <Route path="/meetings/projects" element={<ProjectSpacesPage />} />
            <Route path="/meetings/:id/*" element={<MeetingDetail />} />
            <Route path="/tickets" element={<LegacyTicketsRedirect />} />
            <Route path="/settings" element={standalone?<section className="panel padded"><h1>HTML 로컬 버전 설정</h1><p>데이터는 현재 브라우저에 저장됩니다. 상단에서 백업과 복원을 사용할 수 있습니다.</p><p>AI·Jira 인증 정보는 이 버전에 입력하지 않습니다. 실제 AI 분석·음성 변환·Jira 등록에는 Java 서버 버전을 사용하세요.</p></section>:<SettingsPage />} />
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
          <footer>
            <span>모아 · AI Meeting Hub</span>
            <span>회의를 기록하고, 다음 행동을 연결합니다.</span>
          </footer>
        </main>
      </div>
    </div>
  );
}

function Dashboard() {
  const {
    data: meetings,
    error,
    reload,
  } = useRemote<Meeting[]>("/meetings", [], true);
  const [project, setProject] = useState(""),
    [status, setStatus] = useState(""),
    [period, setPeriod] = useState("all");
  const visible = meetings.filter(
    (m) =>
      (!project || m.content.projectKey === project) &&
      (!status || m.status === status) &&
      (period === "all" ||
        Date.now() - Date.parse(m.createdAt) < Number(period) * 86400000),
  );
  const drafts = visible.flatMap((m) => m.drafts),
    pending = visible.filter((m) => m.status === "REVIEW_PENDING").length;
  return (
    <>
      <PageHeading
        eyebrow="YOUR MEETINGS, IN MOTION"
        title="회의의 끝, 실행의 시작"
        description="흩어진 논의는 모으고, 중요한 다음 단계는 놓치지 마세요."
      >
        <Link className="button primary" to="/meetings/new">
          <Plus size={17} />새 회의 등록
        </Link>
      </PageHeading>
      <ErrorBox error={error} />
      {error && <button onClick={reload}>다시 연결</button>}
      <section className="hero">
        <div>
          <span className="hero-label">
            <span className="pulse" />
            MEETING TO ACTION
          </span>
          <h2>
            좋은 회의가
            <br />
            좋은 실행으로 이어지도록.
          </h2>
          <p>
            회의록부터 업무 정리, JIRA 티켓까지.
            <br />
            모아가 다음 단계를 함께 정리해요.
          </p>
          <Link
            to={meetings[0] ? `/meetings/${meetings[0].id}` : "/meetings/new"}
          >
            {meetings[0] ? "최근 회의 살펴보기" : "첫 회의 등록하기"}
            <ArrowRight size={16} />
          </Link>
        </div>
        <div className="hero-art" aria-hidden="true">
          <div className="orbit orbit-one" />
          <div className="orbit orbit-two" />
          <div className="art-card audio-card">
            <span className="art-icon">
              <AudioLines size={19} />
            </span>
            <div>
              <b>팀의 대화를 모아</b>
              <small>함께 나눈 아이디어</small>
            </div>
            <div className="wave">▂▅▃▇▅▂▆▃</div>
          </div>
          <div className="art-connector">··················· ↓</div>
          <div className="art-card action-card">
            <span className="art-icon green">
              <CheckCheck size={20} />
            </span>
            <div>
              <b>실행할 일로 연결해요</b>
              <small>명확한 업무와 확인 사항</small>
            </div>
            <span className="art-check">✓</span>
          </div>
          <span className="spark s1">✧</span>
          <span className="spark s2">✧</span>
        </div>
        <span className="hero-index">01 — WORK SMARTER, TOGETHER</span>
      </section>
      <div className="stats">
        {[
          [CalendarDays, "전체 회의", visible.length, "함께 쌓아온 대화"],
          [
            AudioLines,
            "분석 진행 중",
            visible.filter((m) => activeJob(m)).length,
            "다음 단계를 정리하고 있어요",
          ],
          [Clock, "검토 대기", pending, "팀의 확인을 기다리고 있어요"],
          [
            Layers,
            "등록된 티켓",
            drafts.filter((d) => d.status === "PUBLISHED").length,
            "등록 완료 티켓",
          ],
        ].map(([Icon, label, value, caption], i) => {
          const I = Icon as typeof Clock;
          return (
            <div className="stat" key={String(label)}>
              <div>
                <span>{String(label)}</span>
                <span className={"stat-icon tone-" + i}>
                  <I size={18} />
                </span>
              </div>
              <strong>
                {String(value)}
                <small>{i === 3 ? "개" : "건"}</small>
              </strong>
              <p>
                <span className="tiny-dot" />
                {String(caption)}
              </p>
            </div>
          );
        })}
      </div>
      <div className="section-head">
        <div>
          <h2>
            최근 회의 <span>{visible.length}</span>
          </h2>
          <p>팀의 대화가 어떻게 실행으로 이어지고 있는지 확인하세요.</p>
        </div>
        <Link to="/meetings" className="text-link">
          전체 보기
          <ArrowUpRight size={16} />
        </Link>
      </div>
      <div className="panel">
        <div className="table-toolbar">
          <div className="tab-mini">
            전체 회의 <span>{visible.length}</span>
          </div>
          <div className="filters">
            <select
              aria-label="기간 필터"
              value={period}
              onChange={(e) => setPeriod(e.target.value)}
            >
              <option value="all">전체 기간</option>
              <option value="7">최근 7일</option>
              <option value="30">최근 30일</option>
            </select>
            <select
              aria-label="프로젝트 필터"
              value={project}
              onChange={(e) => setProject(e.target.value)}
            >
              <option value="">모든 프로젝트</option>
              {[...new Set(meetings.map((m) => m.content.projectKey))].map(
                (p) => (
                  <option key={p}>{p}</option>
                ),
              )}
            </select>
            <select
              aria-label="상태 필터"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">모든 상태</option>
              <option value="REVIEW_PENDING">검토 대기</option>
              <option value="COMPLETED">완료</option>
              <option value="FAILED">실패</option>
            </select>
          </div>
        </div>
        <MeetingTable meetings={visible.slice(0, 6)} />
      </div>
      <div className="bottom-grid">
        <div className="tip-card">
          <span className="round-icon">
            <ShieldCheck size={22} />
          </span>
          <div>
            <h3>등록 전, 한 번 더 확인하세요</h3>
            <p>AI가 제안한 업무를 검토하고 직접 승인한 티켓만 등록돼요.</p>
          </div>
          <span className="tip-pill">검토 후 승인</span>
        </div>
        <Link className="workflow-card" to="/settings">
          <GitBranch size={22} />
          <div>
            <h3>우리 팀에 맞는 워크플로</h3>
            <p>연동 설정과 구현 상태 확인하기</p>
          </div>
          <ArrowUpRight size={20} />
        </Link>
      </div>
    </>
  );
}
function ProcessingStatus({meeting,compact=false}:{meeting:Meeting;compact?:boolean}) {
  const job=activeJob(meeting);
  if(!job)return null;
  const title=job.status==="QUEUED"?"AI 처리 대기 중":job.type==="SCHEDULE"?(job.stage==="ALLOCATING_SCHEDULE"?"담당자별 시작·종료 시각 배정 중":"AI가 작업시간·우선순위·선행관계 분석 중"):meeting.status==="REVIEWING_TRANSCRIPT"?"AI가 음성 기록 문맥 교정 중":meeting.status==="IDENTIFYING_SPEAKERS"?"음성별 화자 분석 중":meeting.status==="TRANSCRIBING"?"음성을 텍스트로 변환 중":meeting.status==="GENERATING_MINUTES"?"AI가 회의록 작성 중":meeting.status==="GENERATING_TICKETS"?"AI가 Jira 티켓 정리 중":"AI가 업무 분석 중";
  return <div className={"processing-status "+(compact?"compact":"")} role="status" aria-live="polite">
    <RefreshCw size={compact?16:24} className="processing-spinner" aria-hidden="true"/>
    <div><strong>{title}</strong>{!compact&&<p>처리가 진행 중입니다. 완료되면 결과가 자동으로 표시됩니다.</p>}
      <progress aria-label={title}/>
      {!compact&&<JobSteps meeting={meeting}/>}
    </div>
  </div>;
}
function MeetingTable({ meetings }: { meetings: Meeting[] }) {
  return meetings.length ? (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>회의명</th>
            <th>회의 일시</th>
            <th>프로젝트</th>
            <th>진행 상태</th>
            <th>추출 업무</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {meetings.map((m) => (
            <tr key={m.id}>
              <td>
                <Link to={`/meetings/${m.id}`} className="meeting-name">
                  <span className="file-icon">
                    <FileText size={19} />
                  </span>
                  <span>
                    {m.title}
                    <small>
                      {m.content.participants.join(", ") || "참석자 미입력"}
                    </small>
                  </span>
                </Link>
              </td>
              <td>
                {m.content.occurredAt?.replace("T", " · ") || "일시 미입력"}
              </td>
              <td>
                <span className="project-chip">{m.content.projectKey}</span>
              </td>
              <td>
                {activeJob(m)?<ProcessingStatus meeting={m} compact/>:<Badge status={m.status} />}
              </td>
              <td>
                <strong className="count">{m.content.items.length}</strong>
                <span className="muted">개</span>
              </td>
              <td>
                <Link aria-label={`${m.title} 열기`} to={`/meetings/${m.id}`}>
                  <ChevronRight size={17} />
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  ) : (
    <Empty>표시할 회의가 없습니다. 새 회의를 등록해 보세요.</Empty>
  );
}
function MeetingsPage() {
  const { data, error } = useRemote<Meeting[]>("/meetings", [], true);
  const {data: spaces} = useRemote<ProjectSpace[]>("/projects", []);
  const location = useLocation();
  const [q, setQ] = useState(
    new URLSearchParams(location.search).get("q") || "",
  );
  const [status, setStatus] = useState("");
  const [searchText,setSearchText]=useState(q);
  useEffect(()=>setSearchText(q),[q]);
  const [project,setProject]=useState(new URLSearchParams(location.search).get("project")||"");
  useEffect(()=>setProject(new URLSearchParams(location.search).get("project")||""),[location.search]);
  useEffect(
    () => setQ(new URLSearchParams(location.search).get("q") || ""),
    [location.search],
  );
  return (
    <>
      <PageHeading
        eyebrow="MEETINGS"
        title="우리 팀의 회의를 모아"
        description="회의 기록부터 실행 항목까지 한곳에서 관리하세요."
      >
        <Link className="button" to="/meetings/projects"><Layers size={17}/>프로젝트(스페이스) 관리</Link>
        <Link className="button primary" to="/meetings/new">
          <Plus size={17} />새 회의 등록
        </Link>
      </PageHeading>
      <ErrorBox error={error} />
      {data.some(m=>activeJob(m))&&<div className="processing-overview" role="status"><RefreshCw size={22} className="processing-spinner" aria-hidden="true"/><div><strong>AI가 회의 자료 {data.filter(m=>activeJob(m)).length}건을 처리하고 있습니다</strong><p>음성 변환·회의록 작성·업무 분석 진행 상태를 아래 목록에서 확인하세요. 완료되면 자동으로 갱신됩니다.</p></div></div>}
      <div className="panel">
        <form className="table-toolbar meeting-search-toolbar" onSubmit={e=>{e.preventDefault();setQ(searchText);}}>
          <div className="meeting-search-filters">
          <select
            aria-label="회의 프로젝트"
            value={project}
            onChange={e=>setProject(e.target.value)}
          >
            <option value="">전체 프로젝트</option>
            {[...new Set([...spaces.map(p=>p.key),...data.map(m=>m.content.projectKey),...(project?[project]:[])])].map(key=><option key={key} value={key}>{spaces.find(p=>p.key===key)?.name||key} · {key}</option>)}
          </select>
          <select
            aria-label="회의 상태"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">전체 상태</option>
            {[
              "UPLOADED",
              "QUEUED",
              "REVIEW_PENDING",
              "COMPLETED",
              "FAILED",
              "CANCELLED",
            ].map((s) => (
              <option value={s} key={s}>
                {statusName[s]}
              </option>
            ))}
          </select>
          </div>
          <div className="search-input meeting-search-field">
            <input aria-label="회의명 검색" placeholder="회의명, 프로젝트 검색" value={searchText} onChange={e=>setSearchText(e.target.value)} />
          </div>
          <button className="primary meeting-search-submit" type="submit"><Search size={16}/>검색</button>
        </form>
        <MeetingTable
          meetings={data.filter(
            (m) =>
              (m.title + " " + m.content.projectKey + " " + (spaces.find(p=>p.key===m.content.projectKey)?.name||""))
                .toLowerCase()
                .includes(q.toLowerCase()) &&
              (!status || m.status === status) && (!project || m.content.projectKey===project),
          )}
        />
      </div>
    </>
  );
}

function MeetingDetail() {
  const aiSettings = useAiSettings();
  const { id } = useParams();
  const location = useLocation(),
    navigate = useNavigate();
  const {
    data: m,
    error,
    reload,
    loading,
  } = useRemote<Meeting | null>(`/meetings/${id}`, null, true);
  const [actionError, setActionError] = useState(""),
    [busy, setBusy] = useState(false),
    [kind, setKind] = useState(""),
    [focus, setFocus] = useState<Item | null>(null);
  const media = useRef<Record<string, HTMLVideoElement | null>>({});
  const playingAsset = useRef<string|null>(null);
  const clip=useRef<{assetId:string;endMs:number;offset:number}|null>(null);
  const clipTimer=useRef<number|null>(null);
  const stoppedClip=useRef<{assetId:string;time:number}|null>(null);
  function clearClipTimer(){if(clipTimer.current!==null){window.clearTimeout(clipTimer.current);clipTimer.current=null;}}
  useEffect(()=>()=>{clearClipTimer();clip.current=null;stoppedClip.current=null;},[id,location.pathname,location.search]);
  function atStoppedClip(assetId:string,player:HTMLVideoElement){return stoppedClip.current?.assetId===assetId&&player.paused&&Math.abs(player.currentTime-stoppedClip.current.time)<.02;}
  function stopClip(player:HTMLVideoElement){
    const current=clip.current;if(!current)return;
    stoppedClip.current={assetId:current.assetId,time:(current.endMs-current.offset)/1000};
    clip.current=null;clearClipTimer();player.pause();
    player.currentTime=(current.endMs-current.offset)/1000;
    playingAsset.current=null;setPlaybackMs(-1);
  }
  function enforceClip(assetId:string,player:HTMLVideoElement){
    const current=clip.current;
    if(current?.assetId===assetId&&player.currentTime*1000+current.offset>=current.endMs){stopClip(player);return true;}
    return false;
  }
  function scheduleClip(assetId:string,player:HTMLVideoElement){
    const current=clip.current;if(!current||current.assetId!==assetId)return;
    clearClipTimer();if(player.paused)return;
    if(enforceClip(assetId,player))return;
    const remaining=(current.endMs-current.offset-player.currentTime*1000)/Math.max(.1,player.playbackRate);
    clipTimer.current=window.setTimeout(()=>{if(!enforceClip(assetId,player))scheduleClip(assetId,player);},Math.max(10,remaining+5));
  }
  const [playbackMs,setPlaybackMs]=useState(-1);
  useEffect(()=>{playingAsset.current=null;setPlaybackMs(-1);},[id]);
  useEffect(() => {
    setFocus(null);
  }, [m?.content.analysisVersion]);
  const tab = location.pathname.endsWith("/tickets")
    ? "tickets"
    : location.pathname.endsWith("/results")
      ? "results"
      : new URLSearchParams(location.search).get("tab") || "minutes";
  async function action(path: string) {
    setBusy(true);
    setActionError("");
    try {
      await api(path, "POST");
      await reload();
      return true;
    } catch (e) {
      setActionError((e as Error).message);
      return false;
    } finally {
      setBusy(false);
    }
  }
  if (!m)
    return (
      <>
        <ErrorBox error={error} />
        {loading ? (
          <Empty>회의를 불러오는 중입니다.</Empty>
        ) : (
          <Empty>
            회의를 찾을 수 없습니다. <Link to="/meetings">목록으로</Link>
          </Empty>
        )}
      </>
    );
  const job = activeJob(m),
    lastJob = m.jobs[0];
  const steps = [
    "자료 등록",
    "음성 기록",
    "회의록",
    "업무 분석",
    "JIRA 검토",
    "등록 결과",
  ];
  const step =
    tab === "results"
      ? 5
      : tab === "tickets"
        ? 4
        : tab === "analysis"
          ? 3
          : tab === "minutes"
            ? 2
            : 1;
  const sources=m.content.assets.map((asset,index)=>{
    const times=m.content.segments.filter(s=>(m.content.speakerGroups?.[s.id]||"").startsWith(`원본 ${index+1} ·`)).map(s=>s.startMs);
    return {asset,offset:index===0?0:times.length?Math.min(...times):null};
  });
  function seek(ms: number,endMs?:number) {
    const source=[...sources].reverse().find(s=>s.offset!==null&&s.offset<=ms);
    const player=source&&media.current[source.asset.id];
    if(player&&source){
      clearClipTimer();stoppedClip.current=null;const segment=m!.content.segments.find(s=>s.startMs===ms)||m!.content.segments.find(s=>s.startMs<=ms&&s.endMs>ms);
      const stop=endMs??segment?.endMs;const boundary=sources.find(s=>s.offset!==null&&s.offset>source.offset!)?.offset;
      clip.current=stop!==undefined&&stop>ms?{assetId:source.asset.id,endMs:Math.min(stop,boundary??Infinity),offset:source.offset!}:null;
      playingAsset.current=source.asset.id;Object.values(media.current).forEach(p=>{if(p!==player)p?.pause();});
      player.currentTime=(ms-source.offset!)/1000;setPlaybackMs(ms);void player.play().then(()=>scheduleClip(source.asset.id,player)).catch(()=>{});
    }
  }
  return (
    <>
      <Link to="/meetings" className="back">
        ← 회의 목록
      </Link>
      <PageHeading
        eyebrow={m.content.projectKey + " / MEETING DETAIL"}
        title={m.title}
        description={`${m.content.occurredAt.replace("T", " · ")} · ${m.content.participants.join(", ") || "참석자 미입력"}`}
      >
        <Badge status={m.status} />
      </PageHeading>
      <ErrorBox error={error || actionError} />
      <div className="stepper">
        {steps.map((s, i) => (
          <button
            key={s}
            className={i === step ? "current" : i < step ? "done" : ""}
            onClick={() =>
              navigate(
                i === 4
                  ? `/meetings/${id}/tickets`
                  : i === 5
                    ? `/meetings/${id}/results`
                    : `/meetings/${id}?tab=${["transcript", "transcript", "minutes", "analysis"][i]}`,
              )
            }
          >
            <span>{i < step ? <Check size={14} /> : i + 1}</span>
            {s}
            {i < 5 && <ChevronRight size={14} />}
          </button>
        ))}
      </div>
      {!standalone&&<AnalysisFlowControls meeting={m} reload={reload}/>}
      {job ? (
        <div className="job-banner">
          <ProcessingStatus meeting={m}/>
          <button
            onClick={() => action(`/jobs/${job.id}/cancel`)}
            disabled={busy}
          >
            취소
          </button>
        </div>
      ) : lastJob?.status === "FAILED" ? (
        <div className="error">
          {lastJob.errorMessage}
          <button
            onClick={() => action(`/jobs/${lastJob.id}/retry`)}
            disabled={busy}
          >
            재시도
          </button>
        </div>
      ) : null}
      {!job&&lastJob&&<JobSteps meeting={m}/>}
      <SpeechRetryUpload key={m.id} meeting={m} disabled={busy||!!job} reload={reload}/>
      {tab === "transcript" && (
        <div className="panel padded">
          <div className="section-head">
            <div>
              <h2>음성 기록</h2>
              <p>{m.content.source==="MEDIA"?"원본 음성과 화자 구분 결과를 확인하고 참석자를 지정하세요.":"입력된 발언의 화자와 시간 정보를 확인하세요."}</p>
            </div>
            <button
              disabled={busy || !!job}
              onClick={() => action(`/meetings/${id}/transcribe`)}
            >
              <RefreshCw size={15} />
              음성 변환 실행
            </button>
          </div>
          {sources.map(({asset:a,offset}) => (
            <div className="media-player" key={a.id}>
              <p>{a.originalName}</p>
              <AuthenticatedVideo
                ref={node=>{media.current[a.id]=node;}}
                onPlay={e=>{stoppedClip.current=null;if(clip.current&&clip.current.assetId!==a.id){clearClipTimer();clip.current=null;}playingAsset.current=a.id;Object.values(media.current).forEach(p=>{if(p!==e.currentTarget)p?.pause();});if(!enforceClip(a.id,e.currentTarget)){setPlaybackMs(offset===null?-1:offset+e.currentTarget.currentTime*1000);scheduleClip(a.id,e.currentTarget);}}}
                onSeeking={e=>{if(atStoppedClip(a.id,e.currentTarget))return;playingAsset.current=a.id;if(!enforceClip(a.id,e.currentTarget)){setPlaybackMs(offset===null?-1:offset+e.currentTarget.currentTime*1000);scheduleClip(a.id,e.currentTarget);}}}
                onTimeUpdate={e=>{if(!atStoppedClip(a.id,e.currentTarget)&&(!playingAsset.current||playingAsset.current===a.id)&&!enforceClip(a.id,e.currentTarget))setPlaybackMs(offset===null?-1:offset+e.currentTarget.currentTime*1000);}}
                onPlaying={e=>scheduleClip(a.id,e.currentTarget)}
                onPause={()=>{if(clip.current?.assetId===a.id)clearClipTimer();}}
                onWaiting={()=>{if(clip.current?.assetId===a.id)clearClipTimer();}}
                onRateChange={e=>scheduleClip(a.id,e.currentTarget)}
                onEnded={()=>{if(clip.current?.assetId===a.id){clearClipTimer();clip.current=null;}if(!playingAsset.current||playingAsset.current===a.id){playingAsset.current=null;setPlaybackMs(-1);}}}
                controls
                path={`/meetings/${id}/assets/${a.id}`}
                style={{ width: "100%", maxHeight: 250 }}
              />
            </div>
          ))}
          <TranscriptView key={m.id} meeting={m} disabled={busy || !!job} reload={reload} seek={seek} playbackMs={playbackMs} />
        </div>
      )}
      {tab === "minutes" && (
        <MinutesEditor
          meeting={m}
          reload={reload}
          generate={() => action(`/meetings/${id}/minutes/generate`)}
          disabled={busy || !!job}
        />
      )}
      {tab === "analysis" && (
        <>
          <div className="section-head">
            <div>
              <h2>
                대화에서 발견한 다음 단계 <span>{m.content.items.length}</span>
              </h2>
              <p>
                {(["openai", "codex"].includes(m.content.analysisMode ?? ""))
                  ? `AI 분석 · ${m.content.analysisModel} · 원문 근거와 제안 내용을 확인하세요.`
                  : "이 회의의 기존 분석은 Mock입니다. 실제 AI 모드로 설정한 뒤 다시 분석할 수 있습니다."}
              </p>
            </div>
            <div className="actions">
              <button
                onClick={() => action(`/meetings/${id}/analyze`)}
                disabled={
                  busy || !!job || !aiSettings.ready || !aiSettings.configured
                }
              >
                <RefreshCw size={15} />
                {(["openai", "codex"].includes(aiSettings.mode ?? ""))
                  ? "AI 업무 다시 분석"
                  : "업무 다시 분석"}
              </button>
              <Link className="button" to={`/meetings/${id}/tickets`}>자동 생성된 Jira 후보 검토 <ArrowRight size={15} /></Link>
            </div>
          </div>
          <div className="filter-tabs">
            {[
              ["", "전체"],
              ["ACTION", "해야 할 일"],
              ["CHECK", "확인할 일"],
            ].map(([key, label]) => (
              <button
                className={key === kind ? "selected" : ""}
                onClick={() => setKind(key)}
                key={key}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="analysis-grid">
            <div>
              {m.content.items
                .filter((i) => !kind || i.kind === kind)
                .map((item) => (
                  <button
                    className={
                      "analysis-card " +
                      (focus?.id === item.id ? "selected" : "")
                    }
                    key={item.id}
                    onClick={() => setFocus(item)}
                  >
                    <span className="item-kind">
                      {item.kind === "ACTION" ? "해야 할 일" : "확인할 일"}
                    </span>
                    {item.title.includes("[AI 추천]") && <span className="ai-recommendation-tag">AI 추천</span>}
                    <h3>{item.title}</h3>
                    <p>{item.checks.join(" · ")}</p>
                    <div>
                      <span>
                        {item.assigneeSuggestion
                          ? `담당자 후보: ${item.assigneeSuggestion}`
                          : "담당자 미확정"}
                      </span>
                      <span>
                        {item.dueDateSuggestion
                          ? `기한 후보: ${item.dueDateSuggestion}`
                          : "기한 미확정"}
                      </span>
                      <span
                        className="inference"
                        title="모델 자체 평가이며 실제 정확도를 의미하지 않습니다."
                      >
                        {(["openai", "codex"].includes(m.content.analysisMode ?? ""))
                          ? "AI 자체 평가"
                          : "Mock 추정"}{" "}
                        · {Math.round(item.confidence * 100)}%
                      </span>
                    </div>
                  </button>
                ))}
              {!m.content.items.length && (
                <Empty>
                  분석된 업무가 없습니다. 업무 다시 분석을 실행하세요.
                </Empty>
              )}
            </div>
            <div className="panel evidence-panel">
              <h2>정리된 업무 상세</h2>
              {(focus || m.content.items[0])?.title.includes("[AI 추천]") && <span className="ai-recommendation-tag">AI 추천 · 원문을 바탕으로 추가 제안</span>}
              <p className="help">{(focus || m.content.items[0])?.title || "업무를 선택하세요."}</p>
              {(focus || m.content.items[0]) && (
                <div className="analysis-detail">
                  {[
                    ["해야 하는 작업", (focus || m.content.items[0]).tasks],
                    ["확인할 항목", (focus || m.content.items[0]).checks],
                    [
                      "완료 조건",
                      (focus || m.content.items[0]).acceptanceCriteria,
                    ],
                    ["의존성", (focus || m.content.items[0]).dependencies],
                  ].map(([label, values]) => (
                    <section key={String(label)}>
                      <h3>{String(label)}</h3>
                      <ul>
                        {(values as string[]).map((value, index) => (
                          <li key={index}>{value}</li>
                        ))}
                      </ul>
                      {!(values as string[]).length && (
                        <p className="help">명시된 항목 없음</p>
                      )}
                    </section>
                  ))}
                </div>
              )}
              <hr />
              <span className="eyebrow">SOURCE EVIDENCE</span>
              <h2>원문에서 확인하기</h2>
              <p className="help">업무를 선택하면 연결된 발언을 표시합니다.</p>
              {(focus || m.content.items[0])?.evidence.map((e) => (
                <blockquote key={e.segmentId + e.quote}>
                  <span className="timestamp">{time(e.startMs)}{e.estimatedTime ? " · 추정 시간" : ""}</span>
                  <p>“{e.quote}”</p>
                  <small>{e.speaker}</small>
                  <Link className="text-link" to={`/meetings/${id}?tab=transcript`}>음성 기록 보기<ArrowUpRight size={14} /></Link>
                </blockquote>
              ))}
            </div>
          </div>
        </>
      )}
      {tab === "tickets" && <TicketReview meeting={m} reload={reload} />}
      {tab === "results" && <Results meeting={m} reload={reload} />}
    </>
  );
}

function LegacyTicketsRedirect(){
  const location=useLocation(),params=new URLSearchParams(location.search),id=params.get("meeting");
  return <Navigate replace to={id?`/meetings/${encodeURIComponent(id)}/${params.get("step")==="3"?"results":"tickets"}`:"/meetings"}/>;
}

function MinutesEditor({
  meeting,
  reload,
  generate,
  disabled,
}: {
  meeting: Meeting;
  reload: () => Promise<void>;
  generate: () => void;
  disabled: boolean;
}) {
  const [editing,setEditing]=useState(false);
  const [templateOpen,setTemplateOpen]=useState(false);
  const [templateBusy,setTemplateBusy]=useState(false);
  const [templateApplied,setTemplateApplied]=useState(false);
  const [minutes, setMinutes] = useState<Minutes | null>(
      meeting.content.minutes,
    ),
    [error, setError] = useState(""),
    [saved, setSaved] = useState(false),
    [saving, setSaving] = useState(false);
  useEffect(() => {
    setMinutes(meeting.content.minutes);
  }, [meeting.id, meeting.content.minutes?.version]);
  async function save() {
    setSaving(true);
    setError("");
    try {
      const result = await api<Minutes>(
        `/meetings/${meeting.id}/minutes`,
        "PUT",
        minutes,
      );
      setMinutes(result);
      setEditing(false);
      setSaved(true);
      await reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }
  async function applyTemplate(template:string){
    setTemplateBusy(true);setError("");setTemplateApplied(false);setSaved(false);
    try{
      const result=await api<Meeting>(`/meetings/${meeting.id}/minutes/template`,"PUT",{template});
      setMinutes(result.content.minutes);setEditing(false);
      if(!result.content.minutes)await api(`/meetings/${meeting.id}/minutes/generate`,"POST");
      await reload();setTemplateApplied(true);setTemplateOpen(false);
    }catch(e){setError((e as Error).message);throw e;}finally{setTemplateBusy(false);}
  }
  return (
    <div className="panel padded minutes-view">
      <div className={`section-head ${editing?"minutes-edit-toolbar":""}`}>
        <div>
          <h2>{editing?"회의록 편집":"회의록"}</h2>
          <p>
            {(["openai", "codex"].includes(minutes?.generationMode ?? ""))
              ? `AI 회의록 · ${minutes?.generationModel}`
              : minutes?.generationMode==='manual' ? '수동 회의록' : "Mock 회의록"}{" "}
            · 버전 {minutes?.version || 0}
          </p>
        </div>
        <div className="actions">
          <button disabled={disabled||saving||templateBusy||editing} onClick={()=>{setTemplateOpen(true);setTemplateApplied(false);}}>회의록 양식 지정</button>
          <button onClick={generate} disabled={disabled||templateBusy||saving||editing}>
            회의록 생성
          </button>
          {minutes&&<button onClick={() => downloadText(minutesText(meeting,minutes), "회의록.md")}>회의록 다운로드</button>}
          {minutes&&!editing&&<button disabled={disabled||saving||templateBusy||templateOpen} onClick={()=>{setEditing(true);setSaved(false);}}>회의록 편집</button>}
          {editing&&<button disabled={saving} onClick={()=>{setMinutes(meeting.content.minutes);setEditing(false);setError("");}}>편집 취소</button>}
          {editing&&<button
            className="primary"
            onClick={save}
            disabled={disabled || saving || !minutes}
          >
            <Check size={16} />
            {saved ? "저장 완료" : "회의록 저장"}
          </button>}
        </div>
      </div>
      <ErrorBox error={error} />
      {templateOpen&&<MinutesTemplateDialog value={meeting.content.template||"일반"} onApply={applyTemplate} onClose={()=>setTemplateOpen(false)} disabled={disabled} applying={templateBusy}/>}
      {templateApplied&&<p className="help" role="status">회의록 양식을 적용했습니다.</p>}
      {saved&&<p className="help" role="status">회의록을 저장했습니다.</p>}
      {editing&&<p className="minutes-edit-help">문서 순서대로 각 항목을 편집하세요. 입력 영역의 오른쪽 아래를 끌어 높이를 조절할 수 있습니다. 편집 후 상단의 ‘회의록 저장’을 누르세요.</p>}
      {minutes&&!editing?<MinutesDocument meeting={meeting} minutes={minutes}/>:minutes?.formatted ? (
        <label className="minutes-custom-editor">업로드 양식으로 작성한 회의록
          <textarea rows={24} value={minutes.formatted} disabled={disabled || saving}
            onChange={e => {setMinutes({...minutes, formatted: e.target.value}); setSaved(false);}} />
        </label>
      ) : minutes ? (
        <div className="minutes-grid minutes-document-editor">
          {[
            ["overview", "회의 개요"],
            ["summary", "주요 안건"],
            ["discussion", "논의 내용"],
            ["decisions", "결정 사항"],
            ["openQuestions", "검토 사항"],
            ["followUp", "후속 조치"],
          ].map(([key, label], i) => (
            <label key={key} className={key === "discussion" ? "full" : ""}>
              <span className="minutes-label">
                <span className="number">{String(i + 1).padStart(2, "0")}</span>
                {label}
              </span>
              <textarea
                rows={key === "discussion" ? 12 : 6}
                disabled={disabled || saving}
                value={String(minutes[key as keyof Minutes])}
                onChange={(e) => {
                  setMinutes({ ...minutes, [key]: e.target.value });
                  setSaved(false);
                }}
              />
            </label>
          ))}
        </div>
      ) : (
        <Empty>회의록이 아직 없습니다. 회의록 생성을 실행하세요.</Empty>
      )}
    </div>
  );
}

function TicketReview({
  meeting,
  reload,
  resultPath,
}: {
  meeting: Meeting;
  reload: () => Promise<void>;
  resultPath?: string;
}) {
  const navigate = useNavigate();
  const jiraSettings = useJiraSettings();
  const aiSettings = useAiSettings();
  const aiRunning = !!activeJob(meeting);
  const real = jiraSettings.mode === "cloud";
  const [projects, setProjects] = useState<Project[]>([]);
  const [issueTypes, setIssueTypes] = useState<IssueType[]>([]);
  const [jiraFields, setJiraFields] = useState<JiraField[]>([]);
  const [selected, setSelected] = useState<string[]>([]),
    [focusId, setFocusId] = useState(meeting.drafts[0]?.id || ""),
    [edit, setEdit] = useState<Draft | null>(null),
    [dirty, setDirty] = useState(false),
    [error, setError] = useState(""),
    [operationBusy, setBusy] = useState(false);
  const [expanded, setExpanded] = useState<string[]>([]);
  const [savingField,setSavingField]=useState("");
  const [assigneeMessage,setAssigneeMessage]=useState("");
  const ticketEditor=useRef<HTMLDivElement>(null);
  const approvalToolbar=useRef<HTMLDivElement>(null);
  const lastEditorId=useRef<string|undefined>(undefined);
  useEffect(()=>{
    const toolbar=approvalToolbar.current,workspace=toolbar?.parentElement;if(!toolbar||!workspace)return;
    const measure=()=>workspace.style.setProperty('--review-toolbar-height',`${Math.ceil(toolbar.getBoundingClientRect().height)}px`);
    measure();const observer=new ResizeObserver(measure);observer.observe(toolbar);return()=>observer.disconnect();
  },[]);
  useEffect(()=>{
    const previous=lastEditorId.current;lastEditorId.current=edit?.id;
    if(previous&&edit?.id&&previous!==edit.id){const frame=requestAnimationFrame(()=>ticketEditor.current?.scrollIntoView({block:'start',behavior:'instant'}));return()=>cancelAnimationFrame(frame);}
  },[edit?.id]);
  useEffect(()=>{
    const editor=ticketEditor.current,header=editor?.querySelector<HTMLElement>('.ticket-editor-header'),content=editor?.querySelector<HTMLElement>('.ticket-editor-body');if(!editor||!header||!content)return;
    let frame=0;
    const measure=()=>{
      frame=0;
      const body=content.getBoundingClientRect(),head=header.getBoundingClientRect();
      content.style.setProperty('--editor-clip-top',`${Math.max(0,head.bottom-body.top)}px`);
    };
    const schedule=()=>{if(!frame)frame=requestAnimationFrame(measure);};
    const observer=new ResizeObserver(schedule);observer.observe(content);observer.observe(header);if(approvalToolbar.current)observer.observe(approvalToolbar.current);
    window.addEventListener('scroll',schedule,{passive:true});window.addEventListener('resize',schedule);measure();
    return()=>{cancelAnimationFrame(frame);observer.disconnect();window.removeEventListener('scroll',schedule);window.removeEventListener('resize',schedule);content.style.removeProperty('--editor-clip-top');};
  },[edit?.id]);
  const [projectsLoaded,setProjectsLoaded]=useState(false);
  const childDrafts = (id:string) => meeting.drafts.filter(d => d.parentDraftId === id);
  const roots = meeting.drafts.filter(d => !d.parentDraftId || !meeting.drafts.some(p=>p.id===d.parentDraftId));
  const busy = operationBusy || aiRunning;
  const current =
    meeting.drafts.find((d) => d.id === focusId) || meeting.drafts[0];
  const editLocked =
    aiRunning || operationBusy ||
    (!!edit && ["PUBLISHED", "PUBLISHING", "UNKNOWN"].includes(edit.status));
  useEffect(() => {
    api<Project[]>("/integrations/jira/projects")
      .then(data=>{setProjects(data);setProjectsLoaded(true);})
      .catch((e) => setError(e.message));
  }, [real]);
  useEffect(() => {
    setIssueTypes([]);
    setJiraFields([]);
    if (!edit?.projectKey || edit.registrationMode==="EXISTING") return;
    let active = true;
    api<IssueType[]>(
      `/integrations/jira/projects/${edit.projectKey}/issue-types`,
    )
      .then((data) => {
        if (active) setIssueTypes(data);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [edit?.projectKey, edit?.registrationMode, real]);
  const selectedType = issueTypes.find(
    (t) => t.id === edit?.issueType || t.name === edit?.issueType,
  ) || (edit?.parentDraftId && edit.issueType === "Sub-task" ? issueTypes.find(t=>String(t.subtask)==="true") : edit?.issueType==="Task" ? issueTypes.find(t=>String(t.subtask)!=="true"&&(t.name==="작업"||t.name.toLowerCase()==="task")) : undefined);
  useEffect(() => {
    setJiraFields([]);
    if (!real || !edit?.projectKey || !selectedType || edit.registrationMode==="EXISTING") return;
    let active = true;
    api<JiraField[]>(
      `/integrations/jira/projects/${edit.projectKey}/issue-types/${selectedType.id}/fields`,
    )
      .then((data) => {
        if (active) setJiraFields(data);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [real, edit?.projectKey, edit?.registrationMode, selectedType?.id]);
  const priorities =
    jiraFields.find((f) => f.id === "priority")?.allowedValues || [];
  useEffect(() => {
    if (!dirty)setEdit(current ? { ...current,projectKey:meeting.content.projectKey } : null);
  }, [current?.id, current?.version, dirty]);
  useEffect(()=>setAssigneeMessage(""),[current?.id]);
  const toggle = (id: string) => setSelected(s=>{
    const group=[id,...childDrafts(id).map(d=>d.id)];
    return s.includes(id)?s.filter(v=>!group.includes(v)):[...new Set([...s,...group])];
  });
  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await fn();
      await reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const selectedDrafts = meeting.drafts.filter((d) => selected.includes(d.id));
  const projectCorrection=!!current&&current.projectKey!==meeting.content.projectKey;
  const invalidSelectedProject=selectedDrafts.some(d=>d.projectKey!==meeting.content.projectKey)||(real&&projectsLoaded&&selectedDrafts.some(d=>!projects.some(p=>p.key===d.projectKey)));
  const approved =
    selectedDrafts.length > 0 &&
    selectedDrafts.every((d) =>
      ["APPROVED", "FAILED", "PUBLISHED", "UNKNOWN"].includes(d.status),
    );
  function focus(id: string) {
    if(busy)return;
    if (dirty) {
      setError("수정한 내용을 먼저 저장하거나 변경 취소를 누르세요.");
      return;
    }
    setFocusId(id);
    if(childDrafts(id).length)setExpanded(s=>s.includes(id)?s.filter(v=>v!==id):[...s,id]);
  }
  function change(key: string, value: unknown) {
    setEdit((e) => (e ? { ...e, [key]: value } : null));
    setDirty(true);
    setAssigneeMessage("");
  }
  async function selectAssignee(user:JiraUser|null){
    if(!edit||busy||editLocked)return;
    const previous=edit,next={...edit,projectKey:meeting.content.projectKey,assignee:user?.accountId||"",assigneeName:user?.displayName||""};
    if(next.assignee===previous.assignee)return;
    setEdit(next);setAssigneeMessage("");
    if(dirty){setAssigneeMessage("담당자 변경이 편집 내용에 포함되었습니다. 상단의 변경사항 저장을 눌러 반영하세요.");return;}
    await autoSaveDraft(next,"담당자");
  }
  async function autoSaveDraft(next:Draft,field:"담당자"|"기한 설정"){
    const previous=edit,subject=field==="담당자"?"담당자를":"기한 설정을";
    setEdit(next);setAssigneeMessage("");setBusy(true);setSavingField(field);setError("");let persisted=false;
    try{
      const saved=await api<Draft>(`/ticket-drafts/${next.id}`,"PUT",next);persisted=true;setEdit(saved);setDirty(false);
      await reload();setAssigneeMessage(`${subject} 저장했습니다.`);
    }catch(e){
      if(!persisted)setEdit(previous);
      const message=(e as Error).message;setError(message);setAssigneeMessage(persisted?`${field}은 저장되었습니다. 최신 화면을 불러오지 못했습니다. ${message}`:`${subject} 저장하지 못했습니다. 다시 선택하세요. ${message}`);
    }finally{setBusy(false);setSavingField("");}
  }
  async function selectScheduleMode(mode:"MANUAL"|"AI"){
    if(!edit||busy||editLocked||(edit.scheduleMode||"AI")===mode)return;
    const next:Draft={...edit,projectKey:meeting.content.projectKey,scheduleMode:mode,...(mode==="AI"?{startAt:"",endAt:"",dueDate:"",estimatedMinutes:0}:{})};
    await autoSaveDraft(next,"기한 설정");
  }
  function applyScheduleToChildren(){
    if(!edit)return;
    const input=edit,keys=["summary","description","assignee","assigneeName","priority","issueType","labels","registrationMode","existingIssueKey","estimatedMinutes","dependencyDraftIds"] as const;
    const pending=Object.fromEntries(keys.filter(key=>JSON.stringify(input[key])!==JSON.stringify(current?.[key])).map(key=>[key,input[key]]));
    return run(async()=>{
      const group=await api<Draft[]>(`/ticket-drafts/${input.id}/schedule/children`,"POST",{scheduleMode:input.scheduleMode||"AI",startAt:input.startAt||"",endAt:input.endAt||"",dueDate:input.dueDate||""});
      const saved=group.find(d=>d.id===input.id);if(saved){setEdit({...saved,...pending});setDirty(Object.keys(pending).length>0);}
      setAssigneeMessage((input.scheduleMode||"AI")==="AI"?"하위업무 전체에 AI 방식을 저장했습니다. AI 일정 설정에서 업무별 기간을 산정하세요.":"상위·하위업무의 기한을 저장했습니다.");
    });
  }
  function saveCurrentDraft(){
    if(!edit)return;
    return run(async()=>{const saved=await api<Draft>(`/ticket-drafts/${edit.id}`,"PUT",edit);setEdit(saved);setDirty(false);setAssigneeMessage("변경사항을 저장했습니다.");});
  }
  function cancelCurrentEdit(){setDirty(false);setEdit(current?{...current,projectKey:meeting.content.projectKey}:null);setError("");setAssigneeMessage("");}
  function candidate(d:Draft,child=false){
    const children=childDrafts(d.id);
    return <div className={"candidate "+(child?"subtask-candidate ":"")+(d.id===current?.id?"focused":"")} key={d.id}>
      <input aria-label={`${d.summary} 선택`} type="checkbox" disabled={busy} checked={selected.includes(d.id)} onChange={()=>toggle(d.id)} />
      <button disabled={busy} onClick={()=>focus(d.id)} aria-expanded={children.length?expanded.includes(d.id):undefined}>
        <span className="project-chip">{child?"↳ 하위업무 · ":"상위업무 · "}{d.projectKey} · {d.issueType}</span>
        {d.registrationMode==="EXISTING"&&<small>기존 이슈 연결 · {d.existingIssueKey}</small>}
        <h3>{d.summary}</h3>
        <div><Badge status={d.status}/>{d.labels.includes("ai-recommended")&&<span className="ai-recommendation-tag">AI 추천</span>}{children.length>0&&<small>{expanded.includes(d.id)?"▾":"▸"} 하위 {children.length}개</small>}{d.duplicateSuspected&&<small className="warning">유사 후보 있음</small>}</div>
      </button>
    </div>;
  }
  return (
    <section className="ticket-review">
      <div className="section-head">
        <div>
          <h2>검토하고, 실행으로 연결하세요</h2>
          <p>내용을 저장한 뒤 승인하세요. 수정하면 승인이 해제됩니다.</p>
        </div>
      </div>
      <p className="help">작업·확인 항목은 상위 티켓에 연결된 하위 후보로 자동 생성됩니다. AI가 추가로 제안한 업무에는 ‘AI 추천’ 태그가 표시됩니다. 부모를 클릭해 하위를 펼치고 추천 이유와 작업 범위를 검토한 뒤 승인·등록하세요.</p>
      <p className="help">AI는 같은 목적의 협의·작업·확인·검증을 하나의 실행 단위로 묶어 티켓을 구성합니다. 세부 수행 내용은 작업목록과 검토사항에 유지하고 독립적인 결과물만 별도 업무로 나눕니다.</p>
      <SchedulePanel meeting={meeting} disabled={busy||dirty||projectCorrection||!aiSettings.ready||!aiSettings.configured} reload={reload}/>
      {projectCorrection&&<p className="help">기존 후보의 프로젝트가 회의와 다릅니다. 초안을 저장하면 회의 프로젝트 '{meeting.content.projectKey}'가 적용됩니다.</p>}
      <ErrorBox error={error} />
      <div className="notice epic-summary">
        <strong>에픽 → 업무 티켓 → 하위 작업 순서로 등록합니다</strong>
        <p>새 업무 등록 시 에픽 설명에 회의록 전체를 작성하고, 등록한 녹음·영상 원본을 첨부합니다. 이후 같은 에픽을 재사용합니다. 에픽 제목은 ‘[회의] {meeting.title}’입니다. 기존 이슈 연결은 원본을 변경하지 않습니다.</p>
        {meeting.epics?.map(e=><div className="epic-result" key={e.id}><span className="project-chip">{e.projectKey} · 에픽</span><Badge status={e.status}/>{e.issueUrl?<a href={e.issueUrl} target="_blank" rel="noopener noreferrer">{e.issueKey} ↗</a>:<span>{e.issueKey||"발급 대기"}</span>}{e.error&&<span className="warning">{e.error}</span>}</div>)}
        {meeting.epics?.filter(e=>e.status==="PUBLISHED").map(e=><p key={e.id} className={e.contentError?'warning':'help'}>{e.issueKey} · {e.contentStatus==="SYNCED"?`회의록·원본 동기화 완료 (등록 원본 ${meeting.content.assets.length}개)`:(e.contentError||'회의록·원본 동기화 필요')}</p>)}
        {meeting.epics?.some(e=>e.status==="PUBLISHED")&&<button disabled={busy||dirty} onClick={()=>run(async()=>{await api(`/meetings/${meeting.id}/epics/sync`,"POST");})}>에픽 회의록·녹음 동기화</button>}
      </div>
      {real&&projectsLoaded&&current&&!projects.some(p=>p.key===current.projectKey)&&<div className="error" role="alert">초안의 프로젝트 '{current.projectKey}'는 현재 Jira에서 조회되지 않습니다. 티켓은 회의 프로젝트 '{meeting.content.projectKey}'로 저장됩니다. 회의 프로젝트의 Jira 접근 권한을 확인하고 초안을 저장하세요.</div>}
      <div className="ticket-review-workspace">
      <div className="approval-bar" ref={approvalToolbar}>
        <div>
          <ShieldCheck size={21} />
          <span>
            <b>{selected.length}개 선택</b>
            <small>
              {selectedDrafts.some(d=>d.registrationMode==="EXISTING")?"기존 이슈는 연결하고, 새 이슈만 발급합니다. 선택 내용을 확인하세요.":
              real
                ? "실제 Jira 프로젝트에 이슈를 생성합니다. 선택 내용을 확인하세요."
                : "Mock 등록이며 실제 Jira 이슈는 생성하지 않습니다."}
            </small>
          </span>
        </div>
        <div className="actions">
          <button
            disabled={
              !selected.length ||
              invalidSelectedProject ||
              busy ||
              dirty ||
              selectedDrafts.some(
                (d) => !["DRAFT", "APPROVED", "FAILED", "PUBLISHED"].includes(d.status),
              ) || !selectedDrafts.some(d=>["DRAFT","APPROVED","FAILED"].includes(d.status))
            }
            onClick={() =>
              run(async () => {
                const ids=selectedDrafts.filter(d=>["DRAFT","APPROVED","FAILED"].includes(d.status)).map(d=>d.id);
                await ensureAiSchedule(meeting,ids,setAssigneeMessage);
                await api("/ticket-drafts/approve", "POST", {ids});
                setAssigneeMessage("선택한 티켓을 승인했습니다.");
              })
            }
          >
            <Check size={16} />
            선택 승인
          </button>
          <button
            className="primary"
            disabled={!approved || busy || dirty || !jiraSettings.ready || invalidSelectedProject}
            onClick={() =>
              run(async () => {
                const results = await api<{ status: string; error?: string }[]>(
                  "/ticket-drafts/publish",
                  "POST",
                  { ids: selected },
                );
                const blocked = results.find(
                  (result) => result.status === "BLOCKED",
                );
                if (blocked)
                  throw new Error(
                    blocked.error ||
                      "현재 Jira 설정에서 티켓을 다시 승인하세요.",
                  );
                navigate(resultPath || `/meetings/${meeting.id}/results`);
              })
            }
          >
            {selectedDrafts.length > 0 &&
            selectedDrafts.every((d) => d.status === "UNKNOWN")
              ? "생성 결과 확인"
              : real
                ? "승인 티켓 Jira 등록"
                : "승인 티켓 Mock 등록"}
            <ArrowUpRight size={16} />
          </button>
        </div>
      </div>
      <div className="review-grid">
        {busy&&assigneeMessage&&<p className="help full" role="status">{assigneeMessage}</p>}
        <div className="panel candidate-panel">
          <div className="candidate-toolbar">
            <label>
              <input
                type="checkbox"
                checked={
                  meeting.drafts.length > 0 &&
                  selected.length === meeting.drafts.length
                }
                onChange={(e) =>
                  setSelected(
                    e.target.checked ? meeting.drafts.map((d) => d.id) : [],
                  )
                }
              />
              전체 선택
            </label>
            <span>상위 {roots.length}개 · 하위 {meeting.drafts.length-roots.length}개</span>
          </div>
          {meeting.drafts.some(d=>!!d.parentDraftId)&&<div className="candidate-toolbar"><button onClick={()=>setExpanded(roots.map(d=>d.id))}>하위 후보 모두 펼치기</button><button onClick={()=>setExpanded([])}>접기</button></div>}
          <div className="candidate-scroll" role="region" aria-label="티켓 후보 목록" tabIndex={0}>
          {roots.map(d=><div className="candidate-group" key={d.id}>
            {candidate(d)}
            {expanded.includes(d.id)&&childDrafts(d.id).length>0&&<div className="subtask-list" aria-label={`${d.summary} 하위 티켓`}>{childDrafts(d.id).map(child=>candidate(child,true))}</div>}
          </div>)}
          {!meeting.drafts.length && (
            <Empty>업무 분석에서 JIRA 후보를 생성하세요.</Empty>
          )}
          </div>
          <div className="candidate-bottom">
            <button
              disabled={busy || dirty || selected.length < 2 || selectedDrafts.some(d=>d.registrationMode==="EXISTING")}
              onClick={() =>
                run(async () => {
                  await api("/ticket-drafts/merge", "POST", { ids: selected });
                  setSelected([]);
                })
              }
            >
              선택 병합
            </button>
            <button
              disabled={
                busy || dirty || !current || current.analysisItemIds.length < 2 || current.registrationMode==="EXISTING"
              }
              onClick={() =>
                run(async () => {
                  await api(`/ticket-drafts/${current.id}/split`, "POST");
                  setSelected([]);
                })
              }
            >
              선택 후보 분할
            </button>
          </div>
        </div>
        <div ref={ticketEditor} className="panel ticket-editor">
          {edit ? (
            <>
              <div className="ticket-editor-header"><div className="editor-title">
                <h2>티켓 상세 편집</h2>
                <Badge status={edit.status} />
              </div>
              {(dirty||projectCorrection)&&<div className="editor-save-strip"><span>저장하지 않은 변경사항이 있습니다.</span><div className="actions"><button type="button" disabled={busy} onClick={cancelCurrentEdit}>편집 취소</button><button type="button" className="primary" disabled={busy||editLocked} onClick={()=>void saveCurrentDraft()}>변경사항 저장</button></div></div>}
              {!!savingField&&<p className="editor-assignee-status" role="status">{savingField} 저장 중…</p>}
              {!savingField&&assigneeMessage&&<p className="editor-assignee-status" role="status">{assigneeMessage}</p>}
              {error&&<p className="editor-inline-error">{error}</p>}
              </div>
              <div className="ticket-editor-body"><div className="form-grid issue-registration">
                <label>Jira 등록 방식<select aria-label="Jira 등록 방식" value={edit.registrationMode||"NEW"} disabled={editLocked} onChange={e=>change("registrationMode",e.target.value)}>
                  <option value="NEW">새 Jira 이슈 발급</option><option value="EXISTING">기존 Jira 이슈 연결</option>
                </select></label>
                {edit.registrationMode==="EXISTING"&&<label>기존 Jira 이슈 키<input aria-label="기존 Jira 이슈 키" value={edit.existingIssueKey||""} maxLength={100} disabled={editLocked} placeholder="연결할 이슈 키 입력" onChange={e=>change("existingIssueKey",e.target.value.toUpperCase())}/></label>}
                <p className="help full">{edit.registrationMode==="EXISTING"?"저장·승인 후 기존 이슈의 존재와 프로젝트·부모 관계를 확인해 연결합니다. 기존 Jira 이슈의 제목·설명은 수정하지 않습니다.":"저장·승인 후 새 Jira 이슈를 발급하고 발급된 이슈 키를 기록합니다."}</p>
              </div>
              <div className="ai-review-toolbar">
                <div>
                  {edit.labels.includes("ai-recommended")&&<p className="help"><span className="ai-recommendation-tag">AI 추천</span> 원문 업무 수행에 필요하다고 AI가 제안했습니다. 설명의 추천 이유와 확인 사항을 검토하세요.</p>}
                  <strong>
                    {(["openai", "codex"].includes(edit.generationMode ?? ""))
                      ? `AI가 정리한 초안 · ${edit.generationModel}`
                      : edit.generationMode === "manual"
                        ? "수동 작성 초안"
                        : "기존 Mock 초안"}
                  </strong>
                  <p className="help">
                    다시 정리하면 제목·설명을 AI가 새로 작성하고 승인이
                    해제됩니다. 프로젝트·담당자·기한은 유지합니다.
                  </p>
                  {(edit.analysisVersion ?? 0) <
                    (meeting.content.analysisVersion ?? 0) && (
                    <p className="help">
                      업무 분석이 갱신되었습니다. 기존 초안은 자동으로 덮어쓰지
                      않으니 필요하면 AI로 다시 정리하세요.
                    </p>
                  )}
                </div>
                <button
                  disabled={
                    busy ||
                    dirty ||
                    editLocked ||
                    !aiSettings.ready ||
                    !aiSettings.configured ||
                    !edit.analysisItemIds.length
                  }
                  onClick={() =>
                    run(async () => {
                      await api(`/ticket-drafts/${edit.id}/ai-review`, "POST");
                    })
                  }
                >
                  {(["openai", "codex"].includes(aiSettings.mode ?? ""))
                    ? "AI로 다시 정리"
                    : "Mock 다시 정리"}
                </button>
              </div>
              <div className="form-grid">
                <label>
                  이슈 유형
                  <select
                    value={edit.issueType}
                    onChange={(e) => change("issueType", e.target.value)}
                    disabled={editLocked}
                  >
                    {!issueTypes.some((t) => t.id === edit.issueType) && (
                      <option value={edit.issueType}>
                        {edit.issueType} {edit.registrationMode==="EXISTING"?"· 기존 이슈 연결":real ? (edit.parentDraftId && edit.issueType==="Sub-task"?"· 하위 유형 자동 선택":"· 실제 유형 선택 필요") : ""}
                      </option>
                    )}
                    {issueTypes.filter(t=>edit.parentDraftId?String(t.subtask)==="true":String(t.subtask)!=="true"&&!['Epic','에픽'].includes(t.name)).map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="full">
                  제목
                  <input
                    value={edit.summary}
                    maxLength={255}
                    onChange={(e) => change("summary", e.target.value)}
                    disabled={editLocked}
                  />
                </label>
                <label className="full">
                  설명
                  <AutoSizeTextarea
                    aria-label="설명"
                    rows={6}
                    value={edit.description}
                    onChange={(e) => change("description", e.target.value)}
                    disabled={editLocked}
                  />
                </label>
                <div className="full assignee-field"><div className="assignee-save-row"><AssigneeSelect key={edit.id} project={meeting.content.projectKey} value={edit.assignee||''} name={edit.assigneeName} disabled={editLocked} onChange={user=>void selectAssignee(user)}/>{!!edit.parentDraftId&&<button type="button" className="primary" aria-label="하위업무 담당자 저장" disabled={(!dirty&&!projectCorrection)||busy||editLocked} onClick={()=>void saveCurrentDraft()}>저장</button>}
                  {!edit.parentDraftId&&childDrafts(edit.id).length>0&&<button type="button" className="bulk-inline-action" aria-label="이 담당자를 하위업무 전체에 적용" title="이 담당자를 하위업무 전체에 적용" disabled={busy||dirty||editLocked||!edit.assignee||childDrafts(edit.id).some(d=>['PUBLISHED','PUBLISHING','UNKNOWN'].includes(d.status)||d.registrationMode==='EXISTING')} onClick={()=>run(async()=>{await api(`/ticket-drafts/${edit.id}/assignee/children`,'POST',{assignee:edit.assignee,assigneeName:edit.assigneeName});})}>하위 전체 적용</button>}</div>
                  <small className="help">다른 편집 내용이 없으면 담당자 선택은 즉시 저장됩니다.{!edit.parentDraftId&&childDrafts(edit.id).length>0&&" 저장 후 하위 전체에 적용할 수 있습니다. 적용하면 AI 일정을 다시 설정하세요."}</small>
                </div>
                <section className="full ticket-schedule" aria-label="업무 기한 설정"><div className="schedule-mode-row"><label>기한 설정 방식<select aria-label="기한 설정 방식" value={edit.scheduleMode||'AI'} disabled={editLocked} onChange={e=>void selectScheduleMode(e.target.value as 'MANUAL'|'AI')}><option value="MANUAL">직접 설정</option><option value="AI">AI 자동 설정</option></select></label>{!!edit.parentDraftId&&<button type="button" className="primary" aria-label="하위업무 기한 저장" disabled={(!dirty&&!projectCorrection)||busy||editLocked} onClick={()=>void saveCurrentDraft()}>저장</button>}
                  {!edit.parentDraftId&&childDrafts(edit.id).length>0&&<button type="button" className="bulk-inline-action" aria-label="이 기한 설정을 하위업무 전체에 적용" title="이 기한 설정을 하위업무 전체에 적용" disabled={busy||editLocked||childDrafts(edit.id).some(d=>['PUBLISHED','PUBLISHING','UNKNOWN'].includes(d.status)||d.registrationMode==='EXISTING')||((edit.scheduleMode||'AI')==='MANUAL'&&!((edit.startAt&&edit.endAt)||edit.dueDate))} onClick={()=>void applyScheduleToChildren()}>하위 전체 적용</button>}</div>
                  <p className="help">방식을 변경하면 현재 편집 내용과 함께 자동 저장됩니다.</p>
                  {(edit.scheduleMode||'AI')==='MANUAL'?<><div className="form-grid"><label>시작 일시<input type="datetime-local" aria-label="업무 시작 일시" disabled={editLocked} value={localDateTime(edit.startAt)} onChange={e=>change('startAt',offsetDateTime(e.target.value))}/></label><label>종료 일시<input type="datetime-local" aria-label="업무 종료 일시" disabled={editLocked} value={localDateTime(edit.endAt)} onChange={e=>{change('endAt',offsetDateTime(e.target.value));change('dueDate',e.target.value.slice(0,10));}}/></label><label>예상 작업시간 (분)<input type="number" min={0} max={19200} step={15} aria-label="예상 작업시간" disabled={editLocked} value={edit.estimatedMinutes||0} onChange={e=>change('estimatedMinutes',Number(e.target.value))}/></label></div>{!edit.endAt&&edit.dueDate&&<p className="help">기존 기한: {edit.dueDate} · 시작·종료 시각을 지정하거나 AI 방식으로 전환하세요.</p>}</>:<div className="ai-schedule-summary"><p>시작 <strong>{displayDateTime(edit.startAt)}</strong></p><p>종료 <strong>{displayDateTime(edit.endAt)}</strong></p><p>예상 작업시간 <strong>{edit.estimatedMinutes||0}분</strong></p><p className="help">AI 방식은 자동 저장됩니다. 선택 승인 시 누락된 일정을 자동 산정합니다. 점심시간 12:00~13:00은 제외합니다. 위의 ‘AI 일정 설정’에서 업무 전체를 분석하세요. 담당자별 시간과 업무 간 의존관계를 함께 계산합니다.</p></div>}
                  {!edit.parentDraftId&&childDrafts(edit.id).length>0&&<p className="help">직접 설정은 시작·종료 일시를 동일하게 저장합니다. AI 방식은 하위에도 적용한 뒤 업무별로 일정을 산정합니다.</p>}
                  {!!edit.scheduleReason&&<p className="schedule-reason">{edit.scheduleReason}</p>}
                  {!!edit.dependencyDraftIds?.length&&<div className="schedule-dependencies"><strong>선행 업무</strong>{edit.dependencyDraftIds.map(id=><span key={id}>{meeting.drafts.find(d=>d.id===id)?.summary||id}</span>)}</div>}
                </section>
                <label>
                  우선순위
                  <select
                    value={edit.priority}
                    onChange={(e) => change("priority", e.target.value)}
                    disabled={editLocked}
                  >
                    <option value="">프로젝트 기본값</option>
                    {real ? (
                      <>
                        {!priorities.some((p) => p.id === edit.priority) &&
                          edit.priority && (
                            <option value={edit.priority}>
                              {edit.priority} · 선택 확인
                            </option>
                          )}
                        {priorities.map((p) => (
                          <option key={p.id} value={p.id}>
                            {p.name}
                          </option>
                        ))}
                      </>
                    ) : (
                      ["Highest", "High", "Medium", "Low", "Lowest"].map(
                        (p) => <option key={p}>{p}</option>,
                      )
                    )}
                  </select>
                </label>
                {!!edit.parentDraftId&&<label>
                  상위 티켓
                  <input
                    placeholder="연결할 Jira 이슈 키"
                    value={edit.parentDraftId ? (meeting.drafts.find(d=>d.id===edit.parentDraftId)?.summary||"")+" · "+(edit.parent||"부모 등록 후 자동 연결") : edit.parent || ""}
                    onChange={(e) => change("parent", e.target.value)}
                    disabled={editLocked || !!edit.parentDraftId || !!edit.epicKey}
                  />
                </label>}
                <label className="full">
                  라벨 (쉼표 구분)
                  <input
                    value={edit.labels.join(",")}
                    onChange={(e) =>
                      change(
                        "labels",
                        e.target.value
                          .split(",")
                          .map((l) => l.trim())
                          .filter(Boolean),
                      )
                    }
                    disabled={editLocked}
                  />
                </label>
              </div>
              <div className="source-summary">
                <Link2 size={15} />
                <span>연결된 원문 근거 {edit.analysisItemIds.length}건</span>
                <Link to={`/meetings/${meeting.id}?tab=analysis`}>
                  확인하기
                </Link>
              </div>
              <div className="form-actions">
                <button
                  className="danger"
                  disabled={busy || editLocked}
                  onClick={() =>
                    run(async () => {
                      await api(`/ticket-drafts/${edit.id}`, "DELETE");
                      setSelected((s) => s.filter((id) => id !== edit.id));
                      setDirty(false);
                      setFocusId("");
                    })
                  }
                >
                  후보 삭제
                </button>
                <button
                  disabled={!dirty || busy}
                  onClick={cancelCurrentEdit}
                >
                  변경 취소
                </button>
                <button
                  className="primary"
                  disabled={(!dirty&&!projectCorrection) || busy || editLocked}
                  onClick={()=>void saveCurrentDraft()}
                >
                  초안 저장
                </button>
              </div>
              </div>
            </>
          ) : (
            <Empty>편집할 티켓을 선택하세요.</Empty>
          )}
        </div>
      </div>
      </div>
    </section>
  );
}

function Results({
  meeting,
  reload,
  reviewPath,
}: {
  meeting: Meeting;
  reload: () => Promise<void>;
  reviewPath?: string;
}) {
  const jiraSettings = useJiraSettings();
  const [searchText,setSearchText]=useState(""),[query,setQuery]=useState(""),[status,setStatus]=useState(""),[kind,setKind]=useState("");
  useEffect(()=>{setSearchText("");setQuery("");setStatus("");setKind("");},[meeting.id]);
  const pending=(value:string)=>!["PUBLISHED","FAILED"].includes(value);
  const matchesStatus=(value:string)=>!status||(status==="PENDING"?pending(value):value===status);
  const matchesQuery=(value:string)=>value.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase());
  const visible=meeting.drafts.filter(d=>matchesStatus(d.status)&&(!kind||(kind==="CHILD"?!!d.parentDraftId:!d.parentDraftId))&&matchesQuery([d.summary,d.projectKey,d.issueKey,d.error,meeting.drafts.find(p=>p.id===d.parentDraftId)?.summary].filter(Boolean).join(" ")));
  const visibleEpics=(meeting.epics||[]).filter(e=>!kind&&matchesStatus(e.status)&&matchesQuery([e.summary,e.projectKey,e.issueKey,e.error].filter(Boolean).join(" ")));
  const filtered=!!query.trim()||!!status||!!kind;
  const statFilters=["PUBLISHED","FAILED","PENDING"];
  function resetFilters(){setSearchText("");setQuery("");setStatus("");setKind("");}
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function retry(id: string) {
    setBusy(true);
    try {
      await api("/ticket-drafts/publish", "POST", { ids: [id] });
      await reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="section-head">
        <div>
          <h2>등록 결과</h2>
          <p>
            {jiraSettings.mode === "cloud"
              ? "실제 Jira 등록 결과입니다. 이슈 키를 눌러 확인하세요."
              : "Mock 환경 결과입니다. 외부 Jira 링크는 생성하지 않습니다."}
          </p>
        </div>
        <Link className="button" to={reviewPath || `/meetings/${meeting.id}/tickets`}>
          초안 수정으로 돌아가기
        </Link>
      </div>
      <ErrorBox error={error} />
      <section className="panel results-search-panel" aria-label="등록 결과 검색">
        <div className="results-search-heading"><div><h3>등록 결과 찾기</h3><p>티켓 제목, 상위 업무명, 프로젝트, 이슈 키 또는 오류 내용으로 검색하세요.</p></div><span className="results-count">{visible.length} / {meeting.drafts.length}개 티켓</span></div>
        <form className="results-search-form" onSubmit={e=>{e.preventDefault();setQuery(searchText);}}>
          <div className="results-search-filters"><select aria-label="등록 결과 상태" value={status} onChange={e=>setStatus(e.target.value)}><option value="">전체 상태</option><option value="PUBLISHED">등록 완료</option><option value="FAILED">등록 실패</option><option value="PENDING">미등록·미확정 전체</option><option value="DRAFT">초안</option><option value="APPROVED">승인됨</option><option value="PUBLISHING">등록 중</option><option value="UNKNOWN">결과 확인 필요</option></select><select aria-label="등록 결과 구분" value={kind} onChange={e=>setKind(e.target.value)}><option value="">전체 구성</option><option value="PARENT">상위업무</option><option value="CHILD">하위업무</option></select></div>
          <input aria-label="등록 결과 검색어" placeholder="티켓 제목, 이슈 키, 오류 내용 검색" value={searchText} onChange={e=>setSearchText(e.target.value)}/>
          {(filtered||searchText)&&<button type="button" onClick={resetFilters}>초기화</button>}
          <button className="primary" type="submit"><Search size={16}/>검색</button>
        </form>
        {filtered&&<p className="results-filter-note" role="status">{query.trim()&&<>‘{query.trim()}’ 검색 · </>}{status&&`${status==="PENDING"?"미등록·미확정":statusName[status]||status} · `}{kind&&`${kind==="CHILD"?"하위업무":"상위업무"} · `}티켓 {visible.length}건{!kind&&meeting.epics?.length?` · 에픽 ${visibleEpics.length}건`:""}</p>}
      </section>
      {!!meeting.epics?.length&&!kind&&<div className="panel padded epic-summary"><strong>에픽 등록 결과 <small>{visibleEpics.length}건</small></strong>{visibleEpics.map(e=><div className="epic-result" key={e.id}><span className="project-chip">{e.projectKey}</span><span>{e.summary}</span><Badge status={e.status}/>{e.issueUrl?<a href={e.issueUrl} target="_blank" rel="noopener noreferrer">에픽 {e.issueKey} ↗</a>:<span>에픽 {e.issueKey||"결과 확인 필요"}</span>}{e.error&&<span className="warning">{e.error}</span>}</div>)}{!visibleEpics.length&&<p className="help">검색 조건에 맞는 에픽이 없습니다.</p>}</div>}
      <div className="result-stats">
        {[
          [
            "등록 성공",
            meeting.drafts.filter((d) => d.status === "PUBLISHED").length,
          ],
          [
            "등록 실패",
            meeting.drafts.filter((d) => d.status === "FAILED").length,
          ],
          [
            "미등록 · 건너뜀",
            meeting.drafts.filter(
              (d) => !["PUBLISHED", "FAILED"].includes(d.status),
            ).length,
          ],
        ].map(([label, n],index) => (
          <button type="button" className="panel result-stat-filter" key={label} aria-pressed={status===statFilters[index]} onClick={()=>setStatus(s=>s===statFilters[index]?"":statFilters[index])}>
            <span>{label}</span>
            <strong>{n}</strong>
            <small>회의 전체 기준 · 눌러서 필터</small>
          </button>
        ))}
      </div>
      <div className="panel results-list-panel"><div className="results-list-heading"><strong>티켓 등록 목록</strong><span>{visible.length}건 표시</span></div><div className="table-scroll">
        <table aria-label="티켓 등록 결과 목록">
          <thead>
            <tr>
              <th>티켓 제목</th>
              <th>등록 상태</th>
              <th>이슈 키</th>
              <th>생성 시간 / 오류 사유</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {visible.map((d) => (
              <tr key={d.id}>
                <td><span className="result-kind">{d.parentDraftId?"하위업무":"상위업무"}</span><span className="result-title">{d.summary}</span></td>
                <td>
                  <Badge status={d.status} />
                </td>
                <td>
                  {d.issueUrl ? (
                    <a href={d.issueUrl} target="_blank" rel="noreferrer">
                      {d.issueKey}
                    </a>
                  ) : (
                    d.issueKey || "—"
                  )}
                </td>
                <td className="result-message">
                  {d.error ||
                    d.publishedAt?.replace("T", " ").slice(0, 19) ||
                    "승인 및 등록 대기"}
                </td>
                <td>
                  {["FAILED", "UNKNOWN"].includes(d.status) && (
                    <button disabled={busy} onClick={() => retry(d.id)}>
                      {d.status === "UNKNOWN" ? "생성 결과 확인" : "재시도"}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
        {!visible.length&&<Empty>{meeting.drafts.length?<><span>검색 조건에 맞는 티켓이 없습니다.</span><button onClick={resetFilters}>검색 초기화</button></>:"아직 등록할 티켓이 없습니다."}</Empty>}
      </div>
    </>
  );
}
