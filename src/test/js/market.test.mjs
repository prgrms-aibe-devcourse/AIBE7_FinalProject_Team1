import test from 'node:test';
import assert from 'node:assert/strict';
import {environment, flush} from './dom.mjs';

async function screen() {
  const env = environment(()=>({items:[{listingId:8,title:'이력 있는 노트북',price:123000,conditionGrade:4,publishedAt:'2026-11-01T00:00:00Z',
    manufacturer:'Apple',modelName:'노트북 모델',specifications:{cpu:'CPU',ramGb:16},aiSummary:{status:'READY',text:'정정된 점검 기록이 있는 제품입니다.'}}],
    page:{number:0,size:20,totalPages:1,totalElements:1,hasNext:false}}));
  env.window.location.href='http://test.invalid/?market=1';
  env.window.history={state:null,replaceState(state,title,url){env.window.location.href=String(url);}};
  const root=env.add('section',{id:'market',className:'page active'});
  const form=env.add('form',{'data-market-form':'',id:'market-form'},root); form.elements={}; form.reportValidity=()=>true;
  for(const name of ['keyword','category','manufacturer','minPrice','maxPrice','cpu','gpu','minRamGb','minStorageGb','storageType','os','minConditionGrade']) {
    form.elements[name]=env.add('input',{},form); form.elements[name].value='';
  }
  const nodes={};
  for(const name of ['sort','size','reset','retry','prev','next','total','pagination','message','items','page']) nodes[name]=env.add('div',{['data-market-'+name]:''},root);
  nodes.size.options=[{value:'20'}]; nodes.size.add=option=>nodes.size.options.push(option);
  env.add('select',{'data-market-manufacturer-select':''},form);
  env.add('label',{'data-market-manufacturer-custom':''},form);
  env.add('details',{className:'mk-spec-fields'},form);
  env.run('categories.js');
  env.run('market.js',{setTimeout:()=>0,clearTimeout(){},Option:function(text,value){const n=env.document.createElement('option');n.textContent=text;n.value=String(value);return n;}});
  await env.document.emit('DOMContentLoaded'); await flush();
  return {...env,root,form,nodes};
}

test('카드는 제목·가격·AI 요약·상태 순서로 읽히며 중복 사양을 반복하지 않는다', async()=>{
  const s=await screen();
  const card=s.nodes.items.children[0];
  const body=card.querySelector('.mk-body');
  assert.equal(body.children[0].textContent,'이력 있는 노트북');
  assert.equal(body.children[1].textContent,'123,000원');
  assert.match(body.children[2].textContent,/정정된 점검/);
  assert.match(body.children[3].textContent,/상태 4등급/);
  assert.equal(body.children.length,4);
  assert.equal(card.href,'/?listingId=8');
});

test('노트북에서 제조사·가격·상태·사양 조건을 함께 검색하고 주소에 보관한다', async()=>{
  const s=await screen();
  for(const [name,value] of Object.entries({category:'LAPTOP',manufacturer:'Apple',minPrice:'100,000',maxPrice:'300,000',minConditionGrade:'4',cpu:'M3'})) s.form.elements[name].value=value;
  await s.form.emit('submit'); await flush();
  const query=new URL(s.calls.at(-1).path,'http://test.invalid').searchParams;
  assert.equal(query.get('category'),'LAPTOP');
  assert.equal(query.get('manufacturer'),'Apple');
  assert.equal(query.get('minPrice'),'100000');
  assert.equal(query.get('maxPrice'),'300000');
  assert.equal(query.get('minConditionGrade'),'4');
  assert.equal(query.get('cpu'),'M3');
  assert.equal(new URL(s.window.location.href).searchParams.get('cpu'),'M3');
  s.form.elements.minPrice.value='400000';
  const before=s.calls.length; await s.form.emit('submit');
  assert.equal(s.calls.length,before);
  assert.equal(s.document.activeElement,s.form.elements.minPrice);
});

test('전체 전자제품에서 시작하고 카테고리를 바꾼 뒤에도 전체 검색으로 초기화할 수 있다', async()=>{
  const s=await screen();
  assert.equal(new URL(s.calls[0].path,'http://test.invalid').searchParams.has('category'),false);
  s.form.elements.category.value='LAPTOP'; s.form.elements.manufacturer.value='Dyson';
  await s.form.elements.category.emit('change'); await flush();
  const selected=new URL(s.calls.at(-1).path,'http://test.invalid').searchParams;
  assert.equal(selected.get('category'),'LAPTOP');
  assert.equal(selected.has('manufacturer'),false);
  await s.nodes.reset.emit('click'); await flush();
  assert.equal(s.form.elements.category.value,'');
  assert.equal(new URL(s.calls.at(-1).path,'http://test.invalid').searchParams.has('category'),false);
});

test('제조사는 목록에서 선택하고 등록된 목록에 없는 이름도 직접 검색할 수 있다', async()=>{
  const s=await screen();
  const select=s.root.querySelector('[data-market-manufacturer-select]');
  const custom=s.root.querySelector('[data-market-manufacturer-custom]');
  assert.equal(custom.hidden,true);
  assert.ok(select.children.some(option=>option.value==='Samsung'));
  select.value='Samsung'; await select.emit('change'); await flush();
  assert.equal(new URL(s.calls.at(-1).path,'http://test.invalid').searchParams.get('manufacturer'),'Samsung');
  assert.equal(custom.hidden,true);
  select.value=select.children.find(option=>option.textContent==='직접 입력').value;
  await select.emit('change');
  assert.equal(custom.hidden,false);
  assert.equal(s.document.activeElement,s.form.elements.manufacturer);
  s.form.elements.manufacturer.value='직접 입력한 제조사';
  await s.form.emit('submit'); await flush();
  assert.equal(new URL(s.calls.at(-1).path,'http://test.invalid').searchParams.get('manufacturer'),'직접 입력한 제조사');
  assert.equal(custom.hidden,false);
  await s.nodes.reset.emit('click'); await flush();
  assert.equal(custom.hidden,true);
  assert.equal(select.value,'');
});
