import type { Meeting, Minutes } from './types';
import { validateTemplateText, type SavedMinutesTemplate } from './minutesTemplateCatalog';

export const standalone = (globalThis as { MOA_STANDALONE?: boolean }).MOA_STANDALONE === true;
const storageKey = 'moa-standalone-v1';
type Store = { meetings: Meeting[]; projects: {key:string;name:string;description:string;createdAt:string}[]; minutesTemplates?:SavedMinutesTemplate[] };
function load(): Store {
  const raw = localStorage.getItem(storageKey);
  return raw ? JSON.parse(raw) : { meetings: [], projects: [] };
}
function save(data: Store) {
  try { localStorage.setItem(storageKey, JSON.stringify(data)); }
  catch { throw new Error('브라우저 저장 공간이 부족하거나 저장이 차단되었습니다. 데이터를 백업하고 저장 공간을 확인하세요.'); }
}
export function backupLocalData() {
  const url = URL.createObjectURL(new Blob([JSON.stringify(load(), null, 2)], {type:'application/json'}));
  const link = document.createElement('a'); link.href=url; link.download='moa-backup.json'; link.click();
  setTimeout(()=>URL.revokeObjectURL(url), 1000);
}
export async function restoreLocalData(file: File) {
  const data = JSON.parse(await file.text());
  if (!Array.isArray(data.meetings) || !Array.isArray(data.projects) ||
      data.meetings.some((m: Meeting)=>!m.id || !m.title || !Array.isArray(m.jobs) || !Array.isArray(m.drafts) || !Array.isArray(m.content?.segments) || !Array.isArray(m.content?.items) || !Array.isArray(m.content?.assets) || !Array.isArray(m.content?.participants)) ||
      data.projects.some((p: {key:string;name:string})=>!p.key || !p.name) ||
      (data.minutesTemplates!==undefined&&(!Array.isArray(data.minutesTemplates)||data.minutesTemplates.some((t:SavedMinutesTemplate)=>!t.id||!t.name||typeof t.template!=='string')))) throw new Error('모아 백업 파일 형식이 아닙니다.');
  data.minutesTemplates?.forEach((t:SavedMinutesTemplate)=>validateTemplateText(t.template));
  save(data);
  location.reload();
}
const unavailable = 'HTML 로컬 버전에서는 AI 분석·음성 변환·Jira 연동을 실행할 수 없습니다. 해당 기능은 Java 서버 버전에서 사용하세요.';
const blankMinutes = (): Minutes => ({version:1,generationMode:'manual',overview:'',summary:'',discussion:'',decisions:'',openQuestions:'',followUp:''});

