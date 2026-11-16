import {test,expect} from "@playwright/test";

test("parent expands child candidates, selects the group, and publishes linked subtasks",async({page,request})=>{
  const response=await request.post("/api/meetings",{data:{title:"Subtask workflow",projectKey:"DEMO",transcript:"[00:01:00] 참석자: 로그인 오류 수정 작업을 진행합시다.",source:"TEXT"}});
  expect(response.ok()).toBeTruthy();const meeting=await response.json();
  async function job(path:string){const j=await(await request.post(path)).json();await expect.poll(async()=>(await(await request.get('/api/jobs/'+j.id)).json()).status).toBe("COMPLETED");}
  await job(`/api/meetings/${meeting.id}/process`);await job(`/api/meetings/${meeting.id}/ticket-drafts`);
  const data=await(await request.get(`/api/meetings/${meeting.id}`)).json();const parent=data.drafts[0];
  const child=await(await request.post(`/api/meetings/${meeting.id}/ticket-drafts/manual`,{data:{projectKey:"DEMO",issueType:"Sub-task",summary:"로그인 회귀 검증",description:"오류 수정 후 검증하고 결과를 기록합니다.",parentDraftId:parent.id,analysisItemIds:parent.analysisItemIds}})).json();
  expect(child.parentDraftId).toBe(parent.id);
  await page.goto(`/meetings/${meeting.id}?tab=tickets`);
  // The app uses the review tab name from its route query.
  await page.locator('.stepper').getByRole('button',{name:/JIRA 검토/}).click();
  await expect(page.locator('.candidate')).toHaveCount(1);
  const parentButton=page.locator('.candidate').first().locator(':scope > button');
  await parentButton.click();await expect(parentButton).toHaveAttribute('aria-expanded','true');
  await expect(page.locator('.subtask-list .candidate')).toHaveCount(1);
  await page.locator('.subtask-list').getByRole('button').click();
  await expect(page.locator('.ticket-editor input[placeholder="연결할 Jira 이슈 키"]')).toHaveValue(new RegExp(parent.summary));
  await parentButton.click();await expect(page.locator('.subtask-list')).toHaveCount(0);
  await parentButton.click();await expect(page.locator('.subtask-list')).toBeVisible();
  await page.getByLabel(parent.summary+' 선택',{exact:true}).check();
  await expect(page.getByLabel('로그인 회귀 검증 선택',{exact:true})).toBeChecked();
  await page.getByRole('button',{name:'선택 승인',exact:true}).click();
  await page.getByRole('button',{name:'승인 티켓 Mock 등록'}).click();
  await expect(page).toHaveURL(/\/results$/);
  const saved=await(await request.get(`/api/meetings/${meeting.id}`)).json();
  const p=saved.drafts.find((d:{id:string})=>d.id===parent.id),c=saved.drafts.find((d:{id:string})=>d.id===child.id);
  expect(p.status).toBe('PUBLISHED');expect(c.status).toBe('PUBLISHED');expect(c.parent).toBe(p.issueKey);
  expect(saved.epics).toHaveLength(1);expect(saved.epics[0].status).toBe('PUBLISHED');expect(p.parent).toBe(saved.epics[0].issueKey);expect(p.epicKey).toBe(saved.epics[0].issueKey);
  // A published parent can receive another child; approval skips the already published rows.
  const extraResponse=await request.post(`/api/meetings/${meeting.id}/ticket-drafts/manual`,{data:{projectKey:"DEMO",issueType:"Sub-task",summary:"추가 검증",description:"추가 확인 결과를 기록합니다.",parentDraftId:parent.id,analysisItemIds:parent.analysisItemIds}});
  expect(extraResponse.ok()).toBeTruthy();const extra=await extraResponse.json();
  await page.goto(`/meetings/${meeting.id}?tab=tickets`);
  await page.locator('.stepper').getByRole('button',{name:/JIRA 검토/}).click();
  await page.locator('.candidate').first().locator(':scope > button').click();
  await page.getByLabel(parent.summary+' 선택',{exact:true}).check();
  await page.getByRole('button',{name:'선택 승인',exact:true}).click();
  await page.getByRole('button',{name:'승인 티켓 Mock 등록'}).click();
  await expect(page).toHaveURL(/\/results$/);
  const final=await(await request.get(`/api/meetings/${meeting.id}`)).json();
  expect(final.drafts.find((d:{id:string})=>d.id===extra.id)).toMatchObject({status:'PUBLISHED',parent:p.issueKey});
  expect(final.epics).toHaveLength(1);
});
