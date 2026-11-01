import test from 'node:test';
import assert from 'node:assert/strict';
import { environment, deferred, flush } from './dom.mjs';

const room = id => ({ roomId: id, listingId: id + 10, listingStatus: 'ACTIVE', listingTitle: '상품 ' + id, counterpartyNickname: '상대방' });

/** 실제 deals 모듈에 목록·방 전환 이벤트를 보내는 시험 화면을 만든다. */
async function screen(override = () => undefined, active = 'deals') {
  const state = { user: 1, rooms: [room(1), room(2)], cleared: 0 };
  const env = environment(async (path, options, calls) => {
    const custom = override(path, options, calls, state);
    if (custom !== undefined) return custom;
    if (path === '/api/auth/me') return { id: state.user };
    if (path.startsWith('/api/v1/chat-rooms?')) return { items: state.rooms };
    if (path.includes('/messages?')) return { items: [] };
    if (path.includes('/purchase-requests?')) return { items: [], roomContext: { listingStatus: 'ACTIVE' } };
    if (path.startsWith('/api/v1/listings/')) return { canManage: false, content: { price: 30000 } };
    if (path.endsWith('/chat-rooms')) return { roomId: 1 };
    return {};
  });
  const page = env.add('section', { id: 'deals', className: 'page' + (active === 'deals' ? ' active' : '') });
  env.add('section', { id: 'detail', className: 'page' + (active === 'detail' ? ' active' : '') });
  const grid = env.add('div', { className: 'dl' }, page);
  const nodes = {};
  for (const name of ['rooms', 'scope', 'all', 'messages', 'completion', 'room-title', 'listing-status', 'info-title', 'info-price', 'info-trade-status', 'chat-section', 'info', 'request-buy', 'leave', 'back', 'input', 'send']) {
    nodes[name] = env.add(['leave', 'back', 'send', 'request-buy'].includes(name) ? 'button' : 'div', { ['data-deals-' + name]: '' }, grid);
  }
  env.window.PastportTradeCompletion = { clear() { state.cleared++; }, async mount() {} };
  env.run('chat-room-list.js');
  env.run('deals.js');
  await env.document.emit('pastport:page-loaded', { id: 'deals' });
  await flush();
  return { ...env, state, nodes, grid, async select(index) { await nodes.rooms.children[index].emit('click'); } };
}

test('계정 A의 늦은 목록 성공은 계정 B의 목록을 덮지 않는다', async () => {
  const pending = deferred(); let first = true;
  const s = await screen(path => {
    if (path.startsWith('/api/v1/chat-rooms?') && first) { first = false; return pending.promise; }
  });
  s.state.user = 2; s.state.rooms = [room(7)];
  await s.document.emit('pastport:auth-changed', { signedIn: true, userId: 2 }); await flush();
  pending.resolve({ items: [room(99)] }); await flush();
  assert.match(s.nodes.rooms.children[0].textContent, /상품 7/);
  assert.doesNotMatch(s.nodes.rooms.children[0].textContent, /상품 99/);
  assert.equal(s.calls.find(x => x.path.startsWith('/api/v1/chat-rooms?')).options.signal.aborted, true);
});

test('계정 A의 늦은 목록 오류는 계정 B의 화면을 지우지 않는다', async () => {
  const pending = deferred(); let first = true;
  const s = await screen(path => { if (path.startsWith('/api/v1/chat-rooms?') && first) { first = false; return pending.promise; } });
  s.state.user = 2; s.state.rooms = [room(7)];
  await s.document.emit('pastport:auth-changed', { signedIn: true }); await flush();
  pending.reject(new Error('old account failure')); await flush();
  assert.match(s.nodes.rooms.children[0].textContent, /상품 7/);
  assert.equal(s.nodes.rooms.innerHTML, '');
});

test('로그아웃 뒤 도착한 사용자 조회도 목록 요청을 다시 시작하지 않는다', async () => {
  const pending = deferred(); let first = true;
  const s = await screen(path => { if (path === '/api/auth/me' && first) { first = false; return pending.promise; } });
  await s.document.emit('pastport:auth-changed', { signedIn: false });
  pending.resolve({ id: 1 }); await flush();
  assert.equal(s.calls.filter(x => x.path.startsWith('/api/v1/chat-rooms?')).length, 0);
  assert.equal(s.nodes.rooms.children.length, 0);
});

test('이전 방의 정지된 폴링과 finally가 새 방의 갱신·busy 상태를 바꾸지 않는다', async () => {
  const a = deferred(), b = deferred(); let holdA = false, holdB = false;
  const s = await screen(path => {
    if (path.includes('/chat-rooms/1/messages?') && holdA) return a.promise;
    if (path.includes('/chat-rooms/2/messages?') && holdB) return b.promise;
  });
  await s.select(0); holdA = true;
  const oldTick = [...s.intervals.values()][0]();
  await s.select(1);
  const latest = [...s.intervals.values()][0]; holdB = true;
  const newTick = latest();
  a.resolve({ items: [] }); await oldTick;
  const count = s.calls.filter(x => x.path.includes('/chat-rooms/2/messages?')).length;
  await latest();
  assert.equal(s.calls.filter(x => x.path.includes('/chat-rooms/2/messages?')).length, count);
  b.resolve({ items: [] }); await newTick; holdB = false;
  await latest();
  assert.equal(s.calls.filter(x => x.path.includes('/chat-rooms/2/messages?')).length, count + 1);
});

