import {test,expect} from '@playwright/test';

test('M4A selected in the main uploader is attached and can be decoded for playback',async({page,request})=>{
  await page.goto('/meetings/new');
  await expect(page.getByRole('heading',{name:'원본 녹음·영상 첨부 · M4A 지원'})).toBeVisible();
  await page.locator('input[name="title"]').fill('M4A upload verification');
  await page.locator('#transcript').fill('녹음 원문과 함께 검토할 작업을 확인합니다.');
  await page.getByLabel('회의 자료 파일 선택').setInputFiles('e2e/fixtures/short.m4a');
  await expect(page.getByRole('status')).toContainText('short.m4a');
  await expect(page.locator('#transcript')).toHaveValue('녹음 원문과 함께 검토할 작업을 확인합니다.');
  await page.getByRole('button',{name:'저장 후 분석 시작'}).click();
  await expect(page).toHaveURL(/\/meetings\/[a-f0-9-]+$/);
  const id=new URL(page.url()).pathname.split('/')[2];
  await page.locator('.stepper').getByRole('button',{name:'음성 기록',exact:true}).click();
  const video=page.locator('.media-player video');await expect(video).toHaveCount(1);
  await expect.poll(()=>video.evaluate((v:HTMLVideoElement)=>v.readyState)).toBeGreaterThanOrEqual(1);
  const meeting=await(await request.get('/api/meetings/'+id)).json();
  expect(meeting.content.assets[0]).toMatchObject({originalName:'short.m4a',mediaType:'audio/mp4'});
  const media=await request.get(`/api/meetings/${id}/assets/${meeting.content.assets[0].id}`);
  expect(media.ok()).toBeTruthy();expect(media.headers()['content-type']).toContain('audio/mp4');
});
