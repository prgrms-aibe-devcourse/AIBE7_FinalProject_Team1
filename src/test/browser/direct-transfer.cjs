const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright-core');
const jsQR = require('jsqr');

const base = new URL(process.env.P5_UI_BASE_URL || '');
if (!['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname) || base.protocol !== 'http:') throw new Error('Loopback HTTP target required');
const evidence = process.env.P5_UI_EVIDENCE;
if (!evidence || fs.existsSync(path.join(evidence, 'browser-result.json'))) throw new Error('Unique UI evidence directory required');
fs.mkdirSync(evidence, { recursive: true });
let step = 'launch';
const started = new Date().toISOString();
const passed = [];
const counts = { create: 0, accept: 0, blockedExternal: 0, uncaughtPageErrors: 0 };
let browser;
let activePage;
const httpStatuses = [];
const createSecurity = [];
const safeMessage = value => String(value || '').split('\n')[0].replace(/https?:\/\/[^\s"']+/g, '[URL REDACTED]').replace(/[a-f0-9]{32,}/gi, '[SECRET REDACTED]').replace(/token=[^\s&"']+/gi, 'token=[REDACTED]').replaceAll(process.env.P5_UI_PASSWORD || '\u0000', '[PASSWORD REDACTED]');
const ensure = (condition, label) => { if (!condition) throw new Error(label); };
const record = label => { passed.push(label); };

async function context() {
  const value = await browser.newContext({ viewport: { width: 1440, height: 1080 }, reducedMotion: 'reduce' });
  await value.route('**/*', route => {
    const target = new URL(route.request().url());
    if (target.origin !== base.origin) { counts.blockedExternal += 1; return route.abort(); }
    return route.continue();
  });
  const page = await value.newPage();
  activePage = page;
  page.on('response', response => {
    const target = new URL(response.url());
    if (target.origin === base.origin && (['/api/auth/login', '/api/auth/refresh', '/api/v1/products', '/api/v1/products/101'].includes(target.pathname) || target.pathname.startsWith('/api/v1/ownership/transfers'))) httpStatuses.push({ method: response.request().method(), path: target.pathname, status: response.status(), contentType: response.headers()['content-type'] || null });
  });
  page.on('pageerror', () => { counts.uncaughtPageErrors += 1; });
  page.on('dialog', dialog => dialog.accept());
  page.on('request', request => {
    const target = new URL(request.url());
    if (request.method() === 'POST' && target.pathname === '/api/v1/ownership/transfers') {
      counts.create += 1;
      createSecurity.push({ csrfHeaderPresent: Boolean(request.headers()['x-xsrf-token']), cookieNames: (request.headers()['cookie'] || '').split(';').map(cookie => cookie.split('=')[0].trim()).filter(Boolean) });
    }
    if (request.method() === 'POST' && target.pathname === '/api/v1/ownership/transfers/accept') counts.accept += 1;
  });
  return { value, page };
}
async function ready(page) {
  step += ':wait-auth-form-loaded';
  await page.locator('#auth [data-auth-form="login"]').waitFor({ state: 'attached' });
  step += ':wait-api-ready';
  await page.waitForFunction(() => Boolean(window.PastportReceive && window.PastportApi));
}
async function login(page, email, receive = false) {
  step = receive ? 'receive-login-navigation' : 'seller-login-navigation';
  if (!receive) await page.locator('.landing-account [data-page="auth"]').click();
  else await page.locator('[data-receive-login]').click();
  const form = page.locator('#auth [data-auth-form="login"]');
  step = receive ? 'receive-login-fill' : 'seller-login-fill';
  await form.locator('[name="email"]').fill(email);
  await form.locator('[name="password"]').fill(process.env.P5_UI_PASSWORD);
  await form.locator('button[type="submit"]').click();
  step = receive ? 'receive-login-session' : 'seller-login-session';
  await page.waitForFunction(() => Boolean(auth.user));
  if (receive) await page.locator('#receive.active').waitFor();
}
async function ownerProduct(page) {
  step = 'owner-home-navigation';
  await page.locator('.landing-nav .nav-item > [data-page="home"]').click();
  await page.locator('.landing-nav .nav-drop [data-page="home"]').click();
  step = 'owner-product-101-list-card';
  await page.locator('#home [data-product-id="101"]').click();
  step = 'owner-product-101-detail-panel';
  await page.locator('[data-ownership-root]').waitFor({ state: 'visible' });
  await page.waitForFunction(() => !document.querySelector('[data-ownership-message]')?.textContent.includes('불러오는'));
}
async function noStoredSecret(page, token) {
  const clean = await page.evaluate(secret => [localStorage, sessionStorage].every(storage =>
    Object.keys(storage).every(key => !String(storage.getItem(key)).includes(secret))), token);
  ensure(clean, 'No browser storage token');
}
async function receiveFixture(page, token, expected) {
  await page.goto(new URL('/receive?token=' + encodeURIComponent(token), base).href);
  await ready(page);
  await page.locator('[data-receive-message]').filter({ hasText: expected }).waitFor();
  ensure(new URL(page.url()).search === '', 'Receiver address cleared');
  ensure(await page.locator('[data-receive-accept]').isDisabled(), 'Terminal acceptance disabled');
  ensure(!await page.locator('[data-receive-home]').isVisible(), 'Completion action hidden for terminal unaccepted link');
  await noStoredSecret(page, token);
}
async function decodeSharedQr(page, sharedLink) {
  const pixels = await page.locator('.ownership-qr').evaluate(async image => {
    if (!image.complete) await new Promise(resolve => { image.onload = resolve; });
    const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
    const drawing = canvas.getContext('2d'); drawing.drawImage(image, 0, 0);
    return { width: canvas.width, height: canvas.height, pixels: Array.from(drawing.getImageData(0, 0, canvas.width, canvas.height).data) };
  });
  const decoded = jsQR(new Uint8ClampedArray(pixels.pixels), pixels.width, pixels.height);
  ensure(decoded && decoded.data === sharedLink, 'Actual QR decoded to exact shared link');
}

(async () => {
  try {
    browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
    const seller = await context();
    step = 'seller-login-and-product';
    await seller.page.goto(base.href); await ready(seller.page);
    await login(seller.page, process.env.P5_UI_SELLER_EMAIL);
    await ownerProduct(seller.page);
    step = 'issue-and-duplicate-guard';
    const issue = seller.page.getByRole('button', { name: '새 링크 · QR 발급', exact: true });
    await issue.evaluate(element => { element.click(); element.click(); });
    await seller.page.locator('.ownership-link').waitFor();
    ensure(counts.create === 1, 'Issue submitted once');
    let sharedLink = await seller.page.locator('.ownership-link').inputValue();
    await noStoredSecret(seller.page, new URL(sharedLink).searchParams.get('token'));
    await decodeSharedQr(seller.page, sharedLink);
    await seller.page.screenshot({ path: path.join(evidence, 'owner-issued-redacted.png'), fullPage: true, mask: [seller.page.locator('[data-ownership-share]')] });
    record('Owner actual product 101 issue; duplicate guard; local real QR decoded and matches same-origin link; no stored token');
    step = 'pending-refresh-cancel-reissue';
    await seller.page.reload(); await ready(seller.page); await seller.page.waitForFunction(() => Boolean(auth.user)); await ownerProduct(seller.page);
    await seller.page.getByText('원본 링크는 다시 조회할 수 없습니다.', { exact: false }).waitFor();
    ensure(await seller.page.locator('.ownership-link').count() === 0, 'Raw token not returned by state query');
    await seller.page.getByRole('button', { name: '이전 요청 취소', exact: true }).click();
    await seller.page.locator('[data-ownership-message]').filter({ hasText: '취소됨' }).waitFor();
    await seller.page.getByRole('button', { name: '새 링크 · QR 발급', exact: true }).click();
    await seller.page.locator('.ownership-link').waitFor();
    await seller.page.getByRole('button', { name: '취소 후 새 링크 발급', exact: true }).click();
    await seller.page.waitForFunction(previous => document.querySelector('.ownership-link')?.value && document.querySelector('.ownership-link').value !== previous, sharedLink);
    await seller.page.waitForFunction(() => !document.querySelector('[data-ownership-root] button')?.disabled);
    sharedLink = await seller.page.locator('.ownership-link').inputValue();
    ensure(counts.create === 3, 'Cancel and reissue request count');
    await decodeSharedQr(seller.page, sharedLink);
    record('Owner reload retains private pending status without raw token; cancel and explicit reissue succeed');
    const receiver = await context();
    step = 'terminal-receive-states';
    await receiveFixture(receiver.page, process.env.P5_UI_EXPIRED_TOKEN, '만료');
    await receiver.page.screenshot({ path: path.join(evidence, 'receive-expired.png'), fullPage: true });
    await receiveFixture(receiver.page, process.env.P5_UI_CANCELLED_TOKEN, '취소');
    await receiver.page.screenshot({ path: path.join(evidence, 'receive-cancelled.png'), fullPage: true });
    record('Expired and cancelled token views differ; no acceptance or browser storage');
    step = 'anonymous-login-explicit-accept-and-logout';
    await receiver.page.goto(sharedLink); await ready(receiver.page);
    await receiver.page.locator('[data-receive-login]').waitFor({ state: 'visible' });
    ensure(await receiver.page.locator('[data-receive-accept]').isDisabled(), 'Anonymous cannot accept');
    ensure(counts.accept === 0, 'No acceptance from GET or QR open');
    ensure(new URL(receiver.page.url()).search === '', 'Receiver query removed immediately');
    await login(receiver.page, process.env.P5_UI_BUYER_EMAIL, true);
    await receiver.page.locator('[data-receive-accept]:not(:disabled)').waitFor();
    ensure(counts.accept === 0, 'Login never automatically accepts');
    await noStoredSecret(receiver.page, new URL(sharedLink).searchParams.get('token'));
    await receiver.page.locator('.landing-account [data-auth-logout]').click();
    await receiver.page.waitForFunction(() => !auth.user);
    await receiver.page.locator('.landing-nav .nav-item > [data-page="market"]').hover();
    await receiver.page.locator('.landing-nav .nav-item > [data-page="receive"]').click();
    await receiver.page.locator('.landing-nav .nav-drop [data-page="receive"]').click();
    await receiver.page.locator('[data-receive-message]').filter({ hasText: '이전 링크가 없거나' }).waitFor();
    record('Guest cannot accept; login resumes same in-memory transfer without POST; logout purges continuation');
    step = 'network-retry-and-explicit-completion';
    await receiver.page.goto(sharedLink); await ready(receiver.page);
    await login(receiver.page, process.env.P5_UI_BUYER_EMAIL, true);
    await receiver.page.locator('[data-receive-accept]:not(:disabled)').waitFor();
    await receiver.page.route('**/api/v1/ownership/transfers/accept', route => route.abort(), { times: 1 });
    await receiver.page.locator('[data-receive-accept]').click();
    await receiver.page.locator('[data-receive-message]').filter({ hasText: '응답을 받지 못했습니다' }).waitFor();
    ensure(await receiver.page.locator('[data-receive-accept]').isDisabled(), 'Uncertain acceptance requires latest verification');
    await receiver.page.locator('[data-receive-retry]').click();
    await receiver.page.locator('[data-receive-accept]:not(:disabled)').waitFor();
    await receiver.page.locator('[data-receive-accept]').evaluate(element => { element.click(); element.click(); });
    await receiver.page.locator('[data-receive-message]').filter({ hasText: '소유권 이전이 완료되었습니다' }).waitFor();
    ensure(counts.accept === 2, 'One aborted request and one actual acceptance despite duplicate click');
    await noStoredSecret(receiver.page, new URL(sharedLink).searchParams.get('token'));
    await seller.page.getByRole('button', { name: '상태 새로고침', exact: true }).click();
    await seller.page.waitForFunction(() => !document.querySelector('.ownership-link') && !document.querySelector('.ownership-qr'));
    record('Previous owner authoritative status denial after completion clears displayed link and QR');
    await receiver.page.screenshot({ path: path.join(evidence, 'receive-complete.png'), fullPage: true });
    await receiver.page.locator('[data-receive-home]').click();
    await receiver.page.locator('#home [data-product-id="101"]').waitFor();
    await receiver.page.screenshot({ path: path.join(evidence, 'buyer-product-list.png'), fullPage: true });
    await receiver.page.locator('.landing-nav .nav-item > [data-page="market"]').hover();
    await receiver.page.locator('.landing-nav .nav-item > [data-page="receive"]').click();
    await receiver.page.locator('.landing-nav .nav-drop [data-page="receive"]').click();
    await receiver.page.locator('[data-receive-message]').filter({ hasText: '이전 링크가 없거나' }).waitFor();
    record('Network failure requires verification retry; explicit accept guarded against double click; completion clears token; new owner sees product 101');
    step = 'current-registration-three-step-navigation';
    await receiver.page.evaluate(() => show('register'));
    await receiver.page.locator('#register.active [data-register-step="1"]:not([hidden])').waitFor();
    await receiver.page.locator('#register [data-product-manufacturer-choice]').selectOption('OTHER');
    await receiver.page.locator('#register [data-product-manufacturer-custom]').fill('maker');
    for (const [name, value] of Object.entries({ title: 'browser regression laptop', modelName: 'model', serialNumber: 'BROWSER-REGRESSION-105' })) {
      await receiver.page.locator(`#register [name="${name}"]`).fill(value);
    }
    await receiver.page.locator('#register [data-register-step="1"] [data-register-next]').click();
    await receiver.page.locator('#register [data-register-step="2"]:not([hidden])').waitFor();
    await receiver.page.locator('#register [data-register-step="2"] [data-register-next]').click();
    await receiver.page.locator('#register [data-register-step="3"]:not([hidden])').waitFor();
    await receiver.page.locator('#register [data-register-step="3"] [data-register-prev]').click();
    await receiver.page.locator('#register [data-register-step="2"]:not([hidden])').waitFor();
    record('Current three-step laptop registration navigates forward and back with preserved session');
    step = 'current-chat-list-and-leave';
    await receiver.page.setViewportSize({ width: 390, height: 844 });
    await receiver.page.evaluate(() => show('deals'));
    await receiver.page.locator('#deals.active [data-room-id="501"]').waitFor();
    await receiver.page.locator('#deals [data-room-id="501"]').click();
    await receiver.page.locator('#deals [data-deals-chat-section]').waitFor({ state: 'visible' });
    await receiver.page.locator('#deals [data-deals-back]').click();
    await receiver.page.locator('#deals [data-deals-chat-section]').waitFor({ state: 'hidden' });
    await receiver.page.locator('#deals [data-room-id="501"]').click();
    await receiver.page.locator('#deals [data-deals-leave]').click();
    await receiver.page.locator('[data-confirm-ok]').click();
    await receiver.page.locator('#deals [data-room-id="501"]').waitFor({ state: 'detached' });
    record('Current chat list opens room, returns to list, and explicitly leaves the room');
    ensure(counts.uncaughtPageErrors === 0, 'No uncaught browser errors');
    step = 'complete';
    fs.writeFileSync(path.join(evidence, 'browser-result.json'), JSON.stringify({ status: 'PASS', started, ended: new Date().toISOString(), browser: browser.version(), playwrightCore: require('playwright-core/package.json').version, jsQR: require('jsqr/package.json').version, qrGenerator: '2.0.4', passed, counts, tokenMaterialRecorded: false, credentialsRecorded: false, externalRequestsBlocked: true }, null, 2));
    console.log('P5_BROWSER_PASS: ' + passed.length + ' real-browser scenarios; evidence redacted');
  } catch (error) {
    let diagnostic = {};
    if (activePage) {
      try {
        diagnostic = await activePage.evaluate(() => ({ activePage: document.querySelector('.page.active')?.id || null, authMessage: document.querySelector('#auth .au-msg')?.textContent || null, productListMessage: document.querySelector('[data-product-list-status]')?.textContent || null, productDetailMessage: document.querySelector('[data-product-detail-status]')?.textContent || null, ownershipMessage: document.querySelector('[data-ownership-message]')?.textContent || null, signedIn: typeof auth !== 'undefined' && Boolean(auth.user) }));
        Object.keys(diagnostic).forEach(key => { if (typeof diagnostic[key] === 'string') diagnostic[key] = safeMessage(diagnostic[key]); });
        await activePage.screenshot({ path: path.join(evidence, 'failure-redacted.png'), fullPage: true, mask: [activePage.locator('input'), activePage.locator('[data-ownership-share]'), activePage.locator('.ownership-qr'), activePage.locator('[data-auth-form]')] });
      } catch { diagnostic = { captureFailed: true }; }
    }
    fs.writeFileSync(path.join(evidence, 'browser-result.json'), JSON.stringify({ status: 'FAIL', failedStep: step, errorType: error.name, errorSummary: safeMessage(error.message), diagnostic, httpStatuses, createSecurity, started, ended: new Date().toISOString(), passed, counts, tokenMaterialRecorded: false }, null, 2));
    console.error('P5_BROWSER_FAIL: ' + step + ' (' + error.name + '); sensitive exception details suppressed');
    process.exitCode = 1;
  } finally { if (browser) await browser.close(); }
})();
