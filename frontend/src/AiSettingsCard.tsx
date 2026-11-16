import { useEffect, useState, type FormEvent } from "react";
import { Sparkles } from "lucide-react";
import { api } from "./api";
import CodexLoginCard from "./CodexLoginCard";
import { useAiSettings, notifyAiSettingsChanged } from "./ai";

export default function AiSettingsCard() {
  const settings = useAiSettings();
  const [mode, setMode] = useState("openai"),
    [model, setModel] = useState("gpt-4.1-mini"),
    [key, setKey] = useState("");
  const [busy, setBusy] = useState(false),
    [dirty, setDirty] = useState(false),
    [message, setMessage] = useState(""),
    [error, setError] = useState("");
  useEffect(() => {
    if (settings.ready) {
      setMode(settings.mode);
      setModel(settings.model);
      setKey("");
      setDirty(false);
    }
  }, [settings.ready, settings.mode, settings.model]);
  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage("");
    setError("");
    try {
      await api("/settings/ai", "PUT", { mode, model, apiKey: key });
      setKey("");
      setDirty(false);
      notifyAiSettingsChanged();
      setMessage("AI 설정을 저장했습니다. 다음 분석·티켓 정리부터 적용됩니다.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function remove() {
    setBusy(true);
    setError("");
    try {
      await api("/settings/ai/key", "DELETE");
      setKey("");
      setDirty(false);
      notifyAiSettingsChanged();
      setMessage("AI API 키를 삭제했습니다.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function test() {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const result = await api<{ message: string }>(
        "/integrations/ai/test",
        "POST",
      );
      setMessage(result.message);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  function changed() {
    setDirty(true);
    setMessage("");
  }
  return (
    <section id="ai-settings" className="panel padded full ai-settings-card">
      <div className="section-head">
        <div>
          <h2>
            <Sparkles size={18} /> AI 분석 · Jira 초안 정리
          </h2>
          <p>회의록, 업무 분석과 Jira 설명을 선택한 AI 모델이 작성합니다.</p>
        </div>
        <span className="tag">
          {settings.mode === "codex" ? "CODEX" : settings.mode === "openai" ? "OPENAI" : "MOCK · 시연 전용"}
        </span>
      </div>
      <form className="jira-connection-form" onSubmit={save}>
        <fieldset disabled={busy || !settings.ready}>
          <div className="form-grid">
            <label>
              AI 처리 모드
              <select
                aria-label="AI 처리 모드"
                value={mode}
                onChange={(e) => {
                  setMode(e.target.value);
                  setModel(e.target.value === "codex" ? "codex-default" : "gpt-4.1-mini");
                  changed();
                }}
              >
                <option value="openai">OpenAI · 실제 AI 분석</option>
                <option value="codex">Codex CLI · 로그인 계정으로 AI 분석</option>
                <option value="mock">Mock · 규칙 기반 시연</option>
              </select>
            </label>
            <label>
              AI 모델 {mode === "codex" && "(codex-default: CLI 기본 모델)"}
              <input
                aria-label="AI 모델"
                list="ai-models"
                value={model}
                required
                maxLength={100}
                pattern="[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"
                onChange={(e) => {
                  setModel(e.target.value);
                  changed();
                }}
                placeholder={mode === "codex" ? "codex-default" : "gpt-4.1-mini"}
              />
              <datalist id="ai-models">
                <option value="gpt-4.1-mini" />
              </datalist>
            </label>
            <label className="full" style={mode !== "openai" ? {display: "none"} : undefined}>
              OpenAI API 키
              <input
                aria-label="OpenAI API 키"
                type="password"
                value={key}
                maxLength={8192}
                onChange={(e) => {
                  setKey(e.target.value);
                  changed();
                }}
                placeholder={
                  settings.keyConfigured
                    ? "저장됨 · 변경할 때만 입력"
                    : "OpenAI API 키 입력"
                }
                autoComplete="new-password"
                spellCheck={false}
              />
            </label>
            {mode === "codex" && <p className="help full">별도 API 키 없이 아래에서 Codex에 로그인하세요. 설정 저장 후 연결 테스트를 실행할 수 있습니다. Codex 계정의 사용 한도가 적용됩니다.</p>}
            <p className="help full" style={mode !== "openai" ? {display: "none"} : undefined}>
              {settings.keyConfigured
                ? "API 키가 암호화 저장되어 있습니다. 빈칸으로 저장하면 기존 키를 유지합니다."
                : "아직 AI API 키가 없습니다. 실제 분석을 사용하려면 키를 저장하세요."}{" "}
              저장한 키는 다시 표시하지 않습니다.
            </p>
          </div>
        </fieldset>
        <div className="actions settings-actions">
          <button
            type="submit"
            className="primary"
            disabled={busy || !settings.ready || !dirty}
          >
            AI 설정 저장
          </button>
          <button
            type="button"
            disabled={busy || dirty || !settings.ready || !settings.configured}
            onClick={test}
          >
            AI 모델 연결 테스트
          </button>
          <button
            type="button"
            className="danger"
            disabled={busy || !settings.keyConfigured}
            onClick={remove}
          >
            AI API 키 삭제
          </button>
        </div>
      </form>
      {mode === "codex" && <CodexLoginCard />}
      {message && (
        <p className="help" role="status">
          {message}
        </p>
      )}
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}
      <div className="notice">
        <strong>AI가 정리하고, 사용자가 최종 검토합니다</strong>
        <p>
          음성 기록·회의록·연결된 업무와 기존 초안을 모델에 보내 작업·확인
          사항·완료 조건·불확실성을 정리합니다. 명시되지 않은 담당자·기한은 자동
          지정하지 않습니다. Jira 등록은 별도 승인 후에만 진행합니다.
        </p>
        <p>
          OpenAI 모드는 API를, Codex 모드는 서버의 Codex CLI를 호출합니다.
          선택한 서비스의 사용 한도와 계정 권한이 적용됩니다. 호출에 실패하면
          Mock으로 바꾸지 않습니다.
        </p>
      </div>
    </section>
  );
}
