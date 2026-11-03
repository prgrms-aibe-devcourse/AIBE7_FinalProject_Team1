import test from 'node:test';
import assert from 'node:assert/strict';
import { environment, deferred, flush } from './dom.mjs';

const product = (productId, title = '내 노트북') => ({ productId, title, manufacturer: '삼성전자', modelName: '갤럭시북' });
const denied = (status, code = '') => ({ response: { ok: false, status, async json() { return { code }; } } });
const issued = id => ({ transferId: id, productId: id, rawToken: 'test-token-' + id, linkUrl: '/receive?token=test-token-' + id, expiresAt: '2099-11-02T00:00:00Z' });

/** 실제 보내기·발급 모듈에만 모의 API를 연결한다. 실제 계정이나 소유권은 변경하지 않는다. */
function screen(handler) {
  // 제품 목록은 실제 API처럼 커서 페이지 응답으로 감싼다. 배열 응답을 그대로 쓰는 기존 사례는 한 페이지로 취급한다.
  const env = environment(async (path, options, calls) => {
    if (!path.startsWith('/api/v1/products?')) return handler(path, options, calls);
    const value = await handler('/api/v1/products', options, calls, new URL(path, 'http://test.invalid').searchParams);
    return Array.isArray(value) ? { items: value, page: { number: 0, size: 100, hasNext: false, nextCursor: null } } : value;
  });
  env.window.PastportApi = { fetch: env.window.fetch.bind(env.window) };
  env.window.location.origin = 'http://test.invalid';
  const trade = env.add('section', { id: 'trade', className: 'page active' });
  env.add('section', { id: 'auth', className: 'page' });
  const passport = env.add('section', { id: 'passport', className: 'page' });
  const passportPanel = env.add('div', { 'data-ownership-root': '' }, passport);
  const nodes = {};
  for (const [name, tag] of [['products', 'div'], ['status', 'p'], ['count', 'span'], ['search', 'input'], ['refresh', 'button'], ['login', 'button'], ['retry', 'button'], ['register', 'button'], ['placeholder', 'div'], ['selected', 'div']]) {
    nodes[name] = env.add(tag, { ['data-external-' + name]: '' }, trade);
  }
  nodes.search.value = '';
  nodes.title = env.add('h2', { 'data-external-product-title': '' }, nodes.selected);
  nodes.panel = env.add('div', { 'data-ownership-root': '' }, nodes.selected);
  const auth = { user: { id: 1 } };
  env.run('ownership.js', { auth, qrcode: () => ({ addData() {}, make() {}, createDataURL: () => 'data:image/png;base64,test' }) });
  env.run('trade.js');
  const goto = async id => {
    env.document.querySelectorAll('.page').forEach(page => page.classList.toggle('active', page.id === id));
    await env.document.emit('pastport:page-shown', { id });
  };
  const click = node => env.document.emit('click', null, { target: node });
  const button = text => nodes.panel.querySelectorAll('button').find(node => node.textContent === text);
  return { ...env, ...nodes, auth, trade, passport, passportPanel, goto, click, button };
}

test('소유 제품을 선택하면 상태만 조회하며 발급 버튼을 눌러야 해당 제품의 링크·QR을 만든다', async () => {
  const s = screen((path, options) => path === '/api/v1/products' ? [product(5), product(6, '<img src=x> 두 번째 제품')]
    : options.method === 'POST' ? issued(JSON.parse(options.body).productId) : denied(404));
  await flush();
  assert.equal(s.products.children.length, 2);
  assert.equal(s.count.textContent, '2개');
  assert.equal(s.calls.filter(call => call.options.method === 'POST').length, 0);
  await s.products.children[1].emit('click'); await flush();
  assert.equal(s.title.textContent, '<img src=x> 두 번째 제품');
  assert.equal(s.title.innerHTML, '');
  assert.equal(s.calls.at(-1).path, '/api/v1/ownership/transfers/product/6');
  assert.equal(s.calls.filter(call => call.options.method === 'POST').length, 0);
  await s.button('새 링크 · QR 발급').emit('click');
  assert.deepEqual(JSON.parse(s.calls.at(-1).options.body), { productId: 6 });
  assert.equal(s.calls.at(-1).path, '/api/v1/ownership/transfers');
  assert.equal(s.panel.querySelector('input').value, 'http://test.invalid/receive?token=test-token-6');
  assert.equal(s.panel.querySelector('img').alt, '공유 링크와 동일한 일회용 이전 QR');
  assert.equal(s.calls.some(call => call.path.includes('/complete') || call.path.includes('/accept')), false);
});

