import { useState, useEffect, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Upload, ShieldCheck, ArrowRight } from "lucide-react";
import { api, upload } from "./api";
import { type Meeting } from "./types";
import { useJiraSettings, type Project } from "./integrations";
import { readTranscript } from "./transcript";
import { useAiSettings } from "./ai";
import {type ProjectSpace} from "./ProjectSpacesPage";
import {useSpeechStatus} from './speech';
import {standalone} from './standalone';
import MinutesTemplate from "./MinutesTemplate";

export default function MeetingForm() {
  const [template, setTemplate] = useState("일반");
  const ai = useAiSettings();
  const speech=useSpeechStatus();
  const [params]=useSearchParams();
  const requestedProject=params.get("project")||"";
  const navigate = useNavigate(),
    settings = useJiraSettings();
  const [file, setFile] = useState<File | null>(null),
    [transcript, setTranscript] = useState(""),
    [transcriptName, setTranscriptName] = useState("");
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [progress, setProgress] = useState(0),
    [createdId, setCreatedId] = useState(""),
    [uploaded, setUploaded] = useState(false);
  const [project, setProject] = useState(requestedProject),
    [projects, setProjects] = useState<Project[]>([]);
  const [spaces,setSpaces]=useState<ProjectSpace[]>([]);
  const [projectError,setProjectError]=useState("");
  useEffect(()=>{api<ProjectSpace[]>("/projects").then(setSpaces).catch(e=>setProjectError(e.message));},[]);
  useEffect(() => {
    if (settings.defaultProject && !requestedProject) setProject(settings.defaultProject);
    api<Project[]>("/integrations/jira/projects")
      .then(data=>{setProjects(data);if(!requestedProject&&!settings.defaultProject)setProject(p=>p||data[0]?.key||"");})
      .catch(() => {});
  }, [settings.defaultProject, settings.mode, requestedProject]);
  async function textFile(f: File | undefined) {
    if (!f) return;
    setError("");
    try {
      setTranscript(await readTranscript(f));
      setTranscriptName(f.name);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  async function meetingFile(f:File|undefined){
    if(f&&/\.(mp3|wav|m4a|aac|acc|mp4|webm)$/i.test(f.name)){mediaFile(f);return;}
    if (createdId) {
      setError("\uC800\uC7A5\uB41C \uD68C\uC758\uC5D0\uC11C\uB294 \uC74C\uC131\u00B7\uC601\uC0C1 \uD30C\uC77C\uB9CC \uB2E4\uC2DC \uC120\uD0DD\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.");
      return;
    }
    await textFile(f);
  }
  function mediaFile(f: File | undefined) {
    if (!f) return;
    if (
      !/\.(mp3|wav|m4a|aac|acc|mp4|webm)$/i.test(f.name) ||
      f.size > 100 * 1024 * 1024 ||
      !f.size
    ) {
      setError("원본 파일은 MP3/WAV/M4A/AAC/ACC/MP4/WEBM, 100MB 이하로 선택하세요.");
      return;
    }
    setFile(f);
    setUploaded(false);
    setProgress(0);
    setError("");
  }
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    try {
      if(standalone&&file)throw new Error('HTML 로컬 버전에서는 음성·영상 대신 TXT/SRT/VTT 전사문을 등록하세요.');
      if(file&&!transcript.trim()&&!speech.ready)throw new Error(speech.message);
      let id = createdId;
      if (!id) {
        const m = await api<Meeting>("/meetings", "POST", {
          title: form.get("title"),
          occurredAt: form.get("occurredAt"),
          projectKey: project,
          participants: String(form.get("participants") || "")
            .split(",")
            .map((s) => s.trim())
            .filter(Boolean),
          memo: "",
          source: transcript.trim()?"TEXT":"MEDIA",
          transcript,
          language: "ko",
          diarization: !!file,
          template,
        });
        id = m.id;
        setCreatedId(id);
      }
      if (file && !uploaded) {
        await upload(id, file, setProgress);
        setUploaded(true);
      }
      if(file||transcript.trim())await api(`/meetings/${id}/process`, "POST");
      navigate(`/meetings/${id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">NEW MEETING</span>
          <h1>새로운 대화를 모아볼까요?</h1>
          <p>
            녹음·녹화 파일 또는 음성 기록을 가져오세요. 음성을 자동으로 텍스트로 변환한 뒤 회의록과 업무를 분석합니다.
          </p>
        </div>
      </div>
      <form className="new-grid" onSubmit={submit}>
        <div className="panel form-panel">
          <h2>
            <span className="number">01</span>회의 정보
          </h2>
          <div className="form-grid">
            <label className="full">
              회의명 <b>*</b>
              <input
                name="title"
                required
                maxLength={200}
                disabled={!!createdId}
                placeholder="회의 제목을 입력하세요"
              />
            </label>
            <label>
              회의 일시
              <input
                name="occurredAt"
                type="datetime-local"
                disabled={!!createdId}
              />
            </label>
            <label>
              프로젝트(스페이스)
              <select aria-label="등록한 프로젝트 선택" value={spaces.some(p=>p.key===project)?project:""} disabled={!!createdId} onChange={e=>{if(e.target.value)setProject(e.target.value);}}>
                <option value="">등록한 프로젝트 선택</option>
                {spaces.map(p=><option key={p.key} value={p.key}>{p.name} · {p.key}</option>)}
              </select>
              <Link to="/meetings/projects?returnTo=new">프로젝트(스페이스) 등록·관리</Link>
            </label>
            <label>
              Jira 프로젝트 키
              <input
                name="projectKey"
                list="meeting-projects"
                required
                pattern="[A-Z][A-Z0-9_]{0,19}"
                value={project}
                onChange={(e) => setProject(e.target.value.toUpperCase())}
                disabled={!!createdId}
              />
              <datalist id="meeting-projects">
                {[...spaces,...projects.filter(p=>!spaces.some(s=>s.key===p.key))].map((p) => (
                  <option key={p.key} value={p.key}>
                    {p.name}
                  </option>
                ))}
              </datalist>
            </label>
            {projectError&&<p className="error full" role="alert">{projectError}</p>}
            <label className="full">
              참석자
              <input
                name="participants"
                placeholder="쉼표로 구분해 입력하세요"
                disabled={!!createdId}
              />
            </label>
          </div>
          <h2>
            <span className="number">02</span>회의 자료
          </h2>
          <div
            className="dropzone"
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              if (!busy && !uploaded) void meetingFile(e.dataTransfer.files[0]);
            }}
          >
            <Upload size={26} />
            <p>
              파일을 끌어 놓거나 선택하세요
            </p>
            <small>
              음성 기록 TXT · SRT · VTT: 1MB / 녹음·영상 M4A · MP3 · WAV · AAC · ACC · MP4 · WEBM: 100MB · 4시간 이내
            </small>
            <input
              aria-label="회의 자료 파일 선택"
              type="file"
              accept=".txt,.srt,.vtt,.m4a,.mp3,.wav,.aac,.acc,.mp4,.webm"
              disabled={busy || uploaded}
              onChange={(e) => void meetingFile(e.target.files?.[0])}
            />
          </div>
          {transcriptName && <p className="help" role="status">음성 기록: {transcriptName}</p>}
          <p className="help">음성 기록 텍스트는 선택입니다. 녹음·영상만 등록해도 음성을 자동 변환해 분석합니다. 자료가 없으면 회의 정보만 저장할 수 있습니다.</p>
            {file && <p className="help" role="status">원본 선택됨: {file.name} · {(file.size/1024/1024).toFixed(1)}MB · {transcript.trim()?"입력한 텍스트 기록을 우선 분석합니다.":"음성을 자동으로 텍스트로 변환한 뒤 분석합니다."}</p>}
            <p className="help">OneDrive·휴대폰 파일은 먼저 PC에 다운로드하세요. 전송에 실패하면 여기에서 파일을 다시 선택할 수 있습니다.</p>
            {file&&!transcript.trim()&&<p className={speech.ready?'help':'warning'}>{speech.message} {!speech.ready&&<Link to="/settings">설정에서 확인</Link>}</p>}
            {progress > 0 && <progress value={progress} max={100} />}

          <div className="field-heading">
            <label htmlFor="transcript">
              음성 기록 텍스트 (선택)
            </label>
          </div>
          <textarea
            id="transcript"
            rows={12}
            maxLength={200000}
            disabled={!!createdId}
            value={transcript}
            onChange={(e) => setTranscript(e.target.value)}
            placeholder={
              "녹음·녹화 파일만 등록해도 분석할 수 있습니다. 이미 변환한 기록이 있으면 붙여넣으세요."
            }
          />
          <p className="help">
            화자·시간이 있는 텍스트는 원문 정보를 유지합니다. 시간이 없으면
            ‘추정 시간’으로 표시해요.
          </p>

          <MinutesTemplate value={template} onChange={setTemplate} disabled={busy || !!createdId} />
          {error && (
            <div className="error" role="alert">
              {error}
            </div>
          )}
          {createdId && (
            <p className="help">
              회의가 저장되었습니다. 재시도 시 같은 회의에서 계속합니다.{" "}
              <Link to={`/meetings/${createdId}`}>저장된 회의 열기</Link>
            </p>
          )}
          <div className="form-actions">
            <Link className="button" to="/meetings">
              취소
            </Link>
            <button
              className="primary"
              disabled={busy || (!standalone&&((!!file||!!transcript.trim())&&(!ai.ready||!ai.configured))) || (!!file&&!transcript.trim()&&!speech.ready)}
            >
              {busy
                ? "자료 저장 중…"
                : createdId
                  ? "분석 재시도"
                  : standalone?"회의 저장":file||transcript.trim()?"저장 후 분석 시작":"회의만 저장"}
              <ArrowRight size={16} />
            </button>
          </div>
        </div>
        <aside className="info-aside">
          <span className="eyebrow">TEXT TO ACTION</span>
          <h2>
            음성 기록을 가져오면
            <br />
            다음 업무가 보여요
          </h2>
          {[
            [
              "01",
              "녹음·녹화 또는 기록 등록",
              "M4A·MP4 등 파일이나 이미 변환한 기록을 가져오세요.",
            ],
            [
              "02",
              "음성 텍스트 변환와 AI 분석",
              "파일의 음성을 자동 텍스트 변환하고 회의록과 업무를 정리합니다.",
            ],
            [
              "03",
              "검토하고 Jira 등록",
              "작업·확인 항목을 검토하고 승인해 등록하세요.",
            ],
          ].map(([n, t, d]) => (
            <div className="how-step" key={n}>
              <span>{n}</span>
              <div>
                <h3>{t}</h3>
                <p>{d}</p>
              </div>
            </div>
          ))}
          <div className="notice">
            <ShieldCheck size={20} />
            <strong>
              {ai.mode === "mock"
                ? "시연용 Mock 분석 모드"
                : "AI가 회의록과 업무를 정리합니다"}
            </strong>
            <p>
              {ai.mode === "mock"
                ? "현재는 규칙 기반 시연 모드입니다. 실제 AI를 사용하려면 설정을 변경하세요."
                : "음성 기록을 AI가 읽고 작업과 확인 항목을 추출합니다. Jira 검토에서 AI가 작성한 설명을 확인하고 승인하세요."}
            </p>
            {!ai.configured && (
              <p>
                <Link to="/settings">
                  설정에서 AI API 키를 먼저 저장하세요 →
                </Link>
              </p>
            )}
          </div>
        </aside>
      </form>
    </>
  );
}
