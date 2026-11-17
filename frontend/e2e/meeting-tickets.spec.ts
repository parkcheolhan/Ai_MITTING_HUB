import {test, expect} from '@playwright/test';

function meeting(id: string, title: string, withDraft = false) {
  return {id, title, status:'REVIEW_PENDING', createdAt:'2026-11-08T10:00:00Z', jobs:[], epics:[],
    content:{occurredAt:'',projectKey:'DEMO',participants:['김민수'],memo:'',source:'TEXT',language:'ko',template:'일반',diarization:false,segments:[],minutes:null,items:[],assets:[]},
    drafts:withDraft ? [{id:'draft-'+id,meetingId:id,projectKey:'DEMO',issueType:'Task',summary:title+' 업무',description:'작업 검토',assignee:'',priority:'Medium',dueDate:'',labels:[],parent:'',parentDraftId:'',analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,publishedAt:null,error:null,duplicateSuspected:false,version:0}] : []};
}
test.beforeEach(async ({page}) => {
  const meetings=[meeting('first','첫 번째 회의',true),meeting('second','두 번째 회의',true),meeting('empty','후보 없는 회의')];
  await page.route('**/api/**', route => {
    const path=new URL(route.request().url()).pathname;
    return route.fulfill({json:path==='/api/meetings' ? meetings : path==='/api/settings' ? {mode:'mock',defaultProject:'DEMO'} : path==='/api/settings/ai' ? {mode:'mock',configured:true} : []});
  });
});
test('keeps meeting review and results isolated after legacy redirects',async ({page}) => {
  await page.route('**/api/meetings/first',route=>route.fulfill({json:meeting('first','First meeting',true)}));
  await page.route('**/api/meetings/second',route=>route.fulfill({json:meeting('second','Second meeting',true)}));
  await page.goto('/tickets?meeting=first&step=2');
  await expect(page).toHaveURL(/meetings\/first\/tickets$/);
  await expect(page.locator('.candidate-panel')).toContainText('First meeting');
  await expect(page.locator('.candidate-panel')).not.toContainText('Second meeting');
  await page.goto('/tickets?meeting=first&step=3');
  await expect(page).toHaveURL(/meetings\/first\/results$/);
  await page.goto('/meetings/second/tickets');
  await expect(page.locator('.candidate-panel')).toContainText('Second meeting');
  await expect(page.locator('.candidate-panel')).not.toContainText('First meeting');
});
test('opens meeting management instead of a duplicate ticket dashboard',async ({page}) => {
  await page.goto('/tickets');
  await expect(page).toHaveURL(/\/meetings$/);
  await expect(page.locator('tbody tr')).toHaveCount(3);
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
test('shows AI recommendation tags on independent tasks and their review details',async ({page}) => {
  const m=meeting('recommended','추천 검증',true);
  const child={...m.drafts[0],id:'recommended-child',parentDraftId:'',issueType:'Task',summary:'세션 만료 회귀 검증',labels:['ai-recommended'],description:'AI 추천 · 로그인 수정 후 회귀 위험을 확인하기 위한 제안입니다.'};
  await page.route('**/api/meetings/recommended',route=>route.fulfill({json:{...m,drafts:[...m.drafts,child]}}));
  await page.goto('/meetings/recommended/tickets');
  await expect(page.locator('.candidate').first().locator('.ai-recommendation-tag')).toHaveCount(0);

  const row=page.locator('.candidate').nth(1);
  await expect(row.locator('.ai-recommendation-tag')).toHaveText('AI 추천');
  await row.getByRole('button',{name:/세션 만료 회귀 검증/}).click();
  await expect(page.locator('.ticket-editor .ai-recommendation-tag')).toHaveText('AI 추천');
});
test('shows automatically generated paragraph candidates without a manual generation action',async ({page}) => {
  const m=meeting('paragraph','중단락 업무',true);
  const child={...m.drafts[0],id:'small-paragraph',parentDraftId:'',issueType:'Task',summary:'소단락 작업 수행'};
  await page.route('**/api/meetings/paragraph',route=>route.fulfill({json:{...m,drafts:[...m.drafts,child]}}));
  await page.goto('/meetings/paragraph/tickets');
  await expect(page.getByRole('button',{name:/후보 추가/})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'중단락·소단락으로 후보 구성',exact:true})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'하위 후보 모두 펼치기',exact:true})).toHaveCount(0);
  await expect(page.locator('.candidate-panel')).toContainText('소단락 작업 수행');
  await expect(page.locator('.candidate-toolbar').first()).toContainText('업무 2개');
  await page.route('**/api/settings/ai',route=>route.fulfill({json:{mode:'codex',configured:true}}));
  await page.goto('/meetings/paragraph?tab=analysis');
  await expect(page.getByRole('button',{name:'AI Jira 초안 생성',exact:true})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'JIRA 후보 생성',exact:true})).toHaveCount(0);
  await expect(page.getByRole('link',{name:'자동 생성된 Jira 후보 검토'})).toBeVisible();
});
