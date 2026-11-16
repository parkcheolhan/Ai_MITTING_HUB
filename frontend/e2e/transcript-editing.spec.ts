import {test,expect} from '@playwright/test';

test('edits transcript, groups only consecutive speakers and retains source timing and originals',async({page})=>{
  const segments=[
    {id:'a',speaker:'김민수',startMs:0,endMs:3000,text:'주간복 때 초연을 검토합니다.',estimatedTime:false},
    {id:'b',speaker:'김민수',startMs:3000,endMs:5000,text:'자막도 확인해 주세요.',estimatedTime:false},
    {id:'c',speaker:'이지은',startMs:5000,endMs:7000,text:'확인하겠습니다.',estimatedTime:false},
    {id:'d',speaker:'김민수',startMs:7000,endMs:10000,text:'일정은 유지합니다.',estimatedTime:false},
  ];
  const meeting={id:'editing',title:'음성 기록 편집 테스트',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),drafts:[],jobs:[],epics:[],content:{occurredAt:'',projectKey:'TEST',participants:['김민수','이지은'],source:'MEDIA',language:'ko',template:'일반',diarization:true,segments,minutes:null,items:[],assets:[],originalTranscriptText:{} as Record<string,string>,transcriptRevision:0,transcriptReviewRevision:0,transcriptReviewedSegments:4,transcriptReviewMode:'codex'}};
  let received:unknown;
  await page.route('**/api/**',async route=>{
    const r=route.request(),path=new URL(r.url()).pathname;
    if(path==='/api/settings/ai')return route.fulfill({json:{mode:'codex',configured:true,model:'synthetic'}});
    if(path==='/api/meetings/editing/transcript'&&r.method()==='PUT'){
      received=r.postDataJSON();
      for(const edit of r.postDataJSON().edits){const s=segments.find(s=>s.id===edit.id)!;meeting.content.originalTranscriptText[s.id]??=s.text;s.text=edit.text;}
      meeting.content.transcriptRevision++;return route.fulfill({json:meeting});
    }
    return route.fulfill({json:path==='/api/meetings/editing'?meeting:[]});
  });
  await page.goto('/meetings/editing?tab=transcript');
  await expect(page.locator('.transcript-utterance')).toHaveCount(3);
  await expect(page.locator('.transcript-review-coverage')).toContainText('전체 4 / 4구간 검토');
  await expect(page.locator('.transcript-utterance').first()).toContainText('자막도 확인해 주세요.');
  await page.getByRole('button',{name:'음성 기록 편집',exact:true}).click();
  const input=page.getByLabel('00:00:00 발언 내용',{exact:true});
  await input.fill('주간보고 때 초안을 검토합니다.');
  // Search must not lose edits or combine the two non-consecutive 김민수 turns.
  await page.getByLabel('발언 검색',{exact:true}).fill('김민수');
  await expect(page.locator('.transcript-utterance')).toHaveCount(2);
  await page.getByRole('button',{name:'음성 기록 저장',exact:true}).click();
  await expect(page.getByRole('status').filter({hasText:'음성 기록을 저장했습니다.'})).toBeVisible();
  await expect(page.locator('.transcript-review-coverage')).not.toContainText('검토 완료');
  expect(received).toEqual({edits:[{id:'a',originalText:'주간복 때 초연을 검토합니다.',text:'주간보고 때 초안을 검토합니다.'}]});
  await page.reload();
  await expect(page.locator('.transcript-utterance')).toHaveCount(3);
  await expect(page.locator('.transcript-utterance').first()).toContainText('주간보고 때 초안을 검토합니다.');
  await page.getByText('처음 수정 전 원문 보기',{exact:true}).click();
  await expect(page.locator('.transcript-original')).toContainText('주간복 때 초연을 검토합니다.');
  expect(segments[0].startMs).toBe(0);expect(segments[1].id).toBe('b');
  await page.getByRole('button',{name:'음성 기록 편집',exact:true}).click();
  await page.getByLabel('00:00:03 발언 내용',{exact:true}).fill('저장하지 않을 내용');
  await page.getByRole('button',{name:'편집 취소',exact:true}).click();
  await expect(page.locator('.transcript-document')).not.toContainText('저장하지 않을 내용');
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
