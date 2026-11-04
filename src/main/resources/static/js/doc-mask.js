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
  // 제품 등록 화면에서 넘긴 원본은 서버에 보내지 않고, 이 화면이 열릴 때 한 번만 소비한다.
  let pendingProductId = null;
  let pendingFile = null;
  let entryVersion = 0;
  // data-docmask-replace-id가 있으면 새 문서를 올리는 대신 해당 문서의 사본을 교체한다.
  let pendingReplaceId = null;
  // 값이 있으면 새 문서를 올리는 대신 이 문서 번호의 사본을 교체한다. (문서 보관함의 "다시 마스킹")
  let replaceId = null;
  // cleanup()마다 올라가서, 그 전에 시작한 이미지 열기·미리보기가 늦게 끝나도 결과를 버릴 수 있게 한다.
  let generation = 0;
  // 값이 있으면 팝업으로 열린 "사본 반환 모드"다. 서버에 올리지 않고, 확인을 마친 사본을 이 함수로 돌려준다.
  let pickResolve = null;

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

  // 선택한 원본과 만든 사본을 정리하고 파일 선택 단계로 돌아간다. 교체 모드는 유지한다.
  function restart() {
    cleanup();
    fail('');
    say('');
    if (root) setStep('pick');
  }

  // 화면을 벗어나거나 "다른 문서 가리기"를 누르면 교체 모드도 함께 끝낸다. 팝업으로 열려 있었다면 취소로 닫는다.
  function reset() {
    finishPick(null);
    restart();
    applyMode(null);
  }

  // 사본 반환 모드를 끝내고 팝업을 닫는다. result가 null이면 사용자가 취소한 것이다.
  function finishPick(result) {
    const resolve = pickResolve;
    pickResolve = null;
    if (root) {
      root.classList.toggle('dm-modal', false);
      root.removeAttribute('role');
      root.removeAttribute('aria-modal');
      const saveButton = find('[data-dm-save]');
      if (saveButton) saveButton.textContent = '확인하고 사본 저장';
    }
    document.body.classList.remove('dm-modal-open');
    setBackgroundInert(false);
    if (resolve) resolve(result);
  }

  // 팝업이 열린 동안 팝업 밖의 모든 화면(머리글·메뉴·등록 폼)을 조작할 수 없게 한다. aria-modal만으로는 키보드 포커스가 팝업 밖으로 나가 가려진 컨트롤에
  // 닿을 수 있다. 팝업 조상마다 형제 요소에 inert를 걸고, 원래 inert였던 요소는 건드리지 않는다.
  let inertTargets = [];
  function setBackgroundInert(on) {
    inertTargets.forEach((node) => { node.inert = false; });
    inertTargets = [];
    if (!on || !root) return;
    for (let node = root; node && node !== document.body; node = node.parentElement) {
      Array.from(node.parentElement.children).forEach((sibling) => {
        if (sibling === node || sibling.inert) return;
        sibling.inert = true;
        inertTargets.push(sibling);
      });
    }
  }

  // 등록 폼 같은 다른 화면에서 호출한다. 화면을 옮기지 않고 팝업으로 마스킹을 마친 뒤, 확인한 사본(Blob)만 돌려준다.
  // 서버 업로드는 하지 않으며, 취소하면 null이다. 원본은 이 브라우저 안에서만 열린다.
  function pick(file) {
    return new Promise((resolve) => {
      init();
      if (!root) {
        resolve(null);
        return;
      }
      finishPick(null);
      restart();
      applyMode(null);
      pickResolve = resolve;
      root.classList.add('dm-modal');
      root.setAttribute('role', 'dialog');
      root.setAttribute('aria-modal', 'true');
      document.body.classList.add('dm-modal-open');
      setBackgroundInert(true);
      find('[data-dm-save]').textContent = '확인하고 이 사본 사용';
      find('[data-dm-close]').focus();
      open(file);
    });
  }

  // 교체 모드에서는 제품을 고정하고, 화면 문구와 완료 후 이동 버튼을 교체용으로 바꾼다.
  function applyMode(id) {
    replaceId = id;
    if (!root) return;
    find('[data-dm-product]').disabled = Boolean(id);
    const title = root.querySelector('.sc-head h1');
    const eyebrow = root.querySelector('.sc-head .eyebrow');
    const back = find('[data-dm-to-passport]');
    if (title) title.textContent = id ? '개인정보 다시 가리기' : '개인정보 가리기 · 확인';
    if (eyebrow) eyebrow.textContent = id ? '문서 사본 교체' : '문서 업로드';
    back.textContent = id ? '문서 보관함으로 돌아가기' : '제품 여권으로 돌아가기';
    back.dataset.page = id ? 'documents' : 'passport';
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
    if (!replaceId && !pickResolve && !find('[data-dm-product]').value) {
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
    // 교체 모드의 요청은 문서 번호로 보내므로 404는 문서가 없거나 내 문서가 아니라는 뜻이다.
    if (status === 404) return replaceId ? '교체할 문서를 찾을 수 없어요. 문서 보관함에서 다시 선택해 주세요.' : '제품을 찾을 수 없어요. 제품 번호를 확인해 주세요.';
    if (status === 413) return '사본이 10MB를 넘어 저장할 수 없어요.';
    if (status === 503) return '파일 저장소를 아직 사용할 수 없어요. 잠시 후 다시 시도해 주세요.';
    return body?.message || '저장하지 못했어요. 잠시 후 다시 시도해 주세요.';
  }

  /** 저장할 문서 이름을 팝업으로 묻는다. 비워 두면 빈 문자열, 취소하면 null을 돌려준다. */
  function askDocumentName(initial = '') {
    return new Promise((resolve) => {
      const dialog = document.createElement('dialog');
      dialog.className = 'dm-name-dialog';
      dialog.innerHTML = '<form method="dialog">'
        + '<h2>문서 이름</h2>'
        + '<p>나중에 증빙을 고를 때 알아보기 쉬운 이름을 붙여 주세요. 이름·연락처 같은 개인정보는 넣지 마세요.</p>'
        + '<input name="name" maxlength="50" placeholder="예: 키보드 수리 영수증 (2026.06)" autocomplete="off">'
        + '<div class="dm-name-actions"><button type="button" class="button" data-dm-name-cancel>취소</button>'
        + '<button type="submit" class="button primary">저장</button></div></form>';
      document.body.append(dialog);
      const input = dialog.querySelector('input');
      input.value = initial;
      let result = null;
      dialog.querySelector('[data-dm-name-cancel]').addEventListener('click', () => dialog.close());
      dialog.querySelector('form').addEventListener('submit', () => { result = input.value.trim(); });
      // Esc는 이름 팝업만 닫는다. 문서로 올라가면 마스킹 모달 전체가 취소된다.
      dialog.addEventListener('keydown', (event) => { if (event.key === 'Escape') event.stopPropagation(); });
      dialog.addEventListener('close', () => { dialog.remove(); resolve(result); });
      dialog.showModal();
      input.focus();
    });
  }

  async function save() {
    if (saving || !blob) return;
    fail('');
    // 사본 반환 모드: 업로드하지 않고 확인한 사본을 호출한 쪽에 넘긴다. 정리는 넘긴 뒤에 한다.
    if (pickResolve) {
      // 제품 등록 화면처럼 호출한 쪽이 나중에 올리는 경우에도 여기서 이름을 받아 함께 넘긴다.
      saving = true;
      const name = await askDocumentName();
      saving = false;
      if (name === null || !pickResolve) return;
      const picked = blob;
      finishPick({ blob: picked, name });
      restart();
      return;
    }
    const productId = Number(find('[data-dm-product]').value);
    if (!replaceId && (!Number.isInteger(productId) || productId < 1)) {
      fail('증빙을 연결할 제품을 선택해 주세요.');
      return;
    }
    // 새 문서만 이름을 묻는다. 사본 교체는 기존 문서의 이름을 그대로 둔다.
    saving = true;
    const name = replaceId ? '' : await askDocumentName();
    if (name === null) { saving = false; return; }
    const button = find('[data-dm-save]');
    button.disabled = true;
    button.textContent = '저장 중…';
    try {
      const extension = blob.type === 'image/jpeg' ? 'jpg' : 'png';
      const form = new FormData();
      form.append('maskingConfirmed', 'true');
      if (name) form.append('name', name);
      // 원본 파일명은 보내지 않는다.
      form.append('file', blob, 'masked.' + extension);
      // 교체 모드면 같은 문서 번호의 파일만 바꾸고, 이력 연결은 그대로 둔다.
      const response = replaceId
        ? await api('/api/v1/documents/' + replaceId + '/file', { method: 'PUT', body: form })
        : await api('/api/v1/products/' + productId + '/documents', { method: 'POST', body: form });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        fail(messageFor(response.status, body));
        return;
      }
      const result = await response.json();
      cleanup();
      find('[data-dm-done-text]').textContent =
        (replaceId ? '개인정보를 다시 가린 사본으로 교체했어요.' : '개인정보를 가린 사본을 저장했어요.')
        + (result?.data?.name ? ' (' + result.data.name + ')' : '') + ' 원본은 이 화면에서 지워졌어요.';
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
    find('[data-dm-products-retry]').addEventListener('click', enterPendingDocument);
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
    find('[data-dm-cancel]').addEventListener('click', restart);
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
    find('[data-dm-close]').addEventListener('click', reset);
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && pickResolve) reset();
    });
    updateCount();
  }

  // 화면 HTML이 이 스크립트보다 먼저 채워질 수도, 나중에 채워질 수도 있으므로 어느 쪽이든 한 번만 초기화한다.
  function init() {
    if (root) return;
    const section = document.querySelector('#docmask');
    if (!section || !section.querySelector('[data-dm]')) return;
    root = section;
    bind();
  }

  /** 모든 제품 페이지를 드롭다운에 채우고 wanted 제품이 있으면 선택한다. 실패 여부를 호출자에 돌려준다. */
  async function loadProducts(wanted) {
    const version = entryVersion;
    const select = find('[data-dm-product]');
    const retry = find('[data-dm-products-retry]');
    const keep = wanted || select.value;
    const setOptions = (placeholder, products = []) => {
      select.replaceChildren(new Option(placeholder, ''));
      for (const product of products) {
        const label = [product.title, product.modelName].filter(Boolean).join(' · ');
        select.add(new Option(label || '이름 없는 제품', String(product.productId)));
      }
    };
    retry.hidden = true;
    setOptions('제품을 불러오는 중이에요');
    try {
      const products = [];
      let page = 0;
      let cursor = null;
      do {
        const params = new URLSearchParams({ page: String(page), size: '100' });
        if (cursor) {
          params.set('cursorCreatedAt', cursor.createdAt);
          params.set('cursorProductId', String(cursor.productId));
        }
        const response = await api(`/api/v1/products?${params}`);
        if (version !== entryVersion) return false;
        if (response.status === 401) {
          setOptions('로그인 후 제품을 선택할 수 있어요');
          return false;
        }
        if (!response.ok) throw new Error('products');
        const result = (await response.json()).data;
        if (version !== entryVersion) return false;
        products.push(...(result?.items || []));
        cursor = result?.page?.nextCursor || null;
        page += 1;
      } while (cursor);
      if (products.length === 0) {
        setOptions('등록된 제품이 없어요. 제품을 먼저 등록해 주세요');
        return true;
      }
      setOptions('증빙을 연결할 제품을 선택해 주세요', products);
      if (keep && products.some((product) => String(product.productId) === String(keep))) select.value = String(keep);
      return true;
    } catch (error) {
      if (version !== entryVersion) return false;
      setOptions('제품 목록을 불러오지 못했어요. 아래 버튼으로 다시 시도해 주세요');
      retry.hidden = false;
      return false;
    }
  }

  // 다른 화면의 버튼이 data-docmask-product-id="제품번호"를 달고 data-page="docmask"로 이동하면 그 제품을 미리 선택한다.
  // 화면 전환(app.js의 click 처리)보다 먼저 값을 받아 두려고 캡처 단계에서 듣는다.
  document.addEventListener('click', (event) => {
    const button = event.target.closest?.('[data-docmask-product-id]');
    if (button) {
      pendingProductId = button.dataset.docmaskProductId;
      pendingReplaceId = button.dataset.docmaskReplaceId || null;
    }
  }, true);

  /** 제품 목록 로딩 성공 뒤에만 등록 화면에서 인계한 제품·원본을 소비한다. */
  async function enterPendingDocument() {
    if (!root) return;
    const version = ++entryVersion;
    const productId = pendingProductId || new URLSearchParams(window.location.search).get('productId');
    const file = pendingFile;
    const replacementId = pendingReplaceId;
    find('[data-dm-to-passport]').hidden = !productId;
    applyMode(replacementId);
    const loaded = await loadProducts(productId);
    if (version !== entryVersion || !root.classList.contains('active')) return;
    if (!loaded) return;
    pendingProductId = null;
    pendingFile = null;
    pendingReplaceId = null;
    if (file) await open(file);
  }

  document.addEventListener('pastport:page-loaded', (event) => {
    if (event.detail.id !== 'docmask') return;
    init();
    if (document.querySelector('#docmask')?.classList.contains('active')) enterPendingDocument();
  });
  init();

  // 다른 화면으로 이동하면 선택한 원본과 만든 사본의 메모리 참조를 정리한다.
  document.addEventListener('pastport:page-shown', (event) => {
    if (root && event.detail.id !== 'docmask') {
      entryVersion += 1;
      pendingProductId = null;
      pendingFile = null;
      pendingReplaceId = null;
      reset();
    }
    // 다른 화면에서 제품을 등록하거나 로그인했을 수 있으니 돌아올 때마다 목록을 새로 받는다.
    if (root && event.detail.id === 'docmask') {
      // 제품 여권·문서 교체·등록 화면에서 넘어온 상태를 적용하고, 등록 화면의 원본이 있으면 바로 연다.
      enterPendingDocument();
    }
  });
  window.addEventListener('pagehide', reset);

  window.PastportDocMask = {
    /** 문서 보관함의 이름 변경도 같은 이름 입력 팝업을 쓴다. 취소하면 null. */
    askName: askDocumentName,
    /** 등록 성공 전에는 업로드하지 않은 원본과 새 제품 ID를 다음 마스킹 화면에 인계한다. */
    prepare(productId, file) {
      pendingProductId = String(productId);
      pendingFile = file;
      pendingReplaceId = null;
    },
    setProduct(id) {
      if (root) find('[data-dm-product]').value = id;
    },
    pick,
  };
})();
