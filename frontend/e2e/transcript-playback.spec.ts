import {test,expect} from '@playwright/test';

test('playback focuses matching speech, supports search and manual follow control',async({page})=>{
  const segments=Array.from({length:20},(_,i)=>({id:`s${i}`,speaker:`참석자 ${i%2+1}`,text:`발언 내용 ${i}`,startMs:i*5000,endMs:(i+1)*5000,estimatedTime:false}));
  const meeting={id:'playback',title:'음성 검증',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),drafts:[],jobs:[],epics:[],content:{occurredAt:'',projectKey:'DEMO',participants:[],source:'MEDIA',language:'ko',template:'일반',diarization:false,segments,minutes:null,items:[],assets:[{id:'audio',originalName:'회의.wav',mediaType:'audio/wav',size:10}]}};
  await page.route('**/api/**',route=>route.fulfill({json:new URL(route.request().url()).pathname==='/api/meetings/playback'?meeting:[]}));
  await page.goto('/meetings/playback?tab=transcript');
  await page.locator('video').evaluate(video=>{video.currentTime=75;video.dispatchEvent(new Event('timeupdate'));});
  await expect(page.locator('.transcript-utterance[data-active="true"]')).toContainText('발언 내용 15');
  await expect.poll(()=>page.locator('.transcript-document').evaluate(el=>el.scrollTop)).toBeGreaterThan(0);
  // Coordinates must be local to the reader even when the whole page has scrolled.
  await page.locator('.transcript-document').evaluate(el=>{
    const active=el.querySelector('[data-active="true"]')!;
    const reader=el.getBoundingClientRect(),row=active.getBoundingClientRect();
    if(Math.abs(row.top-reader.top-el.clientTop-12)>2)throw new Error('Playing utterance is not anchored to the reader top');
  });
  const visibleScroll=await page.locator('.transcript-document').evaluate(el=>el.scrollTop);
  const pageScroll=await page.evaluate(()=>window.scrollY);
  await page.locator('video').evaluate(video=>{video.currentTime=76;video.dispatchEvent(new Event('timeupdate'));});
  expect(await page.locator('.transcript-document').evaluate(el=>el.scrollTop)).toBe(visibleScroll);
  expect(await page.evaluate(()=>window.scrollY)).toBe(pageScroll);
  await page.locator('video').evaluate(video=>{video.currentTime=95;video.dispatchEvent(new Event('timeupdate'));});
  await expect(page.locator('.transcript-utterance[data-active="true"]')).toContainText('발언 내용 19');
  await expect.poll(()=>page.locator('.transcript-document').evaluate(el=>{
    const row=el.querySelector('[data-active="true"]')!.getBoundingClientRect();
    return Math.abs(row.top-el.getBoundingClientRect().top-el.clientTop-12);
  })).toBeLessThan(2);
  await page.getByLabel('재생 발언 따라가기').uncheck();
  await page.locator('.transcript-document').evaluate(el=>el.scrollTop=0);
  await page.locator('video').evaluate(video=>{video.currentTime=85;video.dispatchEvent(new Event('timeupdate'));});
  await expect(page.locator('.transcript-utterance[data-active="true"]')).toContainText('발언 내용 17');
  expect(await page.locator('.transcript-document').evaluate(el=>el.scrollTop)).toBe(0);
  await page.getByLabel('발언 검색',{exact:true}).fill('내용 17');
  await expect(page.locator('.transcript-utterance')).toHaveCount(1);
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test('overlapping utterances focus the latest start and inactive recordings cannot overwrite playback',async({page})=>{
  const segments=[{id:'long',speaker:'참석자 1',text:'긴 이전 발언',startMs:0,endMs:20000,estimatedTime:false},{id:'latest',speaker:'참석자 2',text:'현재 발언',startMs:5000,endMs:12000,estimatedTime:false}];
  const meeting={id:'overlap',title:'겹치는 발언',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),drafts:[],jobs:[],epics:[],content:{occurredAt:'',projectKey:'DEMO',participants:[],source:'MEDIA',language:'ko',template:'일반',diarization:false,segments,minutes:null,items:[],assets:[{id:'a',originalName:'첫 녹음.wav',mediaType:'audio/wav',size:10},{id:'b',originalName:'다음 녹음.wav',mediaType:'audio/wav',size:10}]}};
  await page.route('**/api/**',route=>route.fulfill({json:new URL(route.request().url()).pathname==='/api/meetings/overlap'?meeting:[]}));
  await page.goto('/meetings/overlap?tab=transcript');
  await page.locator('video').first().evaluate(video=>{video.currentTime=6;video.dispatchEvent(new Event('play'));video.dispatchEvent(new Event('timeupdate'));});
  await expect(page.locator('.transcript-utterance[data-active="true"]')).toContainText('현재 발언');
  await page.locator('video').nth(1).evaluate(video=>{video.dispatchEvent(new Event('timeupdate'));video.dispatchEvent(new Event('ended'));});
  await expect(page.locator('.transcript-utterance[data-active="true"]')).toContainText('현재 발언');
  await page.locator('video').first().evaluate(video=>{video.currentTime=1;video.dispatchEvent(new Event('seeking'));});
  await expect(page.locator('.transcript-utterance[data-active="true"]')).toContainText('긴 이전 발언');
});

test('legacy ticket management links open the meeting review',async({page})=>{
  await page.route('**/api/**',route=>route.fulfill({json:new URL(route.request().url()).pathname==='/api/meetings/old'?null:[]}));
  await page.goto('/tickets?meeting=old&step=3');
  await expect(page).toHaveURL(/\/meetings\/old\/results$/);
  await expect(page.locator('nav')).not.toContainText('JIRA 티켓 관리');
  await page.goto('/tickets');await expect(page).toHaveURL(/\/meetings$/);
});
