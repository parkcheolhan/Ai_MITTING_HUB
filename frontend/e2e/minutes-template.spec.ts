import {test, expect} from '@playwright/test';

test('downloads a basic template, previews uploads, and preserves selection on invalid files', async ({page}) => {
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    return route.fulfill({json: path === '/api/settings/ai' ? {mode:'mock', configured:true} : path === '/api/settings' ? {mode:'mock', defaultProject:'DEMO'} : []});
  });
  await page.goto('/meetings/new');
  await expect(page.getByLabel('회의록 작성 예시')).toBeVisible();
  await expect(page.getByRole('status')).toContainText('기본 양식을 사용합니다.');
  await expect(page.locator('textarea[name="memo"]')).toHaveCount(0);
  await page.getByRole('button',{name:'작성 예시',exact:true}).click();
  await expect(page.getByLabel('회의록 작성 예시')).toContainText('제품 개선 주간 회의');
  await page.getByRole('button',{name:'양식 구조',exact:true}).click();
  await expect(page.getByLabel('회의록 양식 구조').locator('mark').first()).toHaveText('{{회의명}}');
  const download = page.waitForEvent('download');
  await page.getByText('내 양식은 어떻게 준비하나요?',{exact:true}).click();
  await page.getByRole('button', {name:'기본 양식 다운로드'}).click();
  expect((await download).suggestedFilename()).toBe('기본-회의록-양식.md');
  const input = page.getByLabel('회의록 양식 업로드');
  const template = '# 회사 양식\n{{회의명}}\n## 합의 내용\n{{결정사항}}\n{{요약}}';
  await input.setInputFiles({name:'회사.md', mimeType:'text/markdown', buffer:Buffer.from(template)});
  await expect(page.getByRole('status')).toContainText('업로드한 양식을 사용합니다: 회사.md');
  await expect(page.getByLabel('회의록 양식 구조')).toContainText('회사 양식');
  await expect(page.getByLabel('회의록 양식 구조')).toContainText('합의 내용');
  await input.setInputFiles({name:'잘못된.txt', mimeType:'text/plain', buffer:Buffer.from('{{알수없음}}')});
  await expect(page.getByRole('alert')).toContainText('지원하지 않는 양식 항목');
  await expect(page.getByLabel('회의록 양식 구조')).toContainText('회사 양식');
  await page.getByRole('button', {name:'기본 양식 사용', exact:true}).click();
  await expect(page.getByRole('status')).toContainText('기본 양식을 사용합니다.');
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test('places meeting filters on the left and search on the right and keeps mobile layout usable',async({page})=>{
  await page.route('**/api/**',route=>route.fulfill({json:[]}));
  await page.goto('/meetings');
  const project=page.getByLabel('회의 프로젝트');
  const status=page.getByLabel('회의 상태');
  const search=page.getByLabel('회의명 검색');
  await expect(page.locator('.meeting-search-field svg')).toHaveCount(0);
  const p=(await project.boundingBox())!,s=(await status.boundingBox())!,q=(await search.boundingBox())!;
  expect(p.x+p.width).toBeLessThanOrEqual(s.x);
  expect(s.x+s.width).toBeLessThan(q.x);
  expect(Math.abs(p.y-q.y)).toBeLessThan(15);
  const submit=page.locator('.meeting-search-toolbar').getByRole('button',{name:'검색',exact:true});
  expect((await submit.boundingBox())!.x).toBeGreaterThan(q.x+q.width);
  expect(q.x-(s.x+s.width)).toBeLessThan(55);
  await search.fill('주간 회의');await submit.click();await expect(search).toHaveValue('주간 회의');
  await page.setViewportSize({width:390,height:844});
  await expect(project).toBeVisible();await expect(status).toBeVisible();await expect(search).toBeVisible();
  expect((await search.boundingBox())!.y).toBeGreaterThan((await project.boundingBox())!.y);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
