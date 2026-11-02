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
    corrections: [{ action: 'HISTORY_CORRECTED', reason: '발생일 오기입 정정', detailsStatus: 'REVIEWED' }, { action: 'HISTORY_CORRECTED', reason: null, detailsStatus: 'EXCLUDED' }]
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
    corrections: [{ action: 'HISTORY_VOIDED', reason: null, detailsStatus: 'EXCLUDED' }]
  }]);
  assert.match(root.textContent, /2026.11.01/);
  assert.match(root.textContent, /무효 처리/);
  assert.match(root.textContent, /공개 가능한 상세 내용이 없어요/);
  assert.equal(root.querySelectorAll('.sl-publication-content').length, 0);
  assert.doesNotMatch(root.textContent, /확인 중|검토 중/);
});

test('정정 뒤 무효 처리해도 각 사유는 해당 처리 종류로 구분한다', () => {
  const env = environment();
  const root = env.add('ol');
  env.run('listing-detail.js');
  env.window.PastportListingDetail.renderTimeline(root, [{
    occurredOn: '2026-11-01', eventType: 'INSPECTION', status: 'VOID', title: '제품 점검',
    detailsStatus: 'REVIEWED',
    corrections: [
      { action: 'HISTORY_VOIDED', reason: '같은 점검을 중복 등록함', detailsStatus: 'REVIEWED' },
      { action: 'HISTORY_CORRECTED', reason: '점검일 오기입을 바로잡음', detailsStatus: 'REVIEWED' },
      { action: 'HISTORY_CORRECTED', reason: '점검 제목을 바로잡음', detailsStatus: 'REVIEWED' }
    ]
  }]);
  assert.deepEqual(root.querySelectorAll('.sl-publication-correction').map(node => node.textContent), [
    '무효 사유: 같은 점검을 중복 등록함',
    '정정 사유: 점검일 오기입을 바로잡음',
    '정정 사유: 점검 제목을 바로잡음'
  ]);
  assert.doesNotMatch(root.textContent, /무효·정정 사유|확인 중/);
});

test('사유가 공개 제외되어도 정정과 무효의 처리 종류는 구분한다', () => {
  const env = environment();
  const root = env.add('ol');
  env.run('listing-detail.js');
  env.window.PastportListingDetail.renderTimeline(root, [{
    occurredOn: '2026-11-01', eventType: 'INSPECTION', status: 'VOID', title: '제품 점검',
    detailsStatus: 'EXCLUDED',
    corrections: [
      { action: 'HISTORY_VOIDED', reason: null, detailsStatus: 'EXCLUDED' },
      { action: 'HISTORY_CORRECTED', reason: null, detailsStatus: 'EXCLUDED' }
    ]
  }]);
  const reasons = root.querySelectorAll('.sl-publication-correction');
  assert.match(reasons[0].textContent, /^무효 사유: 공개 제외 정보/);
  assert.match(reasons[1].textContent, /^정정 사유: 공개 제외 정보/);
  assert.doesNotMatch(root.textContent, /확인 중|무효·정정 사유/);
});

test('처리 종류가 없는 과거 응답을 현재 무효 상태만으로 추측하지 않는다', () => {
  const env = environment();
  const root = env.add('ol');
  env.run('listing-detail.js');
  env.window.PastportListingDetail.renderTimeline(root, [{
    occurredOn: '2026-11-01', eventType: 'INSPECTION', status: 'VOID', title: '제품 점검',
    detailsStatus: 'REVIEWED',
    corrections: [{ reason: '발생일 오기입을 바로잡음', detailsStatus: 'REVIEWED' }]
  }]);
  assert.equal(root.querySelector('.sl-publication-correction').textContent, '변경 사유: 발생일 오기입을 바로잡음');
  assert.match(root.textContent, /무효 사유를 확인할 수 없어요/);
  assert.doesNotMatch(root.textContent, /무효·정정 사유|정정 사유:|무효 사유:/);
});
