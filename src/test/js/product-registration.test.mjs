import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync('src/main/resources/static/js/product-registration.js', 'utf8');
const window = {};
vm.runInNewContext(source, { window, Object, Set, String });
const registration = window.PastportProductRegistration;

test('보증 기준을 선택하지 않아도 입력한 기간과 단위를 유지한다', () => {
  assert.equal(registration.buildWarrantyTerms({ basis: '', duration: '2', unit: '년', extra: '' }), '2년');
  assert.equal(registration.buildWarrantyTerms({ basis: '', duration: '2', unit: '개월', extra: '소모품 제외' }), '2개월 · 소모품 제외');
  assert.equal(registration.buildWarrantyTerms({ basis: '', duration: '', unit: '년', extra: '' }), null);
});

test('사용량·횟수·특정 날짜 기준에는 기간 단위를 붙이지 않는다', () => {
  for (const basis of ['사용량 기준', '사용 횟수 기준', '특정 날짜까지']) {
    assert.equal(registration.buildWarrantyTerms({ basis, duration: '100', unit: '년', extra: '' }), `${basis} 100`);
    assert.equal(registration.usesDurationUnit(basis), false);
  }
  assert.equal(registration.buildWarrantyTerms({ basis: '구매일 기준', duration: '2', unit: '년', extra: '' }), '구매일 기준 2년');
});

test('평생 보증은 만료일을 전송하지 않는다', () => {
  assert.equal(registration.buildWarrantyTerms({ basis: '평생 보증', duration: '3', unit: '년', extra: '소모품 제외' }), '평생 보증 · 소모품 제외');
  assert.equal(registration.warrantyExpiresAt('평생 보증', '2030-01-01'), null);
  assert.equal(registration.warrantyExpiresAt('구매일 기준', '2030-01-01'), '2030-01-01');
});

test('목록 요청을 무효화하면 이전 응답은 현재 상태를 갱신할 수 없다', () => {
  const gate = registration.createLatestRequestGate();
  const beforeRegistration = gate.begin();
  gate.invalidate();
  const afterRegistration = gate.begin();
  assert.equal(gate.isCurrent(beforeRegistration), false);
  assert.equal(gate.isCurrent(afterRegistration), true);
});
