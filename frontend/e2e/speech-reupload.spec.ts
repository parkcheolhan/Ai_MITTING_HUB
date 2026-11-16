import {test,expect} from '@playwright/test';

for(const failStart of [false,true]){
 test(`failed speech can replace file and retry${failStart?' without uploading twice after a start failure':''}`,async({page})=>{
  let uploads=0,starts=0;
  page.on('pageerror',error=>{throw error;});
  const meeting:any={id:'retry-voice',title:'음성 재등록',createdAt:new Date().toISOString(),status:'FAILED',drafts:[],epics:[],jobs:[{id:'failed',type:'PIPELINE',status:'FAILED',stage:'TRANSCRIBING',progress:10,errorMessage:'음성 변환 실패'}],content:{occurredAt:'',projectKey:'DEMO',participants:[],source:'MEDIA',segments:[],items:[],minutes:null,assets:[{id:'old',originalName:'old.wav',mediaType:'audio/wav',size:10}],template:'일반'}};
  await page.route('**/api/**',route=>{
   const path=new URL(route.request().url()).pathname;
   if(path.endsWith('/assets')&&route.request().method()==='PUT'){uploads++;meeting.content.assets=[{id:'new',originalName:'new.acc',mediaType:'audio/aac',size:20}];return route.fulfill({json:meeting.content.assets[0]});}
   if(path.endsWith('/process')){starts++;if(failStart&&starts===1)return route.fulfill({status:400,json:{message:'변환 요청 실패 · 다시 시도하세요'}});meeting.status='QUEUED';meeting.jobs.unshift({id:'retry',type:'PIPELINE',status:'QUEUED',stage:'TRANSCRIBING',progress:0});return route.fulfill({json:meeting.jobs[0]});}
   return route.fulfill({json:path==='/api/meetings/retry-voice'?meeting:path==='/api/settings'?{mode:'mock',configured:true,missing:[]}:path==='/api/settings/ai'?{mode:'mock',configured:true}:[]});
  });
  await page.goto('/meetings/retry-voice?tab=transcript');
  await page.getByLabel('다시 등록할 음성 파일').setInputFiles({name:'new.acc',mimeType:'application/octet-stream',buffer:Buffer.from('audio')});
  await page.getByRole('button',{name:'파일 다시 등록 후 음성 변환',exact:true}).click();
  if(failStart){await expect(page.getByRole('alert')).toContainText('변환 요청 실패');await page.getByRole('button',{name:'파일 다시 등록 후 음성 변환',exact:true}).click();}
  await expect.poll(()=>starts).toBe(failStart?2:1);expect(uploads).toBe(1);
  await expect(page.getByRole('region',{name:'음성 파일 다시 등록'})).toHaveCount(0);
  await expect(page.locator('.media-player')).toContainText('new.acc');await expect(page.locator('.media-player')).not.toContainText('old.wav');
 });
}
