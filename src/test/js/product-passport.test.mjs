import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {environment, deferred, flush} from './dom.mjs';

async function screen({documents} = {}) {
  const product = {productId: 101, title: '내 노트북', manufacturer: 'Samsung', modelName: 'Galaxy Book', factsVersion: 3, specifications: {}, images: [], createdAt: '2026-11-03T12:00:00Z'};
  const env = environment((path) => {
    if (path === '/api/v1/products/101') return product;
    if (path === '/api/v1/products/101/documents') return documents ? documents() : [
      {id: 8, contentType: 'image/png', byteSize: 2048, version: 1, redactionRequired: false},
      {id: 9, contentType: 'image/png', byteSize: 2048, version: 1, redactionRequired: true},
    ];
    if (path === '/api/v1/documents/8/file-url') return {url: '/protected/document/8'};
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
  const buttons = s.$('[data-product-document-list]').querySelectorAll('button');
  assert.equal(buttons.length, 2); assert.equal(buttons[0].disabled, false); assert.equal(buttons[1].disabled, true);
  await s.click(buttons[0]); assert.deepEqual(s.opened, [['/protected/document/8', '_blank', 'noopener']]);
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
  assert.equal(s.$('[data-product-document-list]').querySelectorAll('button').length, 2);
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