/** 나가기 확인 팝업(body 끝에 추가됨)에서 '나가기' 또는 '취소'를 누른다. 대역 DOM은 innerHTML을 해석하지 않으므로 클릭 대상을 직접 지정한다. */
async function answerLeaveConfirm(s, ok) {
  const overlay = s.document.body.children.at(-1);
  const choice = ok ? '[data-confirm-ok]' : '[data-confirm-cancel]';
  await overlay.emit('click', { target: { closest: selector => selector === choice ? {} : null } });
  await flush();
}

/** 나가기 버튼 클릭은 deals.js가 document에서 위임 처리하므로, 대역 DOM에는 버블링 대신 문서로 직접 보낸다. */
async function clickLeave(s) {
  await s.document.emit('click', undefined, { target: { closest: selector => selector === '#deals [data-deals-leave]' ? {} : null } });
  await flush();
}

/** 나가기 확인 팝업이 붙을 수 있도록 대역 문서에 body를 만든다. */
function withBody(s) {
  s.document.body = s.document.createElement('body');
  s.document.root.append(s.document.body);
  return s;
}

test('방을 나가면 메시지·완료 확인·갱신을 정리하고 모바일 목록을 복구한다', async () => {
  const s = withBody(await screen()); await s.select(0);
  assert.equal(s.grid.classList.contains('dl-has-room'), true);
  await clickLeave(s);
  await answerLeaveConfirm(s, true);
  assert.equal(s.calls.some(x => x.path === '/api/v1/chat-rooms/1' && x.options.method === 'DELETE'), true);
  assert.equal(s.grid.classList.contains('dl-has-room'), false);
  assert.equal(s.nodes['chat-section'].style.display, 'none');
  assert.equal(s.intervals.size, 0);
  assert.ok(s.state.cleared > 0);
});

test('나가기 확인에서 취소하면 서버 요청 없이 대화를 그대로 유지한다', async () => {
  const s = withBody(await screen()); await s.select(0);
  await clickLeave(s);
  await answerLeaveConfirm(s, false);
  assert.equal(s.calls.some(x => x.options.method === 'DELETE'), false);
  assert.equal(s.grid.classList.contains('dl-has-room'), true);
});

test('문의 POST 중 다른 판매글로 이동하면 이전 대화로 이동하지 않는다', async () => {
  const pending = deferred();
  const s = await screen(path => path.endsWith('/chat-rooms') ? pending.promise : undefined, 'detail');
  const opening = s.window.PastportDeals.openRoomForListing(5);
  s.window.location.href = 'http://test.invalid/?listingId=6';
  pending.resolve({ roomId: 1 }); await opening;
  assert.deepEqual(s.navigations, []);
});

test('문의 POST 중 계정이 바뀌면 이전 계정의 후속 이동을 막는다', async () => {
  const pending = deferred();
  const s = await screen(path => path.endsWith('/chat-rooms') ? pending.promise : undefined, 'detail');
  const opening = s.window.PastportDeals.openRoomForListing(5);
  await s.document.emit('pastport:auth-changed', { signedIn: true, userId: 2 });
  pending.resolve({ roomId: 1 }); await opening;
  assert.deepEqual(s.navigations, []);
});

test('같은 계정·판매글의 문의는 정상적으로 대화를 열고 가격을 표시한다', async () => {
  const s = await screen(undefined, 'detail');
  await s.window.PastportDeals.openRoomForListing(5); await flush();
  assert.deepEqual(s.navigations, ['deals']);
  assert.equal(s.nodes['chat-section'].style.display, 'flex');
  assert.equal(s.nodes['info-price'].textContent, '30,000원');
});

test('판매글별 방을 열고 목록을 기다리는 중 계정을 바꿔도 이전 방을 선택하지 않는다', async () => {
  const pending = deferred(); let hold = true;
  const s = await screen(path => path.startsWith('/api/v1/chat-rooms?') && hold ? pending.promise : undefined, 'detail');
  s.window.PastportDeals.openSummary(room(1)); await flush();
  hold = false; s.state.user = 2; s.state.rooms = [room(8)];
  await s.document.emit('pastport:auth-changed', { signedIn: true, userId: 2 }); await flush();
  pending.resolve({ items: [room(1)] }); await flush();
  assert.match(s.nodes.rooms.children[0].textContent, /상품 8/);
  assert.equal(s.grid.classList.contains('dl-has-room'), false);
});

