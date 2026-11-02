import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { environment, flush } from './dom.mjs';

// 실제 작성 모듈의 버튼 이벤트와 HTTP 응답을 검사한다. 레이아웃은 실제 브라우저에서 별도로 확인한다.
async function setup({ title = '점검한 노트북', draftValues = {}, images = [{ imageId: 1, url: '/fixture-image' }], failure, confirm = true } = {}) {
  const draft = { draftId: 7, version: 1, title, description: '충전 동작을 확인했습니다.', price: 100000,
    conditionGrade: 3, selectedImageIds: [1], updatedAt: '2026-11-02T00:00:00Z', ...draftValues };
  const product = { productId: 101, title: '테스트 노트북', manufacturer: '제조사', modelName: '모델',
    specifications: {}, images };
  const page = { number: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false };
  const env = environment((path, options, calls) => {
    const rejected = failure?.(path, options, calls);
    if (rejected) return rejected;
    if (path === '/api/v1/listing-draft-products') return [{ productId: 101, title: product.title }];
    if (path.startsWith('/api/v1/listing-drafts?')) return { items: [], page };
    if (path.endsWith('/composition')) return product;
    if (path.includes('/publication?')) return { product, history: { status: 'READY', items: [], page, validCount: 0, voidCount: 0 } };
    if (path === '/fixture-image') return { response: { ok: true, blob: async () => new Blob(['fixture']) } };
    if (path.endsWith('/listings')) return { listingId: 55 };
    if (path.includes('/listing-draft')) return { ...draft, version: options.method ? 2 : 1 };
    throw new Error('Unexpected fixture request: ' + path);
  });
  const root = env.add('section', { id: 'sell', className: 'page active' });
  const html = fs.readFileSync('src/main/resources/static/pages/sell.html', 'utf8');
  for (const tag of html.matchAll(/<([a-z][a-z0-9]*)\b([^>]*\bdata-sale-[^>]*)>/g)) {
    const node = env.add(tag[1], {}, root);
    node.value = '';
    for (const attr of tag[2].matchAll(/\b(data-sale-[\w-]+)(?:="([^"]*)")?/g)) {
      node.setAttribute(attr[1], attr[2] || '');
      node.dataset[attr[1].slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = attr[2] || '';
    }
    if (tag[2].includes(' hidden')) node.hidden = true;
    for (const attr of tag[2].matchAll(/\b(id|aria-[\w-]+)="([^"]*)"/g)) node.setAttribute(attr[1], attr[2]);
    if (tag[1] === 'select') {
      Object.defineProperty(node, 'options', { get: () => node.children });
      node.add = option => node.append(option);
    }
    if (node.matches('[data-sale-error]') || node.matches('[data-sale-action-error]')) env.add('span', {}, node);
    node.scrollIntoView = options => { node.lastScroll = options; };
    if (tag[1] === 'dialog') node.showModal = () => queueMicrotask(() => {
      node.returnValue = confirm ? 'confirm' : 'cancel'; node.emit('close');
    });
  }
  env.window.location.href = 'http://test.invalid/?draftProduct=101';
  env.window.history = { state: {}, replaceState(state, _, url) { env.window.location.href = String(url); } };
  env.window.PastportListingDetail = { renderTimeline() {} };
  env.window.PastportPublishedListing = { open(id) { env.openedListing = id; } };
  env.run('sale-draft.js', { Headers, show() {}, clearTimeout, setTimeout,
    Option: function (text, value) { const option = env.document.createElement('option'); option.textContent = text; option.value = String(value); return option; } });
  await flush();
  env.$ = selector => root.querySelector(selector);
  assert.equal(env.$('[data-sale-publish]').disabled, false, '제품과 저장본을 불러와야 한다');
  return env;
}

function assertActionError(env, pattern) {
  const notice = env.$('[data-sale-action-error]');
  assert.equal(notice.hidden, false);
  assert.match(notice.textContent, pattern);
  assert.equal(env.$('[data-sale-error]').hidden, true);
  assert.equal(env.document.activeElement, notice);
  assert.equal(notice.lastScroll.block, 'nearest');
  assert.equal(env.$('[data-sale-publish]').disabled, false);
}

const reject = (message = '현재 상태에서는 게시할 수 없어요.') => ({ response: {
  ok: false, status: 409, json: async () => ({ code: 'SALE_001', message })
} });

test('필수 입력이 빠지면 해당 입력란에 표시하고 게시 요청을 보내지 않는다', async () => {
  const env = await setup({ title: '' });
  await env.$('[data-sale-publish]').emit('click');
  assertFieldError(env, 'title', /제목을 입력해 주세요/);
  assert.equal(env.document.activeElement, env.$('[data-sale-title]'));
  assert.equal(env.calls.filter(call => call.options.method).length, 0);
});

function assertFieldError(env, name, pattern) {
  const field = env.$('[data-sale-' + name + '-field]');
  const notice = env.$('[data-sale-' + name + '-error]');
  assert.equal(field.classList.contains('is-invalid'), true);
  assert.equal(notice.hidden, false);
  assert.match(notice.textContent, pattern);
  assert.equal(env.$('[data-sale-action-error]').hidden, true);
  return notice;
}

test('사진만 빠지면 사진 영역을 강조하고 대표 사진 선택으로 안내한다', async () => {
  const env = await setup({ draftValues: { selectedImageIds: [] } });
  await env.$('[data-sale-publish]').emit('click');
  const notice = assertFieldError(env, 'photos', /대표 사진을 선택해 주세요/);
  assert.equal(env.document.activeElement, env.$('[data-sale-photos-field]'));
  assert.equal(notice.lastScroll.block, 'center');
  assert.equal(env.$('[data-sale-photos-field]').getAttribute('aria-invalid'), 'true');
  await env.$('[data-sale-form]').parent.emit('click', { target: env.$('[data-sale-image]') });
  assert.equal(env.$('[data-sale-photos-error]').hidden, true);
  assert.equal(env.$('[data-sale-photos-field]').classList.contains('is-invalid'), false);
});

test('등록된 제품 사진이 없으면 먼저 제품 사진을 등록하도록 안내한다', async () => {
  const env = await setup({ images: [], draftValues: { selectedImageIds: [] } });
  await env.$('[data-sale-publish]').emit('click');
  assertFieldError(env, 'photos', /내 제품에서 제품 사진을 등록한 뒤/);
  assert.equal(env.document.activeElement, env.$('[data-sale-photos-field]'));
});

test('여러 필수 항목이 비면 모두 표시하고 첫 입력으로 이동하며 수정 시 표시만 갱신한다', async () => {
  const env = await setup({ title: '', draftValues: { price: null, conditionGrade: null, description: '', selectedImageIds: [] } });
  await env.$('[data-sale-publish]').emit('click');
  for (const name of ['title', 'price', 'grade', 'description', 'photos']) assertFieldError(env, name, /입력|선택/);
  const title = env.$('[data-sale-title]');
  assert.equal(env.document.activeElement, title);
  title.value = '제목을 입력함';
  await env.$('[data-sale-form]').parent.emit('input', { target: title });
  assert.equal(env.$('[data-sale-title-error]').hidden, true);
  assert.equal(env.$('[data-sale-title-field]').classList.contains('is-invalid'), false);
  assert.equal(title.getAttribute('aria-invalid'), null);
  assert.equal(env.document.activeElement, title);
  assertFieldError(env, 'photos', /대표 사진을 선택/);
  assert.equal(env.calls.filter(call => call.options.method).length, 0);
});

test('상태 등급이 비면 등급 선택으로 이동하고 선택 즉시 오류를 지운다', async () => {
  const env = await setup({ draftValues: { conditionGrade: null } });
  await env.$('[data-sale-publish]').emit('click');
  assertFieldError(env, 'grade', /제품 상태 등급을 선택/);
  assert.equal(env.document.activeElement, env.$('[data-sale-grade-group]'));
  await env.$('[data-sale-form]').parent.emit('click', { target: env.$('[data-sale-grade]') });
  assert.equal(env.$('[data-sale-grade-error]').hidden, true);
});

test('설명만 비면 상세 설명 입력란으로 이동한다', async () => {
  const env = await setup({ draftValues: { description: '   ' } });
  await env.$('[data-sale-publish]').emit('click');
  assertFieldError(env, 'description', /상세 설명을 입력/);
  assert.equal(env.document.activeElement, env.$('[data-sale-description]'));
});

for (const price of ['0', '-100', '1.5', 'abc']) {
  test('잘못된 가격 ' + price + '는 확인창 전에 가격 입력란에서 안내한다', async () => {
    const env = await setup({ draftValues: { price } });
    await env.$('[data-sale-publish]').emit('click');
    assertFieldError(env, 'price', /1원 이상.*정수/);
    assert.equal(env.document.activeElement, env.$('[data-sale-price]'));
    assert.equal(env.calls.filter(call => call.options.method).length, 0);
  });
}

test('필수 항목이 비어도 임시저장은 계속 허용한다', async () => {
  const env = await setup({ title: '', draftValues: { price: null, description: '', conditionGrade: null, selectedImageIds: [] } });
  await env.$('[data-sale-form]').emit('submit');
  assert.equal(env.calls.filter(call => call.options.method === 'PUT').length, 1);
  for (const name of ['title', 'price', 'grade', 'description', 'photos']) {
    assert.equal(env.$('[data-sale-' + name + '-error]').hidden, true);
  }
});

test('서버가 게시를 거부해도 아래에서 오류를 읽고 입력을 유지한다', async () => {
  const env = await setup({ failure: path => path.endsWith('/listings') && reject() });
  await env.$('[data-sale-publish]').emit('click');
  assertActionError(env, /현재 상태에서는 게시할 수 없어요/);
  assert.equal(env.$('[data-sale-title]').value, '점검한 노트북');
  assert.equal(env.$('[data-sale-description]').value, '충전 동작을 확인했습니다.');
});

test('게시 전 임시저장이 실패해도 같은 위치에 오류를 표시하고 게시하지 않는다', async () => {
  const env = await setup({ failure: (path, options) => options.method === 'PUT' && reject('저장본이 변경됐어요.') });
  await env.$('[data-sale-publish]').emit('click');
  assertActionError(env, /저장본이 변경됐어요/);
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 0);
});

