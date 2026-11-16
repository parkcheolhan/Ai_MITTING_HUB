import {test,expect} from '@playwright/test';

for(const extension of ['', 'acc','aac']){
 test(extension?`${extension} upload starts analysis without transcript`:'meeting can be saved without media, transcript or AI credentials',async({page})=>{
  const calls:string[]=[];let created:any;
  const meeting:any={id:'optional-input',title:'선택 입력 회의',createdAt:new Date().toISOString(),status:'UPLOADED',drafts:[],jobs:[],epics:[],content:{projectKey:'DEMO',participants:[],assets:[],segments:[],items:[],minutes:null,template:'일반',source:'MEDIA'}};
  await page.route('**/api/**',route=>{
   const path=new URL(route.request().url()).pathname,method=route.request().method();
   if(path==='/api/meetings'&&method==='POST'){calls.push('create');created=route.request().postDataJSON();return route.fulfill({json:meeting});}
   if(path.endsWith('/assets')&&method==='POST'){calls.push('upload');return route.fulfill({json:{}});}
   if(path.endsWith('/process')){calls.push('process');return route.fulfill({json:{id:'job'}});}
   return route.fulfill({json:path==='/api/meetings/optional-input'?meeting:path==='/api/settings'?{mode:'mock',configured:false,defaultProject:'DEMO',missing:[]}:path==='/api/settings/ai'?{mode:'mock',configured:!!extension}:path==='/api/integrations/speech'?{ready:!!extension,message:'엔진 상태'}:path.endsWith('/projects')?[{key:'DEMO',name:'테스트 프로젝트'}]:[]});
  });
  await page.goto('/meetings/new');await page.locator('input[name="title"]').fill('선택 입력 회의');
  await expect(page.locator('#transcript')).not.toHaveAttribute('required','');
  if(extension)await page.getByLabel('회의 자료 파일 선택').setInputFiles({name:`voice.${extension}`,mimeType:'application/octet-stream',buffer:Buffer.from('audio-bytes')});
  await page.getByRole('button',{name:extension?'저장 후 분석 시작':'회의만 저장',exact:true}).click();
  await expect(page).toHaveURL(/\/meetings\/optional-input$/);
  expect(created.transcript).toBe('');expect(calls).toEqual(extension?['create','upload','process']:['create']);
 });
}
