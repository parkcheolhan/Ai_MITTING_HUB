import {test,expect,type Page} from '@playwright/test';

async function fixture(page:Page,custom=false){
  const minutes={version:1,overview:'서비스 개선 회의',summary:'보존할 안건',discussion:'첫 번째 문구\n두 번째 문구\n마지막 문구',decisions:'수정 진행',openQuestions:'',followUp:'검증 결과 공유',formatted:custom?'# 회사 회의록\n## 결정 사항\n- 로그인 수정\n<script>원문</script>':'',generationMode:'codex',generationModel:'test'};
  const meeting={id:'inline',title:'줄별 자동 저장 회의',status:'REVIEW_PENDING',createdAt:'2026-11-17T00:00:00Z',drafts:[],jobs:[],epics:[],content:{occurredAt:'2026-11-17T10:00',projectKey:'TEST',participants:['김민수'],memo:'',source:'TEXT',language:'ko',template:custom?'# 회사 양식\n{{결정사항}}':'일반',segments:[],assets:[],items:[],minutes}};
  const state={requests:[] as any[],fail:false,applyThenFail:false,hold:false,release:()=>{}};
  await page.route('**/api/**',async route=>{
    const r=route.request(),path=new URL(r.url()).pathname;
    if(path==='/api/meetings/inline/minutes/line'&&r.method()==='PATCH'){
      const input=r.postDataJSON();state.requests.push(input);
      if(state.fail&&!state.applyThenFail)return route.fulfill({status:503,json:{message:'저장에 실패했습니다. 다시 시도하세요.'}});
      const field=input.field as keyof typeof minutes,lines=String(minutes[field]||'').split(/\r?\n/);
      if(input.expectedVersion!==minutes.version||lines[input.lineIndex]!==input.originalText)return route.fulfill({status:409,json:{message:'회의록이 다른 곳에서 변경되었습니다. 입력한 문구는 유지했습니다.'}});
      lines[input.lineIndex]=input.text;(minutes as any)[field]=lines.join('\n');minutes.version++;const response=structuredClone(minutes);
      if(state.hold){state.hold=false;await new Promise<void>(resolve=>{state.release=resolve;});}
      if(state.applyThenFail){state.applyThenFail=false;return route.fulfill({status:503,json:{message:'저장 결과 응답이 끊겼습니다.'}});}
      return route.fulfill({json:response});
    }
    if(path==='/api/meetings/inline')return route.fulfill({json:meeting});
    if(path==='/api/settings')return route.fulfill({json:{mode:'mock'}});
    if(path==='/api/meetings/inline/confluence')return route.fulfill({json:{configured:false,status:'NOT_PUBLISHED',pageUrl:'',error:''}});
    return route.fulfill({json:[]});
  });
  await page.goto('/meetings/inline?tab=minutes');return {minutes,meeting,state};
}
const line=(page:Page,key:string)=>page.locator(`[data-minutes-line="${key}"]`);

test('double-click edits one line, autosaves while typing pauses, and blur persists without a save button',async({page})=>{
  const {minutes,state}=await fixture(page);await line(page,'discussion:1').dblclick();let editor=page.getByRole('textbox',{name:'논의 내용 2행 수정',exact:true});await expect(editor).toHaveValue('두 번째 문구');
  await editor.fill('두 번째 문구를 자연스럽게 수정했습니다.');await expect(page.locator('.minutes-line-editor [role="status"]')).toHaveText('자동 저장됨');expect(state.requests).toHaveLength(1);expect(state.requests[0]).toMatchObject({field:'discussion',lineIndex:1,originalText:'두 번째 문구',expectedVersion:1});expect(minutes.summary).toBe('보존할 안건');
  await editor.press('Enter');await expect(editor).toHaveCount(0);await line(page,'discussion:0').dblclick();editor=page.getByRole('textbox',{name:'논의 내용 1행 수정',exact:true});await editor.fill('첫 번째 문구 수정');await page.locator('.minutes-view > .section-head h2').click();await expect(editor).toHaveCount(0);expect(state.requests).toHaveLength(2);
  await page.reload();await expect(line(page,'discussion:0')).toHaveText('첫 번째 문구 수정');await expect(line(page,'discussion:1')).toContainText('자연스럽게 수정');await expect(line(page,'discussion:2')).toHaveText('마지막 문구');
  await line(page,'openQuestions:0').dblclick();await page.getByRole('textbox',{name:'검토 사항 1행 수정',exact:true}).fill('확인이 필요한 내용');await page.getByRole('button',{name:'완료',exact:true}).click();await expect(line(page,'openQuestions:0')).toHaveText('확인이 필요한 내용');
});

test('serializes edits typed during an in-flight save and switches straight to another line',async({page})=>{
  const {state,minutes}=await fixture(page);state.hold=true;await line(page,'discussion:1').dblclick();const editor=page.getByRole('textbox',{name:'논의 내용 2행 수정',exact:true});await editor.fill('첫 저장 문구');await expect.poll(()=>state.requests.length).toBe(1);
  await editor.fill('저장 중에도 계속 작성한 최종 문구');state.release();await expect.poll(()=>state.requests.length).toBe(2);await expect(page.locator('.minutes-line-editor [role="status"]')).toHaveText('자동 저장됨');
  expect(state.requests[1]).toMatchObject({expectedVersion:2,originalText:'첫 저장 문구',text:'저장 중에도 계속 작성한 최종 문구'});expect(minutes.discussion).toBe('첫 번째 문구\n저장 중에도 계속 작성한 최종 문구\n마지막 문구');
  await editor.fill('다른 줄로 넘어가면서 저장');await line(page,'summary:0').dblclick();await expect(page.getByRole('textbox',{name:'주요 안건 1행 수정',exact:true})).toHaveValue('보존할 안건');expect(minutes.discussion).toContain('다른 줄로 넘어가면서 저장');
});

