import {useEffect,useRef,useState,type ReactNode,type FormEvent} from 'react';
import {connection,disconnect,rememberConnection,validateServer} from './remote';
class TemporaryConnectionError extends Error {}
function pause(ms:number,signal:AbortSignal){return new Promise<void>((resolve,reject)=>{function cancel(){clearTimeout(timer);reject(new DOMException('Cancelled','AbortError'));}const timer=setTimeout(()=>{signal.removeEventListener('abort',cancel);resolve();},ms);signal.addEventListener('abort',cancel,{once:true});if(signal.aborted)cancel();});}
export default function ServerConnection({children}:{children:ReactNode}){
  const [connected,setConnected]=useState(false),[server,setServer]=useState(connection().server||'https://chicago-solomon-compared-cannon.trycloudflare.com'),[token,setToken]=useState(connection().token),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const [id,setId]=useState(connection().user||'admin');
  const [progress,setProgress]=useState(''),request=useRef<AbortController|null>(null);
  useEffect(()=>()=>request.current?.abort(),[]);
  async function connect(e:FormEvent){
    e.preventDefault();const controller=new AbortController();request.current=controller;setBusy(true);setError('');setProgress('');
    try{
      const value={server:validateServer(server),token:token.trim()};
      if(!id.trim()||!value.token)throw new Error('ID와 토큰을 입력하세요.');
      const legacy=value.token.length>=32;
      for(let attempt=0;attempt<4;attempt++){
        setProgress(attempt?`터널 연결을 다시 확인하고 있습니다. (${attempt+1}/4)`:'서버 연결을 확인하고 있습니다.');
        try{
          const res=await fetch(value.server+(legacy?'/api/meetings':'/api/auth/login'),{method:legacy?'GET':'POST',headers:legacy?{Authorization:'Bearer '+value.token}:{'Content-Type':'application/json'},body:legacy?undefined:JSON.stringify({id:id.trim(),token:value.token}),signal:AbortSignal.any([controller.signal,AbortSignal.timeout(15000)]),cache:'no-store'});
          if(res.status>=500)throw new TemporaryConnectionError(`서버 또는 터널이 아직 응답하지 않습니다. (${res.status})`);
          if(!res.ok)throw new Error(res.status===401?(legacy?'접속 토큰이 올바르지 않습니다.':'ID 또는 토큰이 올바르지 않습니다.'):res.status===429?'로그인 시도가 많습니다. 1분 후 다시 시도하세요.':res.status===403?'현재 Pages 주소의 접속이 허용되지 않았습니다. 서버의 허용 주소를 확인하세요.':`서버 응답 오류 (${res.status})`);
          let result:unknown;try{result=await res.json();}catch(err){if(err instanceof SyntaxError)throw new Error('모아 API 응답을 받지 못했습니다. API 서버 HTTPS 주소를 확인하세요.');throw err;}
          if(legacy){if(!Array.isArray(result))throw new Error('모아 API 서버 주소인지 확인하세요.');rememberConnection(value);}
          else{const session=result as {token?:string;user?:string;expiresAt?:string};if(typeof session?.token!=='string'||session.token.length<32||typeof session.user!=='string')throw new Error('서버 로그인 응답을 확인하세요.');rememberConnection({server:value.server,token:session.token,user:session.user,expiresAt:session.expiresAt});setToken('');}
          setConnected(true);return;
        }catch(err){
          if(controller.signal.aborted)return;
          const temporary=err instanceof TemporaryConnectionError||err instanceof TypeError||(err as Error).name==='TimeoutError';
          if(!temporary||attempt===3)throw err;
          await pause([1000,2000,4000][attempt],controller.signal);
        }
      }
    }catch(err){if(!controller.signal.aborted)setError(err instanceof TypeError||err instanceof TemporaryConnectionError||(err as Error).name==='TimeoutError'?'터널에 연결할 수 없어 4회 확인했습니다. API 서버 주소와 서버·터널 실행 상태를 확인한 뒤 다시 연결하세요.':(err as Error).message);}finally{if(request.current===controller){request.current=null;setBusy(false);setProgress('');}}
  }
  if(connected)return <><div className="server-connection-bar"><span>로컬 서버 연결 · {connection().server}</span><button onClick={disconnect}>서버 변경 / 연결 해제</button></div>{children}</>;
  return <main style={{maxWidth:640,margin:'8vh auto',padding:24}}><h1>모아 · 서버 연결</h1><p>GitHub Pages 화면에서 내 PC의 AI Meeting Hub 서버에 연결합니다.</p><form onSubmit={connect}><fieldset disabled={busy} style={{border:0,padding:0}}><label>API 서버 HTTPS 주소<input type="url" required value={server} onChange={e=>setServer(e.target.value)} placeholder="https://api.example.com"/></label><label>ID<input aria-label="ID" required autoComplete="username" value={id} onChange={e=>setId(e.target.value)}/></label><label>접속 토큰<input type="password" required autoComplete="off" value={token} onChange={e=>setToken(e.target.value)}/></label><p>ID와 토큰으로 로그인합니다. 로그인 세션은 이 탭에서만 유지됩니다.</p><button className="primary">{busy?'연결 확인 중…':'서버 연결'}</button></fieldset></form>{busy&&<div><p role="status">{progress}</p><button type="button" onClick={()=>request.current?.abort()}>연결 취소</button></div>}{error&&<p role="alert" className="error">{error}</p>}</main>;
}
