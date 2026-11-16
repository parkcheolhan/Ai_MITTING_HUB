export type SavedMinutesTemplate={id:string;name:string;template:string;createdAt:string};
export type TemplateChoice={id:string;name:string;description:string;value:string;kind:'builtin'|'uploaded'|'current'};
export const basicTemplate=`# 회의록

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
const metadata=`회의명: {{회의명}}\n회의 일시: {{회의일시}}\n참석자: {{참석자}}\n`;
export const builtinMinutesTemplates:TemplateChoice[]=[
  {id:'builtin-general',name:'일반 회의록',description:'회의 개요부터 논의·결정·후속 조치까지 전체 내용을 정리합니다.',value:'일반',kind:'builtin'},
  {id:'builtin-weekly',name:'주간 업무 보고',description:'주요 업무 현황과 이슈, 다음 후속 조치를 중심으로 정리합니다.',value:`# 주간 업무 보고 회의록\n\n${metadata}\n## 주요 업무 현황\n{{요약}}\n\n## 업무별 논의\n{{논의사항}}\n\n## 이번 회의 결정\n{{결정사항}}\n\n## 이슈 및 확인 사항\n{{미결사항}}\n\n## 후속 업무\n{{후속조치}}\n`,kind:'builtin'},
  {id:'builtin-project',name:'프로젝트 진행 회의',description:'프로젝트 진행 상황, 주요 쟁점과 실행 항목을 기록합니다.',value:`# 프로젝트 진행 회의록\n\n${metadata}\n## 프로젝트 개요\n{{개요}}\n\n## 진행 상황 및 주요 안건\n{{요약}}\n\n## 주요 쟁점 및 논의\n{{논의사항}}\n\n## 합의 및 결정\n{{결정사항}}\n\n## 추가 확인 사항\n{{미결사항}}\n\n## 실행 항목\n{{후속조치}}\n\n## 참고 메모\n{{메모}}\n`,kind:'builtin'},
  {id:'builtin-decisions',name:'의사결정 회의',description:'검토 안건과 논의, 최종 결정 및 후속 실행을 중심으로 기록합니다.',value:`# 의사결정 회의록\n\n${metadata}\n## 검토 안건\n{{요약}}\n\n## 논의 내용\n{{논의사항}}\n\n## 결정 사항\n{{결정사항}}\n\n## 미결 사항\n{{미결사항}}\n\n## 결정 후 실행 항목\n{{후속조치}}\n`,kind:'builtin'},
  {id:'builtin-summary',name:'간단 요약',description:'핵심 요약·결정·다음 행동을 간결하게 정리합니다.',value:`# 회의 핵심 요약\n\n${metadata}\n## 핵심 요약\n{{요약}}\n\n## 결정 사항\n{{결정사항}}\n\n## 다음 행동\n{{후속조치}}\n`,kind:'builtin'},
];
export const templateKeys=['회의명','회의일시','참석자','메모','개요','요약','논의사항','결정사항','미결사항','후속조치'];
export function validateTemplateText(text:string){
  if(!text.trim()||text.length>20000)throw new Error('양식은 내용이 있는 20,000자 이하 문서로 등록하세요.');
  const slots=[...text.matchAll(/\{\{\s*([^{}]+?)\s*}}/g)].map(m=>m[1].trim());
  const unknown=slots.find(key=>!templateKeys.includes(key));
  if(unknown)throw new Error(`지원하지 않는 양식 항목: ${unknown}`);
  if(!slots.some(key=>templateKeys.slice(4).includes(key)))throw new Error('양식에 {{요약}}, {{논의사항}} 등 회의 내용 항목을 하나 이상 넣어주세요.');
}
export function templateName(value:string){return builtinMinutesTemplates.find(t=>t.value===value)?.name||value.match(/^#\s+(.+)$/m)?.[1]||'저장된 양식';}