test('응답 유실 안내 뒤 재확인은 같은 게시 요청을 유지하고 성공하면 오류를 지운다', async () => {
  let attempts = 0;
  const env = await setup({ failure(path) { if (path.endsWith('/listings') && ++attempts === 1) throw new TypeError('Failed to fetch'); } });
  await env.$('[data-sale-publish]').emit('click');
  assertActionError(env, /게시 결과 다시 확인/);
  await env.$('[data-sale-publish]').emit('click');
  const requests = env.calls.filter(call => call.path.endsWith('/listings'));
  assert.equal(requests.length, 2);
  assert.equal(requests[0].options.body, requests[1].options.body);
  assert.equal(env.calls.filter(call => call.options.method === 'PUT').length, 1);
  assert.equal(env.openedListing, 55);
  assert.equal(env.$('[data-sale-action-error]').hidden, true);
});

test('확인 팝업에서 취소하면 게시하거나 오류를 표시하지 않는다', async () => {
  const env = await setup({ confirm: false });
  await env.$('[data-sale-publish]').emit('click');
  assert.equal(env.calls.filter(call => call.options.method).length, 0);
  assert.equal(env.$('[data-sale-action-error]').hidden, true);
});

test('임시저장 실패도 하단 작업 영역에서 확인한다', async () => {
  const env = await setup({ failure: (path, options) => options.method === 'PUT' && reject('저장에 실패했어요.') });
  await env.$('[data-sale-form]').emit('submit');
  assertActionError(env, /저장에 실패했어요/);
});

test('미리보기 조회 실패도 하단 작업 영역에서 확인한다', async () => {
  const env = await setup({ failure: path => path.includes('/preview?') && reject('미리보기를 준비하지 못했어요.') });
  await env.$('[data-sale-preview]').emit('click');
  assertActionError(env, /임시저장은 완료했지만 미리보기를 열지 못했어요/);
});
