import test from 'node:test';
import assert from 'node:assert/strict';
import { environment, deferred, flush } from './dom.mjs';
import { lockManager } from './auth-locks.mjs';

/** 실제 인증 화면 모듈을 실행한다. 쿠키의 HttpOnly 처리와 문서 리다이렉트는 실제 브라우저에서 별도로 검사한다. */
function setup(href, result, options = {}) {
  let checks = 0;
  const env = environment(async (path, request) => {
    if (path === '/pages/auth-art.html') return { response: { ok: true, status: 200, text: async () => '' } };
    if (path === '/api/auth/me' && typeof result !== 'function' && result?.user && ++checks === 1 && options.expired !== false)
      return { response: { ok: false, status: 401, json: async () => ({}) } };
    const value = await (typeof result === 'function' ? result(path, request) : result);
    return path === '/api/auth/me' && value?.user ? value.user : value;
  });
  env.window.location.href = href;
  env.window.location.replace = path => { env.navigations.push(path); };
  env.document.cookie = 'XSRF-TOKEN=csrf-test';
  env.window.navigator = { locks: options.locks === false ? undefined : options.locks || lockManager() };
  env.document.dispatchEvent = event => env.document.emit(event.type, event.detail, event);
  const create = env.document.createElement.bind(env.document);
  env.document.createElement = tag => {
    const node = create(tag);
    node.click = () => { if (node.dataset.page) env.navigations.push(node.dataset.page); };
    return node;
  };
  const select = env.document.querySelector.bind(env.document);
  env.document.querySelector = selector => {
    const page = selector.match(/^\[data-page="([^"]+)"\]$/);
    return page ? env.document.querySelectorAll('[data-page]').find(node => node.dataset.page === page[1]) || null : select(selector);
  };
  env.header = env.add('div', { className: 'landing-account' });
  const login = env.add('button', { 'data-page': 'auth' });
  login.dataset.page = 'auth';
  login.click = () => env.navigations.push('auth');
  env.add('button', { 'data-page': 'landing' }).dataset.page = 'landing';
  env.run('auth-session.js', options.globals || {});
  env.run('auth.js', { fetch: env.window.fetch.bind(env.window), Headers,
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } } });
  return env;
}

test('외부 보내기에서 시작한 직접 로그인은 보내기 화면으로 복귀한다', async () => {
  const env = setup('http://test.invalid/', { user: { id: 2, role: 'USER', nickname: '회원' } }, { expired: false });
  await flush();
  env.window.PastportReceive = { resumeAfterLogin: () => false };
  env.window.PastportOwnershipSend = { resumeAfterLogin() { env.navigations.push('trade'); return true; } };
  await login(env);
  assert.deepEqual(env.navigations, ['trade']);
});

test('수신 링크로 시작한 로그인은 보내기 화면의 복귀 의도보다 우선한다', async () => {
  const env = setup('http://test.invalid/', { user: { id: 2, role: 'USER', nickname: '회원' } }, { expired: false });
  await flush();
  let senderResumed = false;
  env.window.PastportReceive = { resumeAfterLogin() { env.navigations.push('receive'); return true; } };
  env.window.PastportOwnershipSend = { resumeAfterLogin() { senderResumed = true; return true; } };
  await login(env);
  assert.deepEqual(env.navigations, ['receive']);
  assert.equal(senderResumed, false);
});

test('관리자 세션 복구가 성공한 경우에만 고정된 관리자 화면으로 복귀한다', async () => {
  const env = setup('http://test.invalid/?returnTo=admin', { user: { id: 9, role: 'ADMIN' } });
  await flush();
  assert.deepEqual(env.navigations, ['/admin']);
  const refresh = env.calls.find(call => call.path === '/api/auth/refresh');
  assert.equal(refresh.options.method, 'POST');
  assert.equal(refresh.options.headers['X-XSRF-TOKEN'], 'csrf-test');
});

