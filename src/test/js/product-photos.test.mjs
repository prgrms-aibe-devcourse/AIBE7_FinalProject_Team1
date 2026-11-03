import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {environment, deferred, flush} from './dom.mjs';

// 실제 편집창 HTML과 모듈을 실행하며, 소유 제품 API와 이미지 바이트만 대체한다.
async function screen({count = 2, intercept} = {}) {
  let product = {productId: 101, factsVersion: 3, images: Array.from({length: count}, (_, i) => ({imageId: i + 1, primary: i === 0, url: '/image/' + (i + 1)}))};
  const changes = [], revoked = [];
  const env = environment(async (path, options) => {
    if (path.startsWith('/image/')) return {response: {ok: true, blob: async () => ({})}};
    if (intercept) { const value = await intercept(path, options); if (value !== undefined) return value; }
    if (options.method === 'PUT') {
      const body = JSON.parse(options.body);
      product.images = body.imageIds.map(id => ({...product.images.find(image => image.imageId === id), primary: id === body.primaryImageId}));
    } else if (options.method === 'DELETE') {
      product.images = product.images.filter(image => image.imageId !== Number(path.split('/').at(-1)));
      if (product.images.length && !product.images.some(image => image.primary)) product.images[0].primary = true;
    } else if (options.method === 'POST') {
      const id = product.images.length + 10;
      product.images.push({imageId: id, primary: !product.images.length, url: '/image/' + id});
    } else throw new Error('Unexpected request: ' + path);
    return structuredClone(product);
  });
  const originalCreate = env.document.createElement.bind(env.document);
  env.document.createElement = tag => {
    const node = originalCreate(tag), matches = node.matches.bind(node);
    node.matches = selector => {
      const match = selector.match(/^\[([^=]+)="([^"]*)"\]$/);
      return match ? node.getAttribute(match[1]) === match[2] : matches(selector);
    };
    node.replaceWith = replacement => { node.after(replacement); node.remove(); };
    return node;
  };
  const root = env.add('section', {id: 'passport', className: 'page active'}), stack = [root];
  for (const tag of fs.readFileSync('src/main/resources/static/pages/passport.html', 'utf8').matchAll(/<(\/?)([a-z][a-z0-9]*)\b([^>]*)>/g)) {
    if (tag[1]) { if (stack.at(-1)?.tagName === tag[2]) stack.pop(); continue; }
    const node = env.add(tag[2], {}, stack.at(-1));
    for (const [, name, value = ''] of tag[3].matchAll(/([\w-]+)(?:="([^"]*)")?/g)) {
      node.setAttribute(name, value);
      if (name === 'class') node.className = value;
      if (name === 'id') node.id = value;
      if (name === 'hidden') node.hidden = true;
      if (name.startsWith('data-')) node.dataset[name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
    }
    if (!['input','img','br'].includes(tag[2])) stack.push(node);
  }
  const $ = selector => root.querySelector(selector);
  const modal = $('[data-product-photos-dialog]'); modal.open = false;
  modal.showModal = () => { modal.open = true; };
  modal.close = () => { if (modal.open) { modal.open = false; modal.emit('close'); } };
  const open = env.add('button', {'data-product-images-edit': ''}, root);
  env.window.PastportApi = {fetch: env.window.fetch};
  env.document.dispatchEvent = event => {
    if (event.type === 'pastport:product-images-updated') changes.push(event.detail.product);
    env.document.emit(event.type, event.detail); return true;
  };
  const urls = class extends URL {};
  urls.createObjectURL = () => 'blob:' + Math.random();
  urls.revokeObjectURL = url => revoked.push(url);
  env.run('product-photos.js', {
    URL: urls,
    CustomEvent: class {constructor(type, options) {this.type = type; this.detail = options.detail;}},
    FormData: class {constructor() {this.files = [];} append(...args) {this.files.push(args);}},
  });
  await env.document.emit('pastport:product-detail-rendered', {product});
  const clickNode = async node => { assert.ok(node); await env.document.emit('click', null, {target: node}); await flush(); };
  const action = (kind, id) => root.querySelectorAll('[data-photo-action]').find(node => node.dataset.photoAction === kind && Number(node.dataset.photoId) === id);
  const add = async files => { const input = $('[data-product-photo-files]'); input.files = files; await env.document.emit('change', null, {target: input}); await flush(); };
  await clickNode(open);
  return {...env, root, $, modal, open, action, add, clickNode, changes, revoked,
    mutations: () => env.calls.filter(call => call.options.method), product: () => product};
}

const photo = {name: 'notebook.png', type: 'image/png', size: 200};

test('연필로 편집창을 열면 등록 사진과 대표 표시가 보이고 보기만 해서는 변경 요청을 보내지 않는다', async () => {
  const s = await screen();
  assert.equal(s.modal.open, true);
  assert.equal(s.$('[data-product-photos-count]').textContent, '2 / 10장');
  assert.equal(s.$('[data-product-photos-list]').querySelectorAll('img').length, 2);
  assert.match(s.$('[data-product-photos-list]').textContent, /대표 사진/);
  assert.equal(s.action('left', 1).disabled, true);
  assert.equal(s.mutations().length, 0);
});

test('좌우 순서는 전체 이미지 ID와 기존 대표 ID를 보내고 대표 지정은 해당 사진을 맨 앞으로 옮긴다', async () => {
  const s = await screen(); await s.clickNode(s.action('left', 2));
  assert.deepEqual(JSON.parse(s.mutations()[0].options.body), {imageIds: [2,1], primaryImageId: 1});
  await s.clickNode(s.action('primary', 2));
  assert.deepEqual(JSON.parse(s.mutations()[1].options.body), {imageIds: [2,1], primaryImageId: 2});
  assert.equal(s.changes.at(-1).images[0].primary, true);
});

test('사진 삭제는 대상을 확인한 뒤에만 요청하고 마지막 사진을 지워도 추가할 수 있다', async () => {
  const s = await screen({count: 1}); await s.clickNode(s.action('remove', 1));
  assert.equal(s.mutations().length, 0);
  assert.match(s.$('#product-photo-delete-title').textContent, /1번 사진/);
  assert.equal(s.$('[data-product-photos-delete]').hidden, false);
  await s.clickNode(s.$('[data-product-photo-keep]')); assert.equal(s.mutations().length, 0);
  await s.clickNode(s.action('remove', 1)); await s.clickNode(s.$('[data-product-photo-delete-confirm]'));
  assert.equal(s.mutations()[0].path, '/api/v1/products/101/images/1');
  assert.equal(s.mutations()[0].options.method, 'DELETE');
  assert.equal(s.$('[data-product-photos-count]').textContent, '0 / 10장');
  assert.equal(s.$('[data-product-photo-files]').disabled, false);
  assert.equal(s.document.activeElement, s.$('[data-product-photo-files]'));
});

test('판매글 사용 중인 사진의 삭제 실패는 원본 사진·대표·순서를 유지하고 오류를 표시한다', async () => {
  const s = await screen({intercept: (path, options) => options.method === 'DELETE' ? {response: {ok: false, json: async () => ({message: '판매글에서 사용 중인 이미지입니다.'})}} : undefined});
  await s.clickNode(s.action('remove', 1)); await s.clickNode(s.$('[data-product-photo-delete-confirm]'));
  assert.match(s.$('[data-product-photos-message]').textContent, /판매글에서 사용 중/);
  assert.equal(s.$('[data-product-photos-count]').textContent, '2 / 10장');
  assert.equal(s.action('primary', 1).disabled, true);
  assert.equal(s.changes.length, 0);
});

test('사진 추가 전에 형식·용량·장수를 검증하고 10장이어도 편집·삭제는 가능하다', async () => {
  const s = await screen();
  for (const files of [[{...photo, type: 'application/pdf'}], [{...photo, size: 6 * 1024 * 1024}], Array(9).fill(photo)]) {
    await s.add(files); assert.equal(s.mutations().length, 0);
    assert.equal(s.$('[data-product-photos-message]').getAttribute('role'), 'alert');
  }
  const full = await screen({count: 10});
  assert.equal(full.modal.open, true); assert.equal(full.$('[data-product-photo-files]').disabled, true);
  assert.equal(full.action('remove', 1).disabled, false);
  assert.equal(full.action('left', 2).disabled, false);
});

test('여러 사진 중 일부만 추가돼도 성공한 사진을 유지하고 실패를 알린다', async () => {
  let posts = 0;
  const s = await screen({intercept: (path, options) => options.method === 'POST' && ++posts === 2 ? {response: {ok: false, json: async () => ({message: '업로드 실패'})}} : undefined});
  await s.add([photo, {...photo, name: 'back.png'}]);
  assert.equal(s.$('[data-product-photos-count]').textContent, '3 / 10장');
  assert.match(s.$('[data-product-photos-message]').textContent, /1장은 추가.*업로드 실패/);
  assert.equal(s.changes.length, 1);
  assert.equal(s.$('[data-product-photo-files]').disabled, false);
});

test('요청 중 중복 변경과 Escape 닫기를 막고, 계정 전환 뒤 늦은 응답은 반영하지 않는다', async () => {
  const request = deferred();
  const s = await screen({intercept: (path, options) => options.method === 'PUT' ? request.promise : undefined});
  await s.clickNode(s.action('left', 2)); await s.clickNode(s.action('right', 1));
  assert.equal(s.mutations().length, 1);
  let prevented = false; await s.modal.emit('cancel', {preventDefault() {prevented = true;}});
  assert.equal(prevented, true);
  await s.document.emit('pastport:auth-changed');
  request.resolve({productId: 101, images: [{imageId: 2, primary: true, url: '/image/2'}]}); await flush();
  assert.equal(s.modal.open, false); assert.equal(s.changes.length, 0);
  assert.equal(s.$('[data-product-photos-list]').children.length, 0);
  assert.ok(s.revoked.length >= 2);
});
