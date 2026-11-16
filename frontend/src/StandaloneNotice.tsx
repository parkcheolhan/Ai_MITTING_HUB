import {useState} from 'react';
import {backupLocalData,restoreLocalData} from './standalone';
export default function StandaloneNotice(){
  const [error,setError]=useState('');
  return <section style={{padding:'16px 24px',background:'#fff3d6',color:'#382d15'}} aria-label="HTML 로컬 버전 안내">
    <strong>모아 · HTML 로컬 버전</strong>
    <p>프로젝트·회의·전사문·수동 회의록을 이 브라우저에 저장합니다. AI 분석·음성 변환·Jira 등록은 Java 서버 버전이 필요합니다. 브라우저 데이터 삭제나 HTML 파일 위치 변경에 대비해 백업하세요.</p>
    <div className="actions"><button onClick={()=>{try{backupLocalData();setError('');}catch{setError('백업할 데이터를 읽을 수 없습니다. 브라우저 저장 권한을 확인하세요.');}}}>데이터 백업</button>
    <label>백업 복원 (현재 데이터 교체)<input type="file" accept="application/json,.json" onChange={async e=>{const file=e.target.files?.[0];if(!file)return;if(!window.confirm('현재 브라우저 데이터를 백업 파일로 교체할까요?'))return;try{await restoreLocalData(file);}catch(err){setError((err as Error).message);}e.target.value='';}}/></label></div>
    {error&&<p role="alert">{error}</p>}
  </section>;
}
