import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

/** 브라우저 전용 스크립트를 window 대역 위에서 실행해 입력값 변환·검증 함수를 꺼낸다. */
function load() {
  const context = { window: {}, document: {} };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(new URL('../../main/resources/static/js/doc-extraction.js', import.meta.url), 'utf8'), context);
  return context.window.PastportDocExtraction;
}

/** 검토 폼의 입력칸을 흉내 낸다. */
function form(values) {
  const keys = ['productName', 'modelName', 'purchaseDate', 'vendor', 'price', 'warrantyMonths', 'repairDate', 'repairDescription'];
  return { elements: Object.fromEntries(keys.map((key) => [key, { value: values[key] ?? '' }])) };
}

test('빈 칸은 null, 숫자 칸은 숫자로 바꿔 확정 요청 형태로 만든다', () => {
  const { collect } = load();
  const data = collect(form({ modelName: ' NT960XFG ', purchaseDate: '2026-03-01', price: '1250000', warrantyMonths: '12' }));
  assert.equal(data.modelName, 'NT960XFG');
  assert.equal(data.purchaseDate, '2026-03-01');
  assert.equal(data.price, 1250000);
  assert.equal(data.warrantyMonths, 12);
  assert.equal(data.productName, null);
  assert.equal(data.repairDate, null);
  assert.equal(data.repairDescription, null);
});

test('모든 칸이 비어 있으면 확정할 수 없다', () => {
  const { collect, validate } = load();
  assert.match(validate(collect(form({}))), /확정할 내용이 없어요/);
});

test('구매일이나 수리일이 있으면 통과한다', () => {
  const { collect, validate } = load();
  assert.equal(validate(collect(form({ purchaseDate: '2026-03-01' }))), '');
  assert.equal(validate(collect(form({ repairDate: '2026-09-05' }))), '');
});

test('날짜가 하나도 없으면 이력을 만들 수 없어 확정을 막는다', () => {
  const { collect, validate } = load();
  assert.match(validate(collect(form({ vendor: 'OO전자' }))), /구매일 또는 수리일/);
});

test('안전하게 표현할 수 없는 큰 금액은 반올림되어 전송되지 않도록 막는다', () => {
  const { collect, validate } = load();
  // 9007199254740993은 숫자로 바꾸면 ...992로 반올림된다.
  assert.match(validate(collect(form({ purchaseDate: '2026-03-01', price: '9007199254740993' }))), /구매 금액/);
  assert.equal(validate(collect(form({ purchaseDate: '2026-03-01', price: '9007199254740991' }))), '');
});

test('이력 내용이 2000자를 넘으면 서버가 거부하기 전에 확정을 막는다', () => {
  const { collect, validate } = load();
  const longDescription = '가'.repeat(2000);
  // 제품명 줄이 더해져 2000자를 넘는다.
  assert.match(validate(collect(form({ productName: '갤럭시북4', purchaseDate: '2026-03-01', repairDescription: longDescription }))), /너무 길어요/);
  assert.equal(validate(collect(form({ purchaseDate: '2026-03-01', repairDescription: '가'.repeat(1000) }))), '');
});

const values = { productName: '갤럭시북4', modelName: 'NT960XGQ', vendor: 'OO전자', price: '1250000', warrantyMonths: '12' };

test('미리보기: 구매일만 있으면 구매 이력 한 건', () => {
  const { collect, previewEvents } = load();
  const events = Array.from(previewEvents(collect(form({ ...values, purchaseDate: '2026-03-01' }))));
  assert.equal(events.length, 1);
  assert.equal(events[0].type, '구매');
  assert.equal(events[0].title, '갤럭시북4 구매');
  assert.equal(events[0].content, '제품명: 갤럭시북4\n모델명: NT960XGQ\n보증기간: 12개월');
  assert.equal(events[0].vendor, 'OO전자');
  assert.equal(events[0].price, 1250000);
});

test('미리보기: 둘 다 있으면 제품 정보는 구매 이력에만, 수리 이력은 수리 내용만', () => {
  const { collect, previewEvents } = load();
  const events = Array.from(previewEvents(collect(form({ ...values, purchaseDate: '2026-03-01', repairDate: '2026-09-05', repairDescription: '메인보드 교체' }))));
  assert.deepEqual(events.map(event => event.type), ['구매', '수리']);
  assert.equal(events[1].content, '수리 내용: 메인보드 교체');
  assert.equal(events[1].vendor, null);
  assert.equal(events[1].price, null);
});

test('미리보기: 날짜가 없으면 이력이 없다', () => {
  const { collect, previewEvents } = load();
  assert.equal(previewEvents(collect(form(values))).length, 0);
});

test('금액은 0 이상 정수, 보증기간은 0~120개월만 허용한다', () => {
  const { collect, validate } = load();
  assert.match(validate(collect(form({ price: '-1' }))), /구매 금액/);
  assert.match(validate(collect(form({ price: '1000.5' }))), /구매 금액/);
  assert.match(validate(collect(form({ warrantyMonths: '121' }))), /보증기간/);
  assert.equal(validate(collect(form({ purchaseDate: '2026-03-01', price: '0', warrantyMonths: '120' }))), '');
});
