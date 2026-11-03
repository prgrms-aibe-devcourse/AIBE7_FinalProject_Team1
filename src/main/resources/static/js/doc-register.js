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

  const create = (tag, text, className = '') => {
    const node = document.createElement(tag);
    node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };

  const toast = (message, kind = '') => {
    document.querySelector('.doc-register-toast')?.remove();
    const box = create('div', message, `product-toast doc-register-toast ${kind}`.trim());
    document.body.append(box);
    window.setTimeout(() => box.remove(), 5000);
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

  // 비어 있는 칸에만 값을 넣는다. 사용자가 이미 입력한 값은 덮어쓰지 않는다.
  function fill(form, facts) {
    const filled = [];
    const set = (name, label, value, max) => {
      const input = form.elements[name];
      if (!input || value == null || value === '' || input.value.trim() !== '') return;
      input.value = max ? String(value).slice(0, max) : String(value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      input.dataset.docAutofilled = 'true';
      filled.push(label);
    };
    set('title', '제품 이름', facts.productName, 200);
    set('modelName', '모델명', facts.modelName, 100);
    set('purchaseDate', '구매일', facts.purchaseDate);
    const base = form.elements.purchaseDate?.value || facts.purchaseDate;
    set('warrantyExpiresAt', '보증 만료일', addMonths(base, facts.warrantyMonths));
    return filled;
  }

  function prepare() {
    const assist = document.querySelector('#register .product-document-assist');
    const form = document.querySelector('#register [data-product-form]');
    if (!assist || !form || assist.dataset.docRegister) return;
    assist.dataset.docRegister = 'true';

    const input = assist.querySelector('[data-product-document]');
    const button = assist.querySelector('.product-document-extract');
    const notice = assist.querySelector('.product-document-notice');
    if (!input || !button) return;

    // 연동 안내 문구를 실제 동작에 맞게 바꾼다.
    if (notice) {
      notice.querySelector('b').textContent = '이렇게 처리돼요';
      notice.querySelector('p').textContent =
        '선택한 문서는 이 브라우저에서 개인정보를 가린 뒤, 가려진 사본으로만 정보를 읽어요. 제품을 등록하면 가려진 사본이 증빙 문서로 함께 보관돼요.';
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
    options.append(type, consent);
    button.before(options);
    button.after(status);

    const say = (message, kind = '') => {
      status.textContent = message || '';
      status.dataset.kind = kind;
    };
    const sync = () => {
      button.disabled = busy || !input.files?.[0] || !consentBox.checked;
    };

    const clearPending = () => {
      pending = null;
      say('');
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
    // 사용자가 직접 고친 칸은 자동 입력 표시를 지운다.
    form.addEventListener('input', (event) => {
      if (event.isTrusted && event.target.dataset) delete event.target.dataset.docAutofilled;
    });

    button.addEventListener('click', async () => {
      const file = input.files?.[0];
      if (!file || busy || !consentBox.checked) return;
      busy = true;
      sync();
      say('개인정보를 가릴 화면을 열었어요.');
      try {
        const picked = await window.PastportDocMask?.pick(file);
        if (!picked) {
          clearPending();
          say('마스킹을 마치지 않아서 자동 입력하지 않았어요.');
          return;
        }
        pending = { blob: picked.blob };
        say('가려진 사본에서 제품 정보를 읽는 중이에요…');
        const body = new FormData();
        body.append('maskingConfirmed', 'true');
        body.append('consented', 'true');
        body.append('documentType', type.value);
        body.append('file', picked.blob, fileName(picked.blob));
        const response = await request(PREVIEW_URL, { method: 'POST', body });
        const payload = await response.json().catch(() => null);
        if (!response.ok) throw new Error(payload?.message || '제품 정보를 읽지 못했어요.');
        const filled = fill(form, payload?.data || {});
        say(
          filled.length
            ? `${filled.join(', ')} 칸을 채웠어요. 1단계에서 문서와 맞는지 확인하고 틀린 곳은 고쳐 주세요. 가려진 사본은 제품을 등록하면 증빙 문서로 보관돼요.`
            : '문서에서 새로 채울 값을 찾지 못했거나 이미 입력된 칸이에요. 가려진 사본은 제품을 등록하면 증빙 문서로 보관돼요.',
        );
      } catch (error) {
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
    try {
      const productId = (await response.json())?.data?.productId;
      if (!productId) throw new Error('product');
      const body = new FormData();
      body.append('maskingConfirmed', 'true');
      body.append('file', upload.blob, fileName(upload.blob));
      const saved = await request(`${PRODUCTS_URL}/${productId}/documents`, { method: 'POST', body });
      if (!saved.ok) throw new Error('upload');
      toast('가려진 증빙 문서도 함께 보관했어요.');
    } catch (ignored) {
      toast('제품은 등록됐지만 증빙 문서를 올리지 못했어요. 제품 상세의 "문서 올리기"에서 다시 올려 주세요.', 'error');
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
    if (event.detail?.id !== 'register') pending = null;
  });
  window.addEventListener('pagehide', () => {
    pending = null;
  });
})();
