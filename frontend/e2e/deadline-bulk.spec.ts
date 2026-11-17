import {test,expect,type Page} from '@playwright/test';

async function setup(page:Page){
 const make=(id:string,parentDraftId='')=>({id,meetingId:'deadline-bulk',projectKey:'TEST',issueType:parentDraftId?'Sub-task':'Task',summary:id==='root'?'상위 업무':'하위 업무 '+id,description:'작업 내용',assignee:'minsu',assigneeName:'김민수',priority:'Medium',dueDate:'',startAt:'',endAt:'',estimatedMinutes:0,scheduleMode:'MANUAL',scheduleReason:'',dependencyDraftIds:[],labels:[],parent:'',parentDraftId,analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,error:null,version:0,registrationMode:'NEW'});
 const meeting={id:'deadline-bulk',title:'기한 일괄 적용',status:'REVIEW_PENDING',createdAt:'2026-11-10T00:00:00Z',drafts:[make('root'),make('a'),make('b')],jobs:[],epics:[],content:{occurredAt:'',projectKey:'TEST',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',segments:[],minutes:null,items:[],assets:[]}};
 const state={puts:[] as any[],bulk:[] as any[],fail:false};
 await page.route('**/api/**',route=>{const path=new URL(route.request().url()).pathname;
  if(path.startsWith('/api/ticket-drafts/')&&route.request().method()==='PUT'){
   const input=route.request().postDataJSON();state.puts.push(input);if(state.fail)return route.fulfill({status:503,json:{message:'저장 오류'}});
   const draft=meeting.drafts.find(d=>d.id===path.split('/').at(-1))!;Object.assign(draft,input);draft.version++;return route.fulfill({json:draft});
  }
  if(path.endsWith('/schedule/children')){const input=route.request().postDataJSON();state.bulk.push(input);meeting.drafts.forEach(d=>{Object.assign(d,{scheduleMode:input.scheduleMode,startAt:input.scheduleMode==='AI'?'':input.startAt,endAt:input.scheduleMode==='AI'?'':input.endAt,dueDate:input.scheduleMode==='AI'?'':input.endAt.slice(0,10)});d.version++;});return route.fulfill({json:meeting.drafts});}
  return route.fulfill({json:path==='/api/meetings/deadline-bulk'?meeting:path==='/api/settings'?{mode:'mock',configured:true,missing:[]}:path==='/api/settings/ai'?{mode:'mock',configured:true}:path.endsWith('/projects')?[{key:'TEST',name:'테스트'}]:[]});
 });
 await page.goto('/meetings/deadline-bulk/tickets');await page.locator('.candidate').first().getByRole('button').click();return {meeting,state};
}

test('saves manual dates for the selected task without changing other tasks',async({page})=>{
 const {meeting,state}=await setup(page);await page.getByLabel('업무 시작 일시').fill('2027-03-01T09:00');await page.getByLabel('업무 종료 일시').fill('2027-03-01T18:00');
 await page.getByRole('button',{name:'초안 저장',exact:true}).click();await expect(page.locator('.ticket-editor-header')).toContainText('변경사항을 저장했습니다.');
 expect(state.puts).toHaveLength(1);expect(meeting.drafts[0].endAt).toContain('18:00');expect(meeting.drafts[1].endAt).toBe('');
 await expect(page.getByRole('button',{name:/하위.*적용|하위업무.*저장/})).toHaveCount(0);
});

test('AI mode saves current edits on one task and leaves the other tasks unchanged',async({page})=>{
 const {meeting,state}=await setup(page);await page.getByLabel('제목',{exact:true}).fill('AI 방식으로 저장할 제목');await page.getByLabel('기한 설정 방식').selectOption('AI');
 await expect(page.locator('.ticket-editor-header')).toContainText('기한 설정을 저장했습니다.');expect(state.puts).toHaveLength(1);expect(meeting.drafts[0]).toMatchObject({scheduleMode:'AI',summary:'AI 방식으로 저장할 제목',issueType:'Task',parentDraftId:''});expect(meeting.drafts[1].scheduleMode).toBe('MANUAL');
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test('failed AI-mode save restores the mode and allows retry',async({page})=>{
 const {state}=await setup(page);state.fail=true;await page.getByLabel('기한 설정 방식').selectOption('AI');await expect(page.locator('.ticket-editor-header')).toContainText('기한 설정을 저장하지 못했습니다.');await expect(page.getByLabel('기한 설정 방식')).toHaveValue('MANUAL');
 state.fail=false;await page.getByLabel('기한 설정 방식').selectOption('AI');await expect(page.locator('.ticket-editor-header')).toContainText('기한 설정을 저장했습니다.');
});
