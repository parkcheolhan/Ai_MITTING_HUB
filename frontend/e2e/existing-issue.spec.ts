import {test,expect} from '@playwright/test';

test('review can connect an existing issue as an independent task',async({page,request})=>{
  const m=await(await request.post('/api/meetings',{data:{title:'Existing issue workflow',projectKey:'DEMO',transcript:'실제 회의 내용',source:'TEXT'}})).json();
  const parent=await(await request.post(`/api/meetings/${m.id}/ticket-drafts/manual`,{data:{projectKey:'DEMO',issueType:'Task',summary:'기존 업무 연결',description:'유지할 회의 작업',analysisItemIds:[]}})).json();
  await page.goto(`/meetings/${m.id}/tickets`);
  await expect(page.getByLabel('Jira 등록 방식')).toHaveValue('NEW');
  await page.getByLabel('Jira 등록 방식').selectOption('EXISTING');
  await page.getByLabel('기존 Jira 이슈 키',{exact:true}).fill('demo-42');
  await page.getByRole('button',{name:'초안 저장',exact:true}).click();
  await expect(page.locator('.candidate').first()).toContainText('기존 이슈 연결 · DEMO-42');
  await page.reload();await expect(page.getByLabel('Jira 등록 방식')).toHaveValue('EXISTING');
  await page.getByLabel('기존 업무 연결 선택',{exact:true}).check();
  await page.getByRole('button',{name:'선택 승인',exact:true}).click();
  await page.getByRole('button',{name:'승인 티켓 Mock 등록'}).click();await expect(page).toHaveURL(/\/results$/);
  const saved=await(await request.get(`/api/meetings/${m.id}`)).json();
  expect(saved.drafts.find((d:{id:string})=>d.id===parent.id)).toMatchObject({status:'PUBLISHED',issueKey:'DEMO-42',registrationMode:'EXISTING',description:'유지할 회의 작업'});
});
