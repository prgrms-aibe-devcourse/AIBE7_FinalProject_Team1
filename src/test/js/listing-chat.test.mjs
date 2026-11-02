import test from 'node:test';
import assert from 'node:assert/strict';
import { environment, flush } from './dom.mjs';

/** 판매글 상세와 정식 공유 목록을 함께 실행해 판매자·구매자 진입 계약을 검사한다. */
async function screen({ owner = true, status = 'ACTIVE', failRooms = false } = {}) {
  const opened = [], inquiries = [];
  const env = environment(path => {
    if (path.startsWith('/api/v1/chat-rooms?')) {
      if (failRooms) return { response: { ok: false, status: 500, async json() { return { message: 'test-only list failure' }; } } };
      return { items: [{ roomId: 9, listingId: 5, listingTitle: '판매글', counterpartyNickname: '<b>구매자</b>', listingStatus: status }], page: { number: 0, hasNext: false } };
    }
    return { listingId: 5, canManage: owner, status, content: { price: 12341234 }, history: { items: [], page: { number: 0, hasNext: false } } };
  });
  env.window.history = { state: null, replaceState(state, title, url) { env.window.location.href = String(url); } };
  const detail = env.add('section', { id: 'detail', className: 'page active' });
  const nodes = {};
  for (const name of ['content', 'login', 'more', 'status', 'retry', 'chat', 'chats', 'chat-rooms', 'chats-open', 'actionbar', 'action-price', 'action-state']) nodes[name] = env.add('div', { ['data-listing-' + name]: '' }, detail);
  env.window.PastportListingDetail = { render(root) { root.textContent = '판매글 본문'; } };
  env.window.PastportProductQuestions = { clear() {}, mount(root) { const panel = env.add('section', { className: 'product-questions' }, root); env.add('textarea', {}, panel); } };
  env.window.PastportDeals = { openSummary(room) { opened.push(room); }, async openRoomForListing(id) { inquiries.push(id); } };
  env.run('chat-room-list.js'); env.run('listing-published.js');
  await env.document.emit('DOMContentLoaded'); await flush();
  return { ...env, nodes, opened, inquiries };
}

test('판매자는 자신의 판매글별 공유 목록에서 바로 대화를 열 수 있다', async () => {
  const s = await screen();
  assert.equal(s.nodes.chat.hidden, true);
  assert.equal(s.nodes.chats.hidden, false);
  assert.ok(s.calls.some(x => x.path.includes('listingId=5')));
  await s.nodes['chat-rooms'].querySelector('.dl-row').emit('click');
  assert.equal(s.opened[0].roomId, 9);
  assert.match(s.nodes['chat-rooms'].textContent, /<b>구매자<\/b>/);
  assert.equal(s.nodes['chat-rooms'].querySelector('.t').innerHTML, '');
});

test('과거 판매글의 목록 버튼은 다른 판매글로 바꾼 뒤 대화를 열지 않는다', async () => {
  const s = await screen(); const row = s.nodes['chat-rooms'].querySelector('.dl-row');
  s.window.location.href = 'http://test.invalid/?listingId=6';
  await row.emit('click'); assert.equal(s.opened.length, 0);
});

test('판매자 채팅 목록 조회 실패는 판매글 본문을 지우지 않는다', async () => {
  const s = await screen({ failRooms: true });
  assert.equal(s.nodes.content.textContent, '판매글 본문');
  assert.match(s.nodes['chat-rooms'].textContent, /대화방 목록을 불러오지/);
});

test('예약 중인 글의 구매자는 기존 대화 이어가기만 사용한다', async () => {
  const s = await screen({ owner: false, status: 'RESERVED' });
  assert.equal(s.nodes.chats.hidden, true);
  assert.equal(s.nodes.chat.hidden, false);
  assert.match(s.nodes.chat.textContent, /기존 문의 대화/);
  await s.nodes.chat.onclick(); assert.deepEqual(s.inquiries, [5]);
});

test('구매자 하단에는 조회된 가격과 채팅 진입만 표시하고 받은 채팅 버튼은 숨긴다', async () => {
  const s = await screen({ owner: false });
  assert.equal(s.nodes.actionbar.hidden, false);
  assert.equal(s.nodes['action-price'].textContent, '12,341,234원');
  assert.equal(s.nodes['action-state'].textContent, '판매 중');
  assert.equal(s.nodes.chat.hidden, false);
  assert.equal(s.nodes['chats-open'].hidden, true);
  assert.equal(s.nodes.actionbar.classList.contains('is-owner'), false);
});

test('판매자 하단의 받은 채팅 버튼은 헤더 아래의 기존 채팅 목록으로 이동한다', async () => {
  const s = await screen();
  const header = s.add('header', { className:'site-header' }); header.rect.height = 192;
  assert.equal(s.nodes.actionbar.classList.contains('is-owner'), true);
  assert.equal(s.nodes['chats-open'].hidden, false);
  await s.nodes['chats-open'].onclick();
  assert.equal(s.document.activeElement, s.nodes.chats);
  assert.equal(s.nodes.chats.lastScroll.block, 'start');
  assert.equal(s.nodes.chats.style.scrollMarginTop, '216px');
});

test('이전 판매글의 받은 채팅 버튼은 다른 글로 이동한 뒤에는 동작하지 않는다', async () => {
  const s = await screen();
  s.window.location.href = 'http://test.invalid/?listingId=6';
  await s.nodes['chats-open'].onclick();
  assert.notEqual(s.document.activeElement, s.nodes.chats);
});

test('계정 변경 뒤 조회가 거부되면 이전 가격과 거래 영역을 지운다', async () => {
  const s = await screen({ owner: false });
  s.window.fetch = async () => ({ ok: false, status: 401, json: async () => ({ message: '로그인 필요' }) });
  await s.document.emit('pastport:auth-changed'); await flush();
  assert.equal(s.nodes.actionbar.hidden, true);
  assert.equal(s.nodes['action-price'].textContent, '');
  assert.equal(s.nodes.content.textContent, '');
  assert.equal(s.nodes.login.hidden, false);
  assert.equal(s.nodes['chats-open'].hidden, true);
  assert.equal(s.nodes['chats-open'].onclick, null);
  assert.equal(s.nodes.actionbar.classList.contains('is-owner'), false);
});
