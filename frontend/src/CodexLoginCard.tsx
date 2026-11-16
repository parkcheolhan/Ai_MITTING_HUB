import {useEffect, useState} from "react";
import {api} from "./api";

type Login = {state:string; loggedIn:boolean; userCode:string; verificationUrl:string; message:string};
export default function CodexLoginCard() {
  const [login,setLogin]=useState<Login|null>(null), [busy,setBusy]=useState(false), [error,setError]=useState("");
  const pending=login?.state === "STARTING" || login?.state === "WAITING";
  async function refresh(){try{setLogin(await api<Login>("/settings/ai/codex/login"));setError("");}catch(e){setError((e as Error).message);}}
  useEffect(()=>{void refresh();},[]);
  useEffect(()=>{if(!pending)return;const timer=setInterval(()=>void refresh(),1500);return ()=>clearInterval(timer);},[pending]);
  async function action(method:"POST"|"DELETE"){
    setBusy(true);setError("");
    try{setLogin(await api<Login>("/settings/ai/codex/login",method));}catch(e){setError((e as Error).message);}finally{setBusy(false);}
  }
  return <div className="notice codex-login" aria-label="Codex 로그인">
    <strong>Codex 계정 로그인</strong>
    <p role="status">{login?.message || "Codex 로그인 상태를 확인하고 있습니다."}</p>
    {login?.loggedIn && <p className="help">현재 서버 계정에 로그인 정보가 저장되어 있습니다.</p>}
    <div className="actions">
      <button type="button" disabled={busy||pending} onClick={()=>void action("POST")}>{login?.loggedIn?"Codex 다시 로그인":"Codex 로그인"}</button>
      {pending && <button type="button" disabled={busy} onClick={()=>void action("DELETE")}>로그인 취소</button>}
      {!pending && <button type="button" disabled={busy} onClick={()=>void refresh()}>로그인 상태 확인</button>}
    </div>
    {login?.userCode && <div className="codex-device-code">
      <p>아래 인증 페이지를 열고 이 코드를 입력하세요.</p>
      <code aria-label="Codex 인증 코드">{login.userCode}</code>
      <a className="button primary" href="https://auth.openai.com/codex/device" target="_blank" rel="noopener noreferrer">Codex 인증 페이지 열기 ↗</a>
    </div>}
    {error && <p className="error" role="alert">{error}</p>}
    <p className="help">인증은 OpenAI 페이지에서 진행합니다. 기기 코드 로그인이 비활성화되어 있으면 ChatGPT 보안 설정에서 허용한 뒤 다시 시도하세요.</p>
  </div>;
}
