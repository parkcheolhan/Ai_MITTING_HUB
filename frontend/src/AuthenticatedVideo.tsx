import {useEffect,useState,type ComponentProps} from 'react';
import {pagesMode,apiUrl,authHeaders} from './remote';
export default function AuthenticatedVideo({path,...props}:ComponentProps<'video'>&{path:string}){
  const [source,setSource]=useState(''),[error,setError]=useState('');
  useEffect(()=>{
    if(!pagesMode)return;
    const abort=new AbortController();let objectUrl='';setSource('');setError('');
    void fetch(apiUrl(path),{headers:authHeaders(),signal:abort.signal,cache:'no-store'}).then(async res=>{if(!res.ok)throw new Error(`미디어 로드 실패 (${res.status})`);const blob=await res.blob();if(!abort.signal.aborted){objectUrl=URL.createObjectURL(blob);setSource(objectUrl);}}).catch(e=>{if(!abort.signal.aborted)setError(e.message);});
    return ()=>{abort.abort();if(objectUrl)URL.revokeObjectURL(objectUrl);};
  },[path]);
  return <>{pagesMode&&!source&&<p role="status">{error||'재생 파일을 안전하게 불러오는 중입니다…'}</p>}<video {...props} src={pagesMode?source||undefined:apiUrl(path)}/></>;
}