test('검색으로 선택한 제품을 숨겼다가 다시 표시해도 선택과 발급 대상은 유지한다', async () => {
  const s = screen(path => path === '/api/v1/products' ? [product(5, '선택한 제품'), product(6, '다른 제품')] : denied(404));
  await flush(); await s.products.children[0].emit('click'); await flush();
  s.search.value = '다른'; await s.document.emit('input', null, { target: s.search });
  assert.equal(s.products.children.length, 1);
  assert.equal(s.title.textContent, '선택한 제품');
  s.search.value = ''; await s.document.emit('input', null, { target: s.search });
  assert.equal(s.products.children[0].getAttribute('aria-pressed'), 'true');
  assert.equal(s.products.children[1].getAttribute('aria-pressed'), 'false');
});

test('기존 이전 상태를 확인하는 동안 발급을 막아 늦은 조회가 새 공유 링크를 덮지 않는다', async () => {
  const pending = deferred();
  const s = screen((path, options) => path === '/api/v1/products' ? [product(5)] : options.method === 'POST' ? issued(5) : pending.promise);
  await flush(); await s.products.children[0].emit('click');
  assert.equal(s.button('새 링크 · QR 발급').disabled, true);
  await s.button('새 링크 · QR 발급').emit('click');
  assert.equal(s.calls.filter(call => call.options.method === 'POST').length, 0);
  pending.resolve(denied(404)); await flush();
  assert.equal(s.button('새 링크 · QR 발급').disabled, false);
  await s.button('새 링크 · QR 발급').emit('click');
  assert.equal(s.panel.querySelector('input').value, 'http://test.invalid/receive?token=test-token-5');
});

test('제품 전환 뒤 도착한 이전 상태는 현재 선택과 공유 정보를 덮어쓰지 않는다', async () => {
  const previous = deferred();
  const s = screen((path, options) => path === '/api/v1/products' ? [product(5), product(6, '현재 제품')]
    : path.endsWith('/product/5') ? previous.promise : options.method === 'POST' ? issued(6) : denied(404));
  await flush(); await s.products.children[0].emit('click');
  await s.products.children[1].emit('click'); await flush();
  await s.button('새 링크 · QR 발급').emit('click');
  previous.resolve({ transferId: 99, status: 'PENDING', expiresAt: '2099-11-02T00:00:00Z' }); await flush();
  assert.equal(s.title.textContent, '현재 제품');
  assert.equal(s.panel.querySelector('input').value, 'http://test.invalid/receive?token=test-token-6');
  assert.doesNotMatch(s.panel.textContent, /원본 링크는 다시 조회할 수 없습니다/);
});

test('화면을 떠난 뒤 완료된 발급 응답은 링크·QR을 다시 표시하지 않는다', async () => {
  const pending = deferred();
  const s = screen((path, options) => path === '/api/v1/products' ? [product(5)] : options.method === 'POST' ? pending.promise : denied(404));
  await flush(); await s.products.children[0].emit('click'); await flush();
  const sending = s.button('새 링크 · QR 발급').emit('click');
  await s.goto('auth'); pending.resolve(issued(5)); await sending; await flush();
  assert.equal(s.panel.textContent, ''); assert.equal(s.panel.querySelector('input'), null);
  assert.equal(s.products.children.length, 0); assert.equal(s.selected.hidden, true);
});

test('계정이 바뀌면 이전 제품 목록·선택을 지우고 늦은 목록 응답을 버린다', async () => {
  const previous = deferred(); let reads = 0;
  const s = screen(path => path === '/api/v1/products' ? ++reads === 1 ? previous.promise : [product(6, '새 계정 제품')] : denied(404));
  assert.equal(s.calls.length, 1);
  s.auth.user = { id: 2 };
  await s.document.emit('pastport:auth-changed', { signedIn: true, userId: 2 }); await flush();
  assert.equal(s.calls[0].options.signal.aborted, true);
  previous.resolve([product(5, '이전 계정 제품')]); await flush();
  assert.match(s.products.textContent, /새 계정 제품/); assert.doesNotMatch(s.products.textContent, /이전 계정/);
  await s.products.children[0].emit('click'); await flush();
  await s.document.emit('pastport:logout');
  assert.equal(s.products.textContent, ''); assert.equal(s.title.textContent, ''); assert.equal(s.panel.textContent, '');
});

