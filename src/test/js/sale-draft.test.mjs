import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { environment, flush, deferred } from './dom.mjs';

// 실제 작성 모듈의 버튼 이벤트와 HTTP 응답을 검사한다. 레이아웃은 실제 브라우저에서 별도로 확인한다.
async function setup({ title = '점검한 노트북', draftValues = {}, images = [{ imageId: 1, url: '/fixture-image' }], failure, confirm = true, selection = false, saved = true, ready = true, productOptions = null, draftList = false } = {}) {
  const draft = { draftId: 7, version: 1, title, description: '충전 동작을 확인했습니다.', price: 100000,
    conditionGrade: 3, selectedImageIds: [1], updatedAt: '2026-11-02T00:00:00Z', ...draftValues };
  const product = { productId: 101, title: '테스트 노트북', manufacturer: '제조사', modelName: '모델',
    specifications: {}, images };
  const page = { number: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false };
  const env = environment((path, options, calls) => {
    const rejected = failure?.(path, options, calls);
    if (rejected) return rejected;
    if (path === '/api/v1/listing-draft-products') return productOptions || [{ productId: 101, title: product.title }];
    if (path.startsWith('/api/v1/listing-drafts?')) return { items: [], page };
    if (path.endsWith('/composition')) return product;
    if (path.endsWith('/ai-suggestion')) return { productId:101, title:'AI가 제안한 제목', description:'등록된 점검 이력이 있는 노트북입니다.', sample:false };
    if (path === '/api/v1/products/101/images' && options.method === 'POST') {
      product.images = [...product.images, {imageId:product.images.length + 2, url:'/fixture-image'}]; return product;
    }
    if (path.includes('/preview?')) return { savedAt:draft.updatedAt, content:draft, images:[], product, history:{status:'READY',items:[],page,validCount:0,voidCount:0} };
    if (path.includes('/publication?')) return { product, history: { status: 'READY', items: [], page, validCount: 0, voidCount: 0 } };
    if (path === '/fixture-image') return { response: { ok: true, blob: async () => new Blob(['fixture']) } };
    if (path.endsWith('/listings')) return { listingId: 55 };
    if (!saved && path === '/api/v1/products/101/listing-draft' && !options.method) return {response:{ok:false,status:404,json:async()=>({})}};
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
    if (tag[1] === 'dialog') {
      node.showModal = () => { node.open = true; if (node.matches('[data-sale-confirm]')) queueMicrotask(() => {
        node.returnValue = confirm ? 'confirm' : 'cancel'; node.close();
      }); };
      node.close = () => { node.open = false; return node.emit('close'); };
    }
  }
  env.window.location.href = draftList ? 'http://test.invalid/?drafts=1' : selection ? 'http://test.invalid/' : 'http://test.invalid/?draftProduct=101';
  env.window.history = { state: {}, replaceState(state, _, url) { env.window.location.href = String(url); } };
  env.window.PastportListingDetail = { renderTimeline() {}, render(root) { root.textContent = '판매글 미리보기'; } };
  env.window.PastportPublishedListing = { open(id) { env.openedListing = id; } };
  env.run('sale-draft.js', { Headers, FormData, show() {}, clearTimeout, setTimeout,
    Option: function (text, value) { const option = env.document.createElement('option'); option.textContent = text; option.value = String(value); return option; } });
  await flush();
  env.$ = selector => root.querySelector(selector);
  if (selection) { env.$('[data-sale-product]').value = '101'; await env.$('[data-sale-product]').emit('change'); }
  if (ready) assert.equal(env.$('[data-sale-publish]').disabled, false, '제품과 저장본을 불러와야 한다');
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

const alreadyOpen = () => ({response:{ok:false,status:409,json:async()=>({code:'SALE_002',message:'이미 판매 중이거나 예약 중인 판매글이 있습니다.'})}});

test('제품 선택 때 열린 판매글이 있으면 작성·AI 요청 전에 안내하고 다음을 막는다', async () => {
  const env = await setup({selection:true,saved:false,ready:false,failure:path=>path.endsWith('/composition') && alreadyOpen()});
  assert.equal(env.$('[data-sale-select-panel]').hidden, false);
  assert.equal(env.$('[data-sale-compose-panel]').hidden, true);
  assert.equal(env.$('[data-sale-next]').disabled, true);
  assert.match(env.$('[data-sale-error]').textContent, /이미 판매 중이거나 예약 중인/);
  assert.equal(env.document.activeElement, env.$('[data-sale-error]'));
  assert.equal(env.$('[data-sale-error]').lastScroll.block, 'nearest');
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/ai-suggestion') || call.path.endsWith('/listing-draft')).length, 0);
});

test('작성 중 다른 곳에서 게시되면 첫 게시 버튼에서 저장·미리보기 전에 차단한다', async () => {
  let blocked = false;
  const env = await setup({failure:path=>path.endsWith('/composition') && blocked && alreadyOpen()});
  blocked = true;
  await env.$('[data-sale-publish]').emit('click');
  assertActionError(env, /이미 판매 중이거나 예약 중인/);
  assert.notEqual(env.$('[data-sale-preview-dialog]').open, true);
  assert.equal(env.$('[data-sale-title]').value, '점검한 노트북');
  assert.equal(env.calls.filter(call => call.options.method === 'PUT' || call.path.includes('/preview?') || call.path.endsWith('/listings')).length, 0);
  blocked = false;
  await env.$('[data-sale-publish]').emit('click');
  assert.equal(env.$('[data-sale-preview-dialog]').open, true);
  assert.equal(env.calls.filter(call => call.options.method === 'PUT').length, 1);
});

test('사전 검사 이후 서버 미리보기에서 중복 판매를 감지해도 모달을 열지 않는다', async () => {
  const env = await setup({failure:path=>path.includes('/preview?') && alreadyOpen()});
  await env.$('[data-sale-publish]').emit('click');
  assertActionError(env, /임시저장은 완료했지만.*이미 판매 중이거나 예약 중인/);
  assert.notEqual(env.$('[data-sale-preview-dialog]').open, true);
  assert.equal(env.calls.filter(call => call.options.method === 'PUT').length, 1);
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 0);
});

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

test('등록된 제품 사진이 없으면 카메라에서 추가하도록 안내한다', async () => {
  const env = await setup({ images: [], draftValues: { selectedImageIds: [] } });
  await env.$('[data-sale-publish]').emit('click');
  assertFieldError(env, 'photos', /카메라 버튼을 눌러 제품 사진을 추가/);
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
  await env.$('[data-sale-preview-publish]').emit('click');
  assert.equal(env.$('[data-sale-preview-error]').hidden, false);
  assert.match(env.$('[data-sale-preview-error]').textContent, /현재 상태에서는 게시할 수 없어요/);
  assert.equal(env.document.activeElement, env.$('[data-sale-preview-error]'));
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
  await env.$('[data-sale-preview-publish]').emit('click');
  assert.match(env.$('[data-sale-preview-error]').textContent, /게시 결과 다시 확인/);
  assert.equal(env.$('[data-sale-preview-close]').disabled, true);
  await env.$('[data-sale-preview-publish]').emit('click');
  const requests = env.calls.filter(call => call.path.endsWith('/listings'));
  assert.equal(requests.length, 2);
  assert.equal(requests[0].options.body, requests[1].options.body);
  assert.equal(env.calls.filter(call => call.options.method === 'PUT').length, 1);
  assert.equal(env.openedListing, 55);
  assert.equal(env.$('[data-sale-action-error]').hidden, true);
});

test('미리보기에서 돌아가면 임시저장만 하고 게시하지 않는다', async () => {
  const env = await setup();
  await env.$('[data-sale-publish]').emit('click');
  assert.equal(env.$('[data-sale-preview-dialog]').open, true);
  await env.$('[data-sale-preview-close]').emit('click');
  assert.equal(env.calls.filter(call => call.options.method === 'PUT').length, 1);
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 0);
  assert.equal(env.$('[data-sale-action-error]').hidden, true);
});

