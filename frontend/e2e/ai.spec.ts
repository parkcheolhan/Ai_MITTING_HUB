import { test, expect } from "@playwright/test";

test("화면에서 실제 AI 모드를 설정하고 키를 숨긴 채 연결을 테스트한다", async ({
  page,
}) => {
  const secret = "fake-ai-browser-key";
  let key = "";
  let calls = 0;
  let settings = {
    mode: "openai",
    model: "gpt-4.1-mini",
    configured: false,
    keyConfigured: false,
  };
  await page.route("**/api/settings/ai", async (r) => {
    if (r.request().method() === "PUT") {
      const body = r.request().postDataJSON();
      if (body.apiKey) key = body.apiKey;
      settings = {
        mode: body.mode,
        model: body.model,
        configured: body.mode === "mock" || !!key,
        keyConfigured: !!key,
      };
    }
    await r.fulfill({ json: settings });
  });
  await page.route("**/api/settings/ai/key", async (r) => {
    key = "";
    settings = {
      ...settings,
      keyConfigured: false,
      configured: settings.mode === "mock",
    };
    await r.fulfill({ json: settings });
  });
  await page.route("**/api/integrations/ai/test", async (r) => {
    calls++;
    expect(r.request().postData()).toBeNull();
    await r.fulfill({
      json: { connected: true, message: "AI 모델 연결 성공: gpt-4.1-mini" },
    });
  });
  await page.goto("/settings");
  await expect(page.getByLabel("AI 처리 모드")).toHaveValue("openai");
  await expect(
    page.getByRole("button", { name: "AI 모델 연결 테스트" }),
  ).toBeDisabled();
  await page.getByLabel("OpenAI API 키", { exact: true }).fill(secret);
  await page.getByRole("button", { name: "AI 설정 저장" }).click();
  await expect(page.getByLabel("OpenAI API 키", { exact: true })).toHaveValue(
    "",
  );
  await expect(
    page.getByLabel("OpenAI API 키", { exact: true }),
  ).toHaveAttribute("type", "password");
  await page.getByRole("button", { name: "AI 모델 연결 테스트" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "AI 모델 연결 성공" }),
  ).toBeVisible();
  expect(calls).toBe(1);
  expect(
    await page.evaluate(() => JSON.stringify([localStorage, sessionStorage])),
  ).not.toContain(secret);
  await page.getByRole("button", { name: "AI API 키 삭제" }).click();
  await expect(
    page.getByRole("button", { name: "AI 모델 연결 테스트" }),
  ).toBeDisabled();
  expect(key).toBe("");
});

test("AI 분석 화면과 초안 재검토는 원문 근거를 보여주고 승인 없이 등록하지 않는다", async ({
  page,
  request,
}) => {
  const created = await request.post("/api/meetings", {
    data: {
      title: "AI UI 원문 검증",
      projectKey: "DEMO",
      participants: ["이지은"],
      transcript: "[00:12:34] 이지은: 운영 환경 권한을 확인하겠습니다.",
      source: "TEXT",
    },
  });
  expect(created.ok()).toBeTruthy();
  const m = await created.json();
  async function waitJob(path: string) {
    const j = await (await request.post(path)).json();
    await expect
      .poll(async () => {
        const value = await (await request.get("/api/jobs/" + j.id)).json();
        return value.status;
      })
      .toBe("COMPLETED");
  }
  await waitJob(`/api/meetings/${m.id}/process`);
  await waitJob(`/api/meetings/${m.id}/ticket-drafts`);
  const meeting = await (await request.get(`/api/meetings/${m.id}`)).json();
  meeting.content.analysisMode = "openai";
  meeting.content.analysisModel = "test-ai-model";
  meeting.drafts[0].generationMode = "openai";
  meeting.drafts[0].generationModel = "test-ai-model";
  let reviews = 0,
    publishes = 0;
  await page.route("**/api/settings/ai", (r) =>
    r.fulfill({
      json: {
        mode: "openai",
        model: "test-ai-model",
        configured: true,
        keyConfigured: true,
      },
    }),
  );
  await page.route(`**/api/meetings/${m.id}`, (r) =>
    r.fulfill({ json: meeting }),
  );
  await page.route(
    `**/api/ticket-drafts/${meeting.drafts[0].id}/ai-review`,
    async (r) => {
      reviews++;
      meeting.drafts[0].summary = "AI 재검토한 환경 권한 검증";
      meeting.drafts[0].description =
        "1. 배경 및 목적\n원문을 기반으로 검토했습니다.\n2. 해야 하는 작업 목록\n☐ 접근 권한 확인\n3. 확인해야 하는 항목\n☐ 권한 범위 검증\n4. 완료 조건\n검증 결과 기록\n6. 회의 근거\n00:12:34 이지은: 운영 환경 권한을 확인하겠습니다.";
      meeting.drafts[0].version++;
      meeting.drafts[0].status = "DRAFT";
      await r.fulfill({ json: { id: "ui-review-job", status: "COMPLETED" } });
    },
  );
  await page.route("**/api/ticket-drafts/publish", async (r) => {
    publishes++;
    await r.fulfill({ json: [] });
  });
  await page.goto(`/meetings/${m.id}?tab=analysis`);
  await expect(
    page.getByText("AI 분석 · test-ai-model", { exact: false }),
  ).toBeVisible();
  await expect(page.locator(".evidence-panel")).toContainText("00:12:34");
  await page
    .locator(".stepper")
    .getByRole("button", { name: /JIRA 검토/ })
    .click();
  await expect(
    page.getByText("AI가 정리한 초안 · test-ai-model"),
  ).toBeVisible();
  await page.getByRole("button", { name: "AI로 다시 정리" }).click();
  await expect(page.locator(".candidate")).toContainText(
    "AI 재검토한 환경 권한 검증",
  );
  await expect(page.locator(".ticket-editor textarea")).toContainText(
    "접근 권한 확인",
  );
  expect(reviews).toBe(1);
  expect(publishes).toBe(0);
});


test("Codex settings work without an API key", async ({page}) => {
  await page.route("**/api/settings/ai/codex/login", r => r.fulfill({json:{state:"IDLE",loggedIn:false,userCode:"",verificationUrl:"",message:""}}));
  let settings = {mode: "openai", model: "gpt-4.1-mini", configured: false, keyConfigured: false};
  await page.route("**/api/settings/ai", async r => {
    if(r.request().method() === "PUT") {
      const body = r.request().postDataJSON();
      expect(body.apiKey).toBe("");
      settings = {...settings, mode: body.mode, model: body.model, configured: true};
    }
    await r.fulfill({json: settings});
  });
  await page.route("**/api/integrations/ai/test", async r => r.fulfill({json: {message: "Codex connected"}}));
  await page.goto("/settings");
  await page.locator('.ai-settings-card select').selectOption("codex");
  await expect(page.locator('.ai-settings-card input[list="ai-models"]')).toHaveValue("codex-default");
  await expect(page.locator('.ai-settings-card input[type="password"]')).toBeHidden();
  await page.locator('.ai-settings-card button[type="submit"]').click();
  await expect(page.getByText("CODEX", {exact:true})).toBeVisible();
  await page.locator('.ai-settings-card .settings-actions button').nth(1).click();
  await expect(page.getByRole("status").filter({hasText:"Codex connected"})).toBeVisible();
});
