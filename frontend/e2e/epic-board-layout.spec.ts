import {test,expect} from '@playwright/test';

test('aligns epic columns horizontally and scrolls to distant drop targets while dragging',async({page})=>{
  const epics=Array.from({length:5},(_,i)=>({id:'epic-'+i,projectKey:'TEST',summary:'프로젝트 '+(i+1),status:'PENDING',issueKey:null,issueUrl:null,error:null,currentTarget:true}));
  const draft=(i:number,epicId='')=>({id:'task-'+i,meetingId:'wide-board',projectKey:'TEST',issueType:'Task',summary:'검토 업무 '+i,description:'업무 내용을 확인합니다.',assignee:'',priority:'Medium',dueDate:'',labels:[],parent:'',parentDraftId:'',epicId,analysisItemIds:[],status:'DRAFT',issueKey:null,issueUrl:null,publishedAt:null,error:null,duplicateSuspected:false,version:0,scheduleMode:'MANUAL'});
  const meeting={id:'wide-board',title:'가로 배치 검증',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),jobs:[],epics,drafts:[...Array.from({length:18},(_,i)=>draft(i)),...Array.from({length:8},(_,i)=>draft(i+18,'epic-0'))],content:{occurredAt:'',projectKey:'TEST',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',segments:[],minutes:null,items:[],assets:[]}};
  const moves:any[]=[];const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/**',route=>{
    const r=route.request(),path=new URL(r.url()).pathname;
    if(path==='/api/ticket-drafts/task-0/epic'&&r.method()==='PUT'){
      const input=r.postDataJSON();expect(input.version).toBe(meeting.drafts[0].version);moves.push(input);meeting.drafts[0].epicId=input.epicId;meeting.drafts[0].version++;
      return route.fulfill({json:[meeting.drafts[0]]});
    }
    return route.fulfill({json:path==='/api/meetings/wide-board'?meeting:path==='/api/settings'?{mode:'mock',configured:true}:path==='/api/settings/ai'?{mode:'mock',configured:true}:[]});
  });
  await page.setViewportSize({width:1440,height:1000});await page.goto('/meetings/wide-board/tickets');
  const lanes=page.getByRole('region',{name:'에픽 가로 배치 보드',exact:true}),columns=lanes.locator('.epic-drop-box');
  await expect(columns).toHaveCount(6);
  const boxes=await columns.evaluateAll(elements=>elements.map(el=>{const r=el.getBoundingClientRect();return {x:r.x,y:r.y,height:r.height};}));
  for(let i=1;i<boxes.length;i++){expect(Math.abs(boxes[i].y-boxes[0].y)).toBeLessThan(2);expect(boxes[i].x).toBeGreaterThan(boxes[i-1].x);expect(boxes[i].height).toBe(boxes[0].height);}
  const panel=(await page.locator('.candidate-panel').boundingBox())!;
  await expect(page.getByRole('dialog',{name:'티켓 상세정보',exact:true})).toHaveCount(0);expect(panel.width).toBeGreaterThan(1000);
  expect(await lanes.evaluate(el=>el.scrollWidth>el.clientWidth)).toBe(true);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(1440);
  const unassigned=page.getByRole('region',{name:'미배치 업무 목록',exact:true});
  await unassigned.evaluate(el=>{el.scrollTop=200;});expect(await unassigned.evaluate(el=>el.scrollTop)).toBeGreaterThan(100);
  expect(await page.getByRole('region',{name:'프로젝트 1 업무 목록',exact:true}).evaluate(el=>el.scrollTop)).toBe(0);
  await unassigned.evaluate(el=>{el.scrollTop=0;});await lanes.scrollIntoViewIfNeeded();
  const initialHandle=(await page.getByLabel('검토 업무 0 이동 핸들',{exact:true}).boundingBox())!,body=(await unassigned.boundingBox())!;
  const originalHeader=(await columns.first().locator('.epic-box-heading').boundingBox())!;
  await page.mouse.move(initialHandle.x+initialHandle.width/2,initialHandle.y+initialHandle.height/2);await page.mouse.down();await page.mouse.move(initialHandle.x+initialHandle.width/2+10,initialHandle.y+initialHandle.height/2+2,{steps:4});
  await page.mouse.move(body.x+body.width/2,body.y+body.height-8,{steps:8});
  await expect.poll(()=>unassigned.evaluate(el=>el.scrollTop)).toBeGreaterThan(120);
  expect((await columns.first().locator('.epic-box-heading').boundingBox())!.y).toBeCloseTo(originalHeader.y,0);
  const firstTarget=(await page.getByRole('region',{name:'프로젝트 1 에픽 박스',exact:true}).locator('.epic-box-heading').boundingBox())!;
  await page.mouse.move(firstTarget.x+firstTarget.width/2,firstTarget.y+40,{steps:6});await page.mouse.up();
  await expect(page.getByRole('region',{name:'프로젝트 1 에픽 박스',exact:true}).locator('[data-draft-id="task-0"]')).toHaveCount(1);
  const handle=page.getByLabel('검토 업무 0 이동 핸들',{exact:true});await handle.scrollIntoViewIfNeeded();
  const start=(await handle.boundingBox())!,area=(await lanes.boundingBox())!;
  await page.mouse.move(start.x+start.width/2,start.y+start.height/2);await page.mouse.down();await page.mouse.move(start.x+start.width/2+12,start.y+start.height/2+3,{steps:4});
  await page.mouse.move(area.x+area.width-10,area.y+Math.min(area.height/2,250),{steps:10});
  await expect.poll(()=>lanes.evaluate(el=>el.scrollLeft),{timeout:8000}).toBeGreaterThan(700);
  const last=page.getByRole('region',{name:'프로젝트 5 에픽 박스',exact:true});
  await expect.poll(async()=>{const r=(await last.boundingBox())!;return r.x+r.width<=area.x+area.width+2;},{timeout:8000}).toBe(true);
  const target=(await last.locator('.epic-box-heading').boundingBox())!;await page.mouse.move(target.x+target.width/2,target.y+30,{steps:6});await page.mouse.up();
  await expect(last.locator('[data-draft-id="task-0"]')).toHaveCount(1);expect(moves.map(m=>m.epicId)).toEqual(['epic-0','epic-4']);expect(meeting.drafts[1].epicId).toBe('');
  await page.getByRole('button',{name:'이전 에픽 보기',exact:true}).click();await expect.poll(()=>lanes.evaluate(el=>el.scrollLeft)).toBeLessThan(700);
  await lanes.evaluate(el=>{el.scrollLeft=0;});await page.locator('.candidate-panel').screenshot({path:'test-results/epic-horizontal-desktop.png',animations:'disabled'});
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.getByRole('button',{name:'다음 에픽 보기',exact:true}).click();await expect.poll(()=>lanes.evaluate(el=>el.scrollLeft)).toBeGreaterThan(100);
  await page.locator('.candidate-panel').screenshot({path:'test-results/epic-horizontal-mobile.png',animations:'disabled'});expect(errors).toEqual([]);
});