test('임시저장 실패도 하단 작업 영역에서 확인한다', async () => {
  const env = await setup({ failure: (path, options) => options.method === 'PUT' && reject('저장에 실패했어요.') });
  await env.$('[data-sale-form]').emit('submit');
  assertActionError(env, /저장에 실패했어요/);
});

test('미리보기 조회 실패도 하단 작업 영역에서 확인한다', async () => {
  const env = await setup({ failure: path => path.includes('/preview?') && reject('미리보기를 준비하지 못했어요.') });
  await env.$('[data-sale-publish]').emit('click');
  assertActionError(env, /임시저장은 완료했지만 미리보기를 열지 못했어요/);
  await env.$('[data-sale-preview-publish]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 0);
});

test('제품 선택과 내용 작성은 다음·이전으로 이동하며 입력값을 보존한다', async () => {
  const env = await setup({selection:true});
  assert.equal(env.$('[data-sale-select-panel]').hidden, false);
  assert.equal(env.$('[data-sale-compose-panel]').hidden, true);
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.$('[data-sale-compose-panel]').hidden, false);
  env.$('[data-sale-title]').value = '작성하던 제목';
  await env.$('[data-sale-back]').emit('click');
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.$('[data-sale-title]').value, '작성하던 제목');
});

test('작성 화면 게시 버튼은 저장·미리보기만 하고 최종 버튼만 게시한다', async () => {
  const env = await setup();
  await env.$('[data-sale-preview-publish]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 0);
  await env.$('[data-sale-publish]').emit('click');
  assert.equal(env.$('[data-sale-preview-dialog]').open, true);
  assert.equal(env.calls.filter(call => call.path.includes('/preview?')).length, 1);
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 0);
  await env.$('[data-sale-preview-publish]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 1);
  assert.equal(env.calls.filter(call => call.options.method === 'PUT').length, 1);
  assert.equal(env.openedListing, 55);
});

test('미리보기 후 수정하면 새 저장본을 미리보기로 다시 확인해야 한다', async () => {
  const env = await setup();
  await env.$('[data-sale-publish]').emit('click');
  await env.$('[data-sale-preview-close]').emit('click');
  env.$('[data-sale-title]').value = '수정한 제목';
  await env.$('[data-sale-form]').parent.emit('input', {target:env.$('[data-sale-title]')});
  await env.$('[data-sale-preview-publish]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 0);
  await env.$('[data-sale-publish]').emit('click');
  await env.$('[data-sale-preview-publish]').emit('click');
  assert.equal(env.calls.filter(call => call.options.method === 'PUT').length, 2);
  assert.equal(env.openedListing, 55);
});

test('제품 선택 다음 단계에서 AI 초안을 자동 적용하고 저장·게시는 하지 않는다', async () => {
  const env = await setup({selection:true, saved:false});
  assert.equal(env.calls.filter(call => call.path.endsWith('/ai-suggestion')).length, 0);
  env.$('[data-sale-price]').value = '123000';
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/ai-suggestion')).length, 1);
  assert.notEqual(env.$('[data-sale-ai-dialog]').open, true);
  assert.equal(env.$('[data-sale-title]').value, 'AI가 제안한 제목');
  assert.equal(env.$('[data-sale-description]').value, '등록된 점검 이력이 있는 노트북입니다.');
  assert.equal(env.$('[data-sale-price]').value, '123000');
  assert.equal(env.$('[data-sale-camera-count]').textContent, '0/10');
  assert.equal(env.$('[data-sale-ai-panel]').getAttribute('aria-busy'), 'false');
  assert.match(env.$('[data-sale-ai-status]').textContent, /AI 초안을 채웠어요/);
  assert.equal(env.calls.filter(call => ['PUT','POST'].includes(call.options.method) && !call.path.endsWith('/ai-suggestion')).length, 0);
  await env.$('[data-sale-back]').emit('click');
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/ai-suggestion')).length, 1);
});

test('저장한 초안은 다음 단계에서도 자동 AI 요청 없이 보존한다', async () => {
  const env = await setup({selection:true});
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/ai-suggestion')).length, 0);
  assert.equal(env.$('[data-sale-title]').value, '점검한 노트북');
  assert.match(env.$('[data-sale-ai-status]').textContent, /저장한 제목과 설명/);
  await env.$('[data-sale-write-manual]').emit('click');
  assert.equal(env.$('[data-sale-title]').value, '점검한 노트북');
});

test('자동 AI 실패 사유를 팝업과 작성란에 표시하고 직접 작성으로 전환한다', async () => {
  const env = await setup({selection:true,saved:false,failure:path => path.endsWith('/ai-suggestion') && {response:{ok:false,status:502,json:async()=>({code:'AI_003',message:'AI 제안이 검증을 통과하지 못했습니다.'})}}});
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.$('[data-sale-ai-dialog]').open, true);
  assert.equal(env.$('[data-sale-ai-status]').getAttribute('role'), 'alert');
  assert.match(env.$('[data-sale-ai-error]').textContent, /검증을 통과하지 못했습니다/);
  await env.$('[data-sale-ai-manual]').emit('click');
  assert.equal(env.$('[data-sale-title]').value, '');
  assert.equal(env.$('[data-sale-ai-dialog]').open, false);
  assert.equal(env.$('[data-sale-save]').disabled, false);
  assert.equal(env.document.activeElement, env.$('[data-sale-title]'));
});

test('생성 중 직접 작성하면 요청을 취소하고 늦은 AI 응답을 적용하지 않는다', async () => {
  const pending = deferred();
  const env = await setup({selection:true,saved:false,failure:path => path.endsWith('/ai-suggestion') && pending.promise});
  const generating = env.$('[data-sale-next]').emit('click');
  await flush();
  assert.equal(env.$('[data-sale-save]').disabled, true);
  assert.equal(env.$('[data-sale-write-manual]').disabled, false);
  await env.$('[data-sale-form]').emit('submit');
  assert.equal(env.calls.filter(call => call.options.method === 'POST' && !call.path.endsWith('/ai-suggestion')).length, 0);
  await env.$('[data-sale-write-manual]').emit('click');
  env.$('[data-sale-title]').value = '직접 쓴 제목';
  pending.resolve({productId:101,title:'늦게 도착한 제목',description:'늦은 설명'});
  await generating;
  assert.equal(env.$('[data-sale-title]').value, '직접 쓴 제목');
  assert.equal(env.calls.find(call => call.path.endsWith('/ai-suggestion')).options.signal.aborted, true);
  assert.equal(env.$('[data-sale-ai-panel]').getAttribute('aria-busy'), 'false');
  await env.$('[data-sale-back]').emit('click');
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/ai-suggestion')).length, 1);
});

test('생성 중 제목을 입력하기만 해도 뒤늦은 AI 응답이 덮어쓰지 않는다', async () => {
  const pending = deferred();
  const env = await setup({selection:true,saved:false,failure:path => path.endsWith('/ai-suggestion') && pending.promise});
  const generating = env.$('[data-sale-next]').emit('click'); await flush();
  env.$('[data-sale-title]').value = '입력한 제목';
  await env.$('[data-sale-form]').parent.emit('input',{target:env.$('[data-sale-title]')});
  pending.resolve({productId:101,title:'늦은 제목',description:'늦은 설명'}); await generating;
  assert.equal(env.$('[data-sale-title]').value, '입력한 제목');
  assert.equal(env.$('[data-sale-description]').value, '');
  assert.equal(env.$('[data-sale-publish]').disabled, false);
});

test('직접 작성 전환은 자동 초안만 비우고 사용자 수정과 가격은 보존한다', async () => {
  const env = await setup({selection:true,saved:false});
  await env.$('[data-sale-next]').emit('click');
  env.$('[data-sale-price]').value = '200000';
  await env.$('[data-sale-write-manual]').emit('click');
  assert.equal(env.$('[data-sale-title]').value, '');
  assert.equal(env.$('[data-sale-description]').value, '');
  assert.equal(env.$('[data-sale-price]').value, '200000');
  await env.$('[data-sale-ai-generate]').emit('click');
  env.$('[data-sale-title]').value = 'AI 초안을 수정한 제목';
  await env.$('[data-sale-write-manual]').emit('click');
  assert.equal(env.$('[data-sale-title]').value, 'AI 초안을 수정한 제목');
  assert.equal(env.$('[data-sale-description]').value, '등록된 점검 이력이 있는 노트북입니다.');
});

test('자동 생성 실패 팝업에서 재시도하면 성공한 초안을 바로 적용한다', async () => {
  let attempts = 0;
  const env = await setup({selection:true,saved:false,failure:path => path.endsWith('/ai-suggestion') && ++attempts === 1 && {response:{ok:false,status:502,json:async()=>({code:'AI_003',message:'다시 시도해 주세요.'})}}});
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.$('[data-sale-ai-dialog]').open, true);
  await env.$('[data-sale-ai-retry]').emit('click');
  assert.equal(env.$('[data-sale-ai-dialog]').open, false);
  assert.equal(env.$('[data-sale-title]').value, 'AI가 제안한 제목');
  assert.equal(env.$('[data-sale-ai-status]').getAttribute('role'), 'status');
  assert.equal(env.calls.filter(call => call.path.endsWith('/ai-suggestion')).length, 2);
});