test('리뷰: 시작 회원 조회가 시간 초과되면 대기 중인 직접 로그인은 진행된다', async () => {
  const timers = []; let meSignal;
  const env = setup('http://test.invalid/?returnTo=admin', (path, options) => {
    if (path === '/api/auth/me') {
      meSignal = options.signal;
      return new Promise((resolve, reject) => options.signal?.addEventListener('abort', () => reject(options.signal.reason), { once: true }));
    }
    return path === '/api/auth/login' ? { user: { id: 2, role: 'USER', nickname: '새 회원' } } : {};
  }, { globals: { AbortSignal: { timeout(ms) { assert.equal(ms, 15000); const timer = new AbortController(); timers.push(timer); return timer.signal; } } } });
  await flush(); assert.ok(meSignal);
  const loggingIn = login(env); await flush();
  assert.equal(env.calls.filter(call => call.path === '/api/auth/login').length, 0);
  timers.find(timer => timer.signal === meSignal).abort(new DOMException('Timed out', 'TimeoutError'));
  await loggingIn; await flush();
  assert.match(env.header.textContent, /새 회원/);
  assert.equal(env.navigations.includes('/admin'), false);
  assert.equal(env.calls.find(call => call.path === '/api/auth/login').options.signal, undefined);
});

test('리뷰: 실제로 성공한 204 로그아웃은 취소된 인증 작업과 구분해 만료 판정을 해제한다', async () => {
  let expired = false;
  const denied = { response: { ok: false, status: 401, json: async () => ({}) } };
  const env = setup('http://test.invalid/', path => {
    if (path === '/') return {};
    if (path === '/api/auth/logout') return { response: { ok: true, status: 204 } };
    if (path === '/api/auth/me' && !expired) return { user: { id: 2, role: 'USER', nickname: '현재 회원' } };
    return denied;
  });
  await flush(); expired = true;
  assert.equal((await env.window.PastportApi.fetch('/protected')).status, 401);
  assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 1);
  const button = env.header.querySelector('[data-auth-logout]');
  assert.ok(button);
  button.closest = selector => selector === '[data-auth-logout]' ? button : null;
  await env.document.emit('click', null, { target: button }); await flush();
  await env.window.PastportSession.recover(signal => env.window.fetch('/api/auth/me', { signal }));
  assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 2);
  assert.doesNotMatch(env.header.textContent, /현재 회원/);
});

/** 직접 로그인과 초기 복구의 완료 순서를 제어한다. 실제 HttpOnly 쿠키 처리는 브라우저에서도 검사한다. */
async function login(env) {
  const form = env.add('form', { 'data-auth-form': 'login' });
  form.dataset.authForm = 'login';
  form.elements = { email: { value: 'new@fixture.invalid' }, password: { value: 'test-password' } };
  form.closest = () => form;
  await env.document.emit('submit', null, { target: form, preventDefault() {} });
  await flush();
}

test('직접 로그인 뒤 늦은 초기 복구 성공은 다른 회원의 헤더나 관리자 이동을 덮어쓰지 않는다', async () => {
  const slow = deferred();
  const env = setup('http://test.invalid/?returnTo=admin', path => path === '/api/auth/login'
    ? { user: { id: 2, role: 'USER', nickname: '새 회원' } } : slow.promise);
  await flush(); await login(env);
  slow.resolve({ user: { id: 9, role: 'ADMIN', nickname: '이전 관리자' } });
  await flush();
  assert.equal(env.calls.filter(call => call.path === '/api/auth/login').length, 1);
  assert.match(env.header.textContent, /새 회원/);
  assert.doesNotMatch(env.header.textContent, /이전 관리자/);
  assert.equal(env.navigations.includes('/admin'), false);
});

test('직접 로그인 뒤 늦은 초기 복구 실패는 새 세션을 지우거나 로그인 화면으로 되돌리지 않는다', async () => {
  const slow = deferred();
  const env = setup('http://test.invalid/?returnTo=admin', path => path === '/api/auth/login'
    ? { user: { id: 2, role: 'USER', nickname: '새 회원' } } : slow.promise);
  await flush(); await login(env);
  slow.resolve({ response: { ok: false, status: 401, json: async () => ({ message: '만료' }) } });
  await flush();
  assert.equal(env.calls.filter(call => call.path === '/api/auth/login').length, 1);
  assert.match(env.header.textContent, /새 회원/);
  assert.deepEqual(env.navigations, ['landing']);
});

test('갱신 쿠키도 만료됐다면 관리자 페이지를 반복해서 열지 않고 로그인 화면을 연다', async () => {
  const env = setup('http://test.invalid/?returnTo=admin', { response: { ok: false, status: 401, json: async () => ({ message: '세션이 만료됐어요.' }) } });
  await flush(); assert.deepEqual(env.navigations, ['auth']);
});

