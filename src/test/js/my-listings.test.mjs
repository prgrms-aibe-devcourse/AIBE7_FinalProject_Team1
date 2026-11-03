import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {environment, flush, deferred} from './dom.mjs';

async function screen(drafts = () => ({page:{totalElements:3}})) {
  const env = environment(path => path.startsWith('/api/v1/listing-drafts?') ? drafts() : {
    items:[{listingId:5,title:'내 노트북',price:100000,status:'ACTIVE',publishedAt:'2026-11-01T00:00:00Z',canManage:true}],
    page:{number:0,size:20,totalElements:1,totalPages:1,hasNext:false}
  });
  const root = env.add('section', {id:'my-listings',className:'page active'});
  const html = fs.readFileSync('src/main/resources/static/pages/my-listings.html','utf8');
  for (const tag of html.matchAll(/<([a-z][a-z0-9]*)\b([^>]*\bdata-my-[^>]*)>/g)) {
    const node = env.add(tag[1],{},root);
    for (const attr of tag[2].matchAll(/\b(data-my-[\w-]+)(?:="([^"]*)")?/g)) {
      node.setAttribute(attr[1],attr[2] || '');
      node.dataset[attr[1].slice(5).replace(/-([a-z])/g,(_,c)=>c.toUpperCase())] = attr[2] || '';
    }
  }
  env.window.history = {state:null,replaceState(state,title,url){env.window.location.href=String(url);}};
  env.run('my-listings.js');
  await env.document.emit('pastport:page-loaded',{id:'my-listings'});
  await env.document.emit('pastport:page-shown',{id:'my-listings'});
  await flush();
  env.$ = selector => root.querySelector(selector);
  return env;
}

test('내 판매글에 임시저장 개수를 표시하고 조회 실패해도 게시글은 유지한다', async () => {
  const ok = await screen();
  assert.equal(ok.$('[data-my-draft-count]').textContent,'3');
  assert.match(ok.$('[data-my-items]').textContent,/내 노트북/);
  const failed = await screen(()=>({response:{ok:false,status:500}}));
  assert.equal(failed.$('[data-my-draft-count]').textContent,'');
  assert.match(failed.$('[data-my-items]').textContent,/내 노트북/);
});

test('계정이 바뀌면 이전 임시저장 개수를 비우고 늦은 응답을 무시한다', async () => {
  const old = deferred(); let count = 0;
  const env = await screen(()=>++count === 1 ? old.promise : {page:{totalElements:1}});
  await env.document.emit('pastport:auth-changed'); await flush();
  assert.equal(env.$('[data-my-draft-count]').textContent,'1');
  old.resolve({page:{totalElements:99}}); await flush();
  assert.equal(env.$('[data-my-draft-count]').textContent,'1');
  await env.document.emit('pastport:page-shown',{id:'market'});
  assert.equal(env.$('[data-my-draft-count]').textContent,'');
});
