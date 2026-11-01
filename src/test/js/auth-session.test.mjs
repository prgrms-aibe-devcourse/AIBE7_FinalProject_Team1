import test from 'node:test';
import assert from 'node:assert/strict';
import { environment, deferred, flush } from './dom.mjs';
import { lockManager } from './auth-locks.mjs';

function setup(handler = () => ({}), locks = lockManager()) {
  const env = environment(handler);
  env.window.navigator = { locks };
  env.document.cookie = 'XSRF-TOKEN=current%2Bcsrf';
  env.run('auth-session.js');
  env.session = env.window.PastportSession;
  return env;
}
const denied = () => ({ response: { ok: false, status: 401 } });

test('두 문서의 갱신은 같은 잠금을 공유하고 두 번째 문서는 이미 복구된 쿠키로 조회만 한다', async () => {
  const locks = lockManager(), renewal = deferred();
  let valid = false;
  const handler = async path => {
    if (path === '/') return {};
    if (path === '/api/auth/refresh') { await renewal.promise; valid = true; return {}; }
    return valid ? { id: 9 } : denied();
  };
  const first = setup(handler, locks), second = setup(handler, locks);
  const one = first.session.recover(() => first.window.fetch('/api/auth/me'));
  const two = second.session.recover(() => second.window.fetch('/api/auth/me'));
  await flush();
  assert.equal(first.calls.filter(call => call.path === '/api/auth/refresh').length, 1);
  assert.equal(second.calls.length, 0);
  renewal.resolve();
  const responses = await Promise.all([one, two]);
  assert.deepEqual(responses.map(response => response.status), [200, 200]);
  assert.equal(second.calls.filter(call => call.path === '/api/auth/refresh').length, 0);
  assert.equal(new Set(locks.requests.map(request => request.name)).size, 1);
  const post = first.calls.find(call => call.path === '/api/auth/refresh');
  assert.equal(post.options.method, 'POST');
  assert.equal(post.options.headers['X-XSRF-TOKEN'], 'current+csrf');
  assert.equal(post.options.credentials, 'same-origin');
  assert.equal(post.options.signal, undefined);
});

test('이미 유효한 세션이나 권한 거부 응답에는 갱신 POST를 보내지 않는다', async () => {
  for (const status of [200, 403, 404, 503]) {
    const env = setup(() => ({ response: { status, ok: status === 200 } }));
    const response = await env.session.recover(() => env.window.fetch('/read'));
    assert.equal(response.status, status);
    assert.equal(env.calls.length, 1);
  }
});

test('갱신과 직접 로그인은 응답 수신까지 같은 잠금 안에서 실행된다', async () => {
  const locks = lockManager(), slow = deferred(), order = [];
  let valid = false;
  const first = setup(async path => {
    if (path === '/') return {};
    if (path === '/api/auth/refresh') {
      order.push('refresh:start'); await slow.promise;
      order.push('refresh:cookie'); valid = true; return {};
    }
    return valid ? {} : denied();
  }, locks);
  const second = setup(() => { order.push('login:cookie'); return {}; }, locks);
  const recovery = first.session.recover(() => first.window.fetch('/read'));
  await flush();
  const login = second.session.exclusive(() => second.window.fetch('/api/auth/login', { method: 'POST' }));
  await flush();
  assert.deepEqual(order, ['refresh:start']);
  slow.resolve(); await Promise.all([recovery, login]);
  assert.deepEqual(order, ['refresh:start', 'refresh:cookie', 'login:cookie']);
});

test('갱신 실패 뒤 재검사도 실패하면 401을 그대로 반환하며 회전을 반복하지 않는다', async () => {
  const env = setup(path => path === '/' ? {} : denied());
  const response = await env.session.recover(() => env.window.fetch('/read'));
  assert.equal(response.status, 401);
  assert.equal(env.calls.filter(call => call.path === '/read').length, 2);
  assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 1);
});

test('만료 확인 뒤 후속 401은 회전을 반복하지 않고 다른 탭의 정상 쿠키를 확인하면 복구를 다시 허용한다', async () => {
  let valid = false;
  const env = setup(path => path === '/' || valid ? {} : denied());
  const read = () => env.window.fetch('/read');
  await env.session.recover(read); await env.session.recover(read);
  assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 1);
  valid = true; assert.equal((await env.session.recover(read)).status, 200);
  valid = false; await env.session.recover(read);
  assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 2);
});

test('갱신이 실패해도 마지막 읽기에 새 쿠키가 확인되면 정상 응답을 사용한다', async () => {
  let reads = 0;
  const env = setup(path => path === '/' ? {} : path === '/read' && ++reads === 2 ? {} : denied());
  const response = await env.session.recover(() => env.window.fetch('/read'));
  assert.equal(response.status, 200);
  assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 1);
});

test('공유 잠금 미지원 또는 잠금 접근 거부 환경은 자동 회전을 생략하고 직접 로그인을 허용한다', async () => {
  const error = Object.assign(new Error('Lock denied'), { name: 'SecurityError' });
  for (const locks of [null, { request: async () => { throw error; } }]) {
    const env = setup(() => denied(), locks);
    const response = await env.session.recover(() => env.window.fetch('/read'));
    assert.equal(response.status, 401);
    assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 0);
    let submitted = 0;
    await env.session.exclusive(() => { submitted++; });
    assert.equal(submitted, 1);
  }
});

test('인증 요청 내부 오류가 SecurityError여도 작업을 두 번 실행하지 않고 다음 작업은 진행한다', async () => {
  const env = setup(); let calls = 0;
  const error = Object.assign(new Error('Request failed'), { name: 'SecurityError' });
  await assert.rejects(env.session.exclusive(() => { calls++; throw error; }), error);
  await env.session.exclusive(() => { calls++; });
  assert.equal(calls, 2);
});

test('잠금 대기 중 화면이 바뀌면 읽기·갱신을 시작하지 않는다', async () => {
  const locks = lockManager(), blocker = deferred(), env = setup(() => denied(), locks);
  const held = locks.request('pastport.auth.cookies', { mode: 'exclusive' }, () => blocker.promise);
  let current = true;
  const recovery = env.session.recover(() => env.window.fetch('/read'), () => current);
  await flush(); current = false; blocker.resolve(); await held;
  assert.equal(await recovery, null);
  assert.equal(env.calls.length, 0);
});

test('화면을 떠나도 시작한 회전 응답까지 잠금을 유지하고 이전 읽기 결과만 버린다', async () => {
  const env = setup(path => path === '/' ? {} : path === '/api/auth/refresh' ? slow.promise : denied());
  const slow = deferred(); let current = true, mutation = false;
  const recovery = env.session.recover(() => env.window.fetch('/read'), () => current);
  await flush(); current = false;
  const next = env.session.exclusive(() => { mutation = true; });
  await flush(); assert.equal(mutation, false);
  slow.resolve({});
  assert.equal(await recovery, null); await next;
  assert.equal(mutation, true);
  assert.equal(env.calls.filter(call => call.path === '/read').length, 1);
});

test('CSRF 쿠키가 준비되지 않거나 홈 조회가 실패하면 갱신을 요청하지 않는다', async () => {
  for (const seedFails of [false, true]) {
    const env = setup(path => path === '/' && !seedFails ? {} : denied());
    if (!seedFails) env.document.cookie = '';
    const response = await env.session.recover(() => env.window.fetch('/read'));
    assert.equal(response.status, 401);
    assert.equal(env.calls.filter(call => call.path === '/api/auth/refresh').length, 0);
  }
});