test('조회 오류와 실제 제품 없음은 구분하고 재시도로 정상 목록을 복구한다', async () => {
  let result = denied(503);
  const s = screen(() => result); await flush();
  assert.match(s.status.textContent, /불러오지 못했습니다/); assert.equal(s.retry.hidden, false); assert.equal(s.register.hidden, true);
  result = []; await s.click(s.retry); await flush();
  assert.match(s.status.textContent, /제품이 없습니다/); assert.equal(s.register.hidden, false); assert.equal(s.retry.hidden, true);
  result = [product(5)]; await s.click(s.refresh); await flush();
  assert.equal(s.products.children.length, 1); assert.equal(s.status.textContent, ''); assert.equal(s.register.hidden, true);
});

test('보내기에서 로그인하면 같은 화면으로 한 번 복귀하고 다른 화면 이동 시 복귀 의도를 지운다', async () => {
  let signedIn = false;
  const s = screen(() => signedIn ? [product(5)] : denied(401)); await flush();
  assert.equal(s.login.hidden, false);
  await s.click(s.login); assert.deepEqual(s.navigations, ['auth']);
  signedIn = true;
  assert.equal(s.window.PastportOwnershipSend.resumeAfterLogin(), true); await flush();
  assert.equal(s.navigations.at(-1), 'trade'); assert.equal(s.products.children.length, 1);
  assert.equal(s.window.PastportOwnershipSend.resumeAfterLogin(), false);
  signedIn = false; await s.goto('trade'); await flush(); await s.click(s.login);
  await s.goto('passport');
  assert.equal(s.window.PastportOwnershipSend.resumeAfterLogin(), false);
});

test('제품 상세의 발급 패널과 보내기 화면을 전환해도 숨겨진 상세 응답이 현재 패널을 바꾸지 않는다', async () => {
  const s = screen(path => path === '/api/v1/products' ? [product(5)] : denied(404));
  await flush(); await s.products.children[0].emit('click'); await flush();
  const calls = s.calls.length;
  await s.document.emit('pastport:product-detail-rendered', { product: product(99) }); await flush();
  assert.equal(s.calls.length, calls);
  assert.match(s.panel.textContent, /외부 소유권 이전/); assert.equal(s.passportPanel.textContent, '');
  await s.goto('passport');
  await s.document.emit('pastport:product-detail-rendered', { product: product(42) }); await flush();
  assert.match(s.passportPanel.textContent, /외부 소유권 이전/);
  assert.equal(s.calls.at(-1).path, '/api/v1/ownership/transfers/product/42');
  assert.equal(s.panel.textContent, '');
});

test('판매 중인 제품의 발급 거절은 선택을 유지하고 내부 거래 경로를 안내한다', async () => {
  const s = screen((path, options) => path === '/api/v1/products' ? [product(5)] : options.method === 'POST' ? denied(409, 'OWN_005') : denied(404));
  await flush(); await s.products.children[0].emit('click'); await flush();
  await s.button('새 링크 · QR 발급').emit('click');
  assert.match(s.panel.textContent, /판매 중이거나 내부 예약/);
  assert.equal(s.panel.querySelector('input'), null); assert.equal(s.selected.hidden, false);
  assert.equal(s.button('새 링크 · QR 발급').disabled, false);
});

test('제품이 여러 페이지면 커서를 따라 모두 불러와 검색·선택할 수 있다', async () => {
  const cursor = { createdAt: '2026-11-01T00:00:00Z', productId: 7 };
  const s = screen((path, options, calls, params) => {
    if (path !== '/api/v1/products') return denied(404);
    return params.get('page') === '0'
      ? { items: [product(8, '첫 페이지 제품')], page: { number: 0, size: 100, hasNext: true, nextCursor: cursor } }
      : { items: [product(7, '다음 페이지 제품')], page: { number: 1, size: 100, hasNext: false, nextCursor: null } };
  });
  await flush();
  const listCalls = s.calls.filter(call => call.path.startsWith('/api/v1/products?'));
  assert.equal(listCalls.length, 2);
  const second = new URL(listCalls[1].path, 'http://test.invalid').searchParams;
  assert.equal(second.get('cursorProductId'), '7'); assert.equal(second.get('cursorCreatedAt'), cursor.createdAt);
  assert.equal(s.products.children.length, 2);
  assert.match(s.products.textContent, /다음 페이지 제품/);
});