test('생성 중 제품 선택을 해제하면 이전 제품의 응답을 적용하지 않는다', async () => {
  const pending = deferred();
  const env = await setup({selection:true,saved:false,failure:path => path.endsWith('/ai-suggestion') && pending.promise});
  const generating = env.$('[data-sale-next]').emit('click'); await flush();
  await env.$('[data-sale-back]').emit('click');
  env.$('[data-sale-product]').value = '';
  await env.$('[data-sale-product]').emit('change');
  pending.resolve({productId:101,title:'이전 제품 제목',description:'이전 제품 설명'}); await generating;
  assert.equal(env.$('[data-sale-title]').value, '');
  assert.equal(env.$('[data-sale-compose-panel]').hidden, true);
  assert.equal(env.$('[data-sale-next]').disabled, true);
});

test('사진 선택 해제는 판매글에서만 빼고 제품 삭제 API를 보내지 않는다', async () => {
  const env = await setup();
  await env.$('[data-sale-form]').parent.emit('click',{target:env.$('[data-sale-remove-image]')});
  assert.equal(env.$('[data-sale-camera-count]').textContent, '0/10');
  assert.equal(env.calls.filter(call => call.options.method === 'DELETE').length, 0);
});

test('새 사진은 multipart로 제품에 추가하고 선택 장수와 제목을 보존한다', async () => {
  const env = await setup();
  const input = env.$('[data-sale-photo-files]'); input.files = [new File(['photo'], 'photo.png', {type:'image/png'})];
  await input.emit('change');
  const upload = env.calls.find(call => call.path === '/api/v1/products/101/images' && call.options.method === 'POST');
  assert.ok(upload.options.body instanceof FormData);
  assert.equal(upload.options.headers.has('Content-Type'), false);
  assert.equal(env.$('[data-sale-camera-count]').textContent, '2/10');
  assert.equal(env.$('[data-sale-title]').value, '점검한 노트북');
});

