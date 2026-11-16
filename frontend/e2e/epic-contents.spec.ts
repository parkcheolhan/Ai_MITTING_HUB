import {test,expect} from '@playwright/test';

test('existing epic offers explicit minutes and recording sync and displays failures',async({page})=>{
  const meeting={id:'epic-content-ui',title:'회의록 첨부',status:'COMPLETED',createdAt:new Date().toISOString(),jobs:[],drafts:[],epics:[{id:'epic',projectKey:'SCRUM',summary:'회의 에픽',status:'PUBLISHED',issueKey:'SCRUM-19',issueUrl:'https://example.atlassian.net/browse/SCRUM-19',error:null,contentStatus:'PENDING',contentError:null as string|null}],content:{occurredAt:'',projectKey:'SCRUM',participants:[],memo:'',source:'MEDIA',language:'ko',template:'일반',diarization:false,segments:[],minutes:null,items:[],assets:[{id:'recording',originalName:'회의.m4a',mediaType:'audio/mp4',size:100}]}};
  let syncs=0;
  await page.route('**/api/meetings/epic-content-ui',route=>route.fulfill({json:meeting}));
  await page.route('**/api/meetings/epic-content-ui/epics/sync',route=>{
    syncs++;meeting.epics[0].contentStatus=syncs===1?'FAILED':'SYNCED';meeting.epics[0].contentError=syncs===1?'첨부 권한 없음':null;
    return route.fulfill({json:meeting.epics});
  });
  await page.goto('/meetings/epic-content-ui?tab=tickets');
  await page.locator('.stepper').getByRole('button',{name:/JIRA 검토/}).click();
  expect(syncs).toBe(0);
  await page.getByRole('button',{name:'에픽 회의록·녹음 동기화',exact:true}).click();
  await expect(page.locator('.epic-summary')).toContainText('첨부 권한 없음');
  await page.getByRole('button',{name:'에픽 회의록·녹음 동기화',exact:true}).click();
  await expect(page.locator('.epic-summary')).toContainText('동기화 완료 (등록 원본 1개)');
  expect(syncs).toBe(2);
});
