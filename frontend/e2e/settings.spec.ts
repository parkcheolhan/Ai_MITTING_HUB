import { test, expect } from "@playwright/test";

test("화면에서 토큰과 기본 프로젝트를 저장하고 토큰을 다시 노출하지 않는다", async ({
  page,
}) => {
  const secret = "fake-browser-test-token";
  let token = "";
  let settings = {
    mode: "mock",
    siteUrl: "",
    email: "",
    cloudId: "",
    defaultProject: "",
    configured: false,
    tokenConfigured: false,
    missing: [],
    configurationSource: "database",
  };
  await page.route("**/api/settings", (r) => r.fulfill({ json: settings }));
  await page.route("**/api/settings/jira", async (r) => {
    const body = r.request().postDataJSON();
    if (body.apiToken) token = body.apiToken;
    settings = {
      ...settings,
      mode: body.mode,
      siteUrl: body.siteUrl,
      email: body.email,
      cloudId: body.cloudId,
      tokenConfigured: !!token,
      configured: body.mode === "cloud" && !!token,
    };
    await r.fulfill({ json: settings });
  });
  await page.route("**/api/settings/jira/project", async (r) => {
    settings = {
      ...settings,
      defaultProject: r.request().postDataJSON().projectKey,
    };
    await r.fulfill({ json: settings });
  });
  await page.route("**/api/settings/jira/token", async (r) => {
    expect(r.request().method()).toBe("DELETE");
    token = "";
    settings = { ...settings, tokenConfigured: false, configured: false };
    await r.fulfill({ json: settings });
  });
  await page.route("**/api/integrations/jira/test", (r) =>
    r.fulfill({ json: { connected: true, message: "Jira 테스트 연결 성공" } }),
  );
  await page.route("**/api/integrations/jira/projects", (r) =>
    r.fulfill({
      json: [
        { key: "TEST", name: "테스트" },
        { key: "OTHER", name: "다른 프로젝트" },
      ],
    }),
  );
  await page.route("**/api/integrations/jira/projects/*/issue-types", (r) =>
    r.fulfill({ json: [{ id: "10001", name: "작업", subtask: "false" }] }),
  );
  await page.route(
    "**/api/integrations/jira/projects/*/issue-types/10001/fields",
    (r) => r.fulfill({ json: [] }),
  );
  await page.goto("/settings");
  await page.getByLabel("연동 모드").selectOption("cloud");
  await page
    .getByLabel("Jira 사이트 URL")
    .fill("https://example.atlassian.net");
  await page.getByLabel("Atlassian 계정 이메일").fill("user@example.com");
  await page.getByLabel("Jira API 토큰").fill(secret);
  await expect(
    page.getByRole("button", { name: "Mock 어댑터 점검" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "연결 설정 저장" }).click();
  await expect(page.getByLabel("Jira API 토큰")).toHaveValue("");
  await expect(page.getByLabel("Jira API 토큰")).toHaveAttribute(
    "type",
    "password",
  );
  await expect(
    page.getByText("토큰이 저장되어 있습니다.", { exact: false }),
  ).toBeVisible();
  expect(token).toBe(secret);
  expect(
    await page.evaluate(() => JSON.stringify([localStorage, sessionStorage])),
  ).not.toContain(secret);
  await page.getByRole("button", { name: "실제 Jira 연결 테스트" }).click();
  await expect(page.getByLabel("기본 프로젝트", { exact: true })).toBeEnabled();
  await page.getByLabel("기본 프로젝트", { exact: true }).selectOption("OTHER");
  await page.getByRole("button", { name: "기본 프로젝트 저장" }).click();
  await expect(page.getByText("현재 기본값:", { exact: false })).toContainText(
    "OTHER",
  );
  await page.getByLabel("Cloud ID (스코프 토큰 사용 시)").fill("cloud-test-id");
  await page.getByRole("button", { name: "연결 설정 저장" }).click();
  expect(token).toBe(secret);
  await page.reload();
  await expect(page.getByLabel("Jira API 토큰")).toHaveValue("");
  await expect(page.getByLabel("Atlassian 계정 이메일")).toHaveValue(
    "user@example.com",
  );
  await page.goto("/meetings/new");
  await expect(page.locator('input[name="projectKey"]')).toHaveValue("OTHER");
  await page.goto("/settings");
  await page.getByRole("button", { name: "토큰 삭제" }).click();
  await expect(
    page.getByText("아직 저장된 토큰이 없습니다.", { exact: false }),
  ).toBeVisible();
  expect(token).toBe("");
});
