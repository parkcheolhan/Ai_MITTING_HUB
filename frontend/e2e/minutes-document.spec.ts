import {test,expect} from '@playwright/test';

for(const custom of [false,true])test(`reads ${custom?'uploaded':'basic'} minutes as a document and edits on demand`,async({page})=>{
  const minutes={version:1,overview:'서비스 개선 회의',summary:'로그인 오류를 수정합니다.',discussion:'권한과 운영 환경을 확인합니다.',decisions:'수정 진행',openQuestions:'기한 미정',followUp:'검증 결과 공유',formatted:custom?'# 회사 회의록\n## 결정 사항\n- 로그인 수정\n<script>alert(1)</script>':'',generationMode:'codex',generationModel:'test'};
  const meeting={id:'document',title:'주간 업무 회의',status:'REVIEW_PENDING',createdAt:'2026-11-09T00:00:00Z',drafts:[],jobs:[],epics:[],content:{occurredAt:'2026-11-09T10:00',projectKey:'DEMO',participants:['김민수'],memo:'',source:'TEXT',language:'ko',template:'일반',diarization:false,segments:[],minutes,items:[],assets:[]}};
  await page.route('**/api/**',route=>{
    const path=new URL(route.request().url()).pathname;
    if(path==='/api/minutes-templates')return route.fulfill({json:route.request().method()==='POST'?{...route.request().postDataJSON(),id:'company',createdAt:new Date().toISOString()}:[]});
    if(path==='/api/meetings/document/minutes/template'){
      meeting.content.template=route.request().postDataJSON().template;
      minutes.formatted='# 회사 양식\n'+minutes.summary;minutes.version++;return route.fulfill({json:meeting});
    }
    if(path==='/api/meetings/document/minutes'&&route.request().method()==='PUT'){
      Object.assign(minutes,route.request().postDataJSON(),{version:2});return route.fulfill({json:minutes});
    }
    return route.fulfill({json:path==='/api/meetings/document'?meeting:path==='/api/settings'?{mode:'mock'}:[]});
  });
  await page.goto('/meetings/document?tab=minutes');
  await expect(page.getByRole('article',{name:'회의록 문서'})).toContainText('주간 업무 회의');
  const date=page.locator('.document-heading dd').first();
  await expect(date).toHaveText('2026년 11월 09일 10:00');
  expect(await date.evaluate(el=>Number.parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(16);
  expect((await date.boundingBox())!.width).toBeGreaterThan(250);
  if(!custom)await expect(page.getByRole('article',{name:'회의록 문서'})).toContainText('주요 안건');
  await expect(page.locator('.minutes-view textarea')).toHaveCount(0);
  if(custom){await expect(page.locator('.document-body')).toContainText('<script>alert(1)</script>');await expect(page.locator('.document-body script')).toHaveCount(0);}
  await page.getByRole('button',{name:'회의록 편집',exact:true}).click();
  const editor=custom?page.locator('.minutes-view textarea'):page.locator('.minutes-grid textarea').nth(1);
  await editor.fill('수정한 회의록 본문');
  await page.getByRole('button',{name:'회의록 저장',exact:true}).click();
  await expect(page.getByRole('article',{name:'회의록 문서'})).toContainText('수정한 회의록 본문');
  await page.getByRole('button',{name:'회의록 양식 지정',exact:true}).click();
  await page.getByLabel('양식 파일 업로드').setInputFiles({name:'회사.md',mimeType:'text/markdown',buffer:Buffer.from('# 회사 양식\n{{요약}}')});
  await expect(page.getByLabel('회의록 양식 목록').getByRole('button',{name:'회사.md'})).toBeVisible();
  await page.getByRole('button',{name:'선택한 양식 적용',exact:true}).click();
  await expect(page.getByRole('article',{name:'회의록 문서'})).toContainText('회사 양식');
  await page.reload();await expect(page.getByRole('article',{name:'회의록 문서'})).toContainText('회사 양식');
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'회의록 다운로드',exact:true}).click();expect((await download).suggestedFilename()).toBe('회의록.md');
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
