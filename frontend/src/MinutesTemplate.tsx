import { useEffect, useId, useRef, useState } from "react";
import { api } from './api';
import { basicTemplate, builtinMinutesTemplates, templateKeys, templateName, validateTemplateText, type SavedMinutesTemplate, type TemplateChoice } from './minutesTemplateCatalog';

export { basicTemplate } from './minutesTemplateCatalog';
const example:Record<string,string>={회의명:"제품 개선 주간 회의",회의일시:"2026년 11월 9일 10:00",참석자:"김민수, 이지은",메모:"다음 회의에서 진행 현황 공유",개요:"서비스 개선 사항 및 진행 현황을 점검함.",요약:"로그인 오류 개선 및 배포 일정 검토",논의사항:"로그인 오류 재현 결과를 공유하고 수정 범위를 검토함.",결정사항:"로그인 오류 수정 후 검증을 진행하기로 함.",미결사항:"배포 일정은 검증 결과 확인 후 확정 예정",후속조치:"담당자가 수정 결과 및 검증 내역을 공유하기로 함."};

function TemplatePreview({template}:{template:string}){
  const [sample,setSample]=useState(true);
  return <div className="template-preview">
    <div className="template-preview-bar"><strong>문서 미리보기</strong><div><button type="button" aria-pressed={!sample} onClick={()=>setSample(false)}>양식 구조</button><button type="button" aria-pressed={sample} onClick={()=>setSample(true)}>작성 예시</button></div></div>
    <p className="template-preview-caption">{sample?"설명을 위한 가상 예시입니다. 실제 회의 내용은 작성 시 입력됩니다.":"강조한 위치에 회의 정보와 분석 결과가 자동으로 입력됩니다."}</p>
    <div className="template-preview-paper" aria-label={sample?"회의록 작성 예시":"회의록 양식 구조"}>
      {template.split(/\r?\n/).map((line,i)=>{
        const content=line.replace(/^#{1,6}\s+/,"").split(/(\{\{\s*[^{}]+?\s*}})/g).map((part,j)=>{
          const slot=/^\{\{\s*([^{}]+?)\s*}}$/.exec(part);
          return slot?sample?<span key={j}>{example[slot[1].trim()]||part}</span>:<mark key={j}>{part}</mark>:<span key={j}>{part}</span>;
        });
        return /^#\s/.test(line)?<h3 key={i}>{content}</h3>:/^#{2,6}\s/.test(line)?<h4 key={i}>{content}</h4>:line.trim()?<p key={i}>{content}</p>:<div className="template-preview-gap" key={i}/>;
      })}
    </div>
  </div>;
}
export function downloadText(text: string, filename: string) {
  const url = URL.createObjectURL(new Blob(["\uFEFF", text], { type: "text/plain;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url; a.download = filename; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function MinutesTemplateDialog({value,onApply,onClose,disabled=false,applying=false}:{value:string;onApply:(value:string)=>void|Promise<void>;onClose:()=>void;disabled?:boolean;applying?:boolean}){
  const dialog=useRef<HTMLDialogElement>(null),fileInput=useRef<HTMLInputElement>(null),alive=useRef(false);
  const opener=useRef(document.activeElement as HTMLElement|null);
  const heading=useId();
  const [uploaded,setUploaded]=useState<SavedMinutesTemplate[]>([]);
  const [selectedId,setSelectedId]=useState(builtinMinutesTemplates.find(t=>t.value===value)?.id||'current');
  const [loading,setLoading]=useState(true),[uploading,setUploading]=useState(false),[saving,setSaving]=useState(false);
  const [error,setError]=useState(''),[notice,setNotice]=useState('');
  const choices:TemplateChoice[]=[...builtinMinutesTemplates,...uploaded.map(t=>({id:t.id,name:t.name,description:'업로드한 회의록 양식',value:t.template,kind:'uploaded' as const}))];
  if(!choices.some(t=>t.value===value))choices.push({id:'current',name:templateName(value),description:'현재 회의에 저장된 양식',value,kind:'current'});
  const selected=choices.find(t=>t.id===selectedId)||choices.find(t=>t.value===value)||choices[0];
  const locked=disabled||applying||saving||uploading;
  useEffect(()=>{
    alive.current=true;
    const element=dialog.current;
    element?.showModal();
    api<SavedMinutesTemplate[]>('/minutes-templates').then(list=>{
      if(alive.current)setUploaded(previous=>[...new Map([...list,...previous].map(t=>[t.id,t])).values()]);
    }).catch(e=>{if(alive.current)setError('업로드한 양식을 불러오지 못했습니다. '+(e as Error).message);})
      .finally(()=>{if(alive.current)setLoading(false);});
    return()=>{alive.current=false;element?.close();opener.current?.focus();};
  },[]);
  async function read(file?:File){
    if(!file)return;
    setError('');setNotice('');setUploading(true);
    try{
      if(!/\.(txt|md)$/i.test(file.name)||!file.size||file.size>100*1024)throw new Error('양식은 UTF-8 TXT·MD 파일, 100KB 이하로 선택하세요.');
      const text=new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer()).replace(/^\uFEFF/,'');
      validateTemplateText(text);
      const saved=await api<SavedMinutesTemplate>('/minutes-templates','POST',{name:file.name,template:text});
      if(alive.current){setUploaded(previous=>[...previous.filter(t=>t.id!==saved.id),saved]);setSelectedId(saved.id);setNotice('업로드한 양식이 목록에 추가되었습니다. 미리보기 확인 후 적용하세요.');}
    }catch(e){if(alive.current)setError(e instanceof TypeError?'UTF-8로 저장한 양식을 선택하세요.':(e as Error).message);}
    finally{if(alive.current)setUploading(false);}
  }
  async function apply(){
    setError('');setSaving(true);
    try{await onApply(selected.value);if(alive.current)onClose();}
    catch(e){if(alive.current)setError((e as Error).message);}
    finally{if(alive.current)setSaving(false);}
  }
  return <dialog ref={dialog} className="minutes-template-dialog" aria-labelledby={heading} onCancel={e=>{e.preventDefault();if(!locked)onClose();}}>
    <div className="minutes-template-dialog-shell">
      <div className="minutes-template-dialog-heading"><div><h2 id={heading}>회의록 양식 지정</h2><p>양식을 눌러 미리보고, 사용할 양식을 적용하세요.</p></div><button type="button" aria-label="양식 팝업 닫기" disabled={locked} onClick={onClose}>✕</button></div>
      <div className="minutes-template-dialog-body">
        <aside className="minutes-template-library">
          <div className="minutes-template-library-heading"><strong>제공 양식 5개 · 업로드 {uploaded.length}개</strong><button type="button" disabled={locked} onClick={()=>fileInput.current?.click()}>{uploading?'업로드 중…':'양식 업로드'}</button></div>
          <input ref={fileInput} type="file" hidden accept=".txt,.md" aria-label="양식 파일 업로드" disabled={locked} onChange={e=>{void read(e.target.files?.[0]);e.target.value='';}}/>
          <p className="help">TXT·MD · UTF-8 · 최대 100KB</p>
          {loading&&<p className="help" role="status">저장된 양식을 불러오는 중…</p>}
          <div className="minutes-template-list" aria-label="회의록 양식 목록">{choices.map(choice=><button type="button" key={choice.id} className={'minutes-template-option '+(selected.id===choice.id?'selected':'')} aria-label={choice.name} aria-pressed={selected.id===choice.id} disabled={locked} onClick={()=>{setSelectedId(choice.id);setError('');setNotice('');}}><span className="minutes-template-kind">{choice.kind==='builtin'?'제공 양식':choice.kind==='uploaded'?'업로드 양식':'현재 양식'}</span><strong>{choice.name}</strong><span>{choice.description}</span>{selected.id===choice.id&&<small>선택됨 · 적용 전</small>}</button>)}</div>
        </aside>
        <div className="minutes-template-preview-pane"><div className="section-head"><h3>{selected.name}</h3><button type="button" onClick={()=>downloadText(selected.value==='일반'?basicTemplate:selected.value,selected.name+'.md')}>선택 양식 다운로드</button></div>
          <TemplatePreview template={selected.value==='일반'?basicTemplate:selected.value}/>
          <details className="template-fields"><summary>업로드 양식 작성 안내</summary><p className="help">제공 양식을 다운로드해 제목과 순서를 수정하세요. 아래 항목을 원하는 위치에 넣으면 회의 내용이 채워집니다.</p><div>{templateKeys.map(key=><code key={key}>{`{{${key}}}`}</code>)}</div></details>
        </div>
      </div>
      <div className="minutes-template-dialog-footer"><div><strong>선택한 양식: {selected.name}</strong><p className="help">적용하면 회의록 내용을 선택한 양식에 맞춰 배치합니다.</p>{notice&&<p role="status">{notice}</p>}{error&&<p className="error" role="alert">{error}</p>}</div><div className="actions"><button type="button" disabled={locked} onClick={onClose}>취소</button><button type="button" className="primary" disabled={locked} onClick={()=>void apply()}>{saving||applying?'양식 적용 중…':'선택한 양식 적용'}</button></div></div>
    </div>
  </dialog>;
}

export default function MinutesTemplate({value,onChange,disabled,numbered=true}:{value:string;onChange:(value:string)=>void;disabled:boolean;numbered?:boolean}){
  const [open,setOpen]=useState(false);
  return <section className="template-section"><h2>{numbered&&<span className="number">03</span>}회의록 양식</h2><p className="help">제공 양식 5개 또는 업로드한 양식을 미리보고 지정하세요.</p><div className="template-selected-summary"><span>선택한 양식: <strong>{templateName(value)}</strong></span><button type="button" disabled={disabled} onClick={()=>setOpen(true)}>회의록 양식 지정</button></div>{open&&<MinutesTemplateDialog value={value} onApply={onChange} onClose={()=>setOpen(false)} disabled={disabled}/>}</section>;
}
