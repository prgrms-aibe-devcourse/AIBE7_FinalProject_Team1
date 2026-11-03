import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {environment, Node, deferred, flush} from './dom.mjs';

// 실제 app/상세/수정 모듈을 함께 실행한다. HTTP와 방문 기록만 대체하며 DB에는 접근하지 않는다.
async function screen({canEdit=true, available=3, failure, put}={}) {
  const fixture={listingId:5,productId:101,contentVersion:7,status:canEdit?'ACTIVE':'RESERVED',canEdit,canCancel:true,
    content:{title:'점검한 노트북',description:'정상 동작을 확인했습니다.',price:100000,conditionGrade:4},
    selectedImageIds:[1],availableImages:Array.from({length:available},(_,i)=>({imageId:i+1,url:'/fixture/'+(i+1)}))};
  const env=environment((path,options)=>{
    const error=failure?.(path,options); if(error)return error;
    if(options.method==='PUT')return put?put(path,options):{listingId:5};
    if(path.endsWith('/management'))return structuredClone(fixture);
    if(path.startsWith('/api/v1/listings/5?'))return {listingId:5,canManage:true,status:fixture.status,content:fixture.content,history:{items:[],page:{number:0,hasNext:false}}};
    throw new Error('Unexpected fixture request: '+path);
  });
  let confirmResult=false; const confirmations=[];
  const originalCreate=env.document.createElement.bind(env.document);
  env.document.createElement=tag=>{
    const n=originalCreate(tag); n.blur=()=>{if(env.document.activeElement===n)env.document.activeElement=null;};
    let value=''; Object.defineProperty(n,'value',{get:()=>value,set:v=>{value=String(v);},configurable:true});
    return n;
  };
  const root=env.add('section',{id:'detail',className:'page active'});
  const stack=[root], voids=new Set(['input','img','path','br','hr']);
  for(const tag of fs.readFileSync('src/main/resources/static/pages/detail.html','utf8').matchAll(/<(\/?)([a-z][a-z0-9]*)\b([^>]*)>/g)) {
    if(tag[1]){if(stack.at(-1)?.tagName===tag[2])stack.pop();continue;}
    const n=env.add(tag[2],{},stack.at(-1));
    for(const a of tag[3].matchAll(/([\w-]+)(?:="([^"]*)")?/g)) {
      const [_,name,value='']=a;
      if(name==='class')n.className=value;
      else if(name==='id')n.id=value;
      else n.setAttribute(name,value);
      if(name.startsWith('data-'))n.dataset[name.slice(5).replace(/-([a-z])/g,(_,c)=>c.toUpperCase())]=value;
      if(name==='hidden')n.hidden=true;
      if(name==='disabled')n.disabled=true;
    }
    if(tag[2]==='dialog') {
      n.showModal=()=>{n.open=true;};
      n.close=()=>{if(n.open){n.open=false;return n.emit('close');}};
    }
    if(!voids.has(tag[2]))stack.push(n);
  }
  for(const form of root.querySelectorAll('form')) {
    form.elements={};
    for(const field of form.querySelectorAll('input,textarea,select'))form.elements[field.getAttribute('name')]=field;
    form.reset=()=>Object.values(form.elements).forEach(field=>{field.value='';});
    form.reportValidity=()=>true;
  }
  for(const id of ['market','my-listings'])env.add('section',{id,className:'page'});
  env.add('span',{id:'crumb'});env.add('aside',{id:'side'});
  env.window.scrollTo=()=>{};
  const entries=[{state:{page:'detail',pastportIndex:0},url:env.window.location.href}];let position=0;
  env.window.history={
    get state(){return entries[position].state;},
    replaceState(state,title,url){entries[position]={state,url:String(url)};env.window.location.href=String(url);},
    pushState(state,title,url){entries.splice(position+1);entries.push({state,url:String(url)});position++;env.window.location.href=String(url);},
    go(delta){const next=position+delta;if(next<0||next>=entries.length)return;position=next;env.window.location.href=entries[position].url;return env.document.emit('window:popstate',null,{state:entries[position].state});}
  };
  env.document.dispatchEvent=e=>{env.document.emit(e.type,e.detail,e);return true;};
  const globals={HTMLElement:Node,sessionStorage:{getItem(){return null;},setItem(){}},
    CustomEvent:class{constructor(type,options){this.type=type;this.detail=options.detail;}},
    confirm:message=>{confirmations.push(message);return confirmResult;}};
  env.window.PastportListingDetail={render(container){container.textContent='게시된 판매글';}};
  env.run('app.js',globals);env.run('listing-management.js');env.run('listing-published.js');
  await env.document.emit('DOMContentLoaded');await flush();
  await env.window.PastportListingManagement.open();await flush();
  const $=selector=>root.querySelector(selector);
  const key=value=>root.querySelectorAll('[data-listing-photo-key]').find(n=>n.getAttribute('data-listing-photo-key')===value);
  const edit=async(name,value)=>{$('[data-listing-edit-form]').elements[name].value=value;await $('[data-listing-edit-form]').emit('input');};
  const navigate=async page=>{const button=env.add('button',{'data-page':page});button.dataset.page=page;await env.document.emit('click',null,{target:button});await flush();};
  return {...env,$,key,edit,navigate,fixture,confirmations,confirm(value){confirmResult=value;},async save(){await $('[data-listing-edit-form]').emit('submit',{currentTarget:$('[data-listing-edit-form]')});await flush();}};
}

