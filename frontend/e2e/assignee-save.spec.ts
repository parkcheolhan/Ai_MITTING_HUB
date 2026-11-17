import {test,expect,type Page} from '@playwright/test';

async function setup(page:Page){
 const draft=(id:string,parentDraftId='')=>({id,meetingId:'assignee-save',projectKey:'TEST',issueType:parentDraftId?'Sub-task':'Task',summary:id==='root'?'상위 업무':'다른 업무',description:'검토할 상세 내용\n'.repeat(80),assignee:'',assigneeName:'',priority:'Medium',dueDate:'',startAt:'',endAt:'',estimatedMinutes:0,scheduleMode:'MANUAL',dependencyDraftIds:[],labels:[],parent:'',parentDraftId,analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,error:null,version:0,registrationMode:'NEW'});
 const meeting={id:'assignee-save',title:'담당자 변경 검증',status:'REVIEW_PENDING',createdAt:'2026-11-10T00:00:00Z',drafts:[draft('root'),draft('child','root'),draft('other')],jobs:[],epics:[],content:{occurredAt:'',projectKey:'TEST',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',segments:[],minutes:null,items:[],assets:[]}};
 const state={puts:[] as any[],fail:false,reads:0};
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  if(path.endsWith('/assignees'))return route.fulfill({json:{users:[{accountId:'minsu',displayName:'김민수',active:true}],hasMore:false,nextStartAt:1}});
  if(path==='/api/ticket-drafts/root'&&route.request().method()==='PUT'){
   state.puts.push(route.request().postDataJSON());
   if(state.fail)return route.fulfill({status:503,json:{message:'일시적인 저장 오류'}});
   Object.assign(meeting.drafts[0],state.puts.at(-1));meeting.drafts[0].version++;return route.fulfill({json:meeting.drafts[0]});
  }
  if(path==='/api/meetings/assignee-save')state.reads++;
  return route.fulfill({json:path==='/api/meetings/assignee-save'?meeting:path==='/api/settings'?{mode:'mock',configured:true,missing:[]}:path==='/api/settings/ai'?{mode:'mock',configured:true}:path.endsWith('/projects')?[{key:'TEST',name:'테스트'}]:[]});
 });
 await page.goto('/meetings/assignee-save/tickets');await page.locator('.candidate').first().getByRole('button').click();
 return {state,meeting};
}
async function choose(page:Page){await page.getByRole('combobox',{name:'담당자 검색'}).fill('민수');await page.getByRole('option',{name:/김민수/}).click();}

test('assignee-only selection saves immediately and leaves ticket navigation and bulk assignment available',async({page})=>{
 const {state}=await setup(page);const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 await choose(page);
 await expect(page.locator('.ticket-editor-header')).toContainText('담당자를 저장했습니다.');
 expect(state.puts).toHaveLength(1);expect(state.puts[0].assignee).toBe('minsu');
 await expect(page.getByRole('button',{name:'이 담당자를 하위업무 전체에 적용'})).toHaveCount(0);
 await expect(page.getByRole('button',{name:'변경사항 저장',exact:true})).toHaveCount(0);
 const reads=state.reads;await expect.poll(()=>state.reads).toBeGreaterThan(reads);
 await expect(page.getByRole('combobox',{name:'담당자 검색'})).toHaveValue('김민수');
 await page.getByRole('button',{name:'티켓 상세 닫기',exact:true}).click();await expect(page.getByRole('dialog',{name:'티켓 상세정보',exact:true})).toHaveCount(0);await page.locator('.candidate').last().getByRole('button').click();await expect(page.getByLabel('제목',{exact:true})).toHaveValue('다른 업무');
 expect(errors).toEqual([]);
});

test('keeps other edits unsaved and exposes sticky save controls after assignee selection',async({page})=>{
 const {state}=await setup(page);await page.getByLabel('제목',{exact:true}).fill('함께 저장할 제목');await choose(page);
 expect(state.puts).toHaveLength(0);
 await expect(page.locator('.ticket-editor-header')).toContainText('저장하지 않은 변경사항');
 const save=page.getByRole('button',{name:'변경사항 저장',exact:true});
 await page.locator('.ticket-editor').evaluate(el=>el.scrollTop=el.scrollHeight);
 await expect(save).toBeInViewport();
 await save.click();await expect(page.locator('.ticket-editor-header')).toContainText('변경사항을 저장했습니다.');
 expect(state.puts).toHaveLength(1);expect(state.puts[0]).toMatchObject({summary:'함께 저장할 제목',assignee:'minsu'});
 await expect(page.getByRole('button',{name:'이 담당자를 하위업무 전체에 적용'})).toHaveCount(0);
 await page.setViewportSize({width:390,height:844});await page.getByLabel('제목',{exact:true}).fill('모바일 편집');await page.evaluate(()=>window.scrollTo({top:document.documentElement.scrollHeight,behavior:'instant'}));await page.locator('.ticket-editor').evaluate(el=>el.scrollTop=el.scrollHeight);await expect(save).toBeInViewport();expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);await page.screenshot({path:'test-results/assignee-save-mobile.png'});
});

test('failed assignee save restores the selection and releases controls for retry',async({page})=>{
 const {state}=await setup(page);state.fail=true;await choose(page);
 await expect(page.locator('.ticket-editor-header')).toContainText('담당자를 저장하지 못했습니다.');
 await expect(page.getByRole('combobox',{name:'담당자 검색'})).toBeEnabled();await expect(page.getByRole('combobox',{name:'담당자 검색'})).toHaveValue('');
 state.fail=false;await choose(page);await expect(page.locator('.ticket-editor-header')).toContainText('담당자를 저장했습니다.');
 await page.getByRole('button',{name:'티켓 상세 닫기',exact:true}).click();await expect(page.getByRole('dialog',{name:'티켓 상세정보',exact:true})).toHaveCount(0);await page.locator('.candidate').last().getByRole('button').click();await expect(page.getByLabel('제목',{exact:true})).toHaveValue('다른 업무');
});

test('long descriptions expand and scrolling stays inside the slide panel',async({page})=>{
 await setup(page);const description=page.getByLabel('설명',{exact:true}),body=page.locator('.ticket-editor-body');
 for(const width of [1440,390]){
  await page.setViewportSize({width,height:900});await body.evaluate(el=>{el.scrollTop=0;});
  await expect.poll(()=>description.evaluate(el=>el.scrollHeight-el.clientHeight)).toBeLessThanOrEqual(2);
  expect(await description.evaluate(el=>getComputedStyle(el).overflowY)).toBe('hidden');
  const height=(await description.boundingBox())!.height;await description.fill(('추가로 편집할 긴 설명입니다.\n').repeat(120));await expect.poll(async()=>(await description.boundingBox())!.height).toBeGreaterThan(height);
  await body.evaluate(el=>{el.scrollTop=200;});const windowScroll=await page.evaluate(()=>scrollY);await body.hover();const before=await body.evaluate(el=>el.scrollTop);await page.mouse.wheel(0,400);await expect.poll(()=>body.evaluate(el=>el.scrollTop)).toBeGreaterThan(before);expect(await page.evaluate(()=>scrollY)).toBe(windowScroll);
  await expect(page.getByRole('button',{name:'변경사항 저장',exact:true})).toBeInViewport();await page.getByRole('button',{name:'편집 취소',exact:true}).click();expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
 }
});