test('11장 업로드와 허용하지 않은 파일은 요청 전에 거부한다', async () => {
  for (const files of [Array.from({length:11},()=>new File(['p'],'p.png',{type:'image/png'})),[new File(['p'],'p.pdf',{type:'application/pdf'})]]) {
    const env = await setup(); const input = env.$('[data-sale-photo-files]'); input.files = files;
    await input.emit('change');
    assert.equal(env.$('[data-sale-photo-error]').hidden, false);
    assert.equal(env.calls.filter(call => call.path === '/api/v1/products/101/images').length, 0);
  }
});

test('여러 사진 중 일부 업로드가 실패해도 성공한 사진과 작성 값이 유지된다', async () => {
  let uploads = 0;
  const env = await setup({failure:(path,options) => path === '/api/v1/products/101/images' && options.method === 'POST' && ++uploads === 2 && reject('두 번째 사진 업로드에 실패했어요.')});
  const input = env.$('[data-sale-photo-files]');
  input.files = [new File(['a'],'a.png',{type:'image/png'}),new File(['b'],'b.png',{type:'image/png'})];
  await input.emit('change');
  assert.equal(env.$('[data-sale-camera-count]').textContent, '2/10');
  assert.match(env.$('[data-sale-photo-error]').textContent, /1장은 추가했어요.*두 번째/);
  assert.equal(env.$('[data-sale-title]').value, '점검한 노트북');
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 0);
});

