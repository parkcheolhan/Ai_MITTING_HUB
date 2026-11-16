import {test,expect} from '@playwright/test';

test('shows organized work above evidence and applies transcript times together',async({page})=>{
  const segments=[{id:'s1',speaker:'김민수',text:'로그인 수정 작업을 진행합니다.',startMs:10000,endMs:20000,estimatedTime:false},{id:'s2',speaker:'이지은',text:'접근 권한을 확인합니다.',startMs:20000,endMs:30000,estimatedTime:false}];
  const item={id:'i1',kind:'ACTION',title:'로그인 수정',tasks:['로그인 오류 수정'],checks:['접근 권한 확인'],acceptanceCriteria:['로그인 검증 완료'],dependencies:[],assigneeSuggestion:null,dueDateSuggestion:null,evidence:[{segmentId:'s1',startMs:10000,speaker:'김민수',quote:segments[0].text,estimatedTime:false}],confidence:0.8,inferred:false};
  const meeting={id:'layout',title:'화면 검증',status:'REVIEW_PENDING',createdAt:'2026-11-08T10:00:00Z',drafts:[],jobs:[],epics:[],content:{occurredAt:'',projectKey:'DEMO',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',diarization:false,segments,minutes:null,items:[item],assets:[]}};
  await page.route('**/api/**',route=>{
    const path=new URL(route.request().url()).pathname;
    if(path==='/api/meetings/layout/transcript/timing'){
      const input=route.request().postDataJSON();expect(input).toEqual({startMs:60000,intervalMs:15000});
      segments.forEach((s,i)=>{s.startMs=input.startMs+i*input.intervalMs;s.endMs=s.startMs+input.intervalMs;s.estimatedTime=true;});
      return route.fulfill({json:meeting});
    }
    return route.fulfill({json:path==='/api/meetings/layout'?meeting:path==='/api/settings/ai'?{mode:'mock',configured:true}:path==='/api/settings'?{mode:'mock'}:[]});
  });
  await page.goto('/meetings/layout?tab=analysis');
  const organized=page.getByRole('heading',{name:'정리된 업무 상세',exact:true});
  const evidence=page.getByRole('heading',{name:'원문에서 확인하기',exact:true});
  expect((await organized.boundingBox())!.y).toBeLessThan((await evidence.boundingBox())!.y);
  await expect(page.locator('.analysis-grid > div')).toHaveCount(2);
  await expect(page.locator('.evidence-panel')).toContainText('정리된 업무 상세');
  await expect(page.locator('.evidence-panel')).toContainText('원문에서 확인하기');
  expect((await page.locator('.evidence-panel').boundingBox())!.x).toBeGreaterThan((await page.locator('.analysis-card').boundingBox())!.x);
  await expect(page.locator('.detail-tabs')).toHaveCount(0);
  await page.locator('.stepper').getByRole('button',{name:'음성 기록'}).click();
  await expect(page.locator('.transcript-document')).toContainText(segments[0].text);
  await expect(page.locator('.transcript-document')).toContainText(segments[1].text);
  await expect(page.locator('.segment')).toHaveCount(0);
  await page.getByLabel('기록 시작 시각').fill('00:01:00');
  await page.getByLabel('발언 구간 간격 (초)').fill('15');
  await page.getByRole('button',{name:'전체 시간 적용',exact:true}).click();
  await expect(page.getByRole('status')).toHaveText('전체 발언 시간을 저장했습니다.');
  await expect(page.locator('.segment').first()).toContainText('00:01:00');
  await expect(page.locator('.segment').nth(1)).toContainText('00:01:15');
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