test('preserves uploaded headings and bullets, escapes markup, and fits mobile',async({page})=>{
  const {minutes,state}=await fixture(page,true);await line(page,'formatted:2').dblclick();const editor=page.getByRole('textbox',{name:'회의록 본문 3행 수정',exact:true});await expect(editor).toHaveValue('로그인 수정');await editor.fill('로그인과 권한 수정');await editor.press('Enter');await expect(editor).toHaveCount(0);expect(minutes.formatted).toContain('- 로그인과 권한 수정');
  await line(page,'formatted:0').dblclick();await page.getByRole('textbox',{name:'회의록 본문 1행 수정',exact:true}).fill('변경한 회사 회의록');await page.getByRole('button',{name:'완료',exact:true}).click();await expect(line(page,'formatted:0').getByRole('heading')).toHaveText('변경한 회사 회의록');expect(minutes.formatted).toMatch(/^# 변경한 회사 회의록/);
  await expect(page.locator('.minutes-document script')).toHaveCount(0);await expect(line(page,'formatted:3')).toHaveText('<script>원문</script>');await page.setViewportSize({width:390,height:844});await line(page,'formatted:2').dblclick();await editor.fill('저장하지 않을 문구');await editor.press('Escape');expect(state.requests).toHaveLength(2);
  await line(page,'formatted:2').dblclick();await expect(editor).toHaveValue('로그인과 권한 수정');expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);await page.locator('.minutes-document').screenshot({path:'test-results/minutes-inline-mobile.png',animations:'disabled'});
});

test('retains failed input and reconciles a lost save response without submitting a duplicate',async({page})=>{
  const {state,minutes}=await fixture(page);state.fail=true;await line(page,'discussion:1').dblclick();const editor=page.getByRole('textbox',{name:'논의 내용 2행 수정',exact:true});await editor.fill('잃어버리면 안 되는 수정');await editor.press('Enter');await expect(page.getByRole('alert')).toContainText('저장에 실패');await expect(editor).toHaveValue('잃어버리면 안 되는 수정');expect(minutes.discussion).toContain('두 번째 문구');
  state.fail=false;state.applyThenFail=true;await page.getByRole('button',{name:'다시 저장',exact:true}).click();await expect(page.getByRole('alert')).toContainText('응답이 끊겼습니다');expect(minutes.discussion).toContain('잃어버리면 안 되는 수정');const count=state.requests.length;
  await page.getByRole('button',{name:'다시 저장',exact:true}).click();await expect(editor).toHaveCount(0);expect(state.requests).toHaveLength(count);await expect(line(page,'discussion:1')).toHaveText('잃어버리면 안 되는 수정');
});

test('does not overwrite a concurrent change and keeps the typed line available',async({page})=>{
  const {minutes,state}=await fixture(page);await line(page,'discussion:1').dblclick();const editor=page.getByRole('textbox',{name:'논의 내용 2행 수정',exact:true});await editor.fill('현재 입력 중인 문구');minutes.discussion='다른 곳에서 변경한 최신 내용';minutes.version=2;await editor.press('Enter');await expect(page.getByRole('alert')).toContainText('다른 곳에서 변경');await expect(editor).toHaveValue('현재 입력 중인 문구');
  await page.getByRole('button',{name:'다시 저장',exact:true}).click();await expect(page.getByRole('alert')).toContainText('필요한 문구를 복사');expect(state.requests).toHaveLength(1);expect(minutes.discussion).toBe('다른 곳에서 변경한 최신 내용');await page.getByRole('button',{name:'편집 취소',exact:true}).click();await expect(line(page,'discussion:0')).toHaveText('다른 곳에서 변경한 최신 내용');
});

test('waits for Korean composition to finish before autosaving',async({page})=>{
  const {state}=await fixture(page);await line(page,'discussion:1').dblclick();const editor=page.getByRole('textbox',{name:'논의 내용 2행 수정',exact:true});await editor.dispatchEvent('compositionstart');await editor.fill('자연스러운 대화 문구');await page.waitForTimeout(1150);expect(state.requests).toHaveLength(0);
  await editor.dispatchEvent('keydown',{key:'Enter',code:'Enter',isComposing:true});await expect(editor).toBeVisible();await editor.dispatchEvent('compositionend');await expect.poll(()=>state.requests.length).toBe(1);await expect(page.locator('.minutes-line-editor [role="status"]')).toHaveText('자동 저장됨');
});

test('finishes queued autosaves after navigating away during a pending request',async({page})=>{
  const {state,minutes}=await fixture(page);state.hold=true;await line(page,'discussion:1').dblclick();const editor=page.getByRole('textbox',{name:'논의 내용 2행 수정',exact:true});await editor.fill('먼저 저장한 문구');await expect.poll(()=>state.requests.length).toBe(1);
  await editor.fill('화면을 이동해도 보존할 최종 문구');await page.getByRole('link',{name:'회의 관리',exact:true}).click();state.release();await expect.poll(()=>state.requests.length).toBe(2);await expect.poll(()=>minutes.discussion).toContain('화면을 이동해도 보존할 최종 문구');
});
