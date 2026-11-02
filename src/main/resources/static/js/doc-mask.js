// 문서 마스킹 화면: 원본 이미지는 이 브라우저 안에서만 처리하고, 가린 사본만 서버로 보낸다.
(() => {
  'use strict';

  const MAX_BYTES = 10 * 1024 * 1024;
  const MAX_SIDE = 3200;
  const MIN_MASK = 6;
  const PAD = 2;

  let root = null;
  let bitmap = null;
  let masks = [];
  let history = [];
  let tool = 'add';
  let drag = null;
  let blob = null;
  let resultUrl = null;
  let sourceType = 'image/png';
  let saving = false;
  let autoRunning = false;
  // cleanup()마다 올라가서, 그 전에 시작한 이미지 열기·미리보기가 늦게 끝나도 결과를 버릴 수 있게 한다.
  let generation = 0;

  const find = (selector) => root.querySelector(selector);
  const all = (selector) => Array.from(root.querySelectorAll(selector));

  function setStep(name) {
    all('[data-dm-step]').forEach((li) => {
      const order = ['pick', 'mask', 'review'];
      const current = order.indexOf(name);
      const index = order.indexOf(li.dataset.dmStep);
      li.classList.toggle('done', index < current);
      li.classList.toggle('now', index === current);
    });
    find('[data-dm-pick]').hidden = name !== 'pick';
    find('[data-dm-editor]').hidden = name !== 'mask';
    find('[data-dm-review]').hidden = name !== 'review';
    find('[data-dm-done]').hidden = name !== 'done';
  }

  function say(message) {
    find('[data-dm-live]').textContent = message || '';
  }

  function fail(message) {
    const box = find('[data-dm-error]');
    box.hidden = !message;
    box.querySelector('span').textContent = message || '';
  }

  // 화면을 벗어나거나 취소·저장이 끝나면 원본 이미지와 만든 사본에 대한 참조를 모두 정리한다.
  function cleanup() {
    generation += 1;
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    resultUrl = null;
    blob = null;
    if (bitmap && typeof bitmap.close === 'function') bitmap.close();
    bitmap = null;
    masks = [];
    history = [];
    drag = null;
    if (root) {
      ['[data-dm-base]', '[data-dm-overlay]'].forEach((selector) => {
        const canvas = find(selector);
        canvas.width = 0;
        canvas.height = 0;
      });
      find('[data-dm-file]').value = '';
      find('[data-dm-result]').removeAttribute('src');
      const confirm = find('[data-dm-confirm]');
      confirm.checked = false;
      find('[data-dm-save]').disabled = true;
    }
  }

  function reset() {
    cleanup();
    fail('');
    say('');
    if (root) setStep('pick');
  }

  async function decode(file) {
    if (typeof createImageBitmap === 'function') {
      try {
        return await createImageBitmap(file, { imageOrientation: 'from-image' });
      } catch (ignored) {
        // 아래 Image 방식으로 다시 시도한다.
      }
    }
    const url = URL.createObjectURL(file);
    try {
      const image = new Image();
      image.src = url;
      await image.decode();
      return image;
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  async function open(file) {
    fail('');
    if (!file) return;
    // 제품을 고르기 전에는 마스킹 화면으로 넘어가지 않는다. 같은 파일을 다시 고를 수 있도록 선택값은 비운다.
    if (!find('[data-dm-product]').value) {
      find('[data-dm-file]').value = '';
      fail('먼저 증빙을 연결할 제품을 선택해 주세요.');
      return;
    }
    if (!/^image\/(jpeg|png)$/.test(file.type)) {
      fail('JPG 또는 PNG 사진만 선택할 수 있어요. PDF는 곧 지원돼요.');
      return;
    }
    cleanup();
    const opening = generation;
    let image;
    try {
      image = await decode(file);
    } catch (ignored) {
      if (opening !== generation) return;
      fail('이미지를 열 수 없어요. 다른 파일을 선택해 주세요.');
      return;
    }
    if (opening !== generation) {
      if (typeof image.close === 'function') image.close();
      return;
    }
    const scale = Math.min(1, MAX_SIDE / Math.max(image.width, image.height));
    const width = Math.max(1, Math.round(image.width * scale));
    const height = Math.max(1, Math.round(image.height * scale));
    // 화면에 맞게 줄인 이미지를 기준으로 삼고, 원본 객체는 즉시 해제한다.
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    canvas.getContext('2d').drawImage(image, 0, 0, width, height);
    if (typeof image.close === 'function') image.close();
    bitmap = canvas;
    sourceType = file.type;

    ['[data-dm-base]', '[data-dm-overlay]'].forEach((selector) => {
      const target = find(selector);
      target.width = width;
      target.height = height;
    });
    find('[data-dm-base]').getContext('2d').drawImage(bitmap, 0, 0);
    masks = [];
    history = [];
    drawOverlay();
    updateCount();
    setStep('mask');
    say('가릴 부분을 드래그해서 지정해 주세요.');
  }

  function drawOverlay(preview) {
    const canvas = find('[data-dm-overlay]');
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#16171d';
    masks.forEach((mask) => {
      ctx.fillRect(mask.x, mask.y, mask.w, mask.h);
      ctx.strokeStyle = '#0b6e6a';
      ctx.lineWidth = Math.max(2, canvas.width / 400);
      ctx.strokeRect(mask.x, mask.y, mask.w, mask.h);
    });
    if (preview) {
      ctx.fillStyle = 'rgba(11,110,106,.18)';
      ctx.strokeStyle = '#0b6e6a';
      ctx.setLineDash([8, 6]);
      ctx.lineWidth = Math.max(2, canvas.width / 400);
      ctx.fillRect(preview.x, preview.y, preview.w, preview.h);
      ctx.strokeRect(preview.x, preview.y, preview.w, preview.h);
      ctx.setLineDash([]);
    }
  }

  function updateCount() {
    find('[data-dm-count]').textContent = '가린 영역 ' + masks.length + '개';
    find('[data-dm-undo]').disabled = history.length === 0;
    find('[data-dm-clear]').disabled = masks.length === 0;
  }

  function pushHistory() {
    history.push(masks.map((m) => ({ ...m })));
    if (history.length > 50) history.shift();
  }

  function point(event) {
    const canvas = find('[data-dm-overlay]');
    const rect = canvas.getBoundingClientRect();
    return {
      x: Math.min(canvas.width, Math.max(0, ((event.clientX - rect.left) * canvas.width) / rect.width)),
      y: Math.min(canvas.height, Math.max(0, ((event.clientY - rect.top) * canvas.height) / rect.height)),
    };
  }

  function normalized(a, b) {
    return { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), w: Math.abs(a.x - b.x), h: Math.abs(a.y - b.y) };
  }

  function onPointerDown(event) {
    if (!bitmap || event.button > 0) return;
    const p = point(event);
    if (tool === 'remove') {
      for (let i = masks.length - 1; i >= 0; i -= 1) {
        const m = masks[i];
        if (p.x >= m.x && p.x <= m.x + m.w && p.y >= m.y && p.y <= m.y + m.h) {
          pushHistory();
          masks.splice(i, 1);
          drawOverlay();
          updateCount();
          say('가린 영역을 해제했어요.');
          return;
        }
      }
      return;
    }
    event.target.setPointerCapture(event.pointerId);
    drag = { start: p, current: p };
    drawOverlay(normalized(p, p));
  }

  function onPointerMove(event) {
    if (!drag) return;
    drag.current = point(event);
    drawOverlay(normalized(drag.start, drag.current));
  }

  function onPointerUp(event) {
    if (!drag) return;
    const area = normalized(drag.start, point(event));
    drag = null;
    if (area.w >= MIN_MASK && area.h >= MIN_MASK) {
      pushHistory();
      masks.push(area);
      say('영역을 추가했어요. 모두 가린 뒤 미리보기를 눌러 주세요.');
    }
    drawOverlay();
    updateCount();
  }

  function cancelDrag() {
    drag = null;
    if (bitmap) drawOverlay();
  }

  function setTool(name) {
    tool = name;
    all('[data-dm-tool]').forEach((button) => {
      const active = button.dataset.dmTool === name;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
    find('[data-dm-wrap]').classList.toggle('remove', name === 'remove');
  }

  // 자동 가리기: 브라우저 안에서 글자를 읽어 개인정보 후보를 찾고, 가릴 영역으로 추가한다. 결과는 수동 도구로 고칠 수 있다.
  async function autoMask() {
    const target = bitmap;
    if (!target || autoRunning) return;
    fail('');
    autoRunning = true;
    const button = find('[data-dm-auto]');
    const label = button.textContent;
    button.disabled = true;
    button.textContent = '분석 중…';
    say('글자를 읽는 중이에요. 처음에는 언어 데이터를 받느라 시간이 걸릴 수 있어요.');
    try {
      const found = await window.PastportDocMaskAuto.detect(target, (progress) => {
        button.textContent = '분석 중… ' + Math.round(progress * 100) + '%';
      });
      if (bitmap !== target) return;
      const fresh = found.filter((box) => !masks.some((m) => m.x <= box.x && m.y <= box.y && m.x + m.w >= box.x + box.w && m.y + m.h >= box.y + box.h));
      if (!fresh.length) {
        say('자동으로 찾은 개인정보가 없어요. 가릴 부분이 있으면 직접 지정해 주세요.');
        return;
      }
      pushHistory();
      fresh.forEach((box) => masks.push({ x: box.x, y: box.y, w: box.w, h: box.h }));
      drawOverlay();
      updateCount();
      const kinds = Array.from(new Set(fresh.map((box) => box.kind))).join(', ');
      say('자동으로 ' + fresh.length + '곳(' + kinds + ')을 가렸어요. 놓친 곳은 직접 추가하고, 잘못 가린 곳은 해제해 주세요.');
    } catch (ignored) {
      fail('자동 가리기를 사용할 수 없어요. 네트워크를 확인하거나 직접 가려 주세요.');
    } finally {
      autoRunning = false;
      button.disabled = false;
      button.textContent = label;
    }
  }

  // 원본 픽셀 위에 불투명한 검은색을 덮어쓰고, 다시 인코딩하면서 촬영 위치 같은 메타데이터를 제거한다.
  function render(scale = 1) {
    const out = document.createElement('canvas');
    out.width = Math.max(1, Math.round(bitmap.width * scale));
    out.height = Math.max(1, Math.round(bitmap.height * scale));
    const ctx = out.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(bitmap, 0, 0, out.width, out.height);
    ctx.fillStyle = '#000000';
    const rects = masks.map((m) => ({
      x: Math.max(0, Math.floor((m.x - PAD) * scale)),
      y: Math.max(0, Math.floor((m.y - PAD) * scale)),
      w: Math.ceil((m.w + PAD * 2) * scale),
      h: Math.ceil((m.h + PAD * 2) * scale),
    }));
    rects.forEach((r) => ctx.fillRect(r.x, r.y, r.w, r.h));
    return { canvas: out, rects };
  }

  // 인코딩 전에 가린 영역의 모든 픽셀이 실제로 검은색인지 확인한다.
  function verifyMasked(canvas, rects) {
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    return rects.every((r) => {
      const w = Math.min(r.w, canvas.width - r.x);
      const h = Math.min(r.h, canvas.height - r.y);
      if (w <= 0 || h <= 0) return true;
      const data = ctx.getImageData(r.x, r.y, w, h).data;
      for (let i = 0; i < data.length; i += 4) {
        if (data[i] !== 0 || data[i + 1] !== 0 || data[i + 2] !== 0 || data[i + 3] !== 255) return false;
      }
      return true;
    });
  }

  function encode(canvas, type, quality) {
    return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
  }

  async function buildBlob() {
    let scale = 1;
    let type = sourceType === 'image/jpeg' ? 'image/jpeg' : 'image/png';
    let quality = 0.92;
    for (let attempt = 0; attempt < 6; attempt += 1) {
      const { canvas, rects } = render(scale);
      if (!verifyMasked(canvas, rects)) throw new Error('verify');
      const encoded = await encode(canvas, type, quality);
      canvas.width = 0;
      canvas.height = 0;
      if (encoded && encoded.size <= MAX_BYTES) return { encoded, type };
      if (type === 'image/png') type = 'image/jpeg';
      else scale *= 0.8;
    }
    throw new Error('size');
  }

  async function preview() {
    fail('');
    if (!bitmap) return;
    const previewing = generation;
    find('[data-dm-preview]').disabled = true;
    say('가린 결과를 만드는 중이에요…');
    try {
      const built = await buildBlob();
      if (previewing !== generation) return;
      blob = built.encoded;
      if (resultUrl) URL.revokeObjectURL(resultUrl);
      resultUrl = URL.createObjectURL(blob);
      find('[data-dm-result]').src = resultUrl;
      const confirmText = find('[data-dm-confirm-text]');
      confirmText.textContent =
        masks.length === 0
          ? '이 문서에는 가릴 개인정보가 없음을 확인했어요.'
          : '가려진 사본에 이름·연락처·주소·카드/계좌·주문번호가 남아 있지 않음을 확인했어요.';
      find('[data-dm-confirm]').checked = false;
      find('[data-dm-save]').disabled = true;
      setStep('review');
      say('');
    } catch (error) {
      if (previewing !== generation) return;
      fail(
        error.message === 'size'
          ? '사본이 10MB를 넘어 저장할 수 없어요. 더 작은 사진을 선택해 주세요.'
          : '가린 결과를 확인하지 못했어요. 다시 시도해 주세요.',
      );
      say('');
    } finally {
      find('[data-dm-preview]').disabled = false;
    }
  }

  async function api(url, options = {}) {
    const headers = new Headers(options.headers || {});
    const csrf = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-Token';
    if (csrf && options.method && options.method !== 'GET') headers.set(csrfHeader, csrf);
    const request = window.PastportApi?.fetch || window.fetch.bind(window);
    return request(url, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
  }

  function messageFor(status, body) {
    if (status === 401) return '문서를 저장하려면 먼저 로그인해 주세요.';
    if (status === 403) return '이 제품의 소유자만 문서를 저장할 수 있어요.';
    if (status === 404) return '제품을 찾을 수 없어요. 제품 번호를 확인해 주세요.';
    if (status === 413) return '사본이 10MB를 넘어 저장할 수 없어요.';
    if (status === 503) return '파일 저장소를 아직 사용할 수 없어요. 잠시 후 다시 시도해 주세요.';
    return body?.message || '저장하지 못했어요. 잠시 후 다시 시도해 주세요.';
  }

  async function save() {
    if (saving || !blob) return;
    fail('');
    const productId = Number(find('[data-dm-product]').value);
    if (!Number.isInteger(productId) || productId < 1) {
      fail('증빙을 연결할 제품을 선택해 주세요.');
      return;
    }
    saving = true;
    const button = find('[data-dm-save]');
    button.disabled = true;
    button.textContent = '저장 중…';
    try {
      const extension = blob.type === 'image/jpeg' ? 'jpg' : 'png';
      const form = new FormData();
      form.append('maskingConfirmed', 'true');
      // 원본 파일명은 보내지 않는다.
      form.append('file', blob, 'masked.' + extension);
      const response = await api('/api/v1/products/' + productId + '/documents', { method: 'POST', body: form });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        fail(messageFor(response.status, body));
        return;
      }
      const result = await response.json();
      cleanup();
      find('[data-dm-done-text]').textContent =
        '개인정보를 가린 사본을 저장했어요. (문서 번호 ' + (result?.data?.id ?? '-') + ') 원본은 이 화면에서 지워졌어요.';
      setStep('done');
    } catch (ignored) {
      fail('네트워크 오류로 저장하지 못했어요. 같은 사본으로 다시 저장할 수 있어요.');
    } finally {
      saving = false;
      button.textContent = '확인하고 사본 저장';
      button.disabled = !find('[data-dm-confirm]').checked || !blob;
    }
  }

  function bind() {
    const wrapCanvas = find('[data-dm-overlay]');
    wrapCanvas.addEventListener('pointerdown', onPointerDown);
    wrapCanvas.addEventListener('pointermove', onPointerMove);
    wrapCanvas.addEventListener('pointerup', onPointerUp);
    wrapCanvas.addEventListener('pointercancel', cancelDrag);
    find('[data-dm-file]').addEventListener('change', (event) => open(event.target.files[0]));
    find('[data-dm-product]').addEventListener('change', () => fail(''));
    all('[data-dm-tool]').forEach((button) => button.addEventListener('click', () => setTool(button.dataset.dmTool)));
    find('[data-dm-undo]').addEventListener('click', () => {
      if (!history.length) return;
      masks = history.pop();
      drawOverlay();
      updateCount();
    });
    find('[data-dm-clear]').addEventListener('click', () => {
      if (!masks.length) return;
      pushHistory();
      masks = [];
      drawOverlay();
      updateCount();
    });
    find('[data-dm-auto]').addEventListener('click', autoMask);
    find('[data-dm-cancel]').addEventListener('click', reset);
    find('[data-dm-preview]').addEventListener('click', preview);
    find('[data-dm-back]').addEventListener('click', () => {
      fail('');
      setStep('mask');
    });
    find('[data-dm-confirm]').addEventListener('change', (event) => {
      find('[data-dm-save]').disabled = !event.target.checked || !blob || saving;
    });
    find('[data-dm-save]').addEventListener('click', save);
    find('[data-dm-again]').addEventListener('click', reset);
    updateCount();
  }

  // 화면 HTML이 이 스크립트보다 먼저 채워질 수도, 나중에 채워질 수도 있으므로 어느 쪽이든 한 번만 초기화한다.
  function init() {
    if (root) return;
    const section = document.querySelector('#docmask');
    if (!section || !section.querySelector('[data-dm]')) return;
    root = section;
    bind();
    loadProducts(new URLSearchParams(window.location.search).get('productId'));
  }

  // 내 제품 목록을 드롭다운에 채운다. wanted(제품 번호)가 목록에 있으면 선택해 둔다.
  async function loadProducts(wanted) {
    const select = find('[data-dm-product]');
    const keep = wanted || select.value;
    const setOptions = (placeholder, products = []) => {
      select.replaceChildren(new Option(placeholder, ''));
      for (const product of products) {
        const label = [product.title, product.modelName].filter(Boolean).join(' · ');
        select.add(new Option(`#${product.productId} ${label}`, String(product.productId)));
      }
    };
    setOptions('제품을 불러오는 중이에요');
    try {
      const response = await api('/api/v1/products');
      if (response.status === 401) {
        setOptions('로그인 후 제품을 선택할 수 있어요');
        return;
      }
      if (!response.ok) throw new Error('products');
      const products = (await response.json()).data || [];
      if (products.length === 0) {
        setOptions('등록된 제품이 없어요. 제품을 먼저 등록해 주세요');
        return;
      }
      setOptions('증빙을 연결할 제품을 선택해 주세요', products);
      if (keep && products.some((product) => String(product.productId) === String(keep))) select.value = String(keep);
    } catch (error) {
      setOptions('제품 목록을 불러오지 못했어요. 화면을 다시 열어 주세요');
    }
  }

  // 다른 화면의 버튼이 data-docmask-product-id="제품번호"를 달고 data-page="docmask"로 이동하면 그 제품을 미리 선택한다.
  // 화면 전환(app.js의 click 처리)보다 먼저 값을 받아 두려고 캡처 단계에서 듣는다.
  let pendingProductId = null;
  document.addEventListener('click', (event) => {
    const button = event.target.closest?.('[data-docmask-product-id]');
    if (button) pendingProductId = button.dataset.docmaskProductId;
  }, true);

  document.addEventListener('pastport:page-loaded', (event) => {
    if (event.detail.id === 'docmask') init();
  });
  init();

  // 다른 화면으로 이동하면 선택한 원본과 만든 사본의 메모리 참조를 정리한다.
  document.addEventListener('pastport:page-shown', (event) => {
    if (root && event.detail.id !== 'docmask') reset();
    // 다른 화면에서 제품을 등록하거나 로그인했을 수 있으니 돌아올 때마다 목록을 새로 받는다.
    if (root && event.detail.id === 'docmask') {
      // 제품 여권의 버튼으로 들어왔을 때만 저장 후 그 제품 여권으로 돌아가는 버튼을 보여 준다.
      find('[data-dm-to-passport]').hidden = !pendingProductId;
      loadProducts(pendingProductId);
      pendingProductId = null;
    }
  });
  window.addEventListener('pagehide', reset);

  window.PastportDocMask = {
    setProduct(id) {
      if (root) find('[data-dm-product]').value = id;
    },
  };
})();