test('기존 글의 AI 재작성은 확인 후 제목·설명만 바꾸고 저장하지 않는다', async () => {
  const env = await setup({confirm:false});
  await env.$('[data-sale-ai-generate]').emit('click');
  assert.equal(env.calls.filter(call => call.path.endsWith('/ai-suggestion')).length, 0);
  assert.equal(env.$('[data-sale-title]').value, '점검한 노트북');
  const accepted = await setup();
  await accepted.$('[data-sale-ai-generate]').emit('click');
  assert.equal(accepted.$('[data-sale-title]').value, 'AI가 제안한 제목');
  assert.equal(accepted.$('[data-sale-price]').value, '100000');
  assert.equal(accepted.$('[data-sale-camera-count]').textContent, '1/10');
  assert.equal(accepted.calls.filter(call => call.path.endsWith('/ai-suggestion')).length, 1);
  assert.equal(env.calls.filter(call => call.options.method === 'PUT').length, 0);
  assert.equal(accepted.calls.filter(call => call.options.method === 'PUT').length, 0);
});

test('최종 게시 요청 중 다시 눌러도 게시 API는 한 번만 호출한다', async () => {
  const pending = deferred();
  const env = await setup({failure:path => path.endsWith('/listings') && pending.promise});
  await env.$('[data-sale-publish]').emit('click');
  const publishing = env.$('[data-sale-preview-publish]').emit('click');
  await flush();
  assert.equal(env.$('[data-sale-preview-publish]').disabled, true);
  await env.$('[data-sale-preview-publish]').emit('click');
  pending.resolve({listingId:55}); await publishing;
  assert.equal(env.calls.filter(call => call.path.endsWith('/listings')).length, 1);
});

