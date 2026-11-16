import {test, expect} from '@playwright/test';

test('uploads a readable snapshot while preserving the filename and bytes', async ({page}) => {
  let body = '';
  await page.route('**/api/meetings/upload-probe/assets', async route => {
    body = route.request().postDataBuffer()!.toString('utf8');
    await route.fulfill({status:200, contentType:'application/json', body:'{}'});
  });
  await page.goto('/meetings/new');
  await page.evaluate(async () => {
    // @ts-expect-error Vite resolves application source in the browser.
    const {upload} = await import('/src/api.ts');
    await upload('upload-probe', new File(['recording-bytes'], 'voice.m4a', {type:'audio/mp4'}), () => {});
  });
  expect(body).toContain('filename="voice.m4a"');
  expect(body).toContain('recording-bytes');
});

test('reports unreadable local files before starting a network upload', async ({page}) => {
  let requests = 0;
  await page.route('**/api/meetings/upload-probe/assets', route => {requests++; return route.abort();});
  await page.goto('/meetings/new');
  const message = await page.evaluate(async () => {
    // @ts-expect-error Vite resolves application source in the browser.
    const {upload} = await import('/src/api.ts');
    const file = new File(['recording'], 'voice.m4a');
    file.arrayBuffer = async () => {throw new DOMException('File changed', 'NotReadableError');};
    try {await upload('upload-probe', file, () => {});} catch (e) {return (e as Error).message;}
  });
  expect(message).toContain('선택한 파일을 읽을 수 없습니다');
  expect(requests).toBe(0);
});

test('distinguishes interrupted connections from HTTP upload errors', async ({page}) => {
  await page.goto('/meetings/new');
  const attempt = () => page.evaluate(async () => {
    // @ts-expect-error Vite resolves application source in the browser.
    const {upload} = await import('/src/api.ts');
    try {await upload('upload-probe', new File(['recording'], 'voice.m4a'), () => {});}
    catch (e) {return (e as Error).message;}
  });
  await page.route('**/api/meetings/upload-probe/assets', route => route.abort('connectionreset'));
  expect(await attempt()).toContain('연결이 끊겼습니다');
  await page.unroute('**/api/meetings/upload-probe/assets');
  await page.route('**/api/meetings/upload-probe/assets', route => route.fulfill({status:413, body:'Too large'}));
  expect(await attempt()).toContain('100MB');
});
