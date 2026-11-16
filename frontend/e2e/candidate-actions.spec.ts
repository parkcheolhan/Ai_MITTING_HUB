import {test,expect} from '@playwright/test';

test('each parent card creates its own child from its bottom right button',async({page})=>{
  const base={meetingId:'candidate-ui',projectKey:'SCRUM',issueType:'Task',description:'검토 내용',assignee:'',priority:'',dueDate:'',labels:[],parent:'',analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,publishedAt:null,error:null,duplicateSuspected:false,version:0};
  const meeting={id:'candidate-ui',title:'후보 버튼 확인',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),jobs:[],epics:[],content:{occurredAt:'',projectKey:'SCRUM',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',diarization:false,segments:[],minutes:null,items:[],assets:[]},drafts:[{...base,id:'parent-one',summary:'첫 번째 업무',parentDraftId:''},{...base,id:'parent-two',summary:'두 번째 업무',parentDraftId:''}]};
  const parents:string[]=[];
  await page.route('**/api/meetings/candidate-ui',route=>route.fulfill({json:meeting}));
  await page.route('**/api/meetings/candidate-ui/ticket-drafts/manual',async route=>{
    const input=route.request().postDataJSON();parents.push(input.parentDraftId);
    const child={...base,...input,id:'child-'+parents.length,summary:input.summary};
    meeting.drafts.push(child);await route.fulfill({json:child});
  });
  await page.goto('/meetings/candidate-ui?tab=tickets');
  await page.locator('.stepper').getByRole('button',{name:/JIRA 검토/}).click();
  await expect(page.locator('.candidate-actions')).toHaveCount(2);
  await expect(page.locator('.subtask-list')).toHaveCount(0);
  const second=page.getByRole('button',{name:'두 번째 업무 하위 후보 추가',exact:true});
  const card=await second.locator('xpath=../..').boundingBox(),button=await second.boundingBox();
  expect(button!.x+button!.width).toBeGreaterThan(card!.x+card!.width*0.8);
  expect(button!.y).toBeGreaterThan(card!.y+card!.height/2);
  await second.click();
  await expect(page.getByRole('button',{name:'두 번째 업무 하위 후보 추가',exact:true})).toBeEnabled();
  await expect(page.getByLabel('두 번째 업무 하위 티켓')).toContainText('새 하위 업무');
  expect(parents).toEqual(['parent-two']);
  await page.getByRole('button',{name:'첫 번째 업무 하위 후보 추가',exact:true}).click();
  await expect(page.getByLabel('첫 번째 업무 하위 티켓')).toContainText('새 하위 업무');
  expect(parents).toEqual(['parent-two','parent-one']);
  await expect(page.locator('.subtask-candidate .candidate-actions')).toHaveCount(0);
});
