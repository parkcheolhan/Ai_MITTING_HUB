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
test('logs in using an ID and short token and revokes the session on sign out',async({page})=>{
  let logins=0,logouts=0;await page.route(server+'/**',route=>{const r=route.request(),path=new URL(r.url()).pathname;
    if(path==='/api/auth/login'){logins++;expect(r.method()).toBe('POST');expect(r.postDataJSON()).toEqual({id:'unit-admin',token:'unit-short-token'});return route.fulfill({json:{user:'unit-admin',token,expiresAt:'2026-12-01T00:00:00Z'}});}
    expect(r.headers().authorization).toBe('Bearer '+token);if(path==='/api/auth/session'){logouts++;return route.fulfill({status:204});}
    return route.fulfill({json:path==='/api/settings'?{mode:'mock',configured:false}:path==='/api/settings/ai'?{mode:'mock',configured:false}:[]});
  });await setup(page);await page.getByLabel('ID',{exact:true}).fill('unit-admin');await page.getByLabel('접속 토큰',{exact:true}).fill('unit-short-token');await page.getByRole('button',{name:'서버 연결',exact:true}).click();
  await expect(page.getByRole('button',{name:'서버 변경 / 연결 해제',exact:true})).toBeVisible();expect(logins).toBe(1);const saved=(await page.evaluate(()=>sessionStorage.getItem('moa-server-connection-v1')))!;expect(saved).not.toContain('unit-short-token');expect(JSON.parse(saved)).toMatchObject({user:'unit-admin',token});
  await page.getByRole('button',{name:'서버 변경 / 연결 해제',exact:true}).click();await expect(page.getByRole('heading',{name:'모아 · 서버 연결',exact:true})).toBeVisible();expect(logouts).toBe(1);expect(await page.getByLabel('접속 토큰',{exact:true}).inputValue()).toBe('');
});
test('rejects incorrect ID or short token without retrying or saving credentials',async({page})=>{
  let calls=0;await page.route(server+'/**',route=>{calls++;return route.fulfill({status:401,json:{message:'Invalid login'}});});await setup(page);await page.getByLabel('접속 토큰',{exact:true}).fill('unit-wrong-token');await page.getByRole('button',{name:'서버 연결',exact:true}).click();await expect(page.getByRole('alert')).toHaveText('ID 또는 토큰이 올바르지 않습니다.');expect(calls).toBe(1);expect(await page.evaluate(()=>sessionStorage.getItem('moa-server-connection-v1'))).toBeNull();
});