export async function localApi<T>(path: string, method: string, body?: unknown): Promise<T> {
  const data = load();
  const url = new URL(path, 'https://local.invalid');
  const route = url.pathname;
  const input = (body || {}) as Record<string, any>;
  let result: unknown;
  if(route==='/minutes-templates'&&method==='GET')result=data.minutesTemplates||[];
  else if(route==='/minutes-templates'&&method==='POST'){
    const name=String(input.name||'').trim(),template=String(input.template||'').replace(/^\uFEFF/,'');
    if(!name||name.length>255)throw new Error('양식 이름은 255자 이하로 입력하세요.');
    validateTemplateText(template);
    data.minutesTemplates??=[];
    result=data.minutesTemplates.find(t=>t.template===template);
    if(!result){result={id:crypto.randomUUID(),name,template,createdAt:new Date().toISOString()};data.minutesTemplates.push(result as SavedMinutesTemplate);save(data);}
  }
  else if (route === '/settings/ai' && method === 'GET') result={mode:'openai',model:'서버 연결 필요',configured:false,keyConfigured:false};
  else if (route === '/settings' && method === 'GET') result={mode:'cloud',siteUrl:'',defaultProject:'',configured:false,tokenConfigured:false,missing:['Java 서버 연결 필요']};
  else if (route === '/settings/ai/codex/login' && method === 'GET') result={status:'UNAVAILABLE',message:unavailable};
  else if (route === '/integrations/speech' && method === 'GET') result={ready:false,message:unavailable};
  else if (route === '/integrations/jira/projects' && method === 'GET') result=[];
  else if (route === '/projects' && method === 'GET') result=data.projects.map(p=>({...p,meetingCount:data.meetings.filter(m=>m.content.projectKey===p.key).length}));
  else if ((route === '/projects' && method === 'POST') || (route.startsWith('/projects/') && method === 'PUT')) {
    const key = method === 'PUT' ? decodeURIComponent(route.split('/')[2]) : String(input.key || '');
    if (!/^[A-Z][A-Z0-9_]{0,19}$/.test(key) || !String(input.name || '').trim()) throw new Error('프로젝트 이름과 키를 확인하세요.');
    const existing=data.projects.find(p=>p.key===key);
    if (existing && method === 'POST') throw new Error('이미 등록된 프로젝트 키입니다.');
    if (method==='PUT' && !existing) throw new Error('프로젝트가 없습니다.');
    result={key,name:input.name,description:input.description || '',createdAt:existing?.createdAt || new Date().toISOString()};
    data.projects=data.projects.filter(p=>p.key!==key); data.projects.push(result as Store['projects'][number]); save(data);
  }
  else if (route === '/meetings' && method === 'GET') result=data.meetings;
  else if (route === '/meetings' && method === 'POST') {
    if (!String(input.title || '').trim()) throw new Error('회의명을 입력하세요.');
    const m: Meeting={id:crypto.randomUUID(),title:input.title,status:'UPLOADED',createdAt:new Date().toISOString(),drafts:[],jobs:[],epics:[],content:{occurredAt:input.occurredAt,projectKey:input.projectKey,participants:input.participants || [],memo:input.memo || '',source:input.source || 'TEXT',language:input.language || 'ko',template:input.template || '일반',diarization:false,segments:String(input.transcript || '').split(/\r?\n/).filter(s=>s.trim()).map((text,i)=>({id:crypto.randomUUID(),speaker:'미지정',startMs:i*30000,endMs:(i+1)*30000,text,estimatedTime:true})),minutes:blankMinutes(),items:[],assets:[]}};
    data.meetings.unshift(m); save(data); result=m;
  }
  else if (route.startsWith('/meetings/')) {
    const [, , id, action, subaction] = route.split('/');
    const m=data.meetings.find(m=>m.id===id);
    if (!m) throw new Error('회의를 찾을 수 없습니다.');
    if (!action && method==='GET') result=m;
    else if(action==='transcript'&&!subaction&&method==='PUT'){
      const edits=input.edits as {id:string;originalText:string;text:string}[];
      if(!Array.isArray(edits)||!edits.length)throw new Error('수정할 발언을 확인하세요.');
      const changes=new Map<string,string>();
      for(const edit of edits){
        const current=m.content.segments.find(s=>s.id===edit.id);
        if(!current||current.text!==edit.originalText)throw new Error('음성 기록이 변경되었습니다. 최신 기록을 다시 불러오세요.');
        if(!edit.text?.trim()||changes.has(edit.id))throw new Error('빈 발언이나 중복된 발언을 저장할 수 없습니다.');
        changes.set(edit.id,edit.text.trim());
      }
      const next=m.content.segments.map(s=>({...s,text:changes.get(s.id)??s.text}));
      if(next.reduce((n,s)=>n+s.text.length,0)>200000)throw new Error('전체 기록은 200,000자 이하여야 합니다.');
      m.content.originalTranscriptText??={};
      for(const s of m.content.segments)if(changes.has(s.id)&&s.text!==changes.get(s.id))m.content.originalTranscriptText[s.id]??=s.text;
      m.content.segments=next;m.content.transcriptRevision=(m.content.transcriptRevision??0)+1;save(data);result=m;
    }
    else if (!action && method==='DELETE') {data.meetings=data.meetings.filter(m=>m.id!==id);save(data);}
    else if (action==='process' && method==='POST') result=m; // Text is saved; no AI result is fabricated.
    else if(action==='minutes'&&subaction==='template'&&method==='PUT'){
      const template=String(input.template||'일반');
      if(template!=='일반')validateTemplateText(template);
      m.content.template=template;
      if(m.content.minutes){
        const minutes=m.content.minutes;
        const fields:Record<string,string>={회의명:m.title,회의일시:m.content.occurredAt||'',참석자:m.content.participants.join(', '),메모:m.content.memo||'',개요:minutes.overview,요약:minutes.summary,논의사항:minutes.discussion,결정사항:minutes.decisions,미결사항:minutes.openQuestions,후속조치:minutes.followUp};
        minutes.formatted=template==='일반'?'':template.replace(/\{\{\s*([^{}]+?)\s*}}/g,(_,key:string)=>fields[key.trim()]?.trim()||'기록 없음');
        minutes.version++;
      }
      save(data);result=m;
    }
    else if (action==='minutes' && !subaction && method==='PUT') {m.content.minutes={...input,version:(m.content.minutes?.version || 0)+1,generationMode:'manual'} as Minutes; save(data);result=m.content.minutes;}
    else if (action==='participants' && method==='PUT') {
      m.content.participants=input.participants;
      for (const segment of m.content.segments) if(input.segmentSpeakers?.[segment.id]!==undefined)segment.speaker=input.segmentSpeakers[segment.id];
      save(data);result=m;
    }
    else throw new Error(unavailable);
  }
  else throw new Error(unavailable);
  return structuredClone(result) as T;
}
