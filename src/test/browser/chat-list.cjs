const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.CHAT_LIST_PLAYWRIGHT || 'playwright-core');
const base = new URL(process.env.CHAT_LIST_BASE_URL);
assert.equal(base.hostname, '127.0.0.1');
assert.equal(base.protocol, 'http:');
const evidence = process.env.CHAT_LIST_EVIDENCE;
const passed = [], errors = [], requests = [];
let browser, step = 'launch';
const record = label => { passed.push(label); console.log('PASS ' + label); };
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

async function context(viewport = { width: 1440, height: 1080 }, delayDeals = false) {
  const created = await browser.newContext({ viewport });
  await created.addInitScript(() => {
    const originalSet = window.setInterval, originalClear = window.clearInterval;
    const chatTimers = new Set();
    window.setInterval = (callback, delay, ...args) => {
      const timer = originalSet(callback, delay, ...args);
      if (delay === 3000) chatTimers.add(timer);
      return timer;
    };
    window.clearInterval = timer => { chatTimers.delete(timer); return originalClear(timer); };
    window.chatTimerCount = () => chatTimers.size;
  });
  await created.route('**/*', route => {
    const url = new URL(route.request().url());
    return url.origin === base.origin ? route.continue() : route.abort();
  });
  let releaseDeals;
  if (delayDeals) {
    const held = new Promise(resolve => { releaseDeals = resolve; });
    await created.route('**/pages/deals.html', async route => { await held; await route.continue(); });
  }
  const page = await created.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.origin === base.origin && url.pathname.includes('chat-rooms'))
      requests.push({ method: request.method(), path: url.pathname + url.search });
  });
  page.on('dialog', dialog => dialog.dismiss());
  await page.goto(base.href);
  await page.waitForFunction(delay => window.PastportDeals && window.PastportPublishedListing && document.querySelector('#auth form') && (delay || document.querySelector('#deals [data-deals-rooms]')), delayDeals);
  return { created, page, releaseDeals };
}

async function login(page, actor) {
  await page.evaluate(() => show('auth'));
  const form = page.locator('#auth [data-auth-form="login"]');
  await form.locator('[name=email]').fill('chat' + actor + '@fixture.invalid');
  await form.locator('[name=password]').fill(process.env.CHAT_LIST_PASSWORD);
  await form.locator('[type=submit]').click();
  await page.waitForFunction(() => Boolean(document.querySelector('.landing-account [data-auth-logout]')));
}

async function api(page, url, method = 'GET', body) {
  return page.evaluate(async ({ url, method, body }) => {
    const response = await window.PastportApi.fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
    const payload = await response.json().catch(() => null);
    return { status: response.status, data: payload?.data };
  }, { url, method, body });
}

async function detail(page, listingId, manage = false) {
  await page.evaluate(({ listingId, manage }) => window.PastportPublishedListing.open(listingId, { manage }), { listingId, manage });
  await page.locator('#detail [data-listing-content] .listing-detail').waitFor({ state: 'attached' });
}

async function rows(page, selector, count) {
  await page.waitForFunction(({ selector, count }) => document.querySelectorAll(selector + ' .dl-row').length === count, { selector, count });
}

async function openAll(page, selector = '#side [data-page=deals]') {
  if (selector.startsWith('.landing-nav')) await page.locator('.landing-nav .nav-item').filter({ has: page.locator('[data-page=deals]') }).hover();
  await page.locator(selector).click();
  await rows(page, '#deals [data-deals-rooms]', 20);
  assert.equal(await page.locator('[data-deals-scope]').innerText(), '전체 채팅');
  assert.equal(await page.locator('[data-deals-all]').isHidden(), true);
}

