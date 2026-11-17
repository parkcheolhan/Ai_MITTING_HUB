import {test,expect,type Page} from '@playwright/test';
const server='https://moa-api.example.test',token='synthetic-connection-test-token-32-characters';
async function setup(page:Page){await page.addInitScript(()=>Object.assign(globalThis,{MOA_PAGES:true}));await page.goto('/');await page.getByLabel('API 서버 HTTPS 주소').fill(server);await page.getByLabel('접속 토큰',{exact:true}).fill(token);}
test('recovers from temporary network and tunnel errors before saving the connection',async({page})=>{
  let probes=0;await page.route(server+'/**',async route=>{const path=new URL(route.request().url()).pathname;if(path==='/api/meetings'){probes++;if(probes===1)return route.abort('connectionfailed');if(probes===2)return route.fulfill({status:502,body:'Temporary gateway error'});}return route.fulfill({json:path==='/api/settings'?{mode:'mock',configured:false}:path==='/api/settings/ai'?{mode:'mock',configured:false}:[]});});
  await setup(page);await page.getByRole('button',{name:'서버 연결',exact:true}).click();await expect(page.getByRole('status')).toContainText('다시 확인');expect(await page.evaluate(()=>sessionStorage.getItem('moa-server-connection-v1'))).toBeNull();
  await expect(page.getByRole('button',{name:'서버 변경 / 연결 해제',exact:true})).toBeVisible();expect(probes).toBeGreaterThanOrEqual(3);expect(JSON.parse((await page.evaluate(()=>sessionStorage.getItem('moa-server-connection-v1')))!)).toEqual({server,token});
});
test('does not retry authentication failures or replace an earlier successful connection',async({page})=>{
  let probes=0;await page.route(server+'/**',route=>{probes++;return route.fulfill({status:401,json:{message:'Unauthorized'}});});await setup(page);await page.evaluate(()=>sessionStorage.setItem('moa-server-connection-v1',JSON.stringify({server:'https://previous.example.test',token:'previous-synthetic-connection-token'})));
  await page.getByRole('button',{name:'서버 연결',exact:true}).click();await expect(page.getByRole('alert')).toHaveText('접속 토큰이 올바르지 않습니다.');expect(probes).toBe(1);expect(JSON.parse((await page.evaluate(()=>sessionStorage.getItem('moa-server-connection-v1')))!)).toHaveProperty('server','https://previous.example.test');
});
test('allows cancelling retries and stops further probes',async({page})=>{
  let probes=0;await page.route(server+'/**',route=>{probes++;return route.abort('connectionfailed');});await setup(page);await page.getByRole('button',{name:'서버 연결',exact:true}).click();await expect.poll(()=>probes).toBe(1);await page.getByRole('button',{name:'연결 취소',exact:true}).click();await expect(page.getByRole('button',{name:'서버 연결',exact:true})).toBeEnabled();await page.waitForTimeout(1300);expect(probes).toBe(1);await expect(page.getByRole('alert')).toHaveCount(0);
});
test('limits repeated outages and gives a clear final error',async({page})=>{
  let probes=0;await page.route(server+'/**',route=>{probes++;return route.fulfill({status:530,body:'Tunnel not connected'});});await setup(page);await page.getByRole('button',{name:'서버 연결',exact:true}).click();await expect(page.getByRole('alert')).toContainText('4회 확인했습니다.',{timeout:12000});expect(probes).toBe(4);await expect(page.getByRole('button',{name:'서버 연결',exact:true})).toBeEnabled();expect(await page.evaluate(()=>sessionStorage.getItem('moa-server-connection-v1'))).toBeNull();
});
