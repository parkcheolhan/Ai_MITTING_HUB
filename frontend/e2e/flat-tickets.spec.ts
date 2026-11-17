import {test,expect} from '@playwright/test';

test('shows only independent tasks and deletes a task through its right-click menu',async({page})=>{
  const base={meetingId:'flat',projectKey:'TEST',issueType:'Task',description:'보존할 업무 내용',assignee:'',priority:'Medium',dueDate:'',labels:[],parent:'',parentDraftId:'',analysisItemIds:[],status:'DRAFT',issueKey:null as string|null,issueUrl:null,error:null,version:3,scheduleMode:'MANUAL'};
  const meeting={id:'flat',title:'업무 단일 구성',status:'REVIEW_PENDING',createdAt:new Date().toISOString(),jobs:[],epics:[],content:{occurredAt:'',projectKey:'TEST',participants:[],memo:'',source:'TEXT',language:'ko',template:'일반',segments:[],minutes:null,items:[],assets:[]},drafts:[{...base,id:'a',summary:'첫 업무'},{...base,id:'b',summary:'둘째 업무'},{...base,id:'published',summary:'등록된 업무',status:'PUBLISHED',issueKey:'TEST-42'}]};
  const deletes:string[]=[];let fail=true;
  await page.route('**/api/**',route=>{
    const request=route.request(),url=new URL(request.url()),path=url.pathname;
    if(request.method()==='DELETE'){
      expect(path).toBe('/api/ticket-drafts/b');expect(url.searchParams.get('version')).toBe('3');
      if(fail)return route.fulfill({status:409,json:{message:'업무가 변경되었습니다. 다시 삭제하세요.'}});
      deletes.push('b');meeting.drafts=meeting.drafts.filter(d=>d.id!=='b');return route.fulfill({status:200,body:''});
    }
    return route.fulfill({json:path==='/api/meetings/flat'?meeting:path==='/api/settings'?{mode:'mock'}:path==='/api/settings/ai'?{mode:'mock',configured:true}:[]});
  });
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('/meetings/flat/tickets');await expect(page.locator('.epic-task-group')).toHaveCount(3);
  await expect(page.getByText('드래그해 이동',{exact:true})).toHaveCount(0);await expect(page.getByLabel('이슈 유형',{exact:true})).toHaveCount(0);
  await expect(page.locator('.subtask-list,.subtask-candidate')).toHaveCount(0);await expect(page.getByRole('button',{name:/하위.*적용|하위.*지정|하위 후보/})).toHaveCount(0);
  await page.getByLabel('둘째 업무 선택',{exact:true}).check();
  await page.locator('[data-draft-id="b"]').click({button:'right'});const menu=page.getByRole('menu',{name:'둘째 업무 업무 메뉴',exact:true});await expect(menu).toBeVisible();
  await menu.getByRole('menuitem',{name:'업무 삭제',exact:true}).click();await expect(page.getByRole('alert')).toContainText('업무가 변경되었습니다');await expect(page.locator('[data-draft-id="b"]')).toHaveCount(1);
  fail=false;await page.locator('[data-draft-id="b"]').click({button:'right'});await menu.getByRole('menuitem',{name:'업무 삭제',exact:true}).click();
  await expect(page.locator('[data-draft-id="b"]')).toHaveCount(0);await expect(page.locator('.approval-bar')).toContainText('0개 선택');expect(deletes).toEqual(['b']);await expect(page.locator('[data-draft-id="a"]')).toHaveCount(1);
  await page.locator('[data-draft-id="published"]').click({button:'right'});await expect(page.getByRole('menuitem',{name:'업무 삭제',exact:true})).toBeDisabled();await page.keyboard.press('Escape');await expect(page.getByRole('menu')).toHaveCount(0);
  await page.locator('[data-draft-id="a"]').focus();await page.keyboard.press('Shift+F10');await expect(page.getByRole('menuitem',{name:'업무 삭제',exact:true})).toBeFocused();await page.keyboard.press('Escape');
  await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'첫 업무 업무 메뉴',exact:true}).click();await expect(page.getByRole('menu')).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({path:'test-results/flat-task-menu-mobile.png'});expect(errors).toEqual([]);
});