(async () => {
  browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  step = 'guest';
  const guest = await context();
  await guest.page.evaluate(() => show('deals'));
  await guest.page.getByText('로그인이 필요한 서비스입니다.', { exact: true }).waitFor();
  await guest.page.evaluate(() => window.PastportPublishedListing.open(61));
  await guest.page.locator('[data-listing-login]').waitFor({ state: 'visible' });
  await guest.page.locator('[data-listing-login]').click();
  await login(guest.page, 2);
  await guest.page.locator('#detail.active .listing-detail').waitFor({ state: 'visible' });
  assert.equal(requests.filter(request => request.method === 'POST').length, 0);
  await guest.created.close();
  record('CL-04/08 guest login state, detail return, login alone does not create a room');

  const seller = await context(), buyer = await context();
  await login(seller.page, 1); await login(buyer.page, 2);
  step = 'seller-detail';
  await detail(seller.page, 61);
  const detailRoot = '#detail [data-listing-chat-rooms]';
  await rows(seller.page, detailRoot, 20);
  assert.equal(await seller.page.locator('[data-listing-chat]').isHidden(), true);
  assert.equal((await api(seller.page, '/api/v1/chat-rooms?listingId=61')).data.page.totalElements, 57);
  assert.equal(await seller.page.locator(detailRoot + ' b b').count(), 0);
  await seller.page.locator(detailRoot + ' [data-chat-more]').click();
  await rows(seller.page, detailRoot, 40);
  await seller.page.locator(detailRoot + ' [data-chat-more]').click();
  await rows(seller.page, detailRoot, 57);
  assert.ok((await seller.page.locator(detailRoot).innerText()).includes('<b>buyer</b>'));
  assert.equal(await seller.page.locator(detailRoot + ' span.t b').count(), 0);
  await seller.page.screenshot({ path: path.join(evidence, 'seller-detail.png'), fullPage: true });
  await seller.page.locator(detailRoot + ' [data-room-id="10001"]').click();
  await seller.page.locator('#deals.active [data-deals-chat-section]').waitFor({ state: 'visible' });
  assert.ok((await seller.page.locator('[data-deals-scope]').innerText()).includes('Listing 61'));
  record('CL-05/07 seller detail shared rows, escaped text, 57 rooms, direct selected room');

  step = 'buyer-inquiry';
  await detail(buyer.page, 61);
  assert.equal(await buyer.page.locator('[data-listing-chats]').isHidden(), true);
  await buyer.page.locator('[data-listing-chat]').waitFor({ state: 'visible' });
  const before = requests.filter(request => request.method === 'POST' && request.path === '/api/v1/market/listings/61/chat-rooms').length;
  await buyer.page.locator('[data-listing-chat]').evaluate(button => { button.click(); button.click(); });
  await buyer.page.locator('#deals.active [data-deals-chat-section]').waitFor({ state: 'visible' });
  await buyer.page.waitForFunction(() => window.chatTimerCount() === 1);
  assert.equal(requests.filter(request => request.method === 'POST' && request.path === '/api/v1/market/listings/61/chat-rooms').length - before, 1);
  await buyer.page.locator('[data-deals-input]').fill('<img src=x onerror=alert(1)> buyer message');
  await buyer.page.locator('[data-deals-input]').press('Enter');
  await buyer.page.locator('[data-deals-messages]').getByText('<img src=x onerror=alert(1)> buyer message', { exact: true }).waitFor();
  await seller.page.locator('[data-deals-messages]').getByText('<img src=x onerror=alert(1)> buyer message', { exact: true }).waitFor();
  assert.equal(await seller.page.locator('[data-deals-messages] img').count(), 0);
  await seller.page.locator('[data-deals-input]').fill('seller reply');
  await seller.page.locator('[data-deals-send]').click();
  await buyer.page.locator('[data-deals-messages]').getByText('seller reply', { exact: true }).waitFor();
  await buyer.page.waitForFunction(async () => {
    const response = await window.PastportApi.fetch('/api/v1/chat-rooms?listingId=61');
    return !(await response.json()).data.items[0].hasUnreadMessages;
  });
  const messages = (await api(buyer.page, '/api/v1/chat-rooms/10001/messages')).data.items;
  assert.equal(messages.length, 2);
  await buyer.page.screenshot({ path: path.join(evidence, 'buyer-chat.png'), fullPage: true });
  record('CL-06 inquiry duplicate guard, actual messages, polling, safe message text, read position');

  step = 'all-entry';
  await openAll(seller.page);
  await seller.page.locator('#deals [data-chat-more]').click();
  await rows(seller.page, '#deals [data-deals-rooms]', 40);
  await seller.page.locator('#deals [data-chat-more]').click();
  await rows(seller.page, '#deals [data-deals-rooms]', 59);
  await seller.page.evaluate(() => show('mypage'));
  await openAll(seller.page, '#mypage [data-page=deals]');
  await detail(seller.page, 61);
  await rows(seller.page, detailRoot, 20);
  await seller.page.locator(detailRoot + ' [data-room-id="10001"]').click();
  await rows(seller.page, '#deals [data-deals-rooms]', 20);
  await openAll(seller.page, '.landing-nav [data-page=deals]');
  record('CL-04/07 sidebar, mypage, top menu reset scope and page; all 59 reachable');

  step = 'management-empty-error';
  await detail(seller.page, 62, true);
  await seller.page.locator('[data-listing-editor]').waitFor({ state: 'visible' });
  await rows(seller.page, detailRoot, 1);
  assert.equal(await seller.page.locator('[data-listing-chats]').isVisible(), true);
  await detail(seller.page, 64);
  await seller.page.getByText('아직 이 판매글에 들어온 문의가 없어요', { exact: true }).waitFor();
  const failRoute = '**/api/v1/chat-rooms?*listingId=62*';
  await seller.page.route(failRoute, route => route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"synthetic failure"}' }));
  await detail(seller.page, 62);
  await seller.page.locator(detailRoot).getByText('대화방 목록을 불러오지 못했습니다.', { exact: true }).waitFor();
  assert.equal(await seller.page.locator('[data-listing-content] .listing-detail').count(), 1);
  await seller.page.unroute(failRoute);
  await seller.page.locator(detailRoot).getByText('다시 시도', { exact: true }).click();
  await rows(seller.page, detailRoot, 1);
  record('CL-05 management, empty, isolated failure and retry');

  step = 'detail-delay';
  let release, capturedResolve;
  const held = new Promise(resolve => { release = resolve; });
  const captured = new Promise(resolve => { capturedResolve = resolve; });
  const delayedRoute = '**/api/v1/chat-rooms?*listingId=61*';
  await seller.page.route(delayedRoute, async route => {
    const response = await route.fetch(); capturedResolve(); await held;
    await route.fulfill({ response }).catch(() => {});
  });
  await detail(seller.page, 61); await captured;
  await detail(seller.page, 62);
  await rows(seller.page, detailRoot, 1);
  release(); await pause(300); await seller.page.unroute(delayedRoute);
  assert.equal(await seller.page.locator(detailRoot + ' .dl-row').count(), 1);
  assert.ok((await seller.page.locator(detailRoot).innerText()).includes('Listing 62'));
  record('CL-08 delayed detail A cannot overwrite B');

  step = 'closed-room';
  await openAll(seller.page);
  let releaseMessages, capturedMessagesResolve;
  const heldMessages = new Promise(resolve => { releaseMessages = resolve; });
  const capturedMessages = new Promise(resolve => { capturedMessagesResolve = resolve; });
  const messagesRoute = '**/api/v1/chat-rooms/10001/messages?*';
  await seller.page.route(messagesRoute, async route => {
    const response = await route.fetch(); capturedMessagesResolve(); await heldMessages;
    await route.fulfill({ response }).catch(() => {});
  });
  await seller.page.locator('#deals [data-room-id="10001"]').click();
  await capturedMessages;
  await seller.page.locator('#deals [data-room-id="30001"]').click();
  await seller.page.locator('[data-deals-chat-section]').waitFor({ state: 'visible' });
  releaseMessages(); await pause(300); await seller.page.unroute(messagesRoute);
  assert.equal(await seller.page.locator('[data-deals-messages]').innerText(), '');
  await seller.page.waitForFunction(() => window.chatTimerCount() === 1);
  record('CL-08 delayed messages from prior room discarded; one polling timer');
  await api(buyer.page, '/api/v1/chat-rooms/30001/messages', 'POST', { content: 'closed polling reply' });
  await seller.page.locator('[data-deals-messages]').getByText('closed polling reply', { exact: true }).waitFor();
  await seller.page.locator('[data-deals-input]').fill('closed seller reply');
  await seller.page.locator('[data-deals-send]').click();
  await seller.page.locator('[data-deals-messages]').getByText('closed seller reply', { exact: true }).waitFor();
  record('CL-08 sold listing existing room keeps polling and sending');

  step = 'account-delay';
  let releaseAccount, capturedAccountResolve;
  const heldAccount = new Promise(resolve => { releaseAccount = resolve; });
  const capturedAccount = new Promise(resolve => { capturedAccountResolve = resolve; });
  let intercepted = false;
  const accountRoute = '**/api/v1/chat-rooms?*';
  await seller.page.route(accountRoute, async route => {
    if (intercepted) return route.continue();
    intercepted = true;
    const response = await route.fetch(); capturedAccountResolve(); await heldAccount;
    await route.fulfill({ response }).catch(() => {});
  });
  await seller.page.evaluate(() => show('deals')); await capturedAccount;
  await seller.page.locator('.landing-account [data-auth-logout]').click();
  await seller.page.waitForFunction(() => !document.querySelector('.landing-account [data-auth-logout]'));
  await login(seller.page, 3);
  releaseAccount(); await pause(300); await seller.page.unroute(accountRoute);
  await seller.page.evaluate(() => show('deals'));
  await rows(seller.page, '#deals [data-deals-rooms]', 1);
  assert.equal(await seller.page.locator('#deals [data-room-id="10002"]').count(), 1);
  assert.equal(await seller.page.locator('[data-deals-messages]').innerText(), '');
  assert.equal(await seller.page.locator('[data-deals-chat-section]').isVisible(), false);
  assert.equal(await seller.page.evaluate(() => window.chatTimerCount()), 0);
  record('CL-08 delayed previous account list discarded after logout and login');

  step = 'new-inquiry';
  await detail(seller.page, 62);
  await seller.page.locator('[data-listing-chat]').click();
  await seller.page.locator('#deals.active [data-deals-chat-section]').waitFor({ state: 'visible' });
  const newRoom = (await api(seller.page, '/api/v1/chat-rooms?listingId=62')).data.items[0];
  assert.ok(newRoom.roomId < 10000);
  await detail(seller.page, 62);
  await seller.page.locator('[data-listing-chat]').click();
  await seller.page.locator('#deals.active [data-deals-chat-section]').waitFor({ state: 'visible' });
  assert.equal((await api(seller.page, '/api/v1/chat-rooms?listingId=62')).data.items[0].roomId, newRoom.roomId);
  record('CL-06 new inquiry creates once and reopens same room');

  step = 'mobile';
  const mobile = await context({ width: 390, height: 844 });
  await login(mobile.page, 1);
  await mobile.page.evaluate(() => show('mypage'));
  await mobile.page.locator('#menu').click();
  await mobile.page.locator('#side [data-page=deals]').click();
  await rows(mobile.page, '#deals [data-deals-rooms]', 20);
  assert.equal(await mobile.page.locator('[data-deals-scope]').innerText(), '전체 채팅');
  await mobile.page.screenshot({ path: path.join(evidence, 'mobile-all-chat.png'), fullPage: true });
  record('CL-04 mobile sidebar all chat');

  step = 'empty-all';
  const empty = await context();
  await login(empty.page, 4);
  await empty.page.evaluate(() => show('deals'));
  await empty.page.getByText('아직 나눈 채팅이 없어요', { exact: true }).waitFor();
  await empty.created.close();
  record('CL-04 empty all distinct from guest and failure');

  step = 'first-async-page';
  const early = await context(undefined, true);
  await login(early.page, 2);
  await detail(early.page, 61);
  await early.page.locator('[data-listing-chat]').click();
  await early.page.locator('#deals.active').waitFor({ state: 'attached' });
  early.releaseDeals();
  await early.page.locator('#deals.active [data-deals-chat-section]').waitFor({ state: 'visible' });
  await early.page.waitForFunction(() => window.chatTimerCount() === 1);
  assert.ok((await early.page.locator('[data-deals-room-title]').innerText()).includes('Listing 61'));
  await early.created.close();
  record('CL-08 inquiry waits for first asynchronous deals HTML, one polling timer');
  assert.deepEqual(errors, []);
  fs.writeFileSync(path.join(evidence, 'browser-result.json'), JSON.stringify({ passed, requests, errors, status: 'PASS' }, null, 2));
})().catch(error => {
  console.error('FAIL ' + step + ': ' + error.stack);
  fs.writeFileSync(path.join(evidence, 'browser-result.json'), JSON.stringify({ step, passed, requests, errors, status: 'FAIL', message: error.message }, null, 2));
  process.exitCode = 1;
}).finally(async () => { await browser?.close(); });
