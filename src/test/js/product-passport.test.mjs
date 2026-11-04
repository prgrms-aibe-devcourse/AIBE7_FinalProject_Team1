import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {environment, deferred, flush} from './dom.mjs';

async function screen({documents, rename} = {}) {
  const product = {productId: 101, title: '내 노트북', manufacturer: 'Samsung', modelName: 'Galaxy Book', factsVersion: 3, specifications: {}, images: [], createdAt: '2026-11-03T12:00:00Z'};
  const env = environment((path, options) => {
    if (path === '/api/v1/products/101') return product;
    if (path === '/api/v1/products/101/documents') return documents ? documents() : [
      {id: 8, contentType: 'image/png', byteSize: 2048, version: 1, redactionRequired: false},
      {id: 9, contentType: 'image/png', byteSize: 2048, version: 1, redactionRequired: true},
    ];
    if (path === '/api/v1/documents/8/file-url') return {url: '/protected/document/8'};
    if (path === '/api/v1/documents/8/name') return rename ? rename(options) : {id: 8, name: JSON.parse(options.body).name};
    throw new Error('Unexpected request: ' + path);
  });
  const originalCreate = env.document.createElement.bind(env.document);
  env.document.createElement = tag => {
    const node = originalCreate(tag), matches = node.matches.bind(node);
    node.matches = selector => {
      const match = selector.match(/^\[([^=]+)="([^"]*)"\]$/);
      return match ? node.getAttribute(match[1]) === match[2] : matches(selector);
    };
    return node;
  };
  function mount(html, parent) {
    const stack = [parent];
    for (const tag of html.matchAll(/<(\/?)([a-z][a-z0-9]*)\b([^>]*)>/g)) {
      if (tag[1]) { if (stack.at(-1)?.tagName === tag[2]) stack.pop(); continue; }
      const node = env.add(tag[2], {}, stack.at(-1));
      for (const [, name, value = ''] of tag[3].matchAll(/([\w-]+)(?:="([^"]*)")?/g)) {
        node.setAttribute(name, value);
        if (name === 'class') node.className = value;
        if (name === 'id') node.id = value;
        if (name === 'hidden') node.hidden = true;
        if (name.startsWith('data-')) node.dataset[name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
      }
      if (!['input','img','br','path'].includes(tag[2])) stack.push(node);
    }
  }
  const root = env.add('section', {id: 'passport', className: 'page active'});
  mount(fs.readFileSync('src/main/resources/static/pages/passport.html', 'utf8'), root);
  const detail = root.querySelector('[data-product-detail]');
  Object.defineProperty(detail, 'innerHTML', {set(html) {this.replaceChildren(); this.html = html; mount(html, this);}, get() {return this.html;}});
  env.window.PastportApi = {fetch: env.window.fetch};
  const prepared = [], opened = [];
  env.window.PastportDocMask = {prepare: (...args) => prepared.push(args)};
  env.window.open = (...args) => opened.push(args);
  env.document.dispatchEvent = event => {env.document.emit(event.type, event.detail); return true;};
  env.run('product-registration.js');
  env.run('products.js', {CustomEvent: class {constructor(type, options) {this.type = type; this.detail = options.detail;}}});
  const trigger = env.add('button', {'data-product-id': '101'}); trigger.dataset.productId = '101';
  await env.document.emit('click', null, {target: trigger});
  await env.document.emit('pastport:page-shown', {id: 'passport'}); await flush();
  const $ = selector => root.querySelector(selector);
  const click = async node => {await env.document.emit('click', null, {target: node}); await flush();};
  return {...env, root, $, click, product, prepared, opened};
}

test('상단 증빙 문서 보기는 현재 제품 문서를 조회하고 재마스킹 문서의 보기를 막는다', async () => {
  const s = await screen();
  assert.equal(s.$('[data-product-quick-actions]').hidden, false);
  assert.equal(s.$('[data-product-documents]').hidden, true);
  await s.click(s.$('[data-product-documents-show]'));
  assert.equal(s.calls.at(-1).path, '/api/v1/products/101/documents');
  assert.equal(s.$('[data-product-documents]').hidden, false);
  assert.equal(s.$('[data-product-documents-show]').getAttribute('aria-expanded'), 'true');
  const buttons = s.$('[data-product-document-list]').querySelectorAll('[data-product-document-open]');
  assert.equal(buttons.length, 2); assert.equal(buttons[0].disabled, false); assert.equal(buttons[1].disabled, true);
  await s.click(buttons[0]);
  const dialog = s.document.querySelector('[data-doc-preview-dialog]');
  assert.ok(dialog); assert.equal(dialog.open, true);
  assert.equal(dialog.querySelector('[data-doc-preview-img]').getAttribute('src'), '/protected/document/8');
  await s.click(dialog.querySelector('[data-doc-preview-close]'));
  assert.equal(dialog.open, false);
  await s.click(s.$('[data-product-documents-hide]'));
  assert.equal(s.$('[data-product-documents]').hidden, true);
});

test('상단 증빙 업로드는 현재 제품을 마스킹 화면에 지정하고 이동한다', async () => {
  const s = await screen(); await s.click(s.$('[data-product-document-upload]'));
  assert.deepEqual(s.prepared, [[101, null]]); assert.equal(s.navigations.at(-1), 'docmask');
});

test('사진 변경으로 갤러리를 갱신해도 작성 중인 이력과 펼친 문서 목록은 유지한다', async () => {
  const s = await screen(); await s.click(s.$('[data-product-documents-show]'));
  const history = s.$('[data-history-root]'), field = s.add('input', {}, history); field.value = '작성 중인 점검 기록';
  await s.document.emit('pastport:product-images-updated', {product: {...s.product, images: [{imageId: 1, primary: true, url: '/photo/1'}]}});
  assert.equal(field.isConnected, true); assert.equal(field.value, '작성 중인 점검 기록');
  assert.equal(s.$('[data-product-documents]').hidden, false);
  assert.equal(s.$('[data-product-document-list]').querySelectorAll('[data-product-document-open]').length, 2);
  assert.match(s.$('.product-passport-summary').querySelector('p').textContent, /이미지 1장/);
});

test('이전 상세의 늦은 문서 응답은 다시 연 상세 문서 목록에 반영되지 않는다', async () => {
  const request = deferred(); const s = await screen({documents: () => request.promise});
  await s.click(s.$('[data-product-documents-show]'));
  await s.document.emit('pastport:page-shown', {id: 'passport'}); await flush();
  request.resolve([{id: 88, contentType: 'image/png'}]); await flush();
  assert.equal(s.$('[data-product-document-list]').children.length, 0);
  assert.equal(s.$('[data-product-documents]').hidden, true);
});

test('문서 이름을 변경하면 목록과 보기 팝업에 새 이름을 반영하고 다시 조회해도 유지한다', async () => {
  const items = [{id: 8, name: '기존 보증서', contentType: 'image/png', byteSize: 2048, version: 1}];
  const s = await screen({documents: () => items, rename: options => {
    items[0] = {...items[0], name: JSON.parse(options.body).name}; return items[0];
  }});
  await s.click(s.$('[data-product-documents-show]'));
  const row = s.$('.product-document-row'), rename = row.querySelector('[data-product-document-rename]');
  await rename.emit('click');
  const form = row.querySelector('form'), input = row.querySelector('input');
  assert.equal(form.hidden, false); assert.equal(input.value, '기존 보증서');
  assert.equal(s.document.activeElement, input);
  input.value = '  구매 보증서  ';
  await form.emit('submit');
  const request = s.calls.find(call => call.path === '/api/v1/documents/8/name');
  assert.equal(request.options.method, 'PATCH');
  assert.deepEqual(JSON.parse(request.options.body), {name: '구매 보증서'});
  assert.equal(row.querySelector('b').textContent, '구매 보증서');
  assert.equal(row.querySelector('[data-product-document-open]').dataset.documentName, '구매 보증서');
  assert.equal(form.hidden, true); assert.equal(s.document.activeElement, rename);
  await s.click(s.$('[data-product-documents-hide]'));
  await s.click(s.$('[data-product-documents-show]'));
  assert.equal(s.$('.product-document-row').querySelector('b').textContent, '구매 보증서');
});

test('문서 이름 변경 취소와 Escape는 저장하지 않고 원래 이름을 유지한다', async () => {
  const s = await screen(); await s.click(s.$('[data-product-documents-show]'));
  const row = s.$('.product-document-row'), rename = row.querySelector('[data-product-document-rename]');
  await rename.emit('click'); row.querySelector('input').value = '저장하지 않을 이름';
  await row.querySelector('[data-product-document-name-cancel]').emit('click');
  assert.equal(row.querySelector('form').hidden, true);
  assert.equal(row.querySelector('b').textContent, '이미지 문서');
  await rename.emit('click'); assert.equal(row.querySelector('input').value, '');
  await row.querySelector('form').emit('keydown', {key: 'Escape'});
  assert.equal(row.querySelector('form').hidden, true);
  assert.equal(s.calls.some(call => call.path.endsWith('/name')), false);
});

test('이름 저장 실패는 입력값을 유지해 다시 저장할 수 있고 긴 이름은 요청하지 않는다', async () => {
  let failed = true;
  const s = await screen({rename: () => {
    if (failed) throw new Error('이름을 저장하지 못했습니다.');
    return {id: 8, name: '보증서'};
  }});
  await s.click(s.$('[data-product-documents-show]'));
  const row = s.$('.product-document-row');
  await row.querySelector('[data-product-document-rename]').emit('click');
  const form = row.querySelector('form'), input = row.querySelector('input');
  input.value = '가'.repeat(51); await form.emit('submit');
  assert.equal(s.calls.some(call => call.path.endsWith('/name')), false);
  assert.match(row.querySelector('[data-product-document-name-error]').textContent, /50자/);
  input.value = '보증서'; await form.emit('submit');
  assert.equal(form.hidden, false); assert.equal(input.value, '보증서');
  assert.equal(input.disabled, false);
  assert.match(row.querySelector('[data-product-document-name-error]').textContent, /저장하지 못/);
  failed = false; await form.emit('submit');
  assert.equal(form.hidden, true); assert.equal(row.querySelector('b').textContent, '보증서');
});

test('이름을 비우면 기본 문서명으로 돌아가며 중복 저장과 이전 화면의 늦은 응답을 막는다', async () => {
  const request = deferred();
  const s = await screen({rename: () => request.promise});
  await s.click(s.$('[data-product-documents-show]'));
  const row = s.$('.product-document-row');
  await row.querySelector('[data-product-document-rename]').emit('click');
  const form = row.querySelector('form'), input = row.querySelector('input');
  input.value = '   ';
  const pending = form.emit('submit'); await flush();
  assert.equal(input.disabled, true);
  await form.emit('submit');
  const calls = s.calls.filter(call => call.path.endsWith('/name'));
  assert.equal(calls.length, 1); assert.deepEqual(JSON.parse(calls[0].options.body), {name: null});
  request.resolve({id: 8, name: null}); await pending;
  assert.equal(row.querySelector('b').textContent, '이미지 문서');
  const late = deferred(), other = await screen({rename: () => late.promise});
  await other.click(other.$('[data-product-documents-show]'));
  const oldRow = other.$('.product-document-row');
  await oldRow.querySelector('[data-product-document-rename]').emit('click');
  oldRow.querySelector('input').value = '늦게 저장한 이름';
  const saving = oldRow.querySelector('form').emit('submit'); await flush();
  await other.document.emit('pastport:page-shown', {id: 'passport'}); await flush();
  late.resolve({id: 8, name: '늦게 저장한 이름'}); await saving;
  assert.equal(other.$('[data-product-document-list]').children.length, 0);
  assert.equal(other.$('[data-product-document-status]').textContent, '');
});
