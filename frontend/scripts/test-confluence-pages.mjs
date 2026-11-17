import {chromium,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
// All API requests use synthetic credentials and data; never connect this test to the user's server.
const origin='https://parkcheolhan.github.io',base=origin+'/Ai_MITTING_HUB/',api='https://moa-api.example.test',token='synthetic-confluence-pages-test-token';
const browser=await chromium.launch({headless:true,...(process.env.E2E_CHROMIUM?{executablePath:process.env.E2E_CHROMIUM}:{channel:'chrome'})});
try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const draft=(id,summary,epicId)=>({id,summary,epicId,meetingId:'linked',projectKey:'TEST',issueType:'Task',description:summary+' 설명',assignee:'',assigneeName:'',priority:'Medium',dueDate:'',startAt:'',endAt:'',scheduleMode:'MANUAL',labels:[],parent:'',parentDraftId:'',analysisItemIds:[],status:'DRAFT',version:0});
  const meeting={id:'linked',title:'Confluence 문서 연결 검증',createdAt:'2026-11-17T00:00:00Z',status:'REVIEW_PENDING',jobs:[],epics:[{id:'alpha',summary:'제품 개선',status:'PENDING',projectKey:'TEST',currentTarget:true},{id:'beta',summary:'운영 개선',status:'PENDING',projectKey:'TEST',currentTarget:true}],drafts:[draft('one','로그인 개선','alpha'),draft('two','접근성 검토','alpha'),draft('three','운영 점검','beta')],content:{occurredAt:'2026-11-17T10:00',projectKey:'TEST',participants:['김민수'],source:'TEXT',template:'일반',segments:[],assets:[],items:[],minutes:{version:1,generationMode:'manual',overview:'회의 개요',summary:'안건',discussion:'논의 내용',decisions:'결정',openQuestions:'없음',followUp:'검토',formatted:''}}};
  let settings={enabled:false,useJiraCredentials:true,siteUrl:'https://unit.atlassian.net',email:'unit@example.com',cloudId:'',tokenConfigured:true,connected:false,configured:false,spaceId:'',spaceName:'',parentPageId:'',parentTitle:'',automatic:true},publication={configured:false,automatic:true,status:'NOT_PUBLISHED',pageUrl:'',error:'',syncedAt:'',spaceName:'',parentTitle:''},uploads=0;
  if(!process.env.E2E_PUBLISHED){const html=await readFile('pages/index.html','utf8');await page.route(origin+'/**',r=>r.fulfill({contentType:'text/html',body:html}));}
  await page.route(api+'/**',route=>{
    const r=route.request(),path=new URL(r.url()).pathname,headers={'Access-Control-Allow-Origin':origin,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,PUT,DELETE,OPTIONS'};
    if(r.method()==='OPTIONS')return route.fulfill({status:204,headers});assert.equal(r.headers().authorization,'Bearer '+token);let json=[];
    if(path==='/api/meetings/linked')json=meeting;if(path==='/api/meetings')json=[meeting];if(path==='/api/settings')json={mode:'mock',configured:true,defaultProject:'TEST'};if(path==='/api/settings/ai')json={mode:'mock',configured:true};
    if(path==='/api/integrations/jira/projects')json=[{key:'TEST',name:'검증'}];
    if(path==='/api/settings/confluence')json=settings;
    if(path==='/api/settings/confluence/connection'){settings={...settings,...r.postDataJSON(),connected:true};delete settings.apiToken;json=settings;}
    if(path==='/api/integrations/confluence/spaces')json={results:[{id:'1',name:'제품 개발',key:'DEV'}],nextCursor:''};
    if(path==='/api/integrations/confluence/spaces/1/pages')json={results:[{id:'10',title:'회의록 모음'}],nextCursor:''};
    if(path==='/api/settings/confluence/destination'){settings={...settings,...r.postDataJSON(),configured:true,spaceName:'제품 개발',parentTitle:'회의록 모음'};publication={...publication,configured:true,spaceName:'제품 개발',parentTitle:'회의록 모음'};json=settings;}
    if(path==='/api/meetings/linked/confluence')json=publication;
    if(path==='/api/meetings/linked/confluence/publish'){uploads++;publication={...publication,status:'PUBLISHED',pageUrl:'https://unit.atlassian.net/wiki/pages/viewpage.action?pageId=101',syncedAt:new Date().toISOString()};json=publication;}
    return route.fulfill({headers,json});
  });
  await page.goto(base+'?v='+(process.env.E2E_REVISION||'confluence')+'#/meetings/linked/tickets?ticket=one');
  await page.getByLabel('API 서버 HTTPS 주소').fill(api);await page.getByLabel('접속 토큰',{exact:true}).fill(token);await page.getByRole('button',{name:'서버 연결',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'티켓 상세정보',exact:true}),list=dialog.getByRole('complementary',{name:'같은 에픽 업무 목록'});
  await expect(list.getByRole('button')).toHaveCount(2);await list.getByRole('button',{name:/접근성 검토/}).click();await expect(page.getByLabel('제목',{exact:true})).toHaveValue('접근성 검토');await expect(list).not.toContainText('운영 점검');
  await page.setViewportSize({width:390,height:844});await expect(list).toBeInViewport();assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.getByRole('button',{name:'티켓 상세 닫기',exact:true}).click();await expect(dialog).toHaveCount(0);
  await page.setViewportSize({width:1440,height:1000});await page.evaluate(()=>{location.hash='/settings?section=minutes';});const card=page.locator('#minutes-settings');await expect(card).toBeInViewport();
  await card.getByLabel('Confluence 회의록 업로드 사용').check();await card.getByRole('button',{name:'연결 설정 저장',exact:true}).click();await card.getByLabel('스페이스',{exact:true}).selectOption('1');await card.getByRole('button',{name:'페이지 조회',exact:true}).click();await card.getByLabel('조회된 상위 페이지',{exact:true}).selectOption('10');await card.getByRole('button',{name:'업로드 위치 저장',exact:true}).click();await expect(card.getByRole('status')).toContainText('업로드 위치를 저장했습니다');
  await page.evaluate(()=>{location.hash='/meetings/linked?tab=minutes';});const widget=page.getByRole('region',{name:'Confluence 회의록 업로드'});await expect(widget).toContainText('제품 개발 / 회의록 모음');await widget.getByRole('button',{name:'Confluence에 업로드',exact:true}).click();await expect(widget.getByRole('link',{name:'Confluence에서 보기',exact:true})).toHaveAttribute('href',publication.pageUrl);await expect(widget).toContainText('업로드 완료');assert.equal(uploads,1);
  await expect(widget.getByRole('button',{name:'Confluence 동기화',exact:true})).toBeEnabled();assert.deepEqual(errors,[]);
  console.log('PASS: '+(process.env.E2E_PUBLISHED?'published':'bundled')+' Pages HTML; same Epic navigation, direct detail link, mobile layout, Confluence settings and upload links, synthetic API only.');
}finally{await browser.close();}
