import test from 'node:test';
import assert from 'node:assert/strict';
import { environment, deferred } from './dom.mjs';

function panel(handler, scope = 'listings', globals = {}) {
  const env = environment(handler);
  const container = env.add('div');
  env.run('product-questions.js', globals);
  env.window.PastportProductQuestions.mount(container, { scope, id: 5 });
  const root = container.querySelector('.product-questions');
  const form = root.querySelector('form'), input = root.querySelector('textarea');
  form.reportValidity = () => true;
  input.value = '';
  return { ...env, root, form, input, output: root.querySelector('.pq-output'), submit: form.querySelector('button') };
}

const answer = { answer: '등록된 메모리는 16GB예요.', sources: [] };
const failure = (status, retryAfter = null) => ({ response: {
  ok: false, status, headers: { get: () => retryAfter }, async json() { return {}; }
} });

test('판매글: 전송한 질문은 입력란에서 지우고 요청·답변 영역에 같은 내용으로 유지한다', async () => {
  const pending = deferred(), env = panel(() => pending.promise);
  const question = '현재 메모리 사양과\n교체 기록을 알려줘';
  env.input.value = '  ' + question + '  ';
  const sending = env.form.emit('submit');
  assert.equal(env.input.value, '');
  assert.equal(env.input.disabled, true);
  assert.equal(env.output.hidden, false);
  assert.equal(env.output.querySelector('.pq-question').textContent, question);
  assert.equal(env.calls[0].path, '/api/v1/listings/5/ai/questions');
  assert.deepEqual(JSON.parse(env.calls[0].options.body), { question });
  await env.form.emit('submit');
  assert.equal(env.calls.length, 1, '답변 대기 중에는 중복 전송하지 않는다');

  pending.resolve(answer);
  await sending;
  assert.equal(env.input.value, '');
  assert.equal(env.input.disabled, false);
  assert.equal(env.submit.disabled, false);
  assert.equal(env.output.querySelector('.pq-question').textContent, question);
  assert.equal(env.output.querySelector('.pq-answer').textContent, answer.answer);
  assert.deepEqual(env.output.querySelectorAll('h3').map(node => node.textContent), ['내 질문', '등록된 기록에 따른 답변']);
});

test('제품 여권에는 질문 패널을 다시 만들지 않는다', async () => {
  const env = environment();
  const passport = env.add('section', { id: 'passport' });
  const container = env.add('div', { 'data-product-detail': '' }, passport);
  env.run('product-questions.js');
  env.window.PastportProductQuestions.mount(container, { scope: 'products', id: 5 });
  await env.document.emit('pastport:product-detail-rendered', { product: { productId: 5 } });
  assert.equal(passport.querySelector('.product-questions'), null);
  assert.equal(env.calls.length, 0);
});

test('다음 질문은 이전 질문·답변을 교체하고 HTML처럼 보이는 질문도 텍스트로 표시한다', async () => {
  const next = deferred();
  const env = panel((path, options, calls) => calls.length === 1 ? answer : next.promise);
  env.input.value = '메모리는 몇 GB인가요?';
  await env.form.emit('submit');
  const question = '<img src=x onerror=alert(1)>\n보증은 언제까지인가요?';
  env.input.value = question;
  const sending = env.form.emit('submit');
  assert.equal(env.output.querySelectorAll('.pq-question').length, 1);
  assert.equal(env.output.querySelector('.pq-answer'), null);
  assert.equal(env.output.querySelector('.pq-question').textContent, question);
  assert.equal(env.output.querySelector('.pq-question').innerHTML, '');
  next.resolve({ answer: '보증 만료일은 2027-11-01이에요.', sources: [{ type: 'WARRANTY', label: '보증 정보', facts: { warrantyExpiresOn: '2027-11-01', asOf: '2026-11-02' } }] });
  await sending;
  assert.equal(env.input.value, '');
  assert.equal(env.output.querySelector('.pq-question').textContent, question);
  assert.match(env.output.querySelector('.pq-answer').textContent, /2027-11-01/);
  assert.match(env.output.querySelector('details').textContent, /답변에 사용한 근거 1개/);
  assert.doesNotMatch(env.output.textContent, /메모리는 몇 GB|16GB/);
});

