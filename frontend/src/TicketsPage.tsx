import {useEffect, useState, type ReactNode} from "react";
import {Link, useSearchParams} from "react-router-dom";
import {api} from "./api";
import {activeJob, statusName, type Meeting} from "./types";

const steps = ["회의 선택", "회의록·업무 분석 확인", "티켓 검토·승인", "등록 결과"];
function progress(m: Meeting) {
  if (m.drafts.some(d => ["FAILED", "UNKNOWN"].includes(d.status))) return "등록 확인 필요";
  if (m.drafts.length && m.drafts.every(d => d.status === "PUBLISHED")) return "등록 완료";
  if (activeJob(m) || m.drafts.some(d => d.status === "PUBLISHING")) return "처리 중";
  if (m.drafts.length) return "티켓 검토·승인";
  return "회의록·업무 분석 확인";
}

export default function TicketsPage({review, results}: {
  review: (m: Meeting, reload: () => Promise<void>) => ReactNode;
  results: (m: Meeting, reload: () => Promise<void>) => ReactNode;
}) {
  const [params, setParams] = useSearchParams();
  const id = params.get("meeting") || "";
  const stage = id ? Math.max(1, Math.min(3, Math.floor(Number(params.get("step"))) || 1)) : 0;
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [q, setQ] = useState("");
  const [project, setProject] = useState("");
  const [state, setState] = useState("");
  async function reload() {
    try {setMeetings(await api<Meeting[]>("/meetings")); setError("");}
    catch (e) {setError((e as Error).message);}
    finally {setLoading(false);}
  }
  useEffect(() => {void reload(); const timer = setInterval(reload, 3000); return () => clearInterval(timer);}, []);
  const meeting = meetings.find(m => m.id === id);
  const visible = meetings.filter(m =>
    `${m.title} ${m.content.projectKey} ${m.content.participants.join(" ")} ${m.drafts.map(d => `${d.summary} ${d.issueKey || ""}`).join(" ")}`.toLowerCase().includes(q.toLowerCase()) &&
    (!project || m.content.projectKey === project) && (!state || progress(m) === state));
  function go(step: number, meetingId = id) {setParams(step === 0 ? {} : {meeting: meetingId, step: String(step)});}
  return <>
    <div className="page-heading"><div><span className="eyebrow">MEETING TO JIRA</span><h1>회의별 Jira 티켓 관리</h1><p>등록한 회의를 선택하고 분석 확인부터 티켓 검토·승인, 등록 결과까지 단계별로 진행하세요.</p></div><Link className="button" to="/meetings/new">새 회의 등록</Link></div>
    {error && <div className="error" role="alert">{error}<button onClick={() => void reload()}>다시 불러오기</button></div>}
    <nav className="stepper" aria-label="회의별 티켓 관리 단계">
      {steps.map((label, i) => <button key={label} className={stage === i ? "current" : ""} aria-current={stage === i ? "step" : undefined} disabled={i > 0 && !meeting} onClick={() => go(i)}><span>{i + 1}</span>{label}</button>)}
    </nav>
    {stage === 0 ? <div className="panel">
      <div className="table-toolbar">
        <div className="search-input"><input aria-label="회의·티켓 검색" placeholder="회의명, 참석자, 티켓 제목, 이슈 키 검색" value={q} onChange={e => setQ(e.target.value)} /></div>
        <select aria-label="회의 프로젝트" value={project} onChange={e => setProject(e.target.value)}><option value="">전체 프로젝트</option>{[...new Set(meetings.map(m => m.content.projectKey))].map(key => <option key={key}>{key}</option>)}</select>
        <select aria-label="회의 진행 단계" value={state} onChange={e => setState(e.target.value)}><option value="">전체 단계</option>{["회의록·업무 분석 확인", "티켓 검토·승인", "처리 중", "등록 확인 필요", "등록 완료"].map(s => <option key={s}>{s}</option>)}</select>
      </div>
      <p className="help ticket-meeting-count">등록 회의 {meetings.length}건 · 검색 결과 {visible.length}건 · 최근 등록순</p>
      <div className="table-scroll"><table><thead><tr><th>등록한 회의</th><th>프로젝트</th><th>회의 일시 / 등록일</th><th>진행 단계</th><th>티켓 현황</th><th>관리</th></tr></thead><tbody>
        {visible.map(m => <tr key={m.id}><td><button className="ticket-meeting-title" onClick={() => go(1, m.id)}>{m.title}</button><small>{m.content.participants.join(", ") || "참석자 미입력"}</small></td><td><span className="project-chip">{m.content.projectKey}</span></td><td>{(m.content.occurredAt || m.createdAt).replace("T", " ").slice(0, 16)}</td><td>{progress(m)}</td><td>전체 {m.drafts.length} · 승인 {m.drafts.filter(d => d.status === "APPROVED").length} · 등록 {m.drafts.filter(d => d.status === "PUBLISHED").length}</td><td><button onClick={() => go(1, m.id)}>단계별 관리</button></td></tr>)}
      </tbody></table></div>
      {loading ? <p className="empty" role="status">회의를 불러오는 중입니다.</p> : !visible.length && <div className="empty">{meetings.length ? "검색 조건에 맞는 회의가 없습니다." : "등록한 회의가 없습니다. 새 회의를 먼저 등록하세요."}</div>}
    </div> : meeting ? <>
      <div className="panel padded ticket-meeting-summary"><div><strong>{meeting.title}</strong><p>{meeting.content.projectKey} · {meeting.content.participants.join(", ") || "참석자 미입력"} · {progress(meeting)}</p></div><button onClick={() => go(0)}>다른 회의 선택</button></div>
      {stage === 1 && <div className="panel padded">
        <h2>회의록과 업무 분석을 확인하세요</h2>
        {activeJob(meeting) && <p className="warning" role="status">{statusName[meeting.status] || "분석 처리 중"} · 완료되면 자동 갱신됩니다.</p>}
        {meeting.jobs[0]?.status === "FAILED" && <p className="error" role="alert">{meeting.jobs[0].errorMessage || "분석에 실패했습니다."} 회의 상세에서 확인하고 재시도하세요.</p>}
        <ol className="ticket-preparation">
          <li><div><strong>회의록 확인</strong><p>{meeting.content.minutes ? "작성된 회의록을 검토하세요." : "회의록 생성이 필요합니다."}</p></div><Link className="button" to={`/meetings/${id}?tab=minutes`}>회의록 확인</Link></li>
          <li><div><strong>업무 분석 확인</strong><p>분석된 업무 {meeting.content.items.length}건 · 원문과 작업 범위를 검토하세요.</p></div><Link className="button" to={`/meetings/${id}?tab=analysis`}>업무 분석 확인</Link></li>
          <li><div><strong>티켓 후보 준비</strong><p>{meeting.drafts.length ? `이 회의의 티켓 후보 ${meeting.drafts.length}건을 준비했습니다.` : "실제 AI 업무 분석을 실행하면 상위·하위 후보까지 자동 생성됩니다."}</p></div><Link className="button" to={`/meetings/${id}?tab=analysis`}>업무 분석으로</Link></li>
        </ol>
        <div className="form-actions"><button className="primary" onClick={() => go(2)} disabled={!meeting.drafts.length || !!activeJob(meeting)}>다음: 티켓 검토·승인</button></div>
      </div>}
      {stage === 2 && <>{meeting.drafts.length ? review(meeting, reload) : <div className="panel padded"><p>이 회의의 티켓 후보가 없습니다. 분석 확인 단계에서 후보를 준비하세요.</p><button onClick={() => go(1)}>분석 확인으로</button></div>}<div className="form-actions"><button onClick={() => go(1)}>이전: 분석 확인</button><button onClick={() => go(3)}>다음: 등록 결과</button></div></>}
      {stage === 3 && <>{results(meeting, reload)}<div className="form-actions"><button onClick={() => go(2)}>이전: 티켓 검토·승인</button><button onClick={() => go(0)}>회의 목록으로</button></div></>}
    </> : <div className="panel padded"><p>{loading ? "회의를 불러오는 중입니다." : "선택한 회의를 찾을 수 없습니다."}</p><button onClick={() => go(0)}>회의 다시 선택</button></div>}
  </>;
}
