import { test, expect } from "@playwright/test";

test("대시보드, 모바일 레이아웃, 설정이 동작한다", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.route("**/api/meetings", r => r.fulfill({json: []}));
  await page.goto("/dashboard");
  await expect(
    page.getByRole("heading", { name: "회의의 끝, 실행의 시작" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "첫 회의 등록하기" }),
  ).toBeVisible();
  await page.screenshot({ path: "test-results/dashboard.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({
    path: "test-results/dashboard-mobile.png",
    fullPage: true,
  });
  await page.goto("/settings");
  await expect(
    page.getByRole("heading", { name: "우리 팀에 맞는 연결" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Mock 어댑터 점검" }).click();
  await expect(page.getByRole("status")).toContainText(
    "실제 Jira 연결은 설정에서",
  );
  expect(errors).toEqual([]);
});

test("새 회의 분석부터 초안 편집·승인·등록·중복 방지까지 동작한다", async ({
  page,
  request,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/meetings/new");
  const title = "E2E 회의 " + Date.now();
  await page.locator('input[name="title"]').fill(title);
  await page.locator('input[name="participants"]').fill("김민수, 이지은");
  await expect(page.getByRole("button", {name:"샘플 텍스트 불러오기"})).toHaveCount(0);
  await page.locator("#transcript").fill("[00:01:10] 김민수: 이메일 로그인 방식을 유지하기로 결정했습니다.\n[00:03:20] 이지은: QA 전에 운영 환경과 API 접근 권한을 확인하겠습니다.\n[00:06:05] 김민수: 로그인 실패 메시지 개선 작업을 진행해야 합니다.\n[00:09:40] 이지은: 배포 전에 결제 API 연동 테스트를 진행합시다.");
  await page.getByRole("button", { name: "저장 후 분석 시작" }).click();
  await expect(page).toHaveURL(/\/meetings\/[a-f0-9-]+$/);
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
  await expect(
    page.getByText("Mock 회의록 · 버전 1", { exact: false }),
  ).toBeVisible();
  const meetingId = new URL(page.url()).pathname.split("/")[2];
  await page.getByRole("button", { name: "회의록 편집", exact: true }).click();
  await page
    .locator(".minutes-grid textarea")
    .nth(1)
    .fill("E2E로 수정한 회의 요약");
  await page.getByRole("button", { name: "회의록 저장" }).click();
  await expect(page.getByRole("status")).toContainText("회의록을 저장했습니다.");
  await page
    .locator(".stepper")
    .getByRole("button", { name: "업무 분석" })
    .click();
  await expect(page.locator(".analysis-card")).toHaveCount(3);
  await page.getByRole("button", { name: "JIRA 후보 생성" }).click();
  await expect(page.locator(".candidate")).toHaveCount(3);
  await page
    .locator(".ticket-editor .form-grid input")
    .nth(1)
    .fill("E2E에서 수정한 작업");
  await page.getByRole("button", { name: "초안 저장" }).click();
  await expect(
    page
      .locator(".candidate")
      .getByRole("heading", { name: "E2E에서 수정한 작업" }),
  ).toBeVisible();
  await page
    .locator(".candidate")
    .first()
    .locator('input[type="checkbox"]')
    .check();
  await expect(
    page.getByRole("button", { name: "승인 티켓 Mock 등록" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "선택 승인", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "승인 티켓 Mock 등록" }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "승인 티켓 Mock 등록" }).click();
  await expect(page).toHaveURL(/\/results$/);
  await expect(page.getByText(/^MOCK-/)).toBeVisible();
  const meeting = await (
    await request.get(`/api/meetings/${meetingId}`)
  ).json();
  const published = meeting.drafts.find(
    (d: { status: string }) => d.status === "PUBLISHED",
  );
  const repeat = await request.post("/api/ticket-drafts/publish", {
    data: { ids: [published.id] },
  });
  expect((await repeat.json())[0]).toMatchObject({
    status: "SKIPPED",
    issueKey: published.issueKey,
  });
  await page.screenshot({ path: "test-results/results.png", fullPage: true });
  expect(errors).toEqual([]);
});

test("실제 HTTP API도 승인 전 등록을 차단한다", async ({ request }) => {
  const response = await request.get("/api/ticket-drafts");
  expect(response.ok()).toBeTruthy();
  const drafts = await response.json();
  const draft = drafts.find((d: { status: string }) => d.status === "DRAFT");
  expect(draft).toBeDefined();
  const result = await request.post("/api/ticket-drafts/publish", {
    data: { ids: [draft.id] },
  });
  expect((await result.json())[0].status).toBe("BLOCKED");
});

test("클로바 형식 TXT를 가져오며 녹음 출처를 선택하지 않는다", async ({
  page,
}) => {
  await page.goto("/meetings/new");
  await expect(
    page.getByRole("button", { name: "Zoom 녹화·녹음" }),
  ).toHaveCount(0);
  await page
    .locator('input[name="title"]')
    .fill("클로바 음성 기록 E2E " + Date.now());
  await page
    .getByLabel("회의 자료 파일 선택")
    .setInputFiles({
      name: "clova.txt",
      mimeType: "text/plain",
      buffer: Buffer.from(
        "참석자 1 00:12\n환경과 권한을 확인하겠습니다.\n\n참석자 2 01:05\n로그인 테스트 진행합시다.",
        "utf8",
      ),
    });
  await expect(page.locator("#transcript")).toContainText("참석자 1 00:12");
  await page.getByRole("button", { name: "저장 후 분석 시작" }).click();
  await expect(page).toHaveURL(/\/meetings\/[a-f0-9-]+$/);
  await page
    .locator(".stepper")
    .getByRole("button", { name: "음성 기록" })
    .click();
  await expect(page.locator(".segment")).toHaveCount(2);
  await expect(page.locator(".segment").first()).toContainText("참석자 1");
  await expect(page.locator(".segment").first()).toContainText("00:00:12");
});

test("실제 Jira 모드 UI는 명시적 동의 후 테스트 이슈 생성 요청을 보낸다", async ({
  page,
}) => {
  let creates = 0;
  await page.route("**/api/settings", (r) =>
    r.fulfill({
      json: {
        mode: "cloud",
        siteUrl: "https://example.atlassian.net",
        defaultProject: "TEST",
        configured: true,
        tokenConfigured: true,
        missing: [],
      },
    }),
  );
  await page.route("**/api/integrations/jira/test", (r) =>
    r.fulfill({ json: { connected: true, message: "테스트 서버 연결 성공" } }),
  );
  await page.route("**/api/integrations/jira/projects", (r) =>
    r.fulfill({ json: [{ key: "TEST", name: "테스트 프로젝트" }] }),
  );
  await page.route("**/api/integrations/jira/projects/TEST/issue-types", (r) =>
    r.fulfill({ json: [{ id: "10001", name: "작업", subtask: "false" }] }),
  );
  await page.route(
    "**/api/integrations/jira/projects/TEST/issue-types/10001/fields",
    (r) =>
      r.fulfill({ json: [{ id: "summary", name: "요약", required: true }] }),
  );
  await page.route("**/api/integrations/jira/test-issue", async (r) => {
    creates++;
    expect(r.request().postDataJSON()).toEqual({
      projectKey: "TEST",
      issueType: "10001",
      approved: true,
    });
    await r.fulfill({
      json: {
        status: "PUBLISHED",
        draftId: "test",
        issueKey: "TEST-1",
        issueUrl: "https://example.atlassian.net/browse/TEST-1",
        error: null,
      },
    });
  });
  await page.goto("/settings");
  await expect(
    page.getByRole("button", { name: "테스트 이슈 1개 생성" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "실제 Jira 연결 테스트" }).click();
  await expect(page.getByLabel("테스트 이슈 유형")).toHaveValue("10001");
  await expect(
    page.getByRole("button", { name: "테스트 이슈 1개 생성" }),
  ).toBeDisabled();
  await page.getByRole("checkbox", { name: /테스트 이슈 1개를 생성/ }).check();
  await page.getByRole("button", { name: "테스트 이슈 1개 생성" }).click();
  await expect(
    page.getByRole("link", { name: "Jira에서 열기" }),
  ).toHaveAttribute("href", "https://example.atlassian.net/browse/TEST-1");
  expect(creates).toBe(1);
});
