import {useState} from 'react';
import {api,upload} from './api';
import type {Meeting} from './types';

export default function SpeechRetryUpload({meeting,disabled,reload}:{meeting:Meeting;disabled:boolean;reload:()=>Promise<void>}){
  const [file,setFile]=useState<File|null>(null),[busy,setBusy]=useState(false),[progress,setProgress]=useState(0),[error,setError]=useState(''),[replaced,setReplaced]=useState(false);
  const failed=meeting.jobs[0];
  if(failed?.status!=='FAILED'||failed.stage!=='TRANSCRIBING'||meeting.content.segments.length)return null;
  async function retry(){
    if(!file)return;setBusy(true);setError('');
    try{
      if(!replaced){await upload(meeting.id,file,setProgress,'PUT');setReplaced(true);}
      await api(`/meetings/${meeting.id}/${failed.type==='PIPELINE'?'process':'transcribe'}`,'POST');
      await reload();
    }catch(e){setError((e as Error).message);await reload().catch(()=>{});}finally{setBusy(false);}
  }
  return <section className="panel padded" aria-label="음성 파일 다시 등록">
    <h3>음성 파일 다시 등록</h3>
    <p className="help">다른 파일을 선택하거나 원본 파일을 다시 올려 주세요. 기존 업로드 파일 목록을 새 파일로 교체한 뒤 음성 변환을 재시도합니다.</p>
    <label>다시 등록할 음성·영상 파일<input type="file" aria-label="다시 등록할 음성 파일" accept=".mp3,.wav,.m4a,.aac,.acc,.mp4,.webm" disabled={busy||disabled} onChange={e=>{
      const selected=e.target.files?.[0];setError('');setReplaced(false);setProgress(0);setFile(null);
      if(!selected)return;
      if(!/\.(mp3|wav|m4a|aac|acc|mp4|webm)$/i.test(selected.name)||!selected.size||selected.size>100*1024*1024){setError('MP3/WAV/M4A/AAC/ACC/MP4/WEBM, 100MB 이하의 파일을 선택하세요.');return;}
      setFile(selected);
    }}/></label>
    {progress>0&&<progress value={progress} max={100} aria-label="음성 파일 재등록 진행률"/>}
    {replaced&&<p className="help" role="status">새 파일이 저장되었습니다. 변환 요청에 실패하면 업로드 없이 다시 시도합니다.</p>}
    {error&&<p className="error" role="alert">{error}</p>}
    <button type="button" className="primary" disabled={!file||busy||disabled} onClick={()=>void retry()}>{busy?'파일 등록·변환 요청 중…':'파일 다시 등록 후 음성 변환'}</button>
  </section>;
}
