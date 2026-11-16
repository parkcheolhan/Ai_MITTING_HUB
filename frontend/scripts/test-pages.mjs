import {chromium} from 'playwright';
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {build} from 'esbuild';
// Synthetic data only: never read or forward the user's real server credentials/settings.
const config={apiUrl:'https://moa-api.example.test',pagesOrigin:'https://parkcheolhan.github.io',pagesUrl:'https://parkcheolhan.github.io/Ai_MITTING_HUB/'};
const token='synthetic-browser-test-token-32-characters';
const html=await readFile('pages/index.html','utf8');
const browser=await chromium.launch({headless:true,...(process.env.E2E_CHROMIUM?{executablePath:process.env.E2E_CHROMIUM}:{})});
try{
  const page=await browser.newPage();const errors=[],apiRequests=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('request',r=>{if(r.url().startsWith(config.apiUrl+'/api/'))apiRequests.push(r);});
  // Serve the prepared HTML under the exact Pages origin, with real browser CORS.
  await page.route(config.pagesOrigin+'/**',route=>route.fulfill({status:200,contentType:'text/html',body:html}));
  await page.route(config.apiUrl+'/**',async route=>{
    const r=route.request(),path=new URL(r.url()).pathname;
    const headers={'Access-Control-Allow-Origin':config.pagesOrigin,'Access-Control-Allow-Headers':'Authorization,Content-Type,Range','Access-Control-Allow-Methods':'GET,POST,PUT,DELETE,OPTIONS'};
    if(r.method()==='OPTIONS')return route.fulfill({status:204,headers});
    if(r.headers().authorization!=='Bearer '+token)return route.fulfill({status:401,headers,json:{message:'Unauthorized'}});
    let body=[];
    if(path==='/api/settings')body={mode:'cloud',siteUrl:'',defaultProject:'',configured:false,tokenConfigured:false,missing:[]};
    if(path==='/api/settings/ai')body={mode:'openai',model:'synthetic',configured:false,keyConfigured:false};
    if(path==='/api/settings/ai/codex/login')body={status:'IDLE'};
    if(path==='/api/integrations/speech')body={ready:true,speakerReady:true,message:'Synthetic speech engine'};
    return route.fulfill({status:200,headers,json:body});
  });
  await page.goto(config.pagesUrl);
  await page.getByRole('heading',{name:'모아 · 서버 연결',exact:true}).waitFor();
  assert.equal(apiRequests.length,0,'App must not call the server before login');
  await page.getByLabel('API 서버 HTTPS 주소').fill(config.apiUrl);
  await page.getByLabel('접속 토큰',{exact:true}).fill('incorrect-test-token-at-least-32-characters');
  await page.getByRole('button',{name:'서버 연결',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'접속 토큰이 올바르지 않습니다.'}).waitFor();
  await page.getByLabel('접속 토큰',{exact:true}).fill(token);
  await page.getByRole('button',{name:'서버 연결',exact:true}).click();
  await page.getByRole('button',{name:'서버 변경 / 연결 해제'}).waitFor({timeout:20000});
  await page.locator('h1').waitFor();
  await page.goto(config.pagesUrl+'#/settings');
  await page.getByRole('heading',{name:'우리 팀에 맞는 연결',exact:true}).waitFor();
  const result=await page.evaluate(async({apiUrl,token})=>{
    const response=await fetch(apiUrl+'/api/integrations/speech',{headers:{Authorization:'Bearer '+token}});
    return {status:response.status,...await response.json()};
  },{apiUrl:config.apiUrl,token});
  assert.equal(result.status,200);
  assert(apiRequests.length>3);
  for(const r of apiRequests)assert(!r.url().includes(token),'Secret must not appear in URLs');
  assert.deepEqual(errors,[]);
  // Exercise upload and authenticated media with synthetic files and no real backend changes.
  const probe=await build({stdin:{contents:`import React from 'react';import{createRoot}from'react-dom/client';import{upload}from'./src/api';import Video from'./src/AuthenticatedVideo';globalThis.testUpload=upload;globalThis.testVideo=()=>{const div=document.createElement('div');document.body.append(div);createRoot(div).render(React.createElement(Video,{path:'/meetings/media-check/assets/fake'}));};`,resolveDir:process.cwd(),loader:'tsx'},bundle:true,write:false,format:'iife',define:{'globalThis.MOA_PAGES':'true','globalThis.MOA_STANDALONE':'false','process.env.NODE_ENV':'"production"'}});
  await page.route(config.apiUrl+'/api/meetings/media-check/assets/fake',route=>{
    assert.equal(route.request().headers().authorization,'Bearer '+token);
    return route.fulfill({status:200,headers:{'Access-Control-Allow-Origin':config.pagesOrigin},contentType:'audio/wav',body:Buffer.from('RIFFsynthetic')});
  });
  await page.addScriptTag({content:probe.outputFiles[0].text});
  await page.evaluate(async()=>{await globalThis.testUpload('upload-check',new File(['synthetic'],'test.webm',{type:'audio/webm'}),()=>{});globalThis.testVideo();});
  await page.locator('video[src^="blob:"]').waitFor();
  const uploadRequest=apiRequests.find(r=>r.method()==='POST'&&r.url().endsWith('/upload-check/assets'));
  assert(uploadRequest,'Upload must reach configured API');
  assert.equal(uploadRequest.headers().authorization,'Bearer '+token);
  await page.reload();
  await page.getByLabel('API 서버 HTTPS 주소').waitFor();
  assert.equal(await page.getByLabel('API 서버 HTTPS 주소').inputValue(),config.apiUrl);
  await page.getByRole('button',{name:'서버 연결',exact:true}).click();
  await page.getByRole('button',{name:'서버 변경 / 연결 해제'}).waitFor();
  await page.getByRole('button',{name:'서버 변경 / 연결 해제'}).click();
  await page.getByRole('heading',{name:'모아 · 서버 연결',exact:true}).waitFor();
  assert.equal(await page.getByLabel('접속 토큰',{exact:true}).inputValue(),'');
  console.log('PASS (synthetic API): Pages connection gate, invalid token, settings, authenticated file upload and Blob media, reconnect/disconnect, no page errors or token URLs.');
}finally{await browser.close();}
