import {test,expect} from '@playwright/test';

async function legacyProjectFixture(page:any){
  const draft={id:'legacy',meetingId:'project-fixed',projectKey:'DEMO',issueType:'Task',summary:'프로젝트 수정 대상',description:'보존할 업무 내용',assignee:'',priority:'',dueDate:'',labels:[],parent:'',parentDraftId:'',analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,error:null,version:0};
  const meeting={id:'project-fixed',title:'프로젝트 고정',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),drafts:[draft],jobs:[],epics:[],content:{occurredAt:'',projectKey:'SCRUM',participants:[],source:'TEXT',template:'일반',segments:[],minutes:null,items:[],assets:[]}};
  await page.route('**/api/**',(route:any)=>{
    const path=new URL(route.request().url()).pathname;
    if(path==='/api/ticket-drafts/legacy'&&route.request().method()==='PUT'){Object.assign(draft,route.request().postDataJSON());draft.version++;return route.fulfill({json:draft});}
    return route.fulfill({json:path==='/api/meetings/project-fixed'?meeting:path==='/api/settings'?{mode:'cloud',siteUrl:'https://example.atlassian.net',configured:true,defaultProject:'SCRUM',missing:[]}:path==='/api/settings/ai'?{mode:'mock',configured:true}:path==='/api/integrations/jira/projects'?[{key:'SCRUM',name:'회의 프로젝트'}]:path.endsWith('/issue-types')?[{id:'10003',name:'작업',subtask:false}]:[]});
  });
  return meeting;
}

test('fixes a stale project to the meeting project on save without an editable project field',async({page})=>{
  const meeting=await legacyProjectFixture(page);await page.goto('/meetings/project-fixed/tickets');
  await expect(page.locator('.ticket-editor').getByLabel('프로젝트',{exact:true})).toHaveCount(0);
  await expect(page.getByLabel('일괄 프로젝트',{exact:true})).toHaveCount(0);
  await page.locator('.candidate').first().getByRole('button').click();await page.getByRole('button',{name:'초안 저장',exact:true}).click();await page.getByRole('button',{name:'티켓 상세 닫기',exact:true}).click();await expect(page.getByRole('dialog',{name:'티켓 상세정보'})).toHaveCount(0);
  await expect(page.locator('.candidate .project-chip')).toContainText('SCRUM');
  expect(meeting.drafts[0]).toMatchObject({projectKey:'SCRUM',description:'보존할 업무 내용',status:'DRAFT'});
});

test('blocks approval of an unavailable legacy project until meeting project is saved',async({page})=>{
  await legacyProjectFixture(page);await page.goto('/meetings/project-fixed/tickets');
  await expect(page.getByRole('alert').filter({hasText:'현재 Jira에서 조회되지 않습니다'})).toBeVisible();
  await page.getByLabel('프로젝트 수정 대상 선택',{exact:true}).check();
  await expect(page.getByRole('button',{name:'선택 승인',exact:true})).toBeDisabled();
  await page.locator('.candidate').first().getByRole('button').click();await page.getByRole('button',{name:'초안 저장',exact:true}).click();await page.getByRole('button',{name:'티켓 상세 닫기',exact:true}).click();await expect(page.getByRole('dialog',{name:'티켓 상세정보'})).toHaveCount(0);
  await expect(page.getByRole('alert').filter({hasText:'현재 Jira에서 조회되지 않습니다'})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'선택 승인',exact:true})).toBeEnabled();
});
