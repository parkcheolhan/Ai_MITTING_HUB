import {useEffect,useState} from "react";
import {useLocation,useNavigate} from "react-router-dom";
import {useAiSettings} from "./ai";
import {useJiraSettings} from "./integrations";

export const tutorialKey="meetinghub-setup-tutorial-v1";
export function tutorialNeeded(){try{return !localStorage.getItem(tutorialKey);}catch{return true;}}
export default function SetupTutorial({close}:{close:()=>void}){
  const [step,setStep]=useState(0);const navigate=useNavigate(),location=useLocation();const ai=useAiSettings(),jira=useJiraSettings();
  useEffect(()=>{if(location.hash){const timer=setTimeout(()=>document.getElementById(location.hash.slice(1))?.scrollIntoView({behavior:"smooth",block:"start"}),150);return()=>clearTimeout(timer);}},[location.pathname,location.hash]);
  function finish(){try{localStorage.setItem(tutorialKey,"dismissed");}catch{}close();}
  const titles=["시작하기","AI 설정","Jira 설정","준비 완료"];
  return <section className="setup-tutorial panel padded" aria-label="처음 사용 설정 가이드">
    <div className="section-head"><div><span className="eyebrow">GET STARTED</span><h2>회의를 업무로 연결하는 첫 설정</h2><p>AI 분석과 Jira 연동을 순서대로 설정하세요.</p></div><button onClick={finish}>나중에 설정</button></div>
    <ol className="setup-tutorial-steps">{titles.map((title,i)=><li key={title}><button aria-current={step===i?"step":undefined} onClick={()=>setStep(i)}><span>{i+1}</span>{title}</button></li>)}</ol>
    {step===0&&<div><h3>AI가 정리하고 Jira로 연결합니다</h3><p>AI 설정 → Jira 연결 → 회의 등록 순서로 진행합니다. 분석 후 업무 후보가 자동 생성되며, 검토·승인한 업무만 Jira에 등록됩니다.</p></div>}
    {step===1&&<div><h3>AI 분석 방식 선택</h3><p>Codex는 계정 로그인, OpenAI는 API 키로 연결합니다. 설정을 저장하고 ‘AI 모델 연결 테스트’로 동작을 확인하세요.</p><p className="help">현재 상태: {ai.ready?(ai.configured&&ai.mode!=="mock"?"AI 설정 저장됨 · 연결 테스트를 확인하세요.":"실제 AI 설정 필요"):"설정 확인 중"}</p><button onClick={()=>navigate('/settings#ai-settings')}>AI 설정 열기</button></div>}
    {step===2&&<div><h3>Jira Cloud 연결</h3><p>사이트 URL, Atlassian 이메일, API 토큰을 입력하고 저장하세요. ‘실제 Jira 연결 테스트’ 후 사용할 기본 프로젝트를 선택합니다.</p><p className="help">현재 상태: {jira.ready?(jira.configured&&jira.mode==="cloud"?"Jira 설정 저장됨 · 연결 테스트를 확인하세요.":"실제 Jira 연결 설정 필요"):"설정 확인 중"}</p><button onClick={()=>navigate('/settings#jira-settings')}>Jira 설정 열기</button></div>}
    {step===3&&<div><h3>회의 등록을 시작하세요</h3><p>새 회의에 녹음·영상 또는 음성 기록을 등록하세요. 회의록과 업무 분석, 티켓구성 단계가 자동으로 진행됩니다. 이 가이드는 왼쪽 ‘설정 가이드’에서 다시 열 수 있습니다.</p><button onClick={()=>{finish();navigate('/meetings/new');}}>새 회의 등록</button></div>}
    <div className="form-actions">{step>0&&<button onClick={()=>setStep(s=>s-1)}>이전</button>}{step<3?<button className="primary" onClick={()=>setStep(s=>s+1)}>다음: {titles[step+1]}</button>:<button className="primary" onClick={finish}>가이드 완료</button>}</div>
  </section>;
}