test('변경 전에는 저장을 막고 입력을 원복하면 미저장 표시도 지운다',async()=>{
  const s=await screen();assert.equal(s.$('[data-listing-edit-save]').disabled,true);
  await s.edit('title','새 제목');assert.equal(s.$('[data-listing-edit-save]').disabled,false);
  assert.match(s.$('[data-listing-edit-dirty]').textContent,/저장하지 않은/);
  await s.edit('title',s.fixture.content.title);assert.equal(s.$('[data-listing-edit-save]').disabled,true);
});

test('상세 복귀와 상단 메뉴 이동을 취소하면 입력·주소·편집 화면을 유지한다',async()=>{
  const s=await screen();await s.edit('title','유지할 제목');const url=s.window.location.href;
  await s.$('[data-listing-edit-close]').emit('click');await flush();
  assert.equal(s.window.location.href,url);assert.equal(s.$('[data-listing-editor]').hidden,false);
  await s.navigate('market');
  assert.equal(s.$('[data-listing-edit-form]').elements.title.value,'유지할 제목');
  assert.equal(s.document.querySelector('#detail.active')!==null,true);assert.equal(s.confirmations.length,2);
});

test('이탈을 승인하면 같은 화면 이동에서 확인을 한 번만 하고 편집을 정리한다',async()=>{
  const s=await screen();await s.edit('title','폐기할 제목');s.confirm(true);
  await s.$('[data-listing-edit-back]').emit('click');await flush();
  assert.equal(s.confirmations.length,1);assert.equal(s.$('[data-listing-editor]').hidden,true);
  assert.equal(s.$('[data-listing-edit-bar]').hidden,true);
});

test('브라우저 뒤로가기를 취소하면 방문 기록·URL·입력을 원래 위치로 복구한다',async()=>{
  const s=await screen();await s.navigate('market');s.window.PastportPublishedListing.open(5,{manage:true});await flush();
  await s.edit('title','뒤로가도 유지');const url=s.window.location.href;
  await s.window.history.go(-1);await flush();
  assert.equal(s.window.location.href,url);assert.equal(s.window.history.state.page,'detail');
  assert.equal(s.$('[data-listing-edit-form]').elements.title.value,'뒤로가도 유지');
  s.confirm(true);await s.window.history.go(-1);await flush();
  assert.equal(s.document.querySelector('#market.active')!==null,true);
});

test('브라우저 앞으로가기를 취소해도 앞으로 방문할 화면과 편집 입력을 보존한다',async()=>{
  const s=await screen();await s.navigate('market');s.window.PastportPublishedListing.open(5,{manage:true});await flush();
  await s.navigate('market');await s.window.history.go(-1);await flush();
  await s.edit('title','앞으로가도 유지');const url=s.window.location.href;
  await s.window.history.go(1);await flush();
  assert.equal(s.window.location.href,url);assert.equal(s.$('[data-listing-edit-form]').elements.title.value,'앞으로가도 유지');
  s.confirm(true);await s.window.history.go(1);await flush();
  assert.equal(s.document.querySelector('#market.active')!==null,true);
});

