import { useState, useEffect, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { ShieldCheck, ArrowUpRight, ExternalLink } from "lucide-react";
import { api } from "./api";
import AiSettingsCard from "./AiSettingsCard";
import JiraConnectionGuide from "./JiraConnectionGuide";
import {SpeechSettingsCard} from './speech';
import {ConfluenceSettingsCard} from './Confluence';
import {
  useJiraSettings,
  notifyJiraSettingsChanged,
  type Project,
  type IssueType,
  type JiraField,
} from "./integrations";

export default function JiraSettingsPage() {
  const settings = useJiraSettings();
  const real = settings.mode === "cloud";
  const [form, setForm] = useState({
    mode: "mock",
    siteUrl: "",
    email: "",
    apiToken: "",
    cloudId: "",
  });
  const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [connected, setConnected] = useState(false);
  const [projects, setProjects] = useState<Project[]>([]),
    [project, setProject] = useState(""),
    [types, setTypes] = useState<IssueType[]>([]),
    [type, setType] = useState(""),
    [fields, setFields] = useState<JiraField[]>([]),
    [approved, setApproved] = useState(false);
  const [result, setResult] = useState<{
    status: string;
    issueKey: string | null;
    issueUrl: string | null;
    draftId: string;
    error: string | null;
  } | null>(null);
  const [logs, setLogs] = useState<
    { id: string; action: string; targetId: string; timestamp: string }[]
  >([]);
  useEffect(() => {
    setProject(settings.defaultProject);
  }, [settings.defaultProject]);
  useEffect(() => {
    if (!settings.ready) return;
    setForm({
      mode: settings.mode,
      siteUrl: settings.siteUrl,
      email: settings.email || "",
      cloudId: settings.cloudId || "",
      apiToken: "",
    });
    setDirty(false);
  }, [
    settings.ready,
    settings.mode,
    settings.siteUrl,
    settings.email,
    settings.cloudId,
  ]);
  function change(key: keyof typeof form, value: string) {
    setForm((previous) => ({ ...previous, [key]: value }));
    setDirty(true);
    setConnected(false);
    setProjects([]);
    setApproved(false);
    setResult(null);
    setMessage("");
  }
  async function saveConnection(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await api("/settings/jira", "PUT", form);
      setForm((previous) => ({ ...previous, apiToken: "" }));
      setDirty(false);
      setConnected(false);
      setProjects([]);
      notifyJiraSettingsChanged();
      setMessage(
        "연결 설정을 저장했습니다. 연결 테스트를 실행해 프로젝트를 불러오세요.",
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function saveProject() {
    setBusy(true);
    setError("");
    try {
      await api("/settings/jira/project", "PUT", { projectKey: project });
      notifyJiraSettingsChanged();
      setMessage(
        "기본 프로젝트를 저장했습니다. 새 회의 등록 시 자동 선택됩니다.",
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function removeToken() {
    setBusy(true);
    setError("");
    try {
      await api("/settings/jira/token", "DELETE");
      setForm((previous) => ({ ...previous, apiToken: "" }));
      setDirty(false);
      setConnected(false);
      setProjects([]);
      notifyJiraSettingsChanged();
      setMessage("저장된 API 토큰을 삭제했습니다.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    api<typeof logs>("/audit-logs")
      .then(setLogs)
      .catch(() => {});
  }, []);
  useEffect(() => {
    setTypes([]);
    setType("");
    setFields([]);
    setApproved(false);
    if (!connected || !project) return;
    let active = true;
    api<IssueType[]>(`/integrations/jira/projects/${project}/issue-types`)
      .then((data) => {
        if (active) {
          const available = data.filter((t) => t.subtask !== "true");
          setTypes(available);
          setType(available[0]?.id || "");
        }
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [project, connected]);
  useEffect(() => {
    setFields([]);
    if (!connected || !project || !type) return;
    let active = true;
    api<JiraField[]>(
      `/integrations/jira/projects/${project}/issue-types/${type}/fields`,
    )
      .then((data) => {
        if (active) setFields(data);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [project, type, connected]);
  async function test() {
    setBusy(true);
    setError("");
    setMessage("");
    setConnected(false);
    try {
      const r = await api<{ message: string; connected: boolean }>(
        "/integrations/jira/test",
        "POST",
      );
      setMessage(r.message);
      if (r.connected || !real) {
        const available = await api<Project[]>("/integrations/jira/projects");
        setProjects(available);
        setProject((previous) =>
          available.some((p) => p.key === previous)
            ? previous
            : available[0]?.key || "",
        );
        setConnected(true);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function create() {
    setBusy(true);
    setError("");
    try {
      const r = await api<NonNullable<typeof result>>(
        "/integrations/jira/test-issue",
        "POST",
        { projectKey: project, issueType: type, approved },
      );
      setResult(r);
      if (r.error) setError(r.error);
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
          <span className="eyebrow">SETTINGS</span>
          <h1>우리 팀에 맞는 연결</h1>
          <p>Jira를 연결하고 테스트 이슈 한 개로 실제 등록을 확인하세요.</p>
        </div>
      </div>
      <div className="notice settings-notice">
        <ShieldCheck size={22} />
        <div>
          <strong>
            {real ? "실제 Jira Cloud 연동 모드" : "현재 Jira는 Mock 모드입니다"}
          </strong>
          <p>
            음성 기록을 가져온 뒤 AI가 업무와 티켓 설명을 정리합니다. AI 키와 Jira
            연결은 아래에서 각각 설정하세요.
          </p>
        </div>
      </div>
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}
      <div className="settings-grid">
        <section id="jira-settings" className="panel padded">
          <div className="section-head">
            <h2>Jira Cloud 연결</h2>
            <span className="tag">{real ? "CLOUD" : "MOCK"}</span>
          </div>
          <JiraConnectionGuide siteUrl={form.siteUrl} tokenConfigured={settings.tokenConfigured}/>
          <form onSubmit={saveConnection} className="jira-connection-form">
            <fieldset disabled={busy || !settings.ready}>
              <div className="form-grid">
                <label className="full">
                  연동 모드
                  <select
                    value={form.mode}
                    onChange={(e) => change("mode", e.target.value)}
                  >
                    <option value="mock">Mock · 모의 등록</option>
                    <option value="cloud">실제 Jira Cloud</option>
                  </select>
                </label>
                <label className="full">
                  Jira 사이트 URL
                  <input
                    type="url"
                    value={form.siteUrl}
                    required={form.mode === "cloud"}
                    maxLength={300}
                    onChange={(e) => change("siteUrl", e.target.value)}
                    placeholder="https://your-team.atlassian.net"
                    autoComplete="url"
                  />
                </label>
                <label className="full">
                  Atlassian 계정 이메일
                  <input
                    type="email"
                    value={form.email}
                    required={form.mode === "cloud"}
                    maxLength={254}
                    onChange={(e) => change("email", e.target.value)}
                    placeholder="me@example.com"
                    autoComplete="username"
                  />
                </label>
                <label className="full">
                  Jira API 토큰
                  <input
                    type="password"
                    value={form.apiToken}
                    maxLength={8192}
                    onChange={(e) => change("apiToken", e.target.value)}
                    placeholder={
                      settings.tokenConfigured
                        ? "저장됨 · 변경할 때만 새 토큰 입력"
                        : "API 토큰 입력"
                    }
                    autoComplete="new-password"
                    spellCheck={false}
                  />
                </label>
                <p className="help full token-status">
                  {settings.tokenConfigured
                    ? "토큰이 저장되어 있습니다. 빈칸으로 저장하면 기존 토큰을 유지합니다."
                    : "아직 저장된 토큰이 없습니다."}{" "}
                  토큰은 저장 후 다시 표시하지 않습니다.
                </p>
                <label className="full">
                  Cloud ID (스코프 토큰 사용 시)
                  <input
                    value={form.cloudId}
                    maxLength={100}
                    onChange={(e) => change("cloudId", e.target.value)}
                    placeholder="일반 토큰이면 비워 두세요"
                    autoComplete="off"
                  />
                </label>
              </div>
            </fieldset>
            <div className="actions settings-actions">
              <button
                className="primary"
                type="submit"
                disabled={busy || !settings.ready || !dirty}
              >
                연결 설정 저장
              </button>
              <button
                type="button"
                className="danger"
                disabled={busy || !settings.tokenConfigured}
                onClick={removeToken}
              >
                토큰 삭제
              </button>
            </div>
          </form>
          {real && !settings.configured && (
            <p className="help">필요한 설정: {settings.missing.join(", ")}</p>
          )}
          <button
            disabled={
              busy || dirty || !settings.ready || (real && !settings.configured)
            }
            onClick={test}
          >
            {real ? "실제 Jira 연결 테스트" : "Mock 어댑터 점검"}
            <ArrowUpRight size={15} />
          </button>
          {message && (
            <p className="help" role="status">
              {message}
            </p>
          )}
          {dirty && <p className="help">변경한 연결 설정을 먼저 저장하세요.</p>}
          <div className="default-project-settings">
            <h3>기본 프로젝트</h3>
            <p className="help">
              연결 테스트로 불러온 프로젝트 중 새 회의에서 사용할 프로젝트를
              선택하세요.
            </p>
            <label className="project-picker">
              프로젝트
              <select
                aria-label="기본 프로젝트"
                value={project}
                disabled={!connected || busy || dirty}
                onChange={(e) => setProject(e.target.value)}
              >
                <option value="">프로젝트 선택</option>
                {projects.map((p) => (
                  <option key={p.key} value={p.key}>
                    {p.key} · {p.name}
                  </option>
                ))}
              </select>
            </label>
            <p className="help">
              현재 기본값:{" "}
              <strong>{settings.defaultProject || "미설정"}</strong>
            </p>
            <button
              disabled={
                !connected ||
                !project ||
                busy ||
                dirty ||
                project === settings.defaultProject
              }
              onClick={saveProject}
            >
              기본 프로젝트 저장
            </button>
          </div>
          <div className="setup-links">
            <a
              href="https://www.atlassian.com/software/jira/free"
              target="_blank"
              rel="noreferrer"
            >
              Jira 사이트 만들기
              <ExternalLink size={13} />
            </a>
            <a
              href="https://id.atlassian.com/manage-profile/security/api-tokens"
              target="_blank"
              rel="noreferrer"
            >
              Atlassian API 토큰 관리
              <ExternalLink size={13} />
            </a>
          </div>
        </section>
        <section className="panel padded">
          <div className="section-head">
            <h2>실제 테스트 이슈 1개 생성</h2>
            <span className="tag">실제 등록</span>
          </div>
          <p className="help">
            연결 테스트 후 프로젝트와 이슈 유형을 선택하세요. 같은
            사이트·프로젝트에서는 기존 테스트 티켓을 재사용합니다.
          </p>
          <div className="form-grid">
            <label className="full">
              테스트 프로젝트
              <select
                aria-label="테스트 프로젝트"
                value={project}
                disabled={!connected || busy}
                onChange={(e) => setProject(e.target.value)}
              >
                <option value="">프로젝트 선택</option>
                {projects.map((p) => (
                  <option key={p.key} value={p.key}>
                    {p.key} · {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="full">
              이슈 유형
              <select
                aria-label="테스트 이슈 유형"
                value={type}
                disabled={!connected || busy}
                onChange={(e) => setType(e.target.value)}
              >
                <option value="">유형 선택</option>
                {types.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </select>
            </label>
          </div>
          {fields.length > 0 && (
            <p className="help">
              실제 필수 필드:{" "}
              {fields
                .filter((f) => f.required)
                .map((f) => f.name)
                .join(", ") || "없음"}
              . 추가 커스텀 필수 필드가 있으면 테스트 프로젝트 설정을
              조정하세요.
            </p>
          )}
          <label className="confirm-label">
            <input
              type="checkbox"
              checked={approved}
              disabled={!connected || !real || busy}
              onChange={(e) => setApproved(e.target.checked)}
            />
            선택한 Jira 프로젝트에 테스트 이슈 1개를 생성하는 데 동의합니다.
          </label>
          <button
            className="primary"
            disabled={
              !real ||
              !connected ||
              !project ||
              !type ||
              !approved ||
              busy ||
              dirty
            }
            onClick={create}
          >
            {result?.status === "UNKNOWN"
              ? "기존 생성 결과 확인"
              : "테스트 이슈 1개 생성"}
            <ArrowUpRight size={15} />
          </button>
          {result && (
            <div className="test-issue-result">
              <strong>{result.issueKey || result.status}</strong>
              {result.issueUrl && (
                <a href={result.issueUrl} target="_blank" rel="noreferrer">
                  Jira에서 열기
                  <ExternalLink size={14} />
                </a>
              )}
              <Link to="/tickets">티켓 관리에서 결과 확인</Link>
            </div>
          )}
        </section>
        <ConfluenceSettingsCard />
        <SpeechSettingsCard />
        <AiSettingsCard />
        <section className="panel padded full">
          <h2>음성 기록 입력과 업무 분석</h2>
          <p>
            녹음 출처를 구분하지 않습니다. 클로바노트 등에서 텍스트를 복사해
            붙여넣거나 TXT/SRT/VTT 파일로 가져오세요.
          </p>
          <p className="help">
            클로바노트 기록을 가져오거나 녹음 파일을 자동으로 텍스트로 변환할 수 있습니다.
            회의록·업무 분석·Jira 초안은 위에서 선택한 AI 모델로 처리합니다.
            Mock은 시연을 위해 명시적으로 선택할 수 있는 별도 모드입니다.
          </p>
          <Link className="button" to="/meetings/new">
            음성 기록으로 회의 등록
          </Link>
        </section>
      </div>
      <div className="section-head">
        <h2>
          최근 감사 로그 <span>최대 100건</span>
        </h2>
      </div>
      <div className="panel table-scroll">
        <table>
          <thead>
            <tr>
              <th>작업</th>
              <th>대상</th>
              <th>기록 시각 (UTC)</th>
            </tr>
          </thead>
          <tbody>
            {logs.map((l) => (
              <tr key={l.id}>
                <td>{l.action}</td>
                <td className="mono">{l.targetId.slice(0, 12)}</td>
                <td>{l.timestamp.replace("T", " ").slice(0, 19)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
