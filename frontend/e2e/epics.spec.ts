import {test,expect} from '@playwright/test';

test('publishes an epic before new work and reuses it for later selections',async({page,request})=>{
  const m=await(await request.post('/api/meetings',{data:{title:'Epic first workflow',projectKey:'DEMO',transcript:'회의 업무',source:'TEXT'}})).json();
  async function add(summary:string){return(await(await request.post(`/api/meetings/${m.id}/ticket-drafts/manual`,{data:{projectKey:'DEMO',issueType:'Task',summary,description:'검증할 업무',analysisItemIds:[],scheduleMode:'MANUAL'}})).json());}
  const a=await add('첫 발급 업무'),b=await add('다음 발급 업무');
  const board=await(await request.post(`/api/meetings/${m.id}/epics`,{data:{summary:'직접 선택한 에픽'}})).json();
  for(const d of [a,b])await request.put(`/api/ticket-drafts/${d.id}/epic`,{data:{epicId:board.epics[0].id,version:d.version}});
  await page.goto(`/meetings/${m.id}/tickets`);
  await expect(page.locator('.epic-summary')).toContainText('에픽을 추가하고 업무 티켓을 배치하세요');
  async function publish(summary:string){await page.getByLabel(summary+' 선택',{exact:true}).check();await page.getByRole('button',{name:'선택 승인',exact:true}).click();await page.getByRole('button',{name:'승인 티켓 Mock 등록'}).click();await expect(page).toHaveURL(/\/results$/);}
  await publish(a.summary);
  let saved=await(await request.get(`/api/meetings/${m.id}`)).json();
  expect(saved.epics).toHaveLength(1);const key=saved.epics[0].issueKey;expect(saved.drafts.find((d:{id:string})=>d.id===a.id)).toMatchObject({status:'PUBLISHED',parent:key});
  await page.goto(`/meetings/${m.id}/tickets`);await expect(page.locator('.epic-summary')).toContainText(key);
  await publish(b.summary);
  saved=await(await request.get(`/api/meetings/${m.id}`)).json();expect(saved.epics).toHaveLength(1);expect(saved.epics[0].issueKey).toBe(key);expect(saved.drafts.find((d:{id:string})=>d.id===b.id)).toMatchObject({status:'PUBLISHED',parent:key});
});
