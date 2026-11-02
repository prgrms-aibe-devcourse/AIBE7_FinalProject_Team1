import test from 'node:test';
import assert from 'node:assert/strict';
import { environment, deferred } from './dom.mjs';

const tick = () => new Promise(resolve => setImmediate(resolve));
function page(handler) {
  const env = environment((path, options) => path.startsWith('/api/v1/products') ? handler(path, options) : { items: [], page: { totalElements: 0 } });
  const root = env.add('section', { id: 'mypage', className: 'page active' });
  const lists = Object.fromEntries(['chats', 'listings', 'products'].map(key => [key, env.add('tbody', {}, root)]));
  root.querySelector = selector => lists[selector.match(/data-mp-list="([^"]+)"/)?.[1]] || null;
  env.run('mypage.js', { fetch: env.window.fetch });
  return { ...env, products: lists.products };
}

test('mypage renders three paged products, not the page envelope', async () => {
  const env = page(() => ({ items: [1, 2, 3].map(id => ({ title: `Laptop ${id}`, manufacturer: 'Maker' })) }));
  await env.document.emit('pastport:page-shown', { id: 'mypage' }); await tick();
  assert.equal(env.products.children.length, 3);
  assert.match(env.products.textContent, /Laptop 3/);
  assert.ok(env.calls.some(call => call.path === '/api/v1/products?page=0&size=3'));
});

test('empty products offer registration', async () => {
  const env = page(() => ({ items: [] }));
  await env.document.emit('pastport:page-shown', { id: 'mypage' }); await tick();
  assert.match(env.products.textContent, /새 여권 등록하기/);
});

for (const value of [null, {}, [], { items: null }, { items: [null] }, { response: { ok: false, status: 500, json: async () => ({}) } }]) {
  test(`invalid or failed product page is not empty: ${JSON.stringify(value)}`, async () => {
    const env = page(() => value);
    await env.document.emit('pastport:page-shown', { id: 'mypage' }); await tick();
    assert.match(env.products.textContent, /불러오지 못했어요/);
    assert.doesNotMatch(env.products.textContent, /등록한 제품이 없어요/);
  });
}

test('late account A products cannot replace account B products', async () => {
  const old = deferred(); let request = 0;
  const env = page(() => ++request === 1 ? old.promise : { items: [{ title: 'Account B' }] });
  await env.document.emit('pastport:page-shown', { id: 'mypage' }); await tick();
  await env.document.emit('pastport:auth-changed', { signedIn: true, userId: 2 }); await tick();
  old.resolve({ items: [{ title: 'Account A' }] }); await tick();
  assert.match(env.products.textContent, /Account B/);
  assert.doesNotMatch(env.products.textContent, /Account A/);
});

function account(handler) {
  let current = { id: 1, nickname: 'One', email: 'one@example.invalid' };
  const env = environment(path => path.startsWith('/api/v1/products') ? handler() : path === '/pages/auth-art.html' ? { response: { text: async () => '' } } : current);
  const root = env.add('section', { id: 'mypage' });
  const guest = env.add('div', {}, root), member = env.add('div', {}, root);
  const fields = Object.fromEntries(['initial', 'nickname', 'email', 'ownedCount'].map(key => [key, env.add('span', {}, member)]));
  const image = env.add('img', {}, member), remove = env.add('button', {}, member);
  root.querySelector = selector => selector === '[data-mp="guest"]' ? guest : selector === '[data-mp="member"]' ? member : null;
  member.querySelector = selector => fields[selector.match(/data-mp-field="([^"]+)"/)?.[1]] || (selector === '[data-mp-img]' ? image : selector === '[data-mp-remove]' ? remove : null);
  env.document.cookie = '';
  env.document.dispatchEvent = event => env.document.emit(event.type, event.detail);
  env.window.PastportSession = { recover: read => read(), exclusive: action => action(), subscribe() {}, publish() {} };
  env.run('auth.js', { fetch: env.window.fetch, Headers, localStorage: { getItem: () => null }, CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } } });
  return { ...env, count: fields.ownedCount, async switchAccount() { current = { ...current, id: 2 }; await env.document.emit('window:pageshow', null, { persisted: true }); } };
}

for (const [data, expected] of [
  [{ items: [{}], page: { totalElements: 12 }, summary: { registered: 3 } }, '12'],
  [{ items: [{}], summary: { registered: 12 } }, '12'],
  [{ items: [{}, {}, {}] }, '-'],
  [{ page: { totalElements: -1 }, summary: { registered: -1 } }, '-'],
  [{ response: { ok: false, status: 500, json: async () => ({ data: { page: { totalElements: 12 } } }) } }, '-']
]) {
  test(`owned count uses validated server total: ${expected}`, async () => {
    const env = account(() => data); await tick(); await tick();
    assert.equal(env.count.textContent, expected);
  });
}

test('late previous-account owned total is discarded', async () => {
  const old = deferred(); let request = 0;
  const env = account(() => ++request === 1 ? old.promise : { page: { totalElements: 7 } });
  await tick(); await env.switchAccount(); await tick();
  old.resolve({ page: { totalElements: 99 } }); await tick();
  assert.equal(env.count.textContent, '7');
});