test('수정 중 최신 내용 재조회와 새로고침에도 입력 유실을 확인한다',async()=>{
  const s=await screen();await s.edit('description','보존할 설명');const before=s.calls.length;
  await s.$('[data-listing-edit-reload]').emit('click');assert.equal(s.calls.length,before);
  let prevented=0;await s.document.emit('window:beforeunload',null,{preventDefault(){prevented++;}});assert.equal(prevented,1);
  s.confirm(true);await s.$('[data-listing-edit-reload]').emit('click');await flush();
  assert.equal(s.$('[data-listing-edit-form]').elements.description.value,s.fixture.content.description);
});

test('사진 선택·대표 지정·좌우 이동·선택 해제를 같은 버전과 선택 순서로 저장한다',async()=>{
  const s=await screen();await s.$('[data-listing-photo-open]').emit('click');
  assert.equal(s.$('[data-listing-photo-dialog]').open,true);
  await s.key('pick-2').emit('click');await s.key('pick-3').emit('click');
  await s.$('[data-listing-photo-done]').emit('click');
  await s.key('main-3').emit('click');await s.key('왼쪽-2').emit('click');await s.key('remove-1').emit('click');
  assert.equal(s.$('[data-listing-photo-count]').textContent,'2/10');
  await s.save();
  const request=s.calls.find(c=>c.options.method==='PUT');
  assert.deepEqual(JSON.parse(request.options.body).selectedImageIds,[3,2]);
  assert.equal(JSON.parse(request.options.body).version,7);assert.equal(s.confirmations.length,0);
  assert.equal(s.$('[data-listing-edit-bar]').hidden,true);
});

test('10장 제한과 대표 사진 누락을 저장 전에 안내한다',async()=>{
  const s=await screen({available:11});await s.$('[data-listing-photo-open]').emit('click');
  for(let id=2;id<=10;id++)await s.key('pick-'+id).emit('click');
  await s.key('pick-11').emit('click');assert.equal(s.$('[data-listing-photo-count]').textContent,'10/10');
  assert.match(s.$('[data-listing-photo-error]').textContent,/최대 10장/);
  for(let id=1;id<=10;id++)await s.key('pick-'+id).emit('click');
  await s.$('[data-listing-photo-done]').emit('click');await s.save();
  assert.equal(s.calls.some(c=>c.options.method==='PUT'),false);
  assert.equal(s.document.activeElement,s.$('[data-listing-photo-field]'));
});

test('예약 상태에는 사진 편집과 저장 동작을 열지 않는다',async()=>{
  const s=await screen({canEdit:false});await s.$('[data-listing-photo-open]').emit('click');
  assert.notEqual(s.$('[data-listing-photo-dialog]').open,true);assert.equal(s.$('[data-listing-edit-bar]').hidden,true);
  await s.key('pick-2').emit('click');await s.save();assert.equal(s.calls.some(c=>c.options.method==='PUT'),false);
});

test('저장 중에는 중복 저장과 화면 이탈을 막고 성공 후에는 확인 없이 상세로 돌아간다',async()=>{
  const pending=deferred();const s=await screen({put:()=>pending.promise});await s.edit('title','저장할 제목');
  const saving=s.save();await flush();await s.navigate('market');
  assert.equal(s.document.querySelector('#detail.active')!==null,true);assert.equal(s.$('[data-listing-edit-save]').disabled,true);
  await s.$('[data-listing-edit-form]').emit('submit',{currentTarget:s.$('[data-listing-edit-form]')});
  assert.equal(s.calls.filter(c=>c.options.method==='PUT').length,1);
  pending.resolve({listingId:5});await saving;assert.equal(s.confirmations.length,0);assert.equal(s.$('[data-listing-editor]').hidden,true);
});

test('버전 충돌이면 입력과 사진은 유지하고 저장을 잠그며 하단에도 오류를 표시한다',async()=>{
  const s=await screen({failure:(path,opts)=>opts.method==='PUT'&&{response:{ok:false,status:409,json:async()=>({message:'다른 곳에서 수정됐어요.'})}}});
  await s.edit('title','충돌해도 유지');await s.save();
  assert.equal(s.$('[data-listing-edit-form]').elements.title.value,'충돌해도 유지');
  assert.equal(s.$('[data-listing-photo-count]').textContent,'1/10');assert.equal(s.$('[data-listing-edit-save]').disabled,true);
  assert.match(s.$('[data-listing-edit-dirty]').textContent,/다른 곳에서 수정/);
  await s.navigate('market');assert.equal(s.$('[data-listing-editor]').hidden,false);
});
