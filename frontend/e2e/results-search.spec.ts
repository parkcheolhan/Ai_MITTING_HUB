import {test,expect} from '@playwright/test';

test('searches results by title and issue key, filters status and hierarchy, and keeps retry working',async({page})=>{
  const drafts=[
    {id:'a',summary:'로그인 개선',status:'PUBLISHED',issueKey:'DEMO-11',parentDraftId:'',error:null},
    {id:'b',summary:'접근 권한 검증',status:'FAILED',issueKey:null,parentDraftId:'a',error:'접근 권한 부족'},
    {id:'c',summary:'배포 일정 확인',status:'APPROVED',issueKey:null,parentDraftId:'',error:null},
  ].map(d=>({...d,meetingId:'results-search',projectKey:'DEMO',issueType:d.parentDraftId?'Sub-task':'Task',description:'작업 내용',assignee:'',priority:'Medium',dueDate:'',labels:[],parent:'',analysisItemIds:[],issueUrl:null,publishedAt:null,duplicateSuspected:false,version:0}));
  const m={id:'results-search',title:'등록 결과 검색 검증',status:'REVIEW_PENDING',createdAt:'2026-11-10',drafts,jobs:[],epics:[{id:'e',projectKey:'DEMO',summary:'회의 에픽',status:'PUBLISHED',issueKey:'DEMO-1',issueUrl:null,error:null}],content:{occurredAt:'',projectKey:'DEMO',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',diarization:false,segments:[],minutes:null,items:[],assets:[]}};
  let retried=false;
  await page.route('**/api/**',route=>{
    const path=new URL(route.request().url()).pathname;
    if(path==='/api/ticket-drafts/publish'){expect(route.request().postDataJSON().ids).toEqual(['b']);drafts[1].status='PUBLISHED';drafts[1].issueKey='DEMO-12';drafts[1].error=null;retried=true;return route.fulfill({json:[{status:'PUBLISHED'}]});}
    return route.fulfill({json:path==='/api/meetings/results-search'?m:path==='/api/settings'?{mode:'mock',configured:false}:path==='/api/settings/ai'?{mode:'mock',configured:true}:[]});
  });
  await page.goto('/meetings/results-search/results');
  const table=page.getByRole('table',{name:'티켓 등록 결과 목록'}),rows=table.locator('tbody tr');
  const panel=page.getByRole('region',{name:'등록 결과 검색',exact:true}),input=page.getByLabel('등록 결과 검색어');
  await expect(rows).toHaveCount(3);
  await input.fill('demo-11');await panel.getByRole('button',{name:'검색',exact:true}).click();await expect(rows).toHaveCount(1);await expect(rows).toContainText('로그인 개선');
  await panel.getByRole('button',{name:'초기화',exact:true}).click();
  await page.getByLabel('등록 결과 구분').selectOption('CHILD');await expect(rows).toHaveCount(1);await expect(rows).toContainText('접근 권한 검증');
  await input.fill('로그인');await input.press('Enter');await expect(rows).toHaveCount(1);
  await panel.getByRole('button',{name:'초기화',exact:true}).click();
  await page.getByRole('button',{name:/등록 실패.*1.*회의 전체 기준/}).click();await expect(rows).toHaveCount(1);
  await rows.getByRole('button',{name:'재시도',exact:true}).click();expect(retried).toBe(true);await expect(rows).toHaveCount(0);await expect(page.getByText('검색 조건에 맞는 티켓이 없습니다.',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'검색 초기화',exact:true}).click();await expect(rows).toHaveCount(3);
  await input.fill('회의 에픽');await input.press('Enter');await expect(page.locator('.epic-summary')).toContainText('DEMO-1');
  await panel.getByRole('button',{name:'초기화',exact:true}).click();
  await page.screenshot({path:'test-results/results-search-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
