import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {environment, deferred, flush} from './dom.mjs';

// 실제 등록 HTML과 제품 모듈을 실행한다. 브라우저의 파일·제약 검증과 HTTP만 대체한다.
async function screen({create} = {}) {
  let created = null, menuClicks = 0;
  const env = environment(async (path, options) => {
    if (path === '/api/v1/products' && options.method === 'POST') {
      created = await (create ? create() : {productId: 42}); return created;
    }
    if (path.startsWith('/api/v1/products?')) return {
      items: created ? [{...created, title: '방금 등록한 노트북', manufacturer: 'Samsung', modelName: 'Galaxy Book', createdAt: '2026-11-03T12:00:00Z'}] : [],
      page: {number: 0, hasNext: false}, summary: {registered: created ? 1 : 0, purchased: 0},
    };
    if (path === '/api/v1/products/42/images') return {};
    throw new Error('Unexpected request: ' + path);
  });
  const originalCreate = env.document.createElement.bind(env.document);
  env.document.createElement = tag => {
    const node = originalCreate(tag), matches = node.matches.bind(node);
    node.value = ''; node.files = []; node.validityMessage = ''; node.invalid = false;
    node.matches = selector => {
      const attribute = selector.match(/^\[([^=]+)="([^"]*)"\]$/);
      if (attribute) return node.getAttribute(attribute[1]) === attribute[2];
      if (selector === ':invalid') return node.invalid || !!node.validityMessage;
      return matches(selector);
    };
    Object.defineProperty(node, 'options', {get: () => node.children});
    node.setCustomValidity = text => { node.validityMessage = text; };
    node.reportValidity = () => {
      node.reportedWhileHidden = !!node.closest('[data-register-step]')?.hidden;
      return !node.matches(':invalid');
    };
    return node;
  };
  env.document.body = env.document.root;
  // 실제 상단 메뉴처럼 home 버튼 클릭은 메뉴만 펼치며 화면 전환을 실행하지 않는다.
  const menu = env.add('nav');
  const homeTrigger = env.add('button', {'data-page': 'home'}, menu);
  homeTrigger.click = () => { menuClicks++; };
  const root = env.add('section', {id: 'register', className: 'page active'});
  const stack = [root], voids = new Set(['input', 'img', 'br', 'hr']);
  for (const tag of fs.readFileSync('src/main/resources/static/pages/register.html', 'utf8').matchAll(/<(\/?)([a-z][a-z0-9]*)\b([^>]*)>/g)) {
    if (tag[1]) { if (stack.at(-1)?.tagName === tag[2]) stack.pop(); continue; }
    const node = env.add(tag[2], {}, stack.at(-1));
    for (const [, name, value = ''] of tag[3].matchAll(/([\w-]+)(?:="([^"]*)")?/g)) {
      node.setAttribute(name, value);
      if (name === 'class') node.className = value;
      if (name === 'id') node.id = value;
      if (name === 'name') node.name = value;
      if (name === 'value') node.value = value;
      if (name === 'hidden') node.hidden = true;
      if (name === 'disabled') node.disabled = true;
      if (name.startsWith('data-')) node.dataset[name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
    }
    if (!voids.has(tag[2])) stack.push(node);
  }
  const form = root.querySelector('form'); form.elements = {};
  for (const field of form.querySelectorAll('input,select,textarea')) if (field.name) form.elements[field.name] = field;
  const initial = new Map(form.querySelectorAll('input,select').map(field => [field, field.value]));
  form.reset = () => initial.forEach((value, field) => { field.value = value; field.files = []; });
  env.window.PastportApi = {fetch: env.window.fetch};
  env.window.setTimeout = () => {};
  const prepared = [], revoked = [];
  env.window.PastportDocMask = {prepare: (...args) => prepared.push(args)};
  const home = env.add('section', {id: 'home', className: 'page'});
  const search = env.add('input', {'data-product-search': ''}, home); search.value = '이전 제품 검색어';
  for (const name of ['list', 'list-status', 'count', 'purchased-count', 'latest', 'more']) {
    env.add('div', {['data-' + (name === 'purchased-count' ? name : 'product-' + name)]: ''}, home);
  }
  const urls = class extends URL {};
  urls.createObjectURL = file => 'blob:' + file.name;
  urls.revokeObjectURL = url => revoked.push(url);
  env.run('product-registration.js');
  env.run('products.js', {
    URL: urls,
    Option: function(text, value) { const option = env.document.createElement('option'); option.value = value; return option; },
    FormData: class { constructor() { this.entries = []; } append(...args) { this.entries.push(args); } },
  });
  const $ = selector => root.querySelector(selector);
  const click = async selector => { await env.document.emit('click', null, {target: $(selector)}); await flush(); };
  const submit = async () => { await env.document.emit('submit', null, {target: form, preventDefault() {}}); await flush(); };
  const choose = async files => {
    form.elements.images.files = files;
    await env.document.emit('change', null, {target: form.elements.images}); await flush();
  };
  return {...env, root, home, search, form, $, click, submit, choose, prepared, revoked, menuClicks: () => menuClicks};
}

const photo = {name: 'notebook.jpg', type: 'image/jpeg', size: 100};

test('기본 정보와 사진을 가지고 1·2단계를 오가도 입력과 미리보기를 유지한다', async () => {
  const s = await screen(); s.form.elements.title.value = '내 노트북';
  await s.choose([photo]);
  assert.equal(s.$('[data-product-register-progress]').textContent, '1 / 2');
  assert.equal(s.form.elements.images.closest('[data-register-step]').dataset.registerStep, '1');
  await s.click('[data-register-next]');
  assert.equal(s.form.dataset.currentStep, '2');
  assert.equal(s.$('[data-product-register-progress]').textContent, '2 / 2');
  assert.equal(s.$('[data-register-step-indicator="2"]').getAttribute('aria-current'), 'step');
  await s.click('[data-register-prev]');
  assert.equal(s.form.dataset.currentStep, '1');
  assert.equal(s.form.elements.title.value, '내 노트북');
  assert.equal(s.form.elements.images.files[0], photo);
  assert.equal(s.$('[data-product-image-previews]').children.length, 1);
  assert.equal(s.calls.length, 0);
});

test('첫 단계에서 Enter로 제출해도 제품을 만들지 않고 상세 정보로 이동한다', async () => {
  const s = await screen(); await s.submit();
  assert.equal(s.form.dataset.currentStep, '2'); assert.equal(s.calls.length, 0);
});

test('필수값이나 날짜 오류가 있는 기본 정보는 다음으로 진행하거나 숨긴 채 제출할 수 없다', async () => {
  const s = await screen(); s.form.elements.title.invalid = true;
  await s.click('[data-register-next]'); assert.equal(s.form.dataset.currentStep, '1');
  s.form.elements.title.invalid = false; await s.click('[data-register-next]');
  s.form.elements.purchaseDate.value = '2026-11-01'; s.form.elements.warrantyExpiresAt.value = '2026-10-01';
  await s.submit();
  assert.equal(s.form.dataset.currentStep, '1');
  assert.equal(s.form.elements.warrantyExpiresAt.reportedWhileHidden, false);
  assert.match(s.form.elements.warrantyExpiresAt.validityMessage, /구매일보다/);
  assert.equal(s.calls.length, 0);
});

test('마지막 단계에서 제품 생성 후 사진 업로드·문서 인계하고 첫 단계로 초기화한다', async () => {
  const s = await screen(); await s.choose([photo]);
  const documentFile = {name: 'receipt.png', type: 'image/png'};
  s.$('[data-product-document]').files = [documentFile];
  await s.click('[data-register-next]'); await s.submit();
  assert.deepEqual(s.calls.map(c => c.path), ['/api/v1/products', '/api/v1/products/42/images']);
  assert.deepEqual(s.prepared, [[42, documentFile]]);
  assert.equal(s.navigations.at(-1), 'docmask');
  assert.equal(s.form.dataset.currentStep, '1');
  assert.equal(s.$('[data-product-image-previews]').children.length, 0);
  assert.deepEqual(s.revoked, ['blob:notebook.jpg']);
});

test('등록 성공 시 메뉴 클릭 없이 내 제품 목록을 열고 검색을 비워 새 제품을 조회한다', async () => {
  const s = await screen(); await s.click('[data-register-next]'); await s.submit();
  assert.equal(s.navigations.at(-1), 'home');
  assert.equal(s.home.classList.contains('active'), true);
  assert.equal(s.root.classList.contains('active'), false);
  assert.equal(s.menuClicks(), 0);
  assert.equal(s.search.value, '');
  assert.deepEqual(s.calls.map(c => c.path), ['/api/v1/products', '/api/v1/products?page=0&size=20']);
  assert.match(s.home.querySelector('[data-product-list]').innerHTML, /방금 등록한 노트북/);
});

test('중복 제출은 막고 생성 실패 시 단계·입력·사진을 유지한다', async () => {
  const request = deferred(); const s = await screen({create: () => request.promise});
  s.form.elements.title.value = '유지할 노트북'; await s.choose([photo]);
  await s.click('[data-register-next]'); await s.submit(); await s.submit();
  assert.equal(s.calls.length, 1);
  request.reject(new Error('등록 실패')); await flush();
  assert.equal(s.form.dataset.currentStep, '2');
  assert.equal(s.form.elements.title.value, '유지할 노트북');
  assert.equal(s.form.elements.images.files[0], photo);
  assert.equal(s.$('[data-product-submit]').disabled, false);
  assert.match(s.$('[data-product-form-message]').textContent, /등록 실패/);
});

test('사진 형식·장수·크기 오류를 현재 단계에 표시하고 요약과 미리보기를 초기화한다', async () => {
  const s = await screen();
  for (const [files, message] of [
    [[{...photo, type: 'application/pdf'}], /JPG, PNG, WEBP/],
    [Array(11).fill(photo), /최대 10장/],
    [[{...photo, size: 6 * 1024 * 1024}], /최대 5MB/],
  ]) {
    await s.choose([photo]); await s.choose(files);
    assert.equal(s.form.dataset.currentStep, '1');
    assert.equal(s.$('[data-product-form-message]').hidden, false);
    assert.match(s.$('[data-product-form-message]').textContent, message);
    assert.equal(s.$('[data-product-image-previews]').children.length, 0);
    assert.equal(s.$('[data-product-file-summary]').textContent, '선택된 이미지 없음');
    assert.equal(s.calls.length, 0);
  }
});
