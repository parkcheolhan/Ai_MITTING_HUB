import {test,expect} from '@playwright/test';

test('waiting stages survive reload, allow inline text edits and require explicit continuation',async({page})=>{
  const segment={id:'a',speaker:'화자 1',startMs:0,endMs:2000,text:'기존 음성 문구입니다.',estimatedTime:false,speakerAssignment:'ESTIMATED'};
  const job={id:'confirm-job',meetingId:'confirm-ui',type:'PIPELINE',status:'WAITING_CONFIRMATION',stage:'REVIEWING_TRANSCRIPT',nextStageIndex:2,advanceMode:'CONFIRM',progress:26,errorMessage:null};
  const meeting={id:'confirm-ui',title:'단계 확인 검증',status:'WAITING_CONFIRMATION',createdAt:new Date().toISOString(),drafts:[],jobs:[job],epics:[],content:{occurredAt:'',projectKey:'DEMO',participants:[],memo:'',source:'MEDIA',language:'ko',template:'일반',diarization:true,segments:[segment],minutes:null,items:[],assets:[],analysisAdvanceMode:'CONFIRM',originalTranscriptText:{} as Record<string,string>}};
  let continuations=0,received:unknown;
  await page.route('**/api/**',route=>{
    const r=route.request(),path=new URL(r.url()).pathname;
    if(path==='/api/meetings/confirm-ui/transcript'&&r.method()==='PUT'){segment.text=r.postDataJSON().edits[0].text;return route.fulfill({json:meeting});}
    if(path==='/api/meetings/confirm-ui/analysis-flow'&&r.method()==='PUT'){job.advanceMode=r.postDataJSON().mode;meeting.content.analysisAdvanceMode=r.postDataJSON().mode;return route.fulfill({json:meeting});}
    if(path==='/api/jobs/confirm-job/continue'){
      continuations++;received=r.postDataJSON();
      if(job.advanceMode==='AUTO'){job.status='COMPLETED';job.nextStageIndex=5;meeting.status='REVIEW_PENDING';}
      else{job.nextStageIndex=3;job.stage='GENERATING_MINUTES';}
      return route.fulfill({json:job});
    }
    return route.fulfill({json:path==='/api/meetings/confirm-ui'?meeting:[]});
  });
  await page.goto('/meetings/confirm-ui?tab=transcript');
  await expect(page.getByRole('button',{name:'확인 후 다음 단계 진행',exact:true})).toBeVisible();
  await expect(page.locator('.job-steps li').nth(1)).toContainText('완료');
  await expect(page.locator('.job-steps li').nth(2)).toContainText('확인 대기');
  await page.reload();
  await expect(page.getByRole('button',{name:'확인 후 다음 단계 진행',exact:true})).toBeVisible();
  expect(continuations).toBe(0);
  await page.getByRole('button',{name:'00:00:00 문구 수정',exact:true}).click();
  await page.getByLabel('00:00:00 발언 내용',{exact:true}).fill('현장에서 바로 수정한 문구입니다.');
  await page.getByRole('button',{name:'수정 문구 적용',exact:true}).click();
  await expect(page.locator('.transcript-text-fragment')).toHaveText('현장에서 바로 수정한 문구입니다.');
  await expect(page.getByText('음성 정보가 부족한 구간 포함 · 화자 매칭 추정',{exact:true})).toHaveCount(0);
  expect(continuations).toBe(0);
  await page.getByRole('button',{name:'확인 후 다음 단계 진행',exact:true}).click();
  await expect(page.locator('.job-steps li').nth(3)).toContainText('확인 대기');
  expect(received).toEqual({expectedStageIndex:2});expect(continuations).toBe(1);
  await page.getByLabel('분석 진행 방식',{exact:true}).selectOption('AUTO');
  await expect(page.getByLabel('분석 진행 방식',{exact:true})).toHaveValue('AUTO');
  expect(continuations).toBe(1);
  await page.getByRole('button',{name:'확인 후 다음 단계 진행',exact:true}).click();
  await expect(page.getByRole('button',{name:'확인 후 다음 단계 진행',exact:true})).toHaveCount(0);
  expect(continuations).toBe(2);
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
