import {test,expect} from '@playwright/test';

function fixture(){
 const draft=(id:string,title:string,parentDraftId='')=>({id,meetingId:'schedule-ui',projectKey:'TEST',issueType:parentDraftId?'Sub-task':'Task',summary:title,description:'작업과 검증',assignee:'',assigneeName:'',priority:'Medium',dueDate:'',startAt:'',endAt:'',estimatedMinutes:0,scheduleMode:'MANUAL',scheduleReason:'',dependencyDraftIds:[] as string[],labels:[],parent:'',parentDraftId,analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,publishedAt:null,error:null,version:0,registrationMode:'NEW'});
 return {id:'schedule-ui',title:'일정 UI 검증',status:'REVIEW_PENDING',createdAt:'2026-11-10T00:00:00Z',drafts:[draft('root','보안 개선'),draft('a','암호화 구현'),draft('b','결과 검증')],jobs:[],epics:[],content:{occurredAt:'',projectKey:'TEST',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',diarization:false,segments:[],minutes:null,items:[],assets:[]}};
}

test('approval automatically calculates missing AI dates before approving and preserves completed dates',async({page})=>{
 const meeting:any=fixture();meeting.drafts.forEach((d:any)=>{d.scheduleMode='AI';d.assignee='account-a';d.assigneeName='김민수';});
 const calls:string[]=[];
 await page.route('**/api/**',route=>{
  const path=new URL(route.request().url()).pathname;
  if(path.endsWith('/schedule')){calls.push('schedule');meeting.drafts.forEach((d:any)=>{d.startAt='2027-03-01T11:30:00+09:00';d.endAt='2027-03-01T13:30:00+09:00';d.estimatedMinutes=60;d.version++;});meeting.jobs=[{id:'auto-plan',type:'SCHEDULE',status:'COMPLETED',progress:100}];return route.fulfill({json:meeting.jobs[0]});}
  if(path==='/api/ticket-drafts/approve'){calls.push('approve');const ids=route.request().postDataJSON().ids;meeting.drafts.filter((d:any)=>ids.includes(d.id)).forEach((d:any)=>{d.status='APPROVED';d.version++;});return route.fulfill({json:meeting.drafts});}
  return route.fulfill({json:path==='/api/meetings/schedule-ui'?meeting:path==='/api/settings'?{mode:'mock',configured:true,missing:[]}:path==='/api/settings/ai'?{mode:'mock',configured:true}:path.endsWith('/projects')?[{key:'TEST',name:'테스트'}]:[]});
 });
 await page.goto('/meetings/schedule-ui/tickets');await page.locator('.candidate').first().getByRole('button').click();await expect(page.getByLabel('기한 설정 방식')).toHaveValue('AI');
 await page.getByRole('button',{name:'티켓 상세 닫기',exact:true}).click();await expect(page.getByRole('dialog',{name:'티켓 상세정보',exact:true})).toHaveCount(0);
 await page.getByRole('checkbox',{name:'보안 개선 선택'}).check();
 await page.getByRole('button',{name:'선택 승인',exact:true}).click();
 await expect.poll(()=>calls).toEqual(['schedule','approve']);
 await page.locator('.candidate').first().getByRole('button').click();await expect(page.locator('.ticket-schedule')).toContainText('60분');await page.getByRole('button',{name:'티켓 상세 닫기',exact:true}).click();await expect(page.getByRole('dialog',{name:'티켓 상세정보'})).toHaveCount(0);
 await page.getByRole('button',{name:'선택 승인',exact:true}).click();
 await expect.poll(()=>calls).toEqual(['schedule','approve','approve']);
});
test('searches project assignees, assigns independent tasks and schedules cross-person work',async({page})=>{
 const meeting=fixture();let scheduleRequest:any;const searches:string[]=[];
 await page.route('**/api/**',route=>{
  const url=new URL(route.request().url()),path=url.pathname;
  if(path.endsWith('/assignees')){searches.push(url.searchParams.get('query')||'');const names=[{accountId:'account-a',displayName:'김민수',active:true},{accountId:'account-b',displayName:'이지은',active:true}];return route.fulfill({json:{users:names.filter(u=>!url.searchParams.get('query')||u.displayName.includes(url.searchParams.get('query')!)),hasMore:false,nextStartAt:2}});}
  if(path==='/api/ticket-drafts/root'&&route.request().method()==='PUT'){Object.assign(meeting.drafts[0],route.request().postDataJSON());meeting.drafts[0].version++;return route.fulfill({json:meeting.drafts[0]});}
  if(path.endsWith('/assignee/children')){meeting.drafts.forEach(d=>{d.assignee='account-a';d.assigneeName='김민수';d.version++;});return route.fulfill({json:meeting.drafts});}
  if(path.endsWith('/schedule')){scheduleRequest=route.request().postDataJSON();meeting.drafts.forEach((d,i)=>{d.scheduleMode='AI';d.startAt=i===2?'2027-03-01T11:00:00+09:00':'2027-03-01T09:00:00+09:00';d.endAt=i===1?'2027-03-01T11:00:00+09:00':'2027-03-01T14:00:00+09:00';d.estimatedMinutes=i===0?240:120;d.scheduleReason='구현 후 결과 검증';d.dependencyDraftIds=i===2?['a']:[];d.version++;});return route.fulfill({json:{id:'schedule-job',type:'SCHEDULE',status:'QUEUED'}});}
  return route.fulfill({json:path==='/api/meetings/schedule-ui'?meeting:path==='/api/settings'?{mode:'mock',configured:true,defaultProject:'TEST',missing:[]}:path==='/api/settings/ai'?{mode:'mock',configured:true}:path==='/api/integrations/jira/projects'?[{key:'TEST',name:'테스트'}]:path.endsWith('/issue-types')?[{id:'Task',name:'Task'},{id:'Sub-task',name:'Sub-task',subtask:'true'}]:[]});
 });
 await page.goto('/meetings/schedule-ui/tickets');await page.locator('.candidate').first().getByRole('button').click();
 await expect(page.locator('.ticket-editor').getByLabel('프로젝트',{exact:true})).toHaveCount(0);
 await expect(page.locator('.ticket-editor').getByLabel('상위 티켓',{exact:true})).toHaveCount(0);
 const search=page.getByRole('combobox',{name:'담당자 검색'});await search.fill('민수');await page.getByRole('option',{name:/김민수/}).click();
 await expect(page.getByRole('button',{name:'이 담당자를 하위업무 전체에 적용'})).toHaveCount(0);
 await expect(page.getByRole('button',{name:'초안 저장',exact:true})).toBeDisabled();
 meeting.drafts[1].assignee='account-a';meeting.drafts[1].assigneeName='김민수';meeting.drafts[2].assignee='account-a';
 await expect.poll(()=>meeting.drafts[2].assignee).toBe('account-a');expect(searches).toContain('민수');
 meeting.drafts[2].assignee='account-b';meeting.drafts[2].assigneeName='이지은';meeting.drafts[2].version++;
 await page.reload();await page.getByRole('button',{name:'AI 일정 설정',exact:true}).click();
 await page.getByLabel('계획 시작 기준').fill('2027-03-01T09:00');
 await page.getByLabel('이지은 착수 가능 시각').fill('2027-03-01T11:00');
 await page.getByRole('button',{name:'업무 전체 AI 일정 자동 설정'}).click();
 await expect.poll(()=>scheduleRequest?.availability?.length).toBe(2);
 expect(scheduleRequest.availability.find((a:any)=>a.assignee==='account-b').availableAt).toContain('2027-03-01T11:00');
 await page.getByText('담당자별 배정 결과 · 3개 실행 업무',{exact:true}).click();await expect(page.locator('.schedule-results')).toContainText('이지은');
 await page.locator('.candidate').last().getByRole('button').click();
 await expect(page.getByLabel('기한 설정 방식')).toHaveValue('AI');await expect(page.locator('.ticket-schedule')).toContainText('구현 후 결과 검증');
 await expect(page.locator('.schedule-dependencies')).toContainText('암호화 구현');await expect(page.getByLabel('상위 티켓',{exact:true})).toHaveCount(0);
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
 await page.screenshot({path:'test-results/jira-scheduling-mobile.png'});
});

test('saves explicit manual start and end times with timezone and keeps AI mode separate',async({page})=>{
 const meeting=fixture();let saved:any;
 await page.route('**/api/**',route=>{const path=new URL(route.request().url()).pathname;
  if(path==='/api/ticket-drafts/root'&&route.request().method()==='PUT'){saved=route.request().postDataJSON();Object.assign(meeting.drafts[0],saved);meeting.drafts[0].version++;return route.fulfill({json:meeting.drafts[0]});}
  return route.fulfill({json:path==='/api/meetings/schedule-ui'?meeting:path==='/api/settings'?{mode:'mock',configured:true,missing:[]}:path==='/api/settings/ai'?{mode:'mock',configured:true}:path.endsWith('/projects')?[{key:'TEST',name:'테스트'}]:[]});});
 await page.goto('/meetings/schedule-ui/tickets');await page.locator('.candidate').first().getByRole('button').click();await page.getByLabel('업무 시작 일시').fill('2027-03-01T09:00');await page.getByLabel('업무 종료 일시').fill('2027-03-01T18:00');await page.getByLabel('예상 작업시간',{exact:true}).fill('480');await page.getByRole('button',{name:'초안 저장',exact:true}).click();
 await expect.poll(()=>saved?.startAt).toMatch(/2027-03-01T09:00:00[+-]\d\d:\d\d/);expect(saved.scheduleMode||'MANUAL').toBe('MANUAL');expect(saved.endAt).toContain('18:00');expect(saved.projectKey).toBe('TEST');expect(saved.estimatedMinutes).toBe(480);
});
