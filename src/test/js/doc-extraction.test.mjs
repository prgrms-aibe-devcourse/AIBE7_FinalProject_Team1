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

test('값이 하나라도 있으면 통과한다', () => {
  const { collect, validate } = load();
  assert.equal(validate(collect(form({ vendor: 'OO전자' }))), '');
});

test('금액은 0 이상 정수, 보증기간은 0~120개월만 허용한다', () => {
  const { collect, validate } = load();
  assert.match(validate(collect(form({ price: '-1' }))), /구매 금액/);
  assert.match(validate(collect(form({ price: '1000.5' }))), /구매 금액/);
  assert.match(validate(collect(form({ warrantyMonths: '121' }))), /보증기간/);
  assert.equal(validate(collect(form({ price: '0', warrantyMonths: '120' }))), '');
});
