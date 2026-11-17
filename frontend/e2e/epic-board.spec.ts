import {test,expect} from '@playwright/test';

function fixture(){
  const draft=(id:string,parentDraftId='')=>({id,meetingId:'board',projectKey:'TEST',issueType:parentDraftId?'Sub-task':'Task',summary:id==='root'?'로그인 개선':id==='child'?'회귀 검증':'운영 점검',description:'업무 내용 검토',assignee:'',priority:'Medium',dueDate:'',labels:[],parent:'',parentDraftId,epicId:'',epicKey:'',analysisItemIds:[],status:'APPROVED',issueKey:null,issueUrl:null,publishedAt:null,error:null,duplicateSuspected:false,version:0,scheduleMode:'MANUAL'});
  return {id:'board',title:'에픽 배치 검증',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),jobs:[],drafts:[draft('root'),draft('child'),draft('other')],epics:[] as any[],content:{occurredAt:'',projectKey:'TEST',source:'TEXT',language:'ko',template:'일반',participants:[],memo:'',assets:[],segments:[],minutes:null,items:[]}};
}

test('finds epics, opens boxes, drags independent tasks and persists mobile reassignment',async({page})=>{
  await page.setViewportSize({width:1440,height:1400});
  const meeting=fixture();const moves:any[]=[];let searchCount=0,reject=false;
  await page.route('**/api/**',route=>{
    const request=route.request(),url=new URL(request.url()),path=url.pathname;
    let json:any=[];
    if(path==='/api/meetings/board')json=meeting;
    if(path==='/api/settings')json={mode:'mock',configured:true};
    if(path==='/api/settings/ai')json={mode:'mock',configured:true};
    if(path==='/api/integrations/jira/projects')json=[{key:'TEST',name:'검증'}];
    if(path.endsWith('/issue-types'))json=[{id:'Task',name:'Task'},{id:'Sub-task',name:'Sub-task',subtask:'true'}];
    if(path==='/api/integrations/jira/projects/TEST/epics'){
      searchCount++;expect(url.searchParams.get('query')).toBe('서비스');
      json={epics:[{key:'TEST-100',projectKey:'TEST',summary:'서비스 개선 에픽',status:'진행 중'}],nextPageToken:''};
    }
    if(path==='/api/meetings/board/epics'&&request.method()==='POST'){
      const input=request.postDataJSON();meeting.epics.push({id:input.issueKey?'existing':'new',projectKey:'TEST',summary:input.summary||'서비스 개선 에픽',status:input.issueKey?'PUBLISHED':'PENDING',issueKey:input.issueKey||null,external:!!input.issueKey,currentTarget:true});json=meeting;
    }
    if(path==='/api/ticket-drafts/root/epic'){
      if(reject)return route.fulfill({status:409,json:{message:'티켓이 변경되었습니다. 다시 이동하세요.'}});
      const input=request.postDataJSON();moves.push(input);
      for(const d of meeting.drafts.filter(d=>d.id==='root')){d.epicId=input.epicId;d.status='DRAFT';d.version++;}
      json=meeting.drafts;
    }
    return route.fulfill({json});
  });
  await page.goto('/meetings/board/tickets');
  const unassigned=page.getByRole('region',{name:'미배치 업무 박스',exact:true});
  await expect(unassigned.locator('[data-draft-id]')).toHaveCount(3);
  await page.locator('.candidate-toolbar').first().getByRole('checkbox').check();
  await expect(page.getByRole('button',{name:'승인 티켓 Mock 등록'})).toBeDisabled();
  await page.getByRole('button',{name:'에픽 조회·추가',exact:true}).click();await page.getByLabel('에픽 제목 또는 키').fill('서비스');await page.getByRole('button',{name:'에픽 조회',exact:true}).click();
  await expect(page.getByLabel('에픽 조회 결과')).toContainText('TEST-100');await page.getByRole('button',{name:'박스 추가',exact:true}).click();
  const existing=page.getByRole('region',{name:'서비스 개선 에픽 에픽 박스',exact:true});await expect(existing).toBeVisible();expect(moves).toHaveLength(0);expect(searchCount).toBe(1);
  await page.getByLabel('로그인 개선 이동 핸들',{exact:true}).dragTo(existing);
  await expect(existing.locator('[data-draft-id="root"]')).toHaveCount(1);await expect(unassigned.locator('[data-draft-id]')).toHaveCount(2);
  expect(moves[0]).toEqual({epicId:'existing',version:0});expect(meeting.drafts[1].parentDraftId).toBe('');expect(meeting.drafts[1].epicId).toBe('');
  await expect(page.locator('.subtask-candidate,.subtask-list')).toHaveCount(0);
  await page.getByRole('button',{name:'에픽 조회·추가',exact:true}).click();await page.getByLabel('새 에픽 제목').fill('분리된 새 에픽');await page.getByRole('button',{name:'새 에픽 박스 추가',exact:true}).click();
  const created=page.getByRole('region',{name:'분리된 새 에픽 에픽 박스',exact:true});await expect(created).toBeVisible();await expect(created).toContainText('등록 전 에픽');
  await page.locator('.candidate-panel').screenshot({path:'test-results/epic-board-desktop.png',animations:'disabled'});
  await page.setViewportSize({width:390,height:844});await page.getByLabel('로그인 개선 에픽 이동').selectOption('new');await expect(created.locator('[data-draft-id="root"]')).toHaveCount(1);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.locator('.candidate-panel').screenshot({path:'test-results/epic-board-mobile.png',animations:'disabled'});
  await page.reload();await expect(created.locator('[data-draft-id="root"]')).toHaveCount(1);await expect(page.getByLabel('로그인 개선 에픽 이동')).toHaveValue('new');
  reject=true;await page.getByLabel('로그인 개선 에픽 이동').selectOption('existing');await expect(page.getByRole('alert')).toContainText('티켓이 변경되었습니다');
  await expect(created.locator('[data-draft-id="root"]')).toHaveCount(1);await expect(page.getByLabel('로그인 개선 에픽 이동')).toHaveValue('new');
  reject=false;await page.getByLabel('로그인 개선 에픽 이동').selectOption('');await expect(unassigned.locator('[data-draft-id="root"]')).toHaveCount(1);
});

test('locks registered groups and prevents moves with unsaved ticket edits',async({page})=>{
  const meeting=fixture();meeting.drafts[2].status='PUBLISHED';
  await page.route('**/api/**',route=>{
    const path=new URL(route.request().url()).pathname;
    return route.fulfill({json:path==='/api/meetings/board'?meeting:path==='/api/settings'?{mode:'mock'}:path.endsWith('/issue-types')?[{id:'Task',name:'Task'}]:[]});
  });
  await page.goto('/meetings/board/tickets');await expect(page.getByLabel('운영 점검 에픽 이동')).toBeDisabled();await expect(page.locator('[data-draft-id="other"]')).toHaveAttribute('draggable','false');
  await page.locator('.ticket-editor input').filter({visible:true}).first().waitFor();
  await page.getByLabel('제목',{exact:true}).fill('저장 전 변경');await expect(page.getByLabel('로그인 개선 에픽 이동')).toBeDisabled();await expect(page.getByRole('button',{name:'에픽 조회·추가',exact:true})).toBeDisabled();
});
