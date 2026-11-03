import test from 'node:test';
import assert from 'node:assert/strict';
import { environment, flush, deferred } from './dom.mjs';

async function receiveScreen(handler = () => undefined, signedIn = false) {
  const auth = { user: signedIn ? { id: 2 } : null };
  const info = { transferId: 9, productId: 101, status: 'PENDING', productTitle: '비공개 제품', fromUserNickname: '발신자', expiresAt: new Date(Date.now() + 3600000).toISOString(), canAccept: true };
  const env = environment((path, options) => handler(path, options) ?? info);
  env.window.location.search = '?token=private-token'; env.window.location.pathname = '/receive';
  env.window.history = { replaceState() { env.window.location.search = ''; } };
  env.window.PastportApi = { fetch: env.window.fetch };
  const page = env.add('section', { id: 'receive' });
  const root = env.add('div', { className: 'rc' }, page);
  const nodes = {};
  for (const name of ['message', 'login', 'accept', 'retry', 'title', 'seller', 'expiration', 'time', 'home']) {
    nodes[name] = env.add(['login', 'accept', 'retry', 'home'].includes(name) ? 'button' : 'p', { ['data-receive-' + name]: '' }, root);
  }
  env.run('receive.js', { auth, setTimeout: () => 1, clearTimeout() {} }); await flush();
  return { ...env, auth, nodes, info };
}

test('비회원은 요약 API를 호출하지 않고 로그인 후 같은 토큰을 재검증한다', async () => {
  const s = await receiveScreen();
  assert.equal(s.calls.length, 0);
  assert.equal(s.nodes.accept.disabled, true);
  s.auth.user = { id: 2 };
  await s.document.emit('pastport:auth-changed', { signedIn: true }); await flush();
  assert.ok(s.calls.some(call => call.path.includes('verify?token=private-token')));
  assert.equal(s.calls.some(call => call.options.method === 'POST'), false);
  assert.equal(s.nodes.title.textContent, '비공개 제품');
});

test('수락 응답은 이전 완료가 아니라 최종 승인 대기를 표시하고 중복 클릭을 막는다', async () => {
  const response = deferred();
  const s = await receiveScreen((path, options) => options.method === 'POST' ? response.promise : undefined, true);
  const first = s.nodes.accept.emit('click'); const duplicate = s.nodes.accept.emit('click');
  response.resolve({ recipientAcceptedAt: new Date().toISOString(), stage: 'AWAITING_OWNER_APPROVAL' });
  await Promise.all([first, duplicate]); await flush();
  assert.equal(s.calls.filter(call => call.options.method === 'POST').length, 1);
  assert.match(s.nodes.message.textContent, /최종 승인 후/);
  assert.doesNotMatch(s.nodes.message.textContent, /이전이 완료되었습니다/);
  assert.equal(s.nodes.accept.disabled, true);
});

test('계정 전환 뒤 늦게 도착한 이전 계정의 요약은 표시하지 않는다', async () => {
  const pending = deferred(); let count = 0;
  const s = await receiveScreen(() => ++count === 1 ? pending.promise : { response: { ok: false, status: 403, async json() { return {}; } } }, true);
  const requestsBeforeSwitch = s.calls.length;
  s.auth.user = { id: 3 };
  await s.document.emit('pastport:auth-changed', { signedIn: true }); await flush();
  pending.resolve(s.info); await flush();
  assert.doesNotMatch(s.nodes.title.textContent, /비공개 제품/);
  assert.doesNotMatch(s.nodes.seller.textContent, /발신자/);
  assert.equal(s.calls.length, requestsBeforeSwitch);
  assert.equal(s.nodes.accept.disabled, true);
});

test('소유자 화면은 확인한 수신자·제품 버전으로 최종 승인한다', async () => {
  const transfer = { transferId: 9, productId: 101, status: 'PENDING', recipientId: 2, recipientNickname: '수신자', productTitle: '제품', factsVersion: 7, recipientAcceptedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 3600000).toISOString(), expired: false };
  const env = environment((path, options) => options.method === 'POST' ? { transferId: 9 } : transfer);
  env.window.PastportApi = { fetch: env.window.fetch };
  env.add('section', { 'data-ownership-root': '' }, env.add('section', { id: 'passport', className: 'page active' })); env.run('ownership.js', { auth: { user: { id: 1 } } });
  await env.document.emit('pastport:product-detail-rendered', { product: { productId: 101 } }); await flush();
  const approve = env.document.querySelector('[data-ownership-root]').querySelectorAll('button').find(button => button.textContent.includes('최종 이전 승인'));
  await approve.emit('click'); await flush();
  const request = env.calls.find(call => call.path.endsWith('/9/approve'));
  assert.deepEqual(JSON.parse(request.options.body), { expectedRecipientId: 2, expectedFactsVersion: 7 });
  assert.match(env.document.querySelector('[data-ownership-message]').textContent, /이전이 완료/);
});

test('최종 승인 응답 유실은 당사자 상태 조회로 복구한다', async () => {
  const transfer = { transferId: 9, productId: 101, status: 'PENDING', recipientId: 2, recipientNickname: '수신자', productTitle: '제품', factsVersion: 7, recipientAcceptedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 3600000).toISOString(), expired: false };
  const env = environment((path, options) => {
    if (options.method === 'POST') throw new Error('응답 유실');
    return path.endsWith('/9') ? { status: 'COMPLETED' } : transfer;
  });
  env.window.PastportApi = { fetch: env.window.fetch };
  env.add('section', { 'data-ownership-root': '' }, env.add('section', { id: 'passport', className: 'page active' })); env.run('ownership.js', { auth: { user: { id: 1 } } });
  await env.document.emit('pastport:product-detail-rendered', { product: { productId: 101 } }); await flush();
  const approve = env.document.querySelector('[data-ownership-root]').querySelectorAll('button').find(button => button.textContent.includes('최종 이전 승인'));
  await approve.emit('click'); await flush();
  assert.ok(env.calls.some(call => call.path === '/api/v1/ownership/transfers/9'));
  assert.match(env.document.querySelector('[data-ownership-message]').textContent, /이전이 완료/);
});

test('계정 전환 뒤 늦게 도착한 승인 응답은 이전 화면을 복원하지 않는다', async () => {
  const pending = deferred();
  const transfer = { transferId: 9, productId: 101, status: 'PENDING', recipientId: 2, recipientNickname: '수신자', productTitle: '비공개 제품', factsVersion: 7, recipientAcceptedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 3600000).toISOString(), expired: false };
  const auth = { user: { id: 1 } };
  const env = environment((path, options) => options.method === 'POST' ? pending.promise : transfer);
  env.window.PastportApi = { fetch: env.window.fetch };
  const root = env.add('section', { 'data-ownership-root': '' }, env.add('section', { id: 'passport', className: 'page active' })); env.run('ownership.js', { auth });
  await env.document.emit('pastport:product-detail-rendered', { product: { productId: 101 } }); await flush();
  const approve = root.querySelectorAll('button').find(button => button.textContent.includes('최종 이전 승인'));
  const mutation = approve.emit('click'); await flush();
  auth.user = { id: 3 };
  await env.document.emit('pastport:auth-changed', { signedIn: true }); await flush();
  pending.resolve({ transferId: 9 }); await mutation; await flush();
  assert.equal(root.textContent, '');
});
