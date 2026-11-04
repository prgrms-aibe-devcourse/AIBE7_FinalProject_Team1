import test from 'node:test';
import assert from 'node:assert/strict';
import { Node, environment, flush } from './dom.mjs';

// 대역 노드에 없는 select.add를 보충한다.
Node.prototype.add ??= function add(child) { this.append(child); };
// 실제 form.elements처럼 이름 있는 입력칸을 이름으로 찾게 한다.
Object.defineProperty(Node.prototype, 'elements', {
  configurable: true,
  get() { return Object.fromEntries(this.querySelectorAll('*').filter(node => node.name).map(node => [node.name, node])); },
});

const ITEM = { id: 7, productId: 11, redactionRequired: false };
const FACTS = { productName: '갤럭시북4', modelName: null, purchaseDate: '2026-03-01', vendor: 'OO전자', price: 1200000, warrantyMonths: 12, repairDate: null, repairDescription: null };

function envelope(status, body) {
  return { response: { ok: status < 400, status, async json() { return body; } } };
}

/** 추출 패널을 대역 환경에 올리고, 요청 경로별로 응답을 정하는 핸들러를 쓴다. */
function panelWith(handler, options = {}) {
  const env = environment(handler);
  env.window.PastportApi = { fetch: (path, init) => env.window.fetch(path, init) };
  const state = {};
  env.window.open = (...args) => { state.opened = args; };
  const Option = function Option(label, value) { const option = env.document.createElement('option'); option.text = label; option.value = value; return option; };
  env.run('doc-extraction.js', { setTimeout, clearTimeout, Option });
  const panel = env.window.PastportDocExtraction.panel(ITEM, options);
  env.document.root.append(panel.element);
  return { ...env, panel, state };
}

const all = (node, selector) => node.querySelectorAll(selector);
const buttonByText = (node, text) => all(node, 'button').find(button => button.textContent === text);

test('요청 폼에 AI 없이 직접 입력하는 길이 보이고 제품 여권으로 이동한다', async () => {
  const s = panelWith(path => path.endsWith('/latest') ? envelope(404, { message: '없음' }) : {});
  s.panel.toggle(); await flush();

  const manual = buttonByText(s.panel.element, '이력 등록에서 직접 입력');
  assert.ok(manual, '직접 입력 버튼이 있어야 한다');
  // 제품 여권 카드와 같은 방식(data-page + data-product-id)으로 이동해, 다른 화면의 코드를 건드리지 않는다.
  assert.equal(manual.dataset.page, 'passport');
  assert.equal(manual.dataset.productId, ITEM.productId);
});

test('추출 실패 뒤에도 직접 입력하는 길이 남아 있다', async () => {
  const s = panelWith(path => path.endsWith('/latest') ? { id: 3, status: 'FAILED' } : {});
  s.panel.toggle(); await flush();

  assert.ok(buttonByText(s.panel.element, '이력 등록에서 직접 입력'));
  assert.match(s.panel.element.textContent, /직접 입력해 주세요/);
});

test('동의 안내에 처리 목적과 동의하지 않아도 쓸 수 있다는 내용이 들어 있다', async () => {
  const s = panelWith(path => path.endsWith('/latest') ? envelope(404, {}) : {});
  s.panel.toggle(); await flush();

  const text = s.panel.element.textContent;
  assert.match(text, /목적/);
  assert.match(text, /동의하지 않아도/);
});

test('검토 화면에서 문서 보기가 파일 주소를 받아 모달 팝업으로 연다', async () => {
  const s = panelWith(path => {
    if (path.endsWith('/latest')) return { id: 3, status: 'EXTRACTED', extractedFacts: FACTS };
    if (path.endsWith('/file-url')) return { url: 'https://files.invalid/doc-7?sig=abc' };
    return {};
  });
  s.panel.toggle(); await flush();

  const view = buttonByText(s.panel.element, '문서 보기');
  assert.ok(view, '검토 화면에 문서 보기 버튼이 있어야 한다');
  await view.emit('click'); await flush();

  assert.ok(s.calls.some(call => call.path === '/api/v1/documents/7/file-url'));
  const dialog = s.document.querySelector('[data-doc-preview-dialog]');
  assert.ok(dialog);
  assert.equal(dialog.open, true);
  assert.equal(dialog.querySelector('[data-doc-preview-img]').getAttribute('src'), 'https://files.invalid/doc-7?sig=abc');
});

test('확정에 성공하면 보관함이 삭제 버튼을 거두도록 알린다', async () => {
  let changed = 0;
  const s = panelWith(path => {
    if (path.endsWith('/latest')) return { id: 3, status: 'EXTRACTED', extractedFacts: FACTS };
    if (path.endsWith('/confirm')) return { id: 3, status: 'CONFIRMED', resultEventId: 900 };
    return {};
  }, { onChanged: () => { changed += 1; } });
  s.panel.toggle(); await flush();

  const form = s.panel.element.querySelector('form');
  await form.emit('submit'); await flush();

  assert.equal(changed, 1);
  assert.match(s.panel.element.textContent, /제품 이력에 반영됐어요/);
  assert.doesNotMatch(s.panel.element.textContent, /#900/);
});

test('추출 요청이 409면 기존 추출 상태를 다시 불러온다', async () => {
  let latest = 0;
  const s = panelWith((path, options) => {
    if (path.endsWith('/latest')) {
      latest += 1;
      // 처음에는 기록이 없다가, 요청이 409로 거절된 뒤에는 진행 중인 추출이 보인다.
      return latest === 1 ? envelope(404, {}) : { id: 5, status: 'EXTRACTED', extractedFacts: FACTS };
    }
    if (options?.method === 'POST') return envelope(409, { message: '이미 진행 중이거나 완료된 AI 추출이 있습니다.' });
    return {};
  });
  s.panel.toggle(); await flush();

  const form = s.panel.element.querySelector('form');
  const consent = all(form, '*').find(node => node.name === 'consented');
  consent.checked = true; await consent.emit('change');
  await form.emit('submit'); await flush();

  assert.equal(latest, 2);
  // 요청 폼이 아니라 기존 추출의 검토 화면이 보여야 한다.
  assert.ok(buttonByText(s.panel.element, '확인하고 확정'));
});
