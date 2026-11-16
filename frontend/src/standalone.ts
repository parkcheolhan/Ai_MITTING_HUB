import type { Meeting, Minutes } from './types';

export const standalone = (globalThis as { MOA_STANDALONE?: boolean }).MOA_STANDALONE === true;
const storageKey = 'moa-standalone-v1';
type Store = { meetings: Meeting[]; projects: {key:string;name:string;description:string;createdAt:string}[] };
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
      data.projects.some((p: {key:string;name:string})=>!p.key || !p.name)) throw new Error('모아 백업 파일 형식이 아닙니다.');
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
  if (route === '/settings/ai' && method === 'GET') result={mode:'openai',model:'서버 연결 필요',configured:false,keyConfigured:false};
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
    else if (!action && method==='DELETE') {data.meetings=data.meetings.filter(m=>m.id!==id);save(data);}
    else if (action==='process' && method==='POST') result=m; // Text is saved; no AI result is fabricated.
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
