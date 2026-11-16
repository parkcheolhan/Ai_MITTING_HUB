import {useState,type ReactNode,type FormEvent} from 'react';
import {apiUrl,authHeaders,connection,disconnect,rememberConnection,validateServer} from './remote';
export default function ServerConnection({children}:{children:ReactNode}){
  const [connected,setConnected]=useState(false),[server,setServer]=useState(connection().server),[token,setToken]=useState(connection().token),[busy,setBusy]=useState(false),[error,setError]=useState('');
  async function connect(e:FormEvent){
    e.preventDefault();setBusy(true);setError('');
    const old=connection();
    try{
      const value={server:validateServer(server),token:token.trim()};
      if(value.token.length<32)throw new Error('서버 실행 시 발급한 32자 이상의 접속 토큰을 입력하세요.');
      rememberConnection(value);
      const res=await fetch(apiUrl('/meetings'),{headers:authHeaders(),signal:AbortSignal.timeout(15000),cache:'no-store'});
      if(!res.ok)throw new Error(res.status===401?'접속 토큰이 올바르지 않습니다.':`서버 응답 오류 (${res.status})`);
      if(!Array.isArray(await res.json()))throw new Error('모아 API 서버 주소인지 확인하세요.');
      setConnected(true);
    }catch(err){rememberConnection(old);setError(err instanceof TypeError?'서버에 연결할 수 없습니다. 서버·터널 실행 및 허용된 Pages 주소를 확인하세요.':(err as Error).message);}finally{setBusy(false);}
  }
  if(connected)return <><div className="server-connection-bar"><span>로컬 서버 연결 · {connection().server}</span><button onClick={disconnect}>서버 변경 / 연결 해제</button></div>{children}</>;
  return <main style={{maxWidth:640,margin:'8vh auto',padding:24}}><h1>모아 · 서버 연결</h1><p>GitHub Pages 화면에서 내 PC의 AI Meeting Hub 서버에 연결합니다.</p><form onSubmit={connect}><fieldset disabled={busy} style={{border:0,padding:0}}><label>API 서버 HTTPS 주소<input type="url" required value={server} onChange={e=>setServer(e.target.value)} placeholder="https://api.example.com"/></label><label>접속 토큰<input type="password" required autoComplete="off" value={token} onChange={e=>setToken(e.target.value)}/></label><p>토큰은 이 탭의 세션에만 저장합니다. AI·Jira 키는 연결 후 서버 설정에서 입력하세요.</p><button className="primary">{busy?'연결 확인 중…':'서버 연결'}</button></fieldset></form>{error&&<p role="alert" className="error">{error}</p>}</main>;
}
