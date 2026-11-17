import {test,expect,type Page} from '@playwright/test';

async function fixture(page:Page){
  const draft={id:'task',meetingId:'drawer',projectKey:'TEST',issueType:'Task',summary:'슬라이드 편집 업무',description:'업무 내용과 검증 근거를 보존합니다.\n'.repeat(80),assignee:'',assigneeName:'',priority:'Medium',dueDate:'',startAt:'',endAt:'',scheduleMode:'MANUAL',estimatedMinutes:0,labels:[],parent:'',parentDraftId:'',epicId:'',analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,publishedAt:null,error:null,duplicateSuspected:false,version:0};
  const meeting={id:'drawer',title:'슬라이드 상세정보 검증',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),drafts:[draft],epics:[],jobs:[],content:{occurredAt:'',projectKey:'TEST',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',segments:[],minutes:null,items:[],assets:[]}};
  const state={fail:false,puts:[] as any[]};
  await page.route('**/api/**',route=>{
    const r=route.request(),path=new URL(r.url()).pathname;
    if(path==='/api/ticket-drafts/task'&&r.method()==='PUT'){
      if(state.fail)return route.fulfill({status:503,json:{message:'저장에 실패했습니다. 다시 시도하세요.'}});
      state.puts.push(r.postDataJSON());Object.assign(draft,r.postDataJSON());draft.version++;return route.fulfill({json:draft});
    }
    if(path.endsWith('/assignees'))return route.fulfill({json:{users:[{accountId:'minsu',displayName:'김민수',active:true}],hasMore:false,nextStartAt:1}});
    return route.fulfill({json:path==='/api/meetings/drawer'?meeting:path==='/api/settings'?{mode:'mock',configured:true}:path==='/api/settings/ai'?{mode:'mock',configured:true}:[]});
  });
  await page.goto('/meetings/drawer/tickets');return {meeting,state};
}

test('slides in from the right at two thirds width, preserves drafts on close, and saves inside the panel',async({page})=>{
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));const {meeting,state}=await fixture(page);
  const card=page.locator('[data-draft-id="task"] .candidate > button'),dialog=page.getByRole('dialog',{name:'티켓 상세정보',exact:true});
  await expect(dialog).toHaveCount(0);await expect(page.locator('.ticket-editor')).toHaveCount(0);
  await card.scrollIntoViewIfNeeded();const originalTop=(await card.boundingBox())!.y;await card.click();await expect(dialog).toBeVisible();
  const sheet=dialog.locator('.ticket-details-sheet');
  await expect.poll(async()=>(await sheet.boundingBox())!.x).toBeCloseTo(480,0);
  const rect=(await sheet.boundingBox())!;expect(rect.width).toBeCloseTo(960,0);expect(rect.height).toBe(1000);expect(rect.y).toBe(0);
  expect(await page.evaluate(()=>document.documentElement.style.overflow)).toBe('hidden');
  await page.screenshot({path:'test-results/ticket-drawer-desktop.png',animations:'disabled'});
  await page.getByLabel('제목',{exact:true}).fill('보존할 수정 제목');await page.getByLabel('설명',{exact:true}).fill('저장 전에도 유지할 설명');
  await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('status').filter({hasText:'저장하지 않은 변경사항'})).toBeVisible();expect(state.puts).toHaveLength(0);
  expect(Math.abs((await card.boundingBox())!.y-originalTop)).toBeLessThan(16);
  await page.getByRole('button',{name:'편집 계속',exact:true}).click();await expect(page.getByLabel('제목',{exact:true})).toHaveValue('보존할 수정 제목');await expect(page.getByLabel('설명',{exact:true})).toHaveValue('저장 전에도 유지할 설명');
  state.fail=true;await page.getByRole('button',{name:'변경사항 저장',exact:true}).click();await expect(dialog.locator('.editor-inline-error')).toContainText('저장에 실패했습니다');await expect(dialog).toBeVisible();
  state.fail=false;await page.getByRole('button',{name:'변경사항 저장',exact:true}).click();await expect(dialog.locator('.editor-assignee-status')).toContainText('변경사항을 저장했습니다');expect(state.puts).toHaveLength(1);expect(meeting.drafts[0]).toMatchObject({summary:'보존할 수정 제목',description:'저장 전에도 유지할 설명'});
  await page.getByRole('combobox',{name:'담당자 검색'}).fill('민수');await page.getByRole('option',{name:/김민수/}).waitFor();await page.keyboard.press('Escape');await expect(dialog).toBeVisible();await expect(page.getByRole('listbox')).toHaveCount(0);
  await page.mouse.click(20,180);await expect(dialog).toHaveCount(0);await expect(card).toBeFocused();expect(await page.evaluate(()=>document.documentElement.style.overflow)).not.toBe('hidden');
  await card.click();await expect(page.getByLabel('제목',{exact:true})).toHaveValue('보존할 수정 제목');await page.getByRole('button',{name:'티켓 상세 닫기',exact:true}).click();await expect(dialog).toHaveCount(0);expect(errors).toEqual([]);
});

test('keeps scrolling inside the drawer and uses a full width panel on mobile',async({page})=>{
  await fixture(page);await page.locator('.candidate').first().getByRole('button').click();
  const dialog=page.getByRole('dialog',{name:'티켓 상세정보',exact:true}),body=dialog.locator('.ticket-editor-body');
  for(const width of [1440,390]){
    await page.setViewportSize({width,height:900});await expect(dialog).toBeVisible();
    await expect.poll(()=>page.getByLabel('설명',{exact:true}).evaluate(el=>el.scrollHeight-el.clientHeight)).toBeLessThanOrEqual(2);
    const windowScroll=await page.evaluate(()=>scrollY);await body.evaluate(el=>{el.scrollTop=el.scrollHeight;});
    expect(await body.evaluate(el=>el.scrollTop)).toBeGreaterThan(100);expect(await page.evaluate(()=>scrollY)).toBe(windowScroll);await expect(page.getByRole('button',{name:'티켓 상세 닫기',exact:true})).toBeInViewport();
    expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    if(width===390){const rect=(await dialog.locator('.ticket-details-sheet').boundingBox())!;expect(rect.width).toBeCloseTo(390,0);expect(rect.x).toBeCloseTo(0,0);await page.screenshot({path:'test-results/ticket-drawer-mobile.png',animations:'disabled'});}
  }
  await page.emulateMedia({reducedMotion:'reduce'});await page.getByRole('button',{name:'티켓 상세 닫기',exact:true}).click();await expect(dialog).toHaveCount(0);
});