test('일반 회원이나 일반 홈 진입에는 관리자 자동 복귀를 적용하지 않는다', async () => {
  for (const [href, role] of [['http://test.invalid/?returnTo=admin', 'USER'], ['http://test.invalid/', 'ADMIN'], ['http://test.invalid/?returnTo=https://untrusted.invalid', 'ADMIN']]) {
    const env = setup(href, { user: { id: 9, role } }); await flush();
    assert.deepEqual(env.navigations, []);
  }
});

test('유효한 Access 쿠키가 남아 있으면 회원 조회만 하고 갱신 쿠키를 회전하지 않는다', async () => {
  const env = setup('http://test.invalid/', { user: { id: 2, role: 'USER', nickname: '현재 회원' } }, { expired: false });
  await flush();
  assert.match(env.header.textContent, /현재 회원/);
  assert.equal(env.calls.filter(call => call.path === '/api/auth/me').length, 1);
  assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 0);
});

test('공유 잠금이 없는 환경은 자동 회전 없이 로그인으로 안내하고 직접 로그인은 처리한다', async () => {
  const env = setup('http://test.invalid/?returnTo=admin', path => path === '/api/auth/login'
    ? { user: { id: 2, role: 'USER', nickname: '새 회원' } }
    : { response: { ok: false, status: 401, json: async () => ({}) } }, { locks: false });
  await flush(); assert.deepEqual(env.navigations, ['auth']);
  await login(env);
  assert.match(env.header.textContent, /새 회원/);
  assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 0);
});

test('초기 복구 중 로그아웃을 시작하면 늦은 성공을 표시하거나 관리자로 이동하지 않는다', async () => {
  const slow = deferred();
  const env = setup('http://test.invalid/?returnTo=admin', path => path === '/api/auth/logout'
    ? { response: { ok: true, status: 204 } } : slow.promise);
  await flush();
  const logout = env.document.emit('click', null, { target: { closest: selector => selector === '[data-auth-logout]' ? {} : null } });
  slow.resolve({ user: { id: 9, role: 'ADMIN', nickname: '이전 관리자' } });
  await logout; await flush();
  assert.equal(env.calls.filter(call => call.path === '/api/auth/logout').length, 1);
  assert.doesNotMatch(env.header.textContent, /이전 관리자/);
  assert.deepEqual(env.navigations, ['landing']);
});

test('인증 복구 중 계정 전환이 시작되면 이전 계정의 쓰기 요청을 재전송하지 않는다', async () => {
  const slow = deferred(); let recovering = false;
  const env = setup('http://test.invalid/', path => {
    if (path === '/api/v1/write') return { response: { ok: false, status: 401 } };
    if (path === '/api/auth/login') return { user: { id: 2, role: 'USER', nickname: '새 회원' } };
    if (path === '/api/auth/me') return recovering ? slow.promise : { user: { id: 9, role: 'ADMIN', nickname: '이전 관리자' } };
    return {};
  });
  await flush(); recovering = true;
  const write = env.window.PastportApi.fetch('/api/v1/write', { method: 'POST', body: 'original' });
  await flush(); await login(env);
  slow.resolve({ user: { id: 9, role: 'ADMIN', nickname: '이전 관리자' } });
  assert.equal((await write).status, 401); await flush();
  assert.equal(env.calls.filter(call => call.path === '/api/v1/write').length, 1);
  assert.match(env.header.textContent, /새 회원/);
});

test('페이지 이탈 뒤 초기 복구는 표시하지 않고 뒤로 가기 복원에서는 현재 쿠키로 회원을 다시 확인한다', async () => {
  const slow = deferred(); let checks = 0;
  const env = setup('http://test.invalid/?returnTo=admin', () => ++checks === 1 ? slow.promise
    : { user: { id: 2, role: 'USER', nickname: '현재 회원' } });
  await flush(); await env.document.emit('window:pagehide');
  slow.resolve({ user: { id: 9, role: 'ADMIN', nickname: '이전 관리자' } });
  await flush();
  assert.doesNotMatch(env.header.textContent, /이전 관리자/);
  assert.equal(env.navigations.includes('/admin'), false);
  await env.document.emit('window:pageshow', null, { persisted: true }); await flush();
  assert.match(env.header.textContent, /현재 회원/);
});
