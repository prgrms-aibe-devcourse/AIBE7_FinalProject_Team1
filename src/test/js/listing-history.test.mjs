import test from 'node:test';
import assert from 'node:assert/strict';
import { environment } from './dom.mjs';

test('공개 이력은 상세와 정정 사유를 표시하고 제외된 사유는 대기 중으로 안내하지 않는다', () => {
  const env = environment();
  const root = env.add('ol');
  env.run('listing-detail.js');
  env.window.PastportListingDetail.renderTimeline(root, [{
    occurredOn: '2026-11-01', eventType: 'REPAIR', status: 'VALID', title: '배터리 점검',
    content: '<b>충전 동작 확인</b>', serviceProvider: '공식 서비스센터', replacedPart: '배터리',
    previousOwner: true, systemGenerated: false, detailsStatus: 'REVIEWED',
    corrections: [{ reason: '발생일 오기입 정정', detailsStatus: 'REVIEWED' }, { reason: null, detailsStatus: 'EXCLUDED' }]
  }]);
  assert.match(root.textContent, /배터리 점검/);
  assert.match(root.textContent, /<b>충전 동작 확인<\/b>/);
  assert.equal(root.querySelector('.sl-publication-content').innerHTML, '');
  assert.match(root.textContent, /공식 서비스센터 · 배터리/);
  assert.match(root.textContent, /이전 소유자 기록/);
  assert.match(root.textContent, /발생일 오기입 정정/);
  assert.match(root.textContent, /공개 제외 정보가 포함되어/);
  assert.doesNotMatch(root.textContent, /확인 중|승인|검토 중/);
});

test('본문이 제외되어도 무효 이력의 존재와 날짜는 표시한다', () => {
  const env = environment();
  const root = env.add('ol');
  env.run('listing-detail.js');
  env.window.PastportListingDetail.renderTimeline(root, [{
    occurredOn: '2026-11-01', eventType: 'REPAIR', status: 'VOID', title: '제품 수리',
    content: null, serviceProvider: null, replacedPart: null, detailsStatus: 'EXCLUDED',
    corrections: [{ reason: null, detailsStatus: 'EXCLUDED' }]
  }]);
  assert.match(root.textContent, /2026.11.01/);
  assert.match(root.textContent, /무효 처리/);
  assert.match(root.textContent, /공개 가능한 상세 내용이 없어요/);
  assert.equal(root.querySelectorAll('.sl-publication-content').length, 0);
  assert.doesNotMatch(root.textContent, /확인 중|검토 중/);
});
