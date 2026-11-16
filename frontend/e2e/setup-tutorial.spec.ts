import {test,expect} from '@playwright/test';
test('guides first-time setup and can be reopened after dismissal',async({page})=>{
  await page.route('**/api/**',route=>route.fulfill({json:route.request().url().endsWith('/settings/ai')?{mode:'codex',configured:false}:route.request().url().endsWith('/settings')?{mode:'mock',configured:false}:[]}));
  await page.goto('/dashboard');
  const guide=page.getByRole('region',{name:'처음 사용 설정 가이드'});
  await expect(guide).toBeVisible();
  await guide.getByRole('button',{name:'다음: AI 설정'}).click();await guide.getByRole('button',{name:'AI 설정 열기'}).click();await expect(page).toHaveURL(/settings#ai-settings/);
  await guide.getByRole('button',{name:'다음: Jira 설정'}).click();await guide.getByRole('button',{name:'Jira 설정 열기'}).click();await expect(page).toHaveURL(/settings#jira-settings/);
  await guide.getByRole('button',{name:'나중에 설정'}).click();await page.reload();await expect(guide).toHaveCount(0);
  await page.getByRole('button',{name:'설정 가이드',exact:true}).click();await expect(guide).toBeVisible();
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
test('does not show the tutorial when AI and Jira are already configured',async({page})=>{
  await page.route('**/api/**',route=>route.fulfill({json:route.request().url().endsWith('/settings/ai')?{mode:'codex',configured:true}:route.request().url().endsWith('/settings')?{mode:'cloud',configured:true,siteUrl:'https://team.atlassian.net'}:[]}));
  await page.goto('/dashboard');await expect(page.getByRole('region',{name:'처음 사용 설정 가이드'})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'설정 가이드',exact:true})).toHaveCount(0);
});
