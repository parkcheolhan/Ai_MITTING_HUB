import {test,expect} from "@playwright/test";

test("Codex login displays a device code and reflects browser authentication",async({page})=>{
  let state="IDLE",completed=false,starts=0;
  await page.route("**/api/settings/ai",r=>r.fulfill({json:{mode:"codex",model:"codex-default",configured:true,keyConfigured:false}}));
  await page.route("**/api/settings/ai/codex/login",async r=>{
    if(r.request().method()==="POST"){state="WAITING";starts++;}
    if(r.request().method()==="DELETE")state="CANCELLED";
    if(completed)state="SUCCEEDED";
    await r.fulfill({json:{state,loggedIn:completed,userCode:state==="WAITING"?"ABCD-1234":"",verificationUrl:"https://auth.openai.com/codex/device",message:completed?"Codex 로그인이 완료되었습니다.":"로그인을 진행하세요."}});
  });
  await page.goto("/settings");
  await page.getByRole("button",{name:"Codex 로그인",exact:true}).click();
  await expect(page.getByLabel("Codex 인증 코드")).toHaveText("ABCD-1234");
  const link=page.getByRole("link",{name:/Codex 인증 페이지 열기/});
  await expect(link).toHaveAttribute("href","https://auth.openai.com/codex/device");
  await expect(link).toHaveAttribute("target","_blank");
  await expect(page.getByRole("button",{name:"Codex 로그인",exact:true})).toBeDisabled();
  completed=true;
  await expect(page.getByRole("button",{name:"Codex 다시 로그인"})).toBeVisible();
  await expect(page.getByLabel("Codex 인증 코드")).toHaveCount(0);
  expect(starts).toBe(1);
  expect(await page.evaluate(()=>JSON.stringify([localStorage,sessionStorage]))).not.toContain("ABCD-1234");
});

test("Codex pending login can be cancelled",async({page})=>{
  let state="IDLE";
  await page.route("**/api/settings/ai",r=>r.fulfill({json:{mode:"codex",model:"codex-default",configured:true,keyConfigured:false}}));
  await page.route("**/api/settings/ai/codex/login",async r=>{
    if(r.request().method()==="POST")state="WAITING";
    if(r.request().method()==="DELETE")state="CANCELLED";
    await r.fulfill({json:{state,loggedIn:false,userCode:state==="WAITING"?"ABCD-1234":"",verificationUrl:"",message:state}});
  });
  await page.goto("/settings");
  await page.getByRole("button",{name:"Codex 로그인",exact:true}).click();
  await expect(page.getByLabel("Codex 인증 코드")).toBeVisible();
  await page.getByRole("button",{name:"로그인 취소",exact:true}).click();
  await expect(page.getByLabel("Codex 인증 코드")).toHaveCount(0);
  await expect(page.getByRole("button",{name:"Codex 로그인",exact:true})).toBeEnabled();
  expect(state).toBe("CANCELLED");
});

test("header links to the configured Jira homepage and omits it when unconfigured",async({page})=>{
  let configured=true;
  await page.route("**/api/settings",r=>r.fulfill({json:{mode:"cloud",siteUrl:"https://linked-team.atlassian.net",configured,defaultProject:"TEAM",tokenConfigured:true,missing:[]}}));
  await page.route("**/api/meetings",r=>r.fulfill({json:[]}));
  await page.goto("/dashboard");
  const link=page.getByRole("link",{name:"Jira 홈페이지"});
  await expect(link).toHaveAttribute("href","https://linked-team.atlassian.net");
  await expect(link).toHaveAttribute("rel","noopener noreferrer");
  await page.setViewportSize({width:390,height:844});
  await expect(link).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  configured=false;await page.reload();await expect(link).toHaveCount(0);
});
