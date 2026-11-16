import {useCallback,useEffect,useState} from 'react';
import {api} from './api';

type SpeechStatus={ready:boolean;provider:string;model:string;message:string;loading?:boolean};
export function useSpeechStatus(){
  const [status,setStatus]=useState<SpeechStatus>({ready:false,provider:'local-whisper',model:'small',message:'음성 텍스트 변환 준비 상태를 확인하고 있습니다.',loading:true});
  const refresh=useCallback(async()=>{try{setStatus({...await api<SpeechStatus>('/integrations/speech'),loading:false});}catch{setStatus(s=>({...s,loading:false,ready:false,message:'음성 엔진 상태를 확인할 수 없습니다. 서버 연결을 확인하세요.'}));}},[]);
  useEffect(()=>{void refresh();window.addEventListener('focus',refresh);return()=>window.removeEventListener('focus',refresh);},[refresh]);
  useEffect(()=>{if(status.ready)return;const timer=setInterval(()=>void refresh(),5000);return()=>clearInterval(timer);},[status.ready,refresh]);
  return {...status,refresh};
}
export function SpeechSettingsCard(){
  const speech=useSpeechStatus();
  return <section className="panel padded full"><div className="section-head"><h2>녹음·녹화 자동 텍스트 변환</h2><span className="tag">{speech.ready?'준비 완료':'준비 필요'}</span></div><p>{speech.message}</p><p className="help">M4A·MP3·WAV·MP4·WEBM의 음성을 로컬에서 텍스트로 변환합니다. 화자 구분 모델이 준비되면 음성별로 자동 분류하며, 음성 기록 화면에서 실제 참석자 이름을 연결할 수 있습니다. 동시 발언 등 자동 구분 결과는 검토하세요.</p><button onClick={()=>void speech.refresh()}>음성 엔진 상태 확인</button></section>;
}
