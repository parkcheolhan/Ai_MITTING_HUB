import {test, expect} from '@playwright/test';
test('previews five presets, persists uploads, and applies only on confirmation',async({page})=>{
  const library:{id:string;name:string;template:string;createdAt:string}[]=[];
  await page.route('**/api/**',route=>{
    const path=new URL(route.request().url()).pathname;
    if(path==='/api/minutes-templates'){
      if(route.request().method()==='POST'){
        const entry={...route.request().postDataJSON(),id:'saved-company',createdAt:new Date().toISOString()};library.push(entry);return route.fulfill({json:entry});
      }
      return route.fulfill({json:library});
    }
    return route.fulfill({json:path==='/api/settings/ai'?{mode:'mock',configured:true}:path==='/api/settings'?{mode:'mock',defaultProject:'DEMO'}:[]});
  });
  await page.goto('/meetings/new');
  const launch=page.getByRole('button',{name:'회의록 양식 지정',exact:true});
  const summary=page.locator('.template-selected-summary');
  await expect(summary).toContainText('일반 회의록');
  await launch.click();
  const dialog=page.getByRole('dialog',{name:'회의록 양식 지정'});
  const list=dialog.getByLabel('회의록 양식 목록');
  await expect(dialog).toBeVisible();await expect(list.getByRole('button')).toHaveCount(5);
  for(const [name,heading] of [['주간 업무 보고','주간 업무 보고 회의록'],['프로젝트 진행 회의','프로젝트 진행 회의록'],['의사결정 회의','의사결정 회의록'],['간단 요약','회의 핵심 요약'],['일반 회의록','회의록']]){
    await list.getByRole('button',{name,exact:true}).click();
    await expect(dialog.getByLabel('회의록 작성 예시').getByRole('heading',{name:heading,exact:true})).toBeVisible();
    await expect(summary).toContainText('일반 회의록');
  }
  await list.getByRole('button',{name:'간단 요약',exact:true}).click();await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);await expect(summary).toContainText('일반 회의록');await expect(launch).toBeFocused();
  await launch.click();await list.getByRole('button',{name:'주간 업무 보고',exact:true}).click();
  await dialog.getByRole('button',{name:'양식 구조',exact:true}).click();
  await expect(dialog.getByLabel('회의록 양식 구조').locator('mark').first()).toHaveText('{{회의명}}');
  const download=page.waitForEvent('download');await dialog.getByRole('button',{name:'선택 양식 다운로드'}).click();expect((await download).suggestedFilename()).toBe('주간 업무 보고.md');
  const chooser=page.waitForEvent('filechooser');await dialog.getByRole('button',{name:'양식 업로드',exact:true}).click();
  await (await chooser).setFiles({name:'회사.md',mimeType:'text/markdown',buffer:Buffer.from('# 회사 양식\n{{회의명}}\n## 합의 내용\n{{결정사항}}\n{{요약}}\n<script>bad()</script>')});
  await expect(list.getByRole('button')).toHaveCount(6);await expect(dialog.getByLabel('회의록 양식 구조')).toContainText('회사 양식');
  await expect(dialog.locator('script')).toHaveCount(0);
  await expect(summary).toContainText('일반 회의록');
  await dialog.getByLabel('양식 파일 업로드').setInputFiles({name:'오류.txt',mimeType:'text/plain',buffer:Buffer.from('{{없는항목}}')});
  await expect(dialog.getByRole('alert')).toContainText('지원하지 않는 양식 항목');await expect(list.getByRole('button')).toHaveCount(6);
  await expect(list.getByRole('button',{name:'회사.md'})).toHaveAttribute('aria-pressed','true');
  await dialog.getByRole('button',{name:'취소',exact:true}).click();await launch.click();
  await expect(list.getByRole('button',{name:'회사.md'})).toBeVisible();
  await list.getByRole('button',{name:'회사.md'}).click();await dialog.getByRole('button',{name:'선택한 양식 적용',exact:true}).click();
  await expect(dialog).toHaveCount(0);await expect(summary).toContainText('회사 양식');
  await page.setViewportSize({width:390,height:844});await launch.click();
  await expect(list.getByRole('button',{name:'회사.md'})).toHaveAttribute('aria-pressed','true');
  await list.getByRole('button',{name:'간단 요약',exact:true}).click();
  await expect(dialog.getByLabel('회의록 작성 예시')).toContainText('회의 핵심 요약');
  expect(await dialog.evaluate(el=>el.scrollWidth)).toBeLessThanOrEqual(370);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await dialog.getByRole('button',{name:'선택한 양식 적용',exact:true}).click();await expect(summary).toContainText('간단 요약');
});

test('failed template application keeps the popup open and original minutes intact',async({page})=>{
  const meeting={id:'failure',title:'양식 오류 검증',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),drafts:[],jobs:[],epics:[],content:{occurredAt:'2026-11-17T10:00',projectKey:'DEMO',source:'TEXT',language:'ko',diarization:false,template:'일반',participants:[],memo:'',segments:[],assets:[],items:[],minutes:{version:1,generationMode:'manual',overview:'원래 회의 내용',summary:'변경하지 않은 요약',discussion:'',decisions:'',openQuestions:'',followUp:''}}};
  await page.route('**/api/**',route=>{
    const path=new URL(route.request().url()).pathname;
    if(path.endsWith('/minutes/template'))return route.fulfill({status:409,json:{message:'다른 작업이 실행 중입니다.'}});
    return route.fulfill({json:path==='/api/meetings/failure'?meeting:[]});
  });
  await page.goto('/meetings/failure?tab=minutes');await page.getByRole('button',{name:'회의록 양식 지정',exact:true}).click();
  const dialog=page.getByRole('dialog');await dialog.getByRole('button',{name:'간단 요약',exact:true}).click();await dialog.getByRole('button',{name:'선택한 양식 적용',exact:true}).click();
  await expect(dialog.getByRole('alert')).toHaveText('다른 작업이 실행 중입니다.');await expect(dialog).toBeVisible();
  await dialog.getByRole('button',{name:'취소',exact:true}).click();await expect(page.getByRole('article',{name:'회의록 문서'})).toContainText('변경하지 않은 요약');
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
