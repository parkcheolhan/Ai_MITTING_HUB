import { useRef, useState } from "react";

export const basicTemplate = `# 회의록

회의명: {{회의명}}
회의 일시: {{회의일시}}
참석자: {{참석자}}

## 회의 개요
{{개요}}

## 주요 안건
{{요약}}

## 논의 내용
{{논의사항}}

## 결정 사항
{{결정사항}}

## 검토 사항
{{미결사항}}

## 후속 조치
{{후속조치}}

## 메모
{{메모}}
`;
const keys = ["회의명", "회의일시", "참석자", "메모", "개요", "요약", "논의사항", "결정사항", "미결사항", "후속조치"];
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

export default function MinutesTemplate({value, onChange, disabled, numbered=true}: {value: string; onChange: (value: string) => void; disabled: boolean; numbered?: boolean}) {
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const sequence = useRef(0);
  async function read(file?: File) {
    if (!file) return;
    const request = ++sequence.current;
    setError("");
    try {
      if (!/\.(txt|md)$/i.test(file.name) || !file.size || file.size > 100 * 1024)
        throw new Error("양식은 UTF-8 TXT·MD 파일, 100KB 이하로 선택하세요.");
      const text = new TextDecoder("utf-8", {fatal: true}).decode(await file.arrayBuffer()).replace(/^\uFEFF/, "");
      if (request !== sequence.current) return;
      const slots = [...text.matchAll(/\{\{\s*([^{}]+?)\s*}}/g)].map(m => m[1].trim());
      if (text.length > 20000) throw new Error("양식은 20,000자 이하로 등록하세요.");
      const unknown = slots.find(key => !keys.includes(key));
      if (unknown) throw new Error(`지원하지 않는 양식 항목: ${unknown}`);
      if (!slots.some(key => keys.slice(4).includes(key))) throw new Error("양식에 {{요약}}, {{논의사항}} 등 회의 내용 항목을 하나 이상 넣어주세요. 기본 양식을 내려받아 수정할 수 있습니다.");
      onChange(text); setName(file.name);
    } catch (e) {
      if (request === sequence.current) setError(e instanceof TypeError ? "UTF-8로 저장한 양식을 선택하세요." : (e as Error).message);
    }
  }
  return <section className="template-section">
    <h2>{numbered&&<span className="number">03</span>}회의록 양식</h2>
    <p className="help">사용할 양식을 선택하세요. 아래 문서에서 작성 결과의 예시를 확인할 수 있습니다.</p>
    <div className="template-choice-grid">
      <button className={"template-choice "+(value==="일반"?"selected":"")} type="button" aria-label="기본 양식 사용" aria-pressed={value==="일반"} disabled={disabled} onClick={() => {++sequence.current; onChange("일반"); setName(""); setError("");}}><strong>기본 양식 사용 {value==="일반"&&"✓"}</strong><span>회의 개요, 주요 안건, 결정 사항과 후속 조치를 정리합니다.</span>{value==="일반"&&<small>현재 사용 중</small>}</button>
    <label className={"template-choice template-upload "+(value!=="일반"?"selected":"")}><strong>내 양식 업로드 {value!=="일반"&&"✓"}</strong><span>회사나 팀에서 사용하는 양식으로 작성합니다.</span><span className="help">회의록 양식 업로드 (TXT·MD, UTF-8, 100KB 이하)</span>
      <input type="file" accept=".txt,.md" disabled={disabled} onChange={e => {void read(e.target.files?.[0]); e.target.value = "";}} />
    </label></div>
    <p className="template-selection-status" role="status">{value === "일반" ? "✓ 기본 양식을 사용합니다." : `✓ 업로드한 양식을 사용합니다: ${name||"저장된 양식"}`}</p>
    <TemplatePreview template={value === "일반" ? basicTemplate : value}/>
    <details className="template-fields"><summary>내 양식은 어떻게 준비하나요?</summary><p className="help">기본 양식을 내려받아 제목과 순서를 수정하고 업로드하세요. 자동 입력 항목을 원하는 위치에 넣으면 회의 내용이 채워집니다.</p><button type="button" onClick={() => downloadText(basicTemplate, "기본-회의록-양식.md")}>기본 양식 다운로드</button><div>{keys.map(key=><code key={key}>{`{{${key}}}`}</code>)}</div></details>
    {error && <p className="error" role="alert">{error}</p>}
  </section>;
}
