import {test,expect,type Page} from '@playwright/test';

async function setup(page:Page){
 const make=(id:string,parentDraftId='')=>({id,meetingId:'deadline-bulk',projectKey:'TEST',issueType:parentDraftId?'Sub-task':'Task',summary:id==='root'?'상위 업무':'하위 업무 '+id,description:'작업 내용',assignee:'minsu',assigneeName:'김민수',priority:'Medium',dueDate:'',startAt:'',endAt:'',estimatedMinutes:0,scheduleMode:'MANUAL',scheduleReason:'',dependencyDraftIds:[],labels:[],parent:'',parentDraftId,analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,error:null,version:0,registrationMode:'NEW'});
 const meeting={id:'deadline-bulk',title:'기한 일괄 적용',status:'REVIEW_PENDING',createdAt:'2026-11-10T00:00:00Z',drafts:[make('root'),make('a','root'),make('b','root')],jobs:[],epics:[],content:{occurredAt:'',projectKey:'TEST',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',segments:[],minutes:null,items:[],assets:[]}};
 const state={puts:[] as any[],bulk:[] as any[],fail:false};
 await page.route('**/api/**',route=>{const path=new URL(route.request().url()).pathname;
  if(path.startsWith('/api/ticket-drafts/')&&route.request().method()==='PUT'){
   const input=route.request().postDataJSON();state.puts.push(input);if(state.fail)return route.fulfill({status:503,json:{message:'저장 오류'}});
   const draft=meeting.drafts.find(d=>d.id===path.split('/').at(-1))!;Object.assign(draft,input);draft.version++;return route.fulfill({json:draft});
  }
  if(path.endsWith('/schedule/children')){const input=route.request().postDataJSON();state.bulk.push(input);meeting.drafts.forEach(d=>{Object.assign(d,{scheduleMode:input.scheduleMode,startAt:input.scheduleMode==='AI'?'':input.startAt,endAt:input.scheduleMode==='AI'?'':input.endAt,dueDate:input.scheduleMode==='AI'?'':input.endAt.slice(0,10)});d.version++;});return route.fulfill({json:meeting.drafts});}
  return route.fulfill({json:path==='/api/meetings/deadline-bulk'?meeting:path==='/api/settings'?{mode:'mock',configured:true,missing:[]}:path==='/api/settings/ai'?{mode:'mock',configured:true}:path.endsWith('/projects')?[{key:'TEST',name:'테스트'}]:[]});
 });
 await page.goto('/meetings/deadline-bulk/tickets');return {meeting,state};
}
test('applies manual dates to all children directly and preserves unrelated pending edits',async({page})=>{
 const {meeting,state}=await setup(page);
 await page.getByLabel('제목',{exact:true}).fill('아직 저장하지 않은 제목');
 await page.getByLabel('업무 시작 일시').fill('2027-03-01T09:00');await page.getByLabel('업무 종료 일시').fill('2027-03-03T18:00');
 await page.getByRole('button',{name:'이 기한 설정을 하위업무 전체에 적용',exact:true}).click();
 await expect(page.locator('.ticket-editor-header')).toContainText('상위·하위업무의 기한을 저장했습니다.');
 expect(state.puts).toHaveLength(0);expect(state.bulk).toHaveLength(1);expect(meeting.drafts.every(d=>d.startAt.includes('2027-03-01T09:00')&&d.endAt.includes('2027-03-03T18:00'))).toBe(true);
 expect(meeting.drafts[0].summary).toBe('상위 업무');await expect(page.getByLabel('제목',{exact:true})).toHaveValue('아직 저장하지 않은 제목');await expect(page.getByRole('button',{name:'변경사항 저장',exact:true})).toBeEnabled();
});
test('AI mode saves automatically including current edits and can immediately apply to children',async({page})=>{
 const {meeting,state}=await setup(page);await page.getByLabel('제목',{exact:true}).fill('AI 방식으로 저장할 제목');
 await page.getByLabel('기한 설정 방식').selectOption('AI');await expect(page.locator('.ticket-editor-header')).toContainText('기한 설정을 저장했습니다.');
 expect(state.puts).toHaveLength(1);expect(meeting.drafts[0]).toMatchObject({scheduleMode:'AI',summary:'AI 방식으로 저장할 제목'});await expect(page.getByRole('button',{name:'변경사항 저장',exact:true})).toHaveCount(0);
 await page.getByRole('button',{name:'이 기한 설정을 하위업무 전체에 적용',exact:true}).click();await expect.poll(()=>meeting.drafts.every(d=>d.scheduleMode==='AI')).toBe(true);
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
 await page.locator('.candidate').first().locator('button[aria-expanded]').click();await page.locator('.subtask-candidate').first().getByRole('button').click();await expect(page.getByLabel('기한 설정 방식')).toHaveValue('AI');
});
test('failed AI-mode save restores the mode and leaves the form available for retry',async({page})=>{
 const {state}=await setup(page);state.fail=true;await page.getByLabel('기한 설정 방식').selectOption('AI');await expect(page.locator('.ticket-editor-header')).toContainText('기한 설정을 저장하지 못했습니다.');await expect(page.getByLabel('기한 설정 방식')).toHaveValue('MANUAL');await expect(page.getByLabel('기한 설정 방식')).toBeEnabled();
 state.fail=false;await page.getByLabel('기한 설정 방식').selectOption('AI');await expect(page.locator('.ticket-editor-header')).toContainText('기한 설정을 저장했습니다.');
});
test('child save buttons sit beside their selectors and save edited dates on desktop and mobile',async({page})=>{
 const {meeting}=await setup(page);await page.locator('.candidate').first().locator('button[aria-expanded]').click();await page.locator('.subtask-candidate').first().getByRole('button').click();
 const save=page.getByRole('button',{name:'하위업무 기한 저장',exact:true});
 for(const width of [1440,390]){
  await page.setViewportSize({width,height:900});
  for(const [select,button] of [[page.getByLabel('기한 설정 방식'),save],[page.getByRole('combobox',{name:'담당자 검색'}),page.getByRole('button',{name:'하위업무 담당자 저장',exact:true})]]){
   const a=(await select.boundingBox())!,b=(await button.boundingBox())!;expect(b.x).toBeGreaterThanOrEqual(a.x+a.width);expect(Math.abs((a.y+a.height)-(b.y+b.height))).toBeLessThan(2);
  }
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
 }
 await page.getByLabel('업무 시작 일시').fill('2027-03-01T09:00');await page.getByLabel('업무 종료 일시').fill('2027-03-02T18:00');await expect(save).toBeEnabled();await save.click();await expect.poll(()=>meeting.drafts[1].endAt).toContain('2027-03-02T18:00');await expect(save).toBeDisabled();
});
test('root bulk apply buttons sit beside assignee and AI selectors instead of below them',async({page})=>{
 await setup(page);
 for(const width of [1440,390]){
  await page.setViewportSize({width,height:900});
  for(const [field,button] of [[page.locator('.assignee-input'),page.getByRole('button',{name:'이 담당자를 하위업무 전체에 적용',exact:true})],[page.getByLabel('기한 설정 방식'),page.getByRole('button',{name:'이 기한 설정을 하위업무 전체에 적용',exact:true})]]){
   const a=(await field.boundingBox())!,b=(await button.boundingBox())!;expect(b.x).toBeGreaterThanOrEqual(a.x+a.width);expect(Math.abs((a.y+a.height)-(b.y+b.height))).toBeLessThan(2);
  }
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
 }
 await page.setViewportSize({width:1440,height:1000});await page.locator('.ticket-schedule').scrollIntoViewIfNeeded();await page.screenshot({path:'test-results/inline-bulk-actions.png'});
});