for (const [name, handler] of [
  ['잘못된 요청', () => failure(400)],
  ['제품 정보 변경', () => failure(409)],
  ['서버 오류', () => failure(503)],
  ['연결 실패', () => Promise.reject(new TypeError('Failed to fetch'))],
  ['잘못된 답변 형식', () => ({ answer: null, sources: [] })]
]) {
  test(`${name}: 실패한 질문을 입력란에 복구해 수정·재전송할 수 있다`, async () => {
    const env = panel(handler);
    const draft = '  수리 기록을\n알려줘  ';
    env.input.value = draft;
    await env.form.emit('submit');
    assert.equal(env.input.value, draft);
    assert.equal(env.input.disabled, false);
    assert.equal(env.submit.disabled, false);
    assert.equal(env.output.hidden, true);
    assert.equal(env.output.textContent, '');
    assert.equal(env.root.querySelector('.pq-message').classList.contains('is-error'), true);
  });
}

test('요청 제한 때 질문을 복구하고 대기 시간이 지난 뒤 수정한 질문을 보낸다', async () => {
  let now = Date.now();
  const env = panel((path, options, calls) => calls.length === 1 ? failure(429, '10') : answer,
    'listings', { Date: class extends Date { static now() { return now; } } });
  env.input.value = '최근 수리 기록이 있나요?';
  await env.form.emit('submit');
  assert.equal(env.input.value, '최근 수리 기록이 있나요?');
  assert.equal(env.input.disabled, false);
  assert.equal(env.submit.disabled, true);
  assert.equal(env.submit.textContent, '10초 후 다시 질문');
  env.input.value = '현재 메모리 사양을 알려줘';
  await env.form.emit('submit');
  assert.equal(env.calls.length, 1);
  now += 10000;
  for (const tick of env.intervals.values()) tick();
  assert.equal(env.submit.disabled, false);
  await env.form.emit('submit');
  assert.equal(env.calls.length, 2);
  assert.deepEqual(JSON.parse(env.calls[1].options.body), { question: '현재 메모리 사양을 알려줘' });
  assert.equal(env.input.value, '');
  assert.equal(env.output.querySelector('.pq-question').textContent, '현재 메모리 사양을 알려줘');
});

for (const status of [401, 404]) {
  test(`${status}: 로그인·조회 권한을 잃으면 질문을 입력란이나 답변 영역에 남기지 않는다`, async () => {
    const env = panel(() => failure(status));
    env.input.value = '이 제품의 수리 기록을 알려줘';
    await env.form.emit('submit');
    assert.equal(env.input.value, '');
    assert.equal(env.output.hidden, true);
    assert.equal(env.output.textContent, '');
    assert.equal(env.input.disabled, false);
    assert.match(env.root.querySelector('.pq-message').textContent, status === 401 ? /로그인 후/ : /질문할 수 없어요/);
  });
}

for (const event of ['pastport:page-shown', 'pastport:auth-changed']) {
  test(`${event}: 화면·계정 전환 뒤 도착한 응답이 질문을 복원하거나 새 화면을 바꾸지 않는다`, async () => {
    const pending = deferred(), env = panel(() => pending.promise);
    env.input.value = '이전 판매글의 수리 기록';
    const sending = env.form.emit('submit');
    await env.document.emit(event, { id: 'home' });
    assert.equal(env.calls[0].options.signal.aborted, true);
    assert.equal(env.root.isConnected, false);
    env.window.PastportProductQuestions.mount(env.document.root, { scope: 'listings', id: 6 });
    const newRoot = env.document.querySelector('.product-questions');
    const newInput = newRoot.querySelector('textarea');
    newInput.value = '새 판매글에 작성 중인 질문';
    pending.resolve(failure(503));
    await sending;
    assert.equal(env.input.value, '');
    assert.equal(newInput.value, '새 판매글에 작성 중인 질문');
    assert.equal(newRoot.querySelector('.pq-output').hidden, true);
    assert.equal(newRoot.querySelector('.pq-message').textContent, '');
  });
}
