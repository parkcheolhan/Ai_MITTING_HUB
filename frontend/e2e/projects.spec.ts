import {test,expect} from "@playwright/test";

test("registers a project space, persists edits, and associates and filters meetings",async({page,request})=>{
  const key='SPACE'+Date.now(),name='프로젝트 등록 검증';
  await page.goto('/meetings');
  await page.getByRole('link',{name:'프로젝트(스페이스) 관리',exact:true}).click();
  await expect(page.getByRole('heading',{name:'프로젝트(스페이스) 관리',exact:true})).toBeVisible();
  await page.getByLabel('프로젝트 이름',{exact:true}).fill(name);
  await page.getByLabel('프로젝트 키',{exact:true}).fill(key.toLowerCase());
  await expect(page.getByLabel('프로젝트 키',{exact:true})).toHaveValue(key);
  await page.getByLabel('프로젝트 설명',{exact:true}).fill('회의와 실행 업무를 관리합니다.');
  await page.getByRole('button',{name:'프로젝트 등록',exact:true}).click();
  const card=page.locator('.project-space-card').filter({has:page.getByText(key,{exact:true})});
  await expect(card.getByRole('heading',{name})).toBeVisible();
  await page.reload();await expect(card).toContainText('회의와 실행 업무를 관리합니다.');
  await card.getByRole('button',{name:'정보 수정'}).click();
  await expect(page.getByLabel('프로젝트 키',{exact:true})).toBeDisabled();
  await page.getByLabel('프로젝트 이름',{exact:true}).fill(name+' 수정');
  await page.getByRole('button',{name:'프로젝트 변경 저장'}).click();await expect(card).toContainText(name+' 수정');
  await card.getByRole('link',{name:'새 회의',exact:true}).click();
  await expect(page.getByLabel('등록한 프로젝트 선택')).toHaveValue(key);
  await expect(page.locator('input[name="projectKey"]')).toHaveValue(key);
  const title='프로젝트 연결 회의 '+Date.now();
  await page.locator('input[name="title"]').fill(title);await page.locator('#transcript').fill('로그인 오류 수정 작업을 진행합시다.');
  await page.getByRole('button',{name:'저장 후 분석 시작'}).click();await expect(page).toHaveURL(/\/meetings\/[a-f0-9-]+$/);
  const id=new URL(page.url()).pathname.split('/')[2];const meeting=await(await request.get('/api/meetings/'+id)).json();expect(meeting.content.projectKey).toBe(key);
  await page.goto('/meetings/projects');await expect(card).toContainText('등록된 회의 1건');
  await card.getByRole('link',{name:'회의 보기'}).click();await expect(page.getByLabel('회의 프로젝트')).toHaveValue(key);
  await expect(page.locator('table tbody tr')).toHaveCount(1);
  await expect(page.getByRole('link',{name:new RegExp(title)}).first()).toBeVisible();
});

test("imports a connected Jira project and reports duplicate registration",async({page})=>{
  const key='JIRA'+Date.now();
  await page.route('**/api/settings',r=>r.fulfill({json:{mode:'cloud',siteUrl:'https://linked-team.atlassian.net',configured:true,defaultProject:'',tokenConfigured:true,missing:[]}}));
  await page.route('**/api/integrations/jira/projects',r=>r.fulfill({json:[{key,name:'연결된 Jira 프로젝트'}]}));
  await page.goto('/meetings/projects');await page.getByRole('button',{name:'Jira 프로젝트 불러오기'}).click();
  await page.getByLabel('가져올 Jira 프로젝트').selectOption(key);
  await expect(page.getByLabel('프로젝트 이름',{exact:true})).toHaveValue('연결된 Jira 프로젝트');
  await expect(page.getByLabel('프로젝트 키',{exact:true})).toHaveValue(key);
  await page.getByRole('button',{name:'프로젝트 등록',exact:true}).click();
  await expect(page.locator('.project-space-card').filter({hasText:key})).toBeVisible();
  await page.getByLabel('프로젝트 이름',{exact:true}).fill('중복 등록');await page.getByLabel('프로젝트 키',{exact:true}).fill(key);
  await page.getByRole('button',{name:'프로젝트 등록',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('이미 등록된 프로젝트 키');
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
