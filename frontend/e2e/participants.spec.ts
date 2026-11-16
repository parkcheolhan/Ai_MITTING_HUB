import {test,expect} from '@playwright/test';
test('assigns detected voices to attendees and adds a listener without a speech assignment',async({page})=>{
  const segments=[{id:'s1',speaker:'화자 1',startMs:0,endMs:3000,text:'로그인 수정 작업을 진행합니다.',estimatedTime:false},{id:'s2',speaker:'화자 2',startMs:4000,endMs:7000,text:'접근 권한을 확인합니다.',estimatedTime:false}];
  const meeting={id:'voices',title:'음성별 참석자 검증',status:'REVIEW_PENDING',createdAt:'2026-11-09',jobs:[],drafts:[],epics:[],content:{occurredAt:'',projectKey:'DEMO',participants:[] as string[],memo:'',source:'MEDIA',language:'ko',template:'일반',diarization:true,segments,speakerGroups:{s1:'화자 1',s2:'화자 2'},minutes:null,items:[],assets:[]}};
  let saved=false;
  await page.route('**/api/**',route=>{
    const path=new URL(route.request().url()).pathname;
    if(path==='/api/meetings/voices/participants'){
      const input=route.request().postDataJSON();expect(input.participants).toEqual(['김민수','이지은','참관인']);expect(input.segmentSpeakers).toEqual({s1:'김민수',s2:'이지은'});
      meeting.content.participants=input.participants;segments.forEach(s=>s.speaker=input.segmentSpeakers[s.id]);saved=true;return route.fulfill({json:meeting});
    }
    return route.fulfill({json:path==='/api/meetings/voices'?meeting:path==='/api/settings/ai'?{mode:'codex',configured:true}:path==='/api/settings'?{mode:'cloud',configured:true}:[]});
  });
  await page.goto('/meetings/voices?tab=transcript');
  for(const name of ['김민수','이지은','참관인']){await page.getByLabel('참석자 이름',{exact:true}).fill(name);await page.getByRole('button',{name:'참석자 추가',exact:true}).click();}
  await page.getByLabel('화자 1 참석자 지정').selectOption('김민수');await page.getByLabel('화자 2 참석자 지정').selectOption('이지은');
  await page.getByRole('button',{name:'참석자 지정 저장',exact:true}).click();await expect(page.getByText('참석자와 발언자 지정을 저장했습니다.',{exact:true})).toBeVisible();expect(saved).toBe(true);
  await expect(page.locator('.transcript-utterance strong').first()).toHaveText('김민수');await expect(page.locator('.participant-chips')).toContainText('참관인');
  await page.reload();await expect(page.getByLabel('화자 1 참석자 지정')).toHaveValue('김민수');
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