test('이전 계정의 늦은 메시지 401 응답은 새 계정의 대화를 초기화하지 않는다', async () => {
  const pending = deferred(); let hold = false;
  const s = await screen(path => path.includes('/chat-rooms/1/messages?') && hold ? pending.promise : undefined);
  await s.select(0); hold = true;
  const tick = [...s.intervals.values()][0]();
  s.state.user = 2; s.state.rooms = [room(8)];
  await s.document.emit('pastport:auth-changed', { signedIn: true }); await flush(); await s.select(0);
  pending.resolve({ response: { ok: false, status: 401, async json() { return { message: 'old unauthorized' }; } } });
  await tick;
  assert.equal(s.grid.classList.contains('dl-has-room'), true);
  assert.match(s.nodes['room-title'].innerHTML, /상품 8/);
  assert.equal(s.intervals.size, 1);
});

test('공유 목록의 더 보기로 첫 20개 뒤의 방도 선택할 수 있다', async () => {
  const s = await screen(path => {
    if (!path.startsWith('/api/v1/chat-rooms?')) return;
    const page = Number(new URLSearchParams(path.split('?')[1]).get('page'));
    return { items: Array.from({ length: 45 }, (_, i) => room(i + 1)).slice(page * 20, page * 20 + 20), page: { number: page, hasNext: page < 2 } };
  });
  assert.equal(s.nodes.rooms.querySelectorAll('.dl-row').length, 20);
  await s.nodes.rooms.querySelector('[data-chat-more]').emit('click');
  await s.nodes.rooms.querySelector('[data-chat-more]').emit('click');
  assert.equal(s.nodes.rooms.querySelectorAll('.dl-row').length, 45);
  assert.equal(s.nodes.rooms.querySelector('[data-chat-more]'), null);
  await s.nodes.rooms.querySelectorAll('.dl-row')[44].emit('click');
  assert.match(s.nodes['room-title'].innerHTML, /상품 45/);
});

test('판매글별 목록 진입과 전체 채팅 재진입은 범위와 선택 방을 각각 갱신한다', async () => {
  const s = await screen(undefined, 'detail');
  s.window.PastportDeals.openSummary(room(1)); await flush();
  assert.match(s.nodes.scope.textContent, /상품 1/);
  assert.equal(s.nodes.all.hidden, false);
  assert.ok(s.calls.some(x => x.path.includes('listingId=11')));
  await s.document.emit('pastport:page-shown', { id: 'deals' }); await flush();
  assert.equal(s.nodes.scope.textContent, '전체 채팅');
  assert.equal(s.nodes.all.hidden, true);
  assert.equal(s.grid.classList.contains('dl-has-room'), false);
  assert.equal(s.intervals.size, 0);
});

test('최초 채팅 HTML이 늦게 도착해도 판매글별 진입 대상을 유지한다', async () => {
  const s = await screen(undefined, 'detail');
  s.grid.remove();
  s.window.PastportDeals.openSummary(room(1)); await flush();
  assert.equal(s.intervals.size, 0);
  s.document.querySelector('#deals').append(s.grid);
  await s.document.emit('pastport:page-loaded', { id: 'deals' }); await flush();
  assert.match(s.nodes.scope.textContent, /상품 1/);
  assert.match(s.nodes['room-title'].innerHTML, /상품 1/);
  assert.equal(s.intervals.size, 1);
});

test('메시지 전송 중 중복 클릭을 막고 전송 완료 후 버튼을 복구한다', async () => {
  const pending = deferred();
  const s = await screen((path, options) => path.endsWith('/messages') && options.method === 'POST' ? pending.promise : undefined);
  await s.select(0); s.nodes.input.value = '첫 메시지';
  const sending = s.nodes.send.emit('click');
  s.nodes.input.value = '중복'; await s.nodes.send.emit('click');
  assert.equal(s.calls.filter(x => x.path.endsWith('/messages') && x.options.method === 'POST').length, 1);
  assert.equal(s.nodes.send.disabled, true);
  pending.resolve({}); await sending;
  assert.equal(s.nodes.send.disabled, false);
});

test('읽음 저장이 성공하면 공유 목록의 안 읽음 표시도 제거한다', async () => {
  const s = await screen((path, options, calls, state) => {
    if (path.startsWith('/api/v1/chat-rooms?')) { state.rooms[0].hasUnreadMessages = true; return { items: state.rooms }; }
    if (path.includes('/messages?')) return { items: [{ messageId: 7, senderId: 2, content: 'hello', createdAt: '2026-10-30T00:00:00Z' }] };
  });
  assert.ok(s.nodes.rooms.children[0].querySelector('.dl-dot'));
  await s.select(0);
  assert.equal(s.nodes.rooms.children[0].querySelector('.dl-dot'), null);
  assert.ok(s.calls.some(x => x.path === '/api/v1/chat-rooms/1/read-position'));
});