for (const listingStatus of ['ACTIVE', 'RESERVED']) test('제품 선택 목록의 ' + listingStatus + ' 안내는 기존 글로 연결하고 AI 작성 전에 막는다', async () => {
  const env = await setup({selection:true, ready:false, productOptions:[{productId:101,title:'판매 중인 제품',canCompose:false,listingId:55,listingStatus}]});
  assert.equal(env.$('[data-sale-product-blocked]').hidden, false);
  assert.match(env.$('[data-sale-product-blocked-message]').textContent, listingStatus === 'ACTIVE' ? /판매 중/ : /예약 중/);
  assert.equal(env.$('[data-sale-existing-listing]').href, '/?listingId=55');
  assert.equal(env.$('[data-sale-next]').disabled, true);
  await env.$('[data-sale-next]').emit('click');
  assert.equal(env.calls.some(call => call.path.endsWith('/composition') || call.path.endsWith('/ai-suggestion')), false);
});

test('임시저장 바로가기는 기존 저장 목록으로 이동하고 새 초안을 만들지 않는다', async () => {
  const env = await setup({draftList:true,ready:false});
  assert.equal(env.document.activeElement, env.$('[data-sale-drafts-section]'));
  assert.equal(env.$('[data-sale-drafts-section]').lastScroll.block, 'start');
  assert.equal(env.$('[data-sale-select-panel]').hidden, false);
  assert.equal(env.calls.some(call => call.options.method || call.path.endsWith('/ai-suggestion')), false);
});
