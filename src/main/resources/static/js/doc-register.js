// 새 제품 등록 2단계의 "문서로 자동 입력": 마스킹 팝업 → 저장 없는 미리보기 추출 → 등록 폼 채우기 → 제품 등록 뒤 증빙 문서로 보관.
// 제품 등록 화면(register.html, products.js)은 고치지 않고, 이미 있는 요소에 붙어서 동작한다.
(() => {
  'use strict';

  const PREVIEW_URL = '/api/v1/documents/extraction-preview';
  const PRODUCTS_URL = '/api/v1/products';
  const NOTICE =
    '제품 정보를 읽어 폼을 채울 목적으로, 개인정보가 가려진 사본이 외부 AI(Gemini)로 전송돼요. 원본과 가려진 개인정보는 전송되지 않고, 동의하지 않아도 직접 입력해 등록할 수 있어요.';
  const TYPES = [['RECEIPT', '영수증'], ['WARRANTY', '보증서'], ['OTHER', '기타']];

  // 마스킹을 마친 사본. 제품 등록이 성공하면 증빙 문서로 올리고, 그 전에는 이 브라우저 메모리에만 둔다.
  let pending = null;
  let busy = false;
  // 사본을 버리거나(새 문서 선택·사본 지우기·등록 완료) 화면을 떠날 때마다 올라간다. 그 전에 시작한 추출 요청의 늦은 응답을 버리는 데 쓴다.
  let generation = 0;
  // 등록 화면의 안내 문구·버튼·동의 상태를 처음으로 되돌린다. 화면이 만들어질 때 prepare()가 채운다.
  let resetUi = () => {};

  const create = (tag, text, className = '') => {
    const node = document.createElement(tag);
    node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };

  // action이 있으면 알림 안에 버튼을 넣고 더 오래 보여 준다. 누르면 알림을 닫고 action.run()을 부른다.
  const toast = (message, kind = '', action = null) => {
    document.querySelector('.doc-register-toast')?.remove();
    const box = create('div', message, `product-toast doc-register-toast ${kind}`.trim());
    if (action) {
      const retry = create('button', action.label, 'doc-register-toast-action');
      retry.type = 'button';
      retry.addEventListener('click', () => {
        box.remove();
        action.run();
      });
      box.append(' ', retry);
    }
    document.body.append(box);
    window.setTimeout(() => box.remove(), action ? 20000 : 5000);
  };

  const request = (url, options = {}) => {
    const headers = new Headers(options.headers || {});
    const csrf = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-Token';
    if (csrf && options.method && options.method !== 'GET') headers.set(csrfHeader, csrf);
    const send = window.PastportApi?.fetch || window.fetch.bind(window);
    return send(url, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
  };

  const fileName = (blob) => `masked.${blob.type === 'image/jpeg' ? 'jpg' : 'png'}`;

  // "2026-01-31"에 개월 수를 더한다. 말일이 없는 달이면 그 달 말일로 맞춘다.
  function addMonths(isoDate, months) {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate || '');
    if (!match || !Number.isInteger(months) || months < 1) return null;
    const year = Number(match[1]);
    const monthIndex = Number(match[2]) - 1 + months;
    const lastDay = new Date(Date.UTC(year, monthIndex + 1, 0)).getUTCDate();
    const result = new Date(Date.UTC(year, monthIndex, Math.min(Number(match[3]), lastDay)));
    return result.toISOString().slice(0, 10);
  }

  // 자동 입력으로 채운 보증 만료일을 계산한 보증 개월 수. 구매일을 고치면 이 값으로 만료일을 다시 계산한다.
  let autofillMonths = null;

  // 구매일을 사용자가 고쳤을 때, 만료일이 아직 자동 입력값이면 고친 날짜로 다시 계산한다.
  // 날짜를 키보드로 입력하는 중에는 값이 잠깐 비어 있다가(예: "0"만 친 순간) 완성되므로, 입력 중(input)에는 계산되는 날짜가 있을 때만 바꾸고
  // 만료일을 비우는 것은 사용자가 구매일 칸을 떠났을 때(focusout, final)만 한다. 계산할 수 없는 채로 끝나면 틀린 날짜가 남지 않게 비운다.
  function recomputeWarranty(form, final) {
    const expiry = form.elements.warrantyExpiresAt;
    if (!expiry || !expiry.dataset.docAutofilled || !autofillMonths) return;
    const next = addMonths(form.elements.purchaseDate?.value, autofillMonths);
    if (!next && !final) return;
    expiry.value = next || '';
    if (!next) delete expiry.dataset.docAutofilled;
    expiry.dispatchEvent(new Event('input', { bubbles: true }));
    expiry.dispatchEvent(new Event('change', { bubbles: true }));
  }

  // 자동 입력으로 채웠고 사용자가 고치지 않은 칸의 값을 지운다. 값의 출처인 문서(사본)를 버릴 때 함께 정리해, 이전 문서의 값이 다음 등록에 남지 않게 한다.
  function clearAutofilled(form) {
    autofillMonths = null;
    form.querySelectorAll('[data-doc-autofilled]').forEach((input) => {
      delete input.dataset.docAutofilled;
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
  }

  // 비어 있는 칸에만 값을 넣는다. 사용자가 이미 입력한 값은 덮어쓰지 않는다.
  function fill(form, facts) {
    const filled = [];
    const set = (name, label, value, max) => {
      const input = form.elements[name];
      if (!input || value == null || value === '' || input.value.trim() !== '') return false;
      input.value = max ? String(value).slice(0, max) : String(value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      input.dataset.docAutofilled = 'true';
      filled.push(label);
      return true;
    };
    set('title', '제품 이름', facts.productName, 200);
    set('modelName', '모델명', facts.modelName, 100);
    set('purchaseDate', '구매일', facts.purchaseDate);
    const base = form.elements.purchaseDate?.value || facts.purchaseDate;
    if (set('warrantyExpiresAt', '보증 만료일', addMonths(base, facts.warrantyMonths))) autofillMonths = facts.warrantyMonths;
    return filled;
  }

  function prepare() {
    const assist = document.querySelector('#register .product-document-assist');
    const form = document.querySelector('#register [data-product-form]');
    if (!assist || !form || assist.dataset.docRegister) return;

    const input = assist.querySelector('[data-product-document]');
    const notice = assist.querySelector('.product-document-notice');
    // 문서 선택 칸이 없으면 붙일 곳이 없다. 화면이 늦게 채워질 수 있으니 표시를 남기지 않고 나중에 다시 시도한다.
    if (!input) return;
    assist.dataset.docRegister = 'true';

    // 등록 화면은 문서를 고르면 제품 등록 뒤 마스킹 화면으로 이동하는 흐름이다. 그 흐름은 그대로 두고,
    // 자동 입력 버튼은 여기서 직접 만들어 넣는다. 화면에 이미 같은 버튼이 있으면 그것을 쓴다.
    let button = assist.querySelector('.product-document-extract');
    if (!button) {
      button = create('button', '개인정보 가리고 자동 입력', 'button product-document-extract');
      button.type = 'button';
      if (notice) notice.before(button);
      else assist.append(button);
    }

    // 안내 문구를 실제 동작에 맞게 바꾼다. 자동 입력을 쓰면 원본이 아니라 가려진 사본이 AI로 전송된다.
    const dropNote = assist.querySelector('.product-document-drop small');
    if (dropNote) dropNote.textContent = '선택 사항 · JPG, PNG · 원본은 서버로 전송되지 않고, 가려진 사본만 사용돼요.';
    if (notice) {
      notice.querySelector('b').textContent = '두 가지 방법이 있어요';
      notice.querySelector('p').textContent =
        '문서만 선택하면 제품 등록 뒤 마스킹 화면에서 가려 저장해요. "개인정보 가리고 자동 입력"을 쓰면 지금 가린 뒤 제품 정보를 읽어 입력하고, 제품을 등록할 때 가려진 사본을 증빙 문서로 함께 보관해요.';
    }

    const options = create('div', null, 'doc-register-options');
    const type = create('select');
    type.setAttribute('aria-label', '문서 종류');
    TYPES.forEach(([value, label]) => type.add(new Option(label, value)));
    const consent = create('label', null, 'doc-register-consent');
    const consentBox = create('input');
    consentBox.type = 'checkbox';
    consent.append(consentBox, create('span', NOTICE));
    const status = create('p', '', 'doc-register-status');
    status.setAttribute('role', 'status');
    status.setAttribute('aria-live', 'polite');
    const discard = create('button', '가려진 사본 지우기', 'button small doc-register-discard');
    discard.type = 'button';
    discard.hidden = true;
    options.append(type, consent);
    button.before(options);
    button.after(status);
    status.after(discard);

    const say = (message, kind = '') => {
      status.textContent = message || '';
      status.dataset.kind = kind;
    };
    const sync = () => {
      button.disabled = busy || !input.files?.[0] || !consentBox.checked;
      // 추출 요청이 진행되는 동안에는 문서 종류를 바꿀 수 없다. 바꿔도 이미 보낸 요청에는 반영되지 않아 화면과 결과의 문서 종류가 어긋난다.
      type.disabled = busy;
    };

    const clearPending = () => {
      pending = null;
      generation += 1;
      discard.hidden = true;
      say('');
      clearAutofilled(form);
    };
    discard.addEventListener('click', clearPending);
    resetUi = () => {
      clearPending();
      consentBox.checked = false;
      sync();
    };

    // 가려진 사본은 이 스크립트가 들고 있으므로, 같은 문서가 제품 등록 뒤 마스킹 화면 이동 흐름으로 또 올라가지 않게 파일 선택을 비운다.
    const releaseInput = () => {
      input.value = '';
      const selected = assist.querySelector('[data-product-document-selected]');
      if (selected) selected.hidden = true;
      discard.hidden = false;
    };

    input.addEventListener('change', () => {
      clearPending();
      sync();
    });
    consentBox.addEventListener('change', sync);
    // 문서 제거 버튼은 products.js가 파일 선택을 비운다. 그 처리가 끝난 뒤 상태를 맞춘다.
    assist.addEventListener('click', (event) => {
      if (!event.target.closest?.('[data-product-document-clear]')) return;
      clearPending();
      window.setTimeout(sync, 0);
    });
    // 사용자가 직접 고친 칸은 자동 입력 표시를 지운다. 구매일을 고치면 자동 입력된 만료일도 그 날짜로 다시 계산한다.
    form.addEventListener('input', (event) => {
      if (!event.isTrusted || !event.target.dataset) return;
      delete event.target.dataset.docAutofilled;
      if (event.target.name === 'purchaseDate') recomputeWarranty(form, false);
    });
    // 입력이 끝난 시점은 칸을 떠날 때다. 브라우저가 날짜를 타이핑하는 중간 상태에도 change를 보내서 change는 끝으로 볼 수 없다.
    form.addEventListener('focusout', (event) => {
      if (event.target.name === 'purchaseDate') recomputeWarranty(form, true);
    });
    // 폼이 초기화되면(제품 등록 뒤 form.reset()) 값은 비워지지만 표시는 남으므로, 빈 칸에 강조가 남지 않게 표시도 지운다.
    form.addEventListener('reset', () => {
      form.querySelectorAll('[data-doc-autofilled]').forEach((field) => delete field.dataset.docAutofilled);
    });

    button.addEventListener('click', async () => {
      const file = input.files?.[0];
      if (!file || busy || !consentBox.checked) return;
      busy = true;
      sync();
      say('개인정보를 가릴 화면을 열었어요.');
      // 이 요청이 시작된 시점의 세대. 응답이 늦게 와도 그 사이 사본을 버렸다면(generation이 바뀜) 결과를 폼에 쓰지 않는다.
      let token = generation;
      try {
        const picked = await window.PastportDocMask?.pick(file);
        if (!picked) {
          clearPending();
          say('마스킹을 마치지 않아서 자동 입력하지 않았어요.');
          return;
        }
        pending = { blob: picked.blob, name: picked.name || '' };
        releaseInput();
        token = generation;
        say('가려진 사본에서 제품 정보를 읽는 중이에요…');
        const body = new FormData();
        body.append('maskingConfirmed', 'true');
        body.append('consented', 'true');
        body.append('documentType', type.value);
        body.append('file', picked.blob, fileName(picked.blob));
        const response = await request(PREVIEW_URL, { method: 'POST', body });
        const payload = await response.json().catch(() => null);
        // 본문을 읽는 동안에도 폼이 초기화될 수 있어서 응답 본문을 읽은 뒤에 확인한다.
        if (token !== generation) return;
        if (!response.ok) throw new Error(payload?.message || '제품 정보를 읽지 못했어요.');
        const filled = fill(form, payload?.data || {});
        say(
          filled.length
            ? `${filled.join(', ')} 칸을 채웠어요. 1단계에서 문서와 맞는지 확인하고 틀린 곳은 고쳐 주세요. 가려진 사본은 제품을 등록하면 증빙 문서로 보관돼요.`
            : '문서에서 새로 채울 값을 찾지 못했거나 이미 입력된 칸이에요. 가려진 사본은 제품을 등록하면 증빙 문서로 보관돼요.',
        );
      } catch (error) {
        if (token !== generation) return;
        const kept = pending ? ' 가려진 사본은 제품을 등록하면 증빙 문서로 보관돼요.' : '';
        say(`${error.message} 직접 입력해 주세요.${kept}`, 'error');
      } finally {
        busy = false;
        sync();
      }
    });
    sync();
  }

  // 제품 등록이 성공하면 가려진 사본을 그 제품의 증빙 문서로 올린다.
  // products.js가 등록 요청에 쓰는 PastportApi.fetch를 지켜보다가, POST /api/v1/products 성공 응답에서 제품 번호를 읽는다.
  async function uploadPending(response) {
    const upload = pending;
    pending = null;
    if (!upload) return;
    // 사본은 이제 이 함수가 들고 있다. 등록이 끝난 화면에 이전 안내와 버튼이 남지 않게 정리한다.
    resetUi();
    let productId = null;
    try {
      productId = (await response.json())?.data?.productId;
    } catch (ignored) {
      // 제품 번호를 못 읽으면 아래에서 안내한다.
    }
    if (!productId) {
      toast('제품은 등록됐지만 증빙 문서를 올리지 못했어요. 제품 상세의 "문서 올리기"에서 다시 올려 주세요.', 'error');
      return;
    }
    await uploadWithRetry(productId, upload.blob, false, upload.name);
  }

  // 사본을 증빙 문서로 올린다. 실패해도 사본은 이 함수의 클로저에 남겨 두고 "다시 올리기" 버튼을 보여 줘서, 원본을 다시 가리지 않고 재시도할 수 있게 한다.
  async function uploadWithRetry(productId, blob, retrying = false, name = '') {
    try {
      // 응답만 유실되고 서버에는 이미 저장됐을 수 있다. 다시 올리기 전에 같은 사본이 있는지 보고, 있으면 또 올리지 않는다(문서는 제품당 20개 한도).
      if (retrying && (await alreadySaved(productId, blob))) {
        toast('가려진 증빙 문서도 함께 보관했어요.');
        return;
      }
      const body = new FormData();
      body.append('maskingConfirmed', 'true');
      if (name) body.append('name', name);
      body.append('file', blob, fileName(blob));
      const saved = await request(`${PRODUCTS_URL}/${productId}/documents`, { method: 'POST', body });
      if (!saved.ok) throw new Error('upload');
      toast('가려진 증빙 문서도 함께 보관했어요.');
    } catch (ignored) {
      toast('제품은 등록됐지만 증빙 문서를 올리지 못했어요.', 'error', {
        label: '다시 올리기',
        run: () => uploadWithRetry(productId, blob, true, name),
      });
    }
  }

  // 제품에 같은 종류·크기의 문서가 이미 있으면 이 사본이 저장된 것으로 본다. 서버에 중복 방지 키가 없어서 목록으로 확인한다. 목록을 못 읽으면 저장 안 된 것으로 본다.
  async function alreadySaved(productId, blob) {
    try {
      const response = await request(`${PRODUCTS_URL}/${productId}/documents`);
      if (!response.ok) return false;
      const documents = (await response.json())?.data || [];
      return documents.some((item) => item.byteSize === blob.size && item.contentType === blob.type);
    } catch (ignored) {
      return false;
    }
  }

  function watchProductCreate() {
    const api = window.PastportApi;
    if (!api || api.docRegisterWatched) return;
    const original = api.fetch;
    api.fetch = async function watched(url, options) {
      const response = await original.apply(this, arguments);
      const method = String(options?.method || 'GET').toUpperCase();
      if (pending && response.ok && method === 'POST' && String(url) === PRODUCTS_URL) {
        uploadPending(response.clone());
      }
      return response;
    };
    api.docRegisterWatched = true;
  }

  watchProductCreate();
  document.addEventListener('pastport:page-loaded', (event) => {
    if (event.detail?.id === 'register') prepare();
  });
  prepare();
  // 다른 화면으로 옮기면 메모리에 둔 사본을 버린다.
  document.addEventListener('pastport:page-shown', (event) => {
    if (event.detail?.id === 'register') return;
    pending = null;
    resetUi();
  });
  window.addEventListener('pagehide', () => {
    pending = null;
  });
})();
