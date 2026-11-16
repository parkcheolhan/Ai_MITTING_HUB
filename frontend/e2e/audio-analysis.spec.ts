import {test,expect} from '@playwright/test';

for(const file of ['meeting-voice.m4a','meeting-video.mp4']){
  test(`transcribes ${file} without supplied text and analyzes the real speech`,async({page,request})=>{
    test.setTimeout(90000);
    await page.goto('/meetings/new');await page.locator('input[name="title"]').fill('Audio analysis '+file);
    await page.getByLabel('회의 자료 파일 선택').setInputFiles('e2e/fixtures/'+file);
    await expect(page.locator('#transcript')).toHaveValue('');
    await expect(page.getByRole('button',{name:'저장 후 분석 시작'})).toBeEnabled();
    await page.getByRole('button',{name:'저장 후 분석 시작'}).click();await expect(page).toHaveURL(/\/meetings\/[a-f0-9-]+$/);
    const id=new URL(page.url()).pathname.split('/')[2];
    await expect.poll(async()=>{
      const m=await(await request.get('/api/meetings/'+id)).json();
      if(m.jobs[0]?.status==='FAILED')throw new Error(m.jobs[0].errorMessage);
      return m.jobs[0]?.status;
    },{timeout:60000}).toBe('COMPLETED');
    const m=await(await request.get('/api/meetings/'+id)).json();
    expect(m.content.source).toBe('MEDIA');expect(m.content.segments.length).toBeGreaterThan(0);
    expect(m.content.segments.map((s:{text:string})=>s.text).join(' ')).toContain('로그인 오류');
    expect(m.content.segments[0]).toMatchObject({speaker:'화자 미분리',estimatedTime:false});
    expect(m.content.minutes).toBeTruthy();expect(m.content.items.length).toBeGreaterThan(0);
    await page.locator('.stepper').getByRole('button',{name:'음성 기록',exact:true}).click();await expect(page.locator('.segment').first()).toContainText('로그인 오류');
  });
}

test('silent recording fails without inventing a transcript',async({page,request})=>{
  test.setTimeout(60000);
  await page.goto('/meetings/new');await page.locator('input[name="title"]').fill('Silence validation');
  await page.getByLabel('회의 자료 파일 선택').setInputFiles('e2e/fixtures/short.m4a');
  await page.getByRole('button',{name:'저장 후 분석 시작'}).click();await expect(page).toHaveURL(/\/meetings\/[a-f0-9-]+$/);
  const id=new URL(page.url()).pathname.split('/')[2];
  await expect.poll(async()=>(await(await request.get('/api/meetings/'+id)).json()).jobs[0].status,{timeout:30000}).toBe('FAILED');
  const m=await(await request.get('/api/meetings/'+id)).json();expect(m.content.segments).toHaveLength(0);expect(m.content.minutes).toBeNull();expect(m.jobs[0].errorMessage).toContain('음성을 인식하지 못했습니다');
});
