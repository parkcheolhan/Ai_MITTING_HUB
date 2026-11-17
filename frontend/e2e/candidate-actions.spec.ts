import {test,expect} from '@playwright/test';

test('keeps generated tasks independently selectable inside the epic board',async({page})=>{
  const base={meetingId:'candidate-ui',projectKey:'SCRUM',issueType:'Task',description:'검토 내용',assignee:'',priority:'',dueDate:'',labels:[],parent:'',analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,publishedAt:null,error:null,duplicateSuspected:false,version:0};
  const meeting={id:'candidate-ui',title:'업무 그룹 선택',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),jobs:[],epics:[],content:{occurredAt:'',projectKey:'SCRUM',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',diarization:false,segments:[],minutes:null,items:[],assets:[]},drafts:[
    {...base,id:'parent-one',summary:'첫 번째 업무',parentDraftId:''},{...base,id:'parent-two',summary:'두 번째 업무',parentDraftId:''},
    {...base,id:'child-one',summary:'첫 번째 검증',parentDraftId:'parent-one',issueType:'Sub-task'},{...base,id:'child-two',summary:'두 번째 검증',parentDraftId:'parent-two',issueType:'Sub-task'}]};
  await page.route('**/api/**',route=>route.fulfill({json:new URL(route.request().url()).pathname==='/api/meetings/candidate-ui'?meeting:[]}));
  await page.goto('/meetings/candidate-ui/tickets');
  await expect(page.locator('.epic-task-group')).toHaveCount(4);await expect(page.locator('.subtask-list')).toHaveCount(0);
  await page.getByLabel('첫 번째 업무 선택',{exact:true}).check();await expect(page.locator('.approval-bar')).toContainText('1개 선택');
  await expect(page.getByRole('button',{name:'하위 후보 모두 펼치기',exact:true})).toHaveCount(0);
  await expect(page.getByLabel('첫 번째 검증 선택',{exact:true})).not.toBeChecked();await expect(page.getByLabel('두 번째 검증 선택',{exact:true})).not.toBeChecked();
  await page.locator('.candidate').filter({has:page.getByLabel('두 번째 업무 선택',{exact:true})}).getByRole('button').click();
  await expect(page.getByLabel('제목',{exact:true})).toHaveValue('두 번째 업무');await expect(page.locator('.approval-bar')).toContainText('1개 선택');
  await expect(page.getByRole('button',{name:/하위 후보 추가/})).toHaveCount(0);
});
