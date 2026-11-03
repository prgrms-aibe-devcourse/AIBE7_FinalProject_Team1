// 판매글 작성 화면: 제품 선택 → 임시저장 → 미리보기 → 게시 API를 연결한다. 작성 내용은 서버에 비공개로 저장한다.
(() => {
  'use strict';
  let root;
  let productId = null;
  let product = null;
  let productOptions = new Map();
  let productOptionsLoading = false;
  let draftId = null;
  let selected = [];
  let grade = null;
  let version = null;
  let revision = 0;
  let savedRevision = 0;
  let savedAt = null;
  let saving = false;
  let loading = false;
  let deleting = null;
  let confirming = false;
  let initialized = false;
  let productLoad = null;
  let draftListLoad = null;
  let listing = false;
  let draftItems = [];
  let draftPage = -1;
  let draftHasNext = false;
  let publicationLoad = null;
  let publicationLoading = false;
  let publicationPage = -1;
  let publicationHasNext = false;
  let publicationEvents = [];
  let previewing = false;
  let previewOpen = false;
  let previewLoad = null;
  let previewData = null;
  let publishing = false;
  let pendingPublish = null;
  let publicationValidation = false;
  let step = 'product';
  let uploading = false;
  let reviewedDraft = null;
  let aiLoading = false;
  let aiResult = null;
  let aiRequest = null;
  let aiSequence = 0;
  let aiRetryAt = 0;
  let aiRetryTimer = null;
  let aiAttempted = false;
  const imageUrls = new Map();
  const find = selector => root.querySelector(selector);
  const dirty = () => revision !== savedRevision;
  const busy = () => saving || loading || uploading || deleting !== null || confirming || previewing || previewOpen || publishing;
  const maxPhotos = 10;
  const productPath = id => '/api/v1/products/' + id + '/listing-draft';
  const formatTime = value => new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value));
  const validPrice = /^(?:[1-9]\d{0,11}|[1-9]\d{0,2}(?:,\d{3}){1,3})$/;

  async function api(url, options = {}, allowMissing = false) {
    const headers = new Headers(options.headers || {});
    const csrf = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-Token';
    if (csrf && options.method && options.method !== 'GET') headers.set(csrfHeader, csrf);
    if (options.body && !(typeof FormData !== 'undefined' && options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
    const request = window.PastportApi?.fetch || window.fetch.bind(window);
    const response = await request(url, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok && !(allowMissing && response.status === 404)) {
      const payload = await response.json().catch(() => ({}));
      let message = payload.message || '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.';
      if (response.status === 401) message = '판매글을 작성하려면 먼저 로그인해 주세요.';
      if (response.status === 404) message = '이 제품이나 저장본을 사용할 수 없어요. 현재 보유한 제품을 다시 확인해 주세요.';
      if (response.status === 409) message = '저장본이 변경되었어요. 작성 중인 내용은 유지했어요. 저장본을 다시 불러온 뒤 확인해 주세요.';
      const failure = new Error(message); failure.status = response.status; failure.code = payload.code;
      if (payload.code?.startsWith('SALE_') || payload.code?.startsWith('AI_')) failure.message = payload.message || message;
      if (response.status === 429) failure.retryAfter = Math.min(3600, Math.max(1, Number(response.headers.get('Retry-After')) || 60));
      throw failure;
    }
    return response;
  }

  function error(message = '', atActions = false) {
    const target = find(atActions ? '[data-sale-action-error]' : '[data-sale-error]');
    for (const notice of [find('[data-sale-error]'), find('[data-sale-action-error]')]) {
      notice.hidden = !message || notice !== target;
      notice.querySelector('span').textContent = notice === target ? message : '';
    }
    // 제품 선택·하단 버튼 어디에서 실패해도 오류로 초점을 옮겨 현재 스크롤 위치에 가리지 않게 한다.
    if (message) {
      target.focus({ preventScroll: true });
      target.scrollIntoView({ block: 'nearest' });
    }
  }

  // 게시 시 누락 항목을 모두 표시하고 첫 오류로 이동한다. 이후 입력·사진 선택에서는 표시만 갱신한다.
  function validatePublication(focus = false) {
    const price = find('[data-sale-price]').value.trim();
    const checks = [
      ['title', '[data-sale-title]', !find('[data-sale-title]').value.trim() ? '판매글 제목을 입력해 주세요.' : ''],
      ['price', '[data-sale-price]', !price ? '판매 가격을 입력해 주세요.' : !validPrice.test(price) ? '가격은 1원 이상, 최대 12자리의 정수로 입력해 주세요.' : ''],
      ['grade', '[data-sale-grade-group]', grade === null ? '제품 상태 등급을 선택해 주세요.' : ''],
      ['description', '[data-sale-description]', !find('[data-sale-description]').value.trim() ? '상세 설명을 입력해 주세요.' : ''],
      ['photos', '[data-sale-photos-field]', selected.length > maxPhotos ? '판매글 사진은 최대 10장까지 선택해 주세요.' : !selected.length ? product?.images.length
        ? '대표 사진을 선택해 주세요. 첫 번째로 선택한 사진이 대표 사진이 돼요.'
        : '대표 사진이 필요해요. 카메라 버튼을 눌러 제품 사진을 추가해 주세요.' : '']
    ];
    let first = null;
    for (const [name, selector, issue] of checks) {
      const message = publicationValidation ? issue : '';
      const field = find('[data-sale-' + name + '-field]');
      const notice = find('[data-sale-' + name + '-error]');
      const control = find(selector);
      field.classList.toggle('is-invalid', Boolean(message));
      notice.textContent = message; notice.hidden = !message;
      if (message) {
        control.setAttribute('aria-invalid', 'true');
        if (!first) first = { control, notice };
      } else control.removeAttribute('aria-invalid');
    }
    if (focus && first) {
      first.control.focus({ preventScroll: true });
      first.notice.scrollIntoView({ block: 'center' });
    }
    return first === null;
  }

  function counters() {
    find('[data-sale-title-count]').textContent = find('[data-sale-title]').value.length + ' / 60자';
    find('[data-sale-description-count]').textContent = find('[data-sale-description]').value.length + ' / 2,000자';
  }

  function status() {
    const blocked = busy() || pendingPublish !== null;
    find('[data-sale-select-panel]').hidden = step !== 'product';
    find('[data-sale-compose-panel]').hidden = step !== 'compose';
    find('[data-sale-product-step]').className = step === 'product' ? 'now' : 'done';
    find('[data-sale-content-step]').className = previewOpen ? 'done' : step === 'compose' ? 'now' : '';
    find('[data-sale-preview-step]').className = previewOpen ? 'now' : '';
    find('[data-sale-step-caption]').textContent = step === 'product' ? '판매할 내 제품을 먼저 선택해 주세요.' : '제품 사진과 판매 내용을 적고 미리보기에서 확인하세요.';
    find('[data-sale-next]').disabled = !product || blocked;
    find('[data-sale-back]').disabled = blocked;
    find('[data-sale-photo-open]').disabled = !product || blocked;
    find('[data-sale-photo-upload]').disabled = !product || blocked || product.images.length >= maxPhotos;
    find('[data-sale-photo-done]').disabled = uploading;
    find('[data-sale-photo-close]').disabled = uploading;
    const saved = find('[data-sale-saved]');
    if (deleting !== null) saved.textContent = '임시저장본을 삭제하는 중이에요…';
    else if (loading) saved.textContent = '제품과 저장본을 불러오는 중이에요…';
    else if (saving) saved.textContent = '비공개로 저장하는 중이에요…';
    else if (savedAt) saved.textContent = '마지막 임시저장 ' + formatTime(savedAt) + (dirty() ? ' · 저장하지 않은 변경이 있어요.' : ' · 비공개 저장 완료');
    else saved.textContent = dirty() ? '작성한 내용을 임시저장해 주세요.' : '아직 임시저장하지 않았어요.';
    find('[data-sale-save]').disabled = !product || blocked || aiLoading;
    find('[data-sale-save]').textContent = saving ? '저장 중…' : '임시저장';
    find('[data-sale-product]').disabled = blocked || productOptionsLoading;
    find('[data-sale-products-refresh]').disabled = blocked || productOptionsLoading;
    find('[data-sale-reload]').hidden = !product;
    find('[data-sale-reload]').disabled = blocked;
    find('[data-sale-delete]').hidden = draftId === null;
    find('[data-sale-delete]').disabled = blocked;
    find('[data-sale-drafts-refresh]').disabled = listing || blocked;
    find('[data-sale-drafts-more]').disabled = listing || blocked;
    find('[data-sale-publication-refresh]').disabled = !product || publicationLoading || busy();
    find('[data-sale-publication-more]').disabled = publicationLoading || busy();
    find('[data-sale-publish]').disabled = !product || busy() || aiLoading;
    find('[data-sale-publish]').textContent = publishing ? '게시 중…' : pendingPublish ? '게시 결과 다시 확인' : '게시하기';
    if (previewing) find('[data-sale-publish]').textContent = '미리보기 준비 중…';
    find('[data-sale-preview-publish]').disabled = !previewOpen || !reviewedDraft || previewing || publishing || saving;
    find('[data-sale-preview-publish]').textContent = publishing ? '게시 중…' : pendingPublish ? '게시 결과 다시 확인' : '게시하기';
    find('[data-sale-preview-close]').disabled = publishing || pendingPublish !== null;
    find('[data-sale-ai-generate]').disabled = !product || blocked || aiLoading || Date.now() < aiRetryAt;
    find('[data-sale-ai-generate]').textContent = aiLoading ? 'AI 초안 작성 중…' : aiResult ? 'AI로 다시 작성' : 'AI로 초안 작성';
    find('[data-sale-write-manual]').disabled = !product || blocked;
    find('[data-sale-ai-manual]').disabled = !product || blocked;
    find('[data-sale-ai-retry]').disabled = !product || blocked || aiLoading || Date.now() < aiRetryAt;
    find('[data-sale-ai-retry]').textContent = aiLoading ? '생성 중…' : Date.now() < aiRetryAt ? '잠시 후 다시 생성' : '다시 생성';
    find('[data-sale-ai-panel]').setAttribute('aria-busy', String(aiLoading));
    find('[data-sale-title]').placeholder = aiLoading ? 'AI가 제목을 작성하고 있어요…' : '판매글 제목을 입력해 주세요';
    find('[data-sale-description]').placeholder = aiLoading ? 'AI가 제품 정보와 이력으로 설명을 작성하고 있어요…' : '제품의 상태와 판매 내용을 적어 주세요.';
    root.querySelectorAll('[data-sale-open-draft], [data-sale-delete-draft]').forEach(button => { button.disabled = blocked; });
    root.querySelectorAll('[data-sale-title], [data-sale-price], [data-sale-description], [data-sale-grade]').forEach(input => { input.disabled = !product || loading || uploading || deleting !== null || previewing || previewOpen || publishing || pendingPublish !== null; });
    root.querySelectorAll('[data-sale-image], [data-sale-remove-image]').forEach(button => { button.disabled = blocked; });
    root.querySelectorAll('[data-sale-move]').forEach(button => {
      const index = selected.indexOf(Number(button.dataset.imageId));
      button.disabled = blocked || index < 0 || (button.dataset.saleMove === 'right' ? index === selected.length - 1 : index === 0);
    });
  }

  function change() {
    reviewedDraft = null;
    revision++; counters(); status();
    if (publicationValidation) validatePublication();
  }

  function aiNotice(message, alert = false) {
    find('[data-sale-ai-status]').textContent = message;
    find('[data-sale-ai-status]').setAttribute('role', alert ? 'alert' : 'status');
  }

  function clearAi(message = '다음 단계에서 AI가 제목과 설명을 먼저 작성해 드려요.') {
    aiSequence++; aiRequest?.abort(); aiRequest = null; aiLoading = false; aiResult = null;
    find('[data-sale-ai-sample]').hidden = true;
    aiNotice(message);
  }

  // 새 글의 다음 단계에서 초안을 자동 적용한다. 직접 입력·제품 변경 이후의 응답은 적용하지 않는다.
  async function generateAi({ automatic = false } = {}) {
    if (!product || busy() || pendingPublish || aiLoading || Date.now() < aiRetryAt) return;
    const title = find('[data-sale-title]'); const description = find('[data-sale-description]');
    if (!automatic && (title.value.trim() || description.value.trim())
        && (title.value !== aiResult?.title || description.value !== aiResult?.description)
        && !await confirmAction('AI로 다시 작성하면 현재 제목과 설명이 바뀌어요. 계속할까요?', 'AI로 작성', '작성 내용 확인')) return;
    if (!product || busy() || pendingPublish || aiLoading) return;
    const originalTitle = title.value; const originalDescription = description.value;
    aiAttempted = true;
    clearAi('제품 정보와 등록된 이력으로 제목·설명을 작성하고 있어요. 기다리지 않고 직접 작성해도 돼요.');
    if (find('[data-sale-ai-dialog]').open) find('[data-sale-ai-dialog]').close();
    const requestedProduct = productId;
    const sequence = aiSequence;
    const controller = new AbortController(); aiRequest = controller; aiLoading = true; status();
    const deadline = setTimeout(() => controller.abort(), 75000);
    try {
      const response = await api(productPath(requestedProduct) + '/ai-suggestion', { method: 'POST', signal: controller.signal });
      const { data } = await response.json();
      if (sequence !== aiSequence || productId !== requestedProduct) return;
      if (data?.productId !== requestedProduct || typeof data.title !== 'string' || typeof data.description !== 'string'
          || !data.title.trim() || data.title.length > 60 || !data.description.trim() || data.description.length > 2000) {
        throw new Error('AI 결과를 확인하지 못했어요. 재시도하거나 직접 작성해 주세요.');
      }
      if (title.value !== originalTitle || description.value !== originalDescription) {
        aiNotice('직접 작성한 내용을 유지했어요. 필요한 부분을 이어서 작성해 주세요.');
        return;
      }
      aiResult = data;
      title.value = data.title; description.value = data.description;
      change();
      find('[data-sale-ai-sample]').hidden = !data.sample;
      aiNotice('AI 초안을 채웠어요. 내용을 확인하고 자유롭게 수정해 주세요. 아직 저장되거나 게시되지 않았어요.');
    } catch (failure) {
      if (sequence !== aiSequence || productId !== requestedProduct) return;
      if (failure.status === 429) {
        aiRetryAt = Date.now() + failure.retryAfter * 1000;
        clearTimeout(aiRetryTimer);
        aiRetryTimer = setTimeout(() => status(), failure.retryAfter * 1000 + 50);
      }
      const message = failure.name === 'AbortError'
        ? 'AI 응답을 기다리는 시간이 초과됐어요. 재시도하거나 직접 작성해 주세요.'
        : failure.status || failure.message?.startsWith('AI 결과') ? failure.message : 'AI에 연결하지 못했어요. 재시도하거나 직접 작성해 주세요.';
      aiNotice(message, true);
      find('[data-sale-ai-error]').textContent = message;
      if (step === 'compose' && !find('[data-sale-ai-dialog]').open) find('[data-sale-ai-dialog]').showModal();
    } finally {
      clearTimeout(deadline);
      if (sequence === aiSequence) { aiLoading = false; aiRequest = null; status(); }
    }
  }

  function writeManually() {
    if (!product || busy() || pendingPublish) return;
    const title = find('[data-sale-title]'); const description = find('[data-sale-description]');
    // 손대지 않은 자동 초안만 비운다. 저장본이나 사용자가 수정한 문장은 그대로 둔다.
    const untouched = aiResult && title.value === aiResult.title && description.value === aiResult.description;
    if (untouched) { title.value = ''; description.value = ''; }
    aiAttempted = true;
    clearAi(title.value || description.value ? '작성 중인 내용은 유지했어요. 제목과 설명을 직접 수정해 주세요.' : '제목과 설명을 직접 작성해 주세요.');
    if (find('[data-sale-ai-dialog]').open) find('[data-sale-ai-dialog]').close();
    if (untouched) change(); else status();
    title.focus();
  }

  async function confirmAction(message, action, title = '임시저장본 확인') {
    const dialog = find('[data-sale-confirm]');
    find('[data-sale-confirm-message]').textContent = message;
    find('[data-sale-confirm-action]').textContent = action;
    find('[data-sale-confirm-title]').textContent = title;
    confirming = true; status();
    try {
      return await new Promise(resolve => {
        dialog.returnValue = 'cancel';
        dialog.addEventListener('close', () => resolve(dialog.returnValue === 'confirm'), { once: true });
        dialog.showModal();
      });
    } finally { confirming = false; status(); }
  }

  function reset() {
    step = 'product'; reviewedDraft = null;
    aiAttempted = false;
    for (const selector of ['[data-sale-ai-dialog]', '[data-sale-preview-dialog]', '[data-sale-photo-dialog]']) if (find(selector).open) find(selector).close();
    previewOpen = false;
    clearAi();
    pendingPublish = null;
    previewLoad?.abort(); previewData = null;
    publicationLoad?.abort(); publicationLoading = false;
    publicationPage = -1; publicationHasNext = false; publicationEvents = [];
    find('[data-sale-publication-product]').hidden = true;
    find('[data-sale-publication-product]').replaceChildren();
    find('[data-sale-publication-history]').hidden = true;
    find('[data-sale-publication-events]').replaceChildren();
    find('[data-sale-publication-more]').hidden = true;
    find('[data-sale-publication-count]').textContent = '';
    find('[data-sale-publication-status]').textContent = '제품을 선택하면 공개 예정 정보를 보여드려요.';
    product = null; draftId = null; selected = []; grade = null; version = null;
    find('[data-sale-product-blocked]').hidden = true;
    find('[data-sale-existing-listing]').removeAttribute('href');
    revision = 0; savedRevision = 0; savedAt = null;
    imageUrls.forEach(url => URL.revokeObjectURL(url)); imageUrls.clear();
    find('[data-sale-title]').value = '';
    find('[data-sale-price]').value = '';
    find('[data-sale-description]').value = '';
    find('[data-sale-product-card]').hidden = true;
    find('[data-sale-specs]').hidden = true;
    find('[data-sale-photos]').replaceChildren();
    find('[data-sale-photos-empty]').hidden = false;
    find('[data-sale-photos-empty]').textContent = '제품을 선택하면 등록된 사진이 보여요.';
    find('[data-sale-photo-count]').textContent = '선택한 사진 0장';
    find('[data-sale-camera-count]').textContent = '0/10';
    find('[data-sale-photo-choices]').replaceChildren();
    find('[data-sale-photo-error]').hidden = true;
    root.querySelectorAll('[data-sale-grade]').forEach(button => { button.classList.remove('on'); button.setAttribute('aria-checked', 'false'); });
    find('[data-sale-product-step]').className = 'now';
    find('[data-sale-content-step]').className = '';
    publicationValidation = false; validatePublication();
    counters(); status();
  }

  function setLocation(id) {
    const url = new URL(window.location.href);
    url.searchParams.delete('drafts');
    if (id) url.searchParams.set('draftProduct', String(id));
    else url.searchParams.delete('draftProduct');
    window.history.replaceState(window.history.state, '', url);
  }

  function applyDraft(draft) {
    if (!draft) return;
    draftId = draft.draftId; version = draft.version; savedAt = draft.updatedAt;
    find('[data-sale-title]').value = draft.title ?? '';
    find('[data-sale-price]').value = draft.price == null ? '' : String(draft.price);
    find('[data-sale-description]').value = draft.description ?? '';
    grade = draft.conditionGrade;
    selected = draft.selectedImageIds.filter(id => product.images.some(image => image.imageId === id));
    if (selected.length !== draft.selectedImageIds.length) {
      revision++;
      error('저장 후 삭제된 사진이 있어요. 저장본을 다시 불러와 최신 선택을 확인해 주세요.');
    }
    root.querySelectorAll('[data-sale-grade]').forEach(button => {
      const on = Number(button.dataset.saleGrade) === grade;
      button.classList.toggle('on', on); button.setAttribute('aria-checked', String(on));
    });
    counters();
  }

  function renderProduct() {
    aiNotice(draftId !== null ? '저장한 제목과 설명을 불러왔어요. 이어서 수정해 주세요.' : '다음 단계에서 AI가 제목과 설명을 먼저 작성해 드려요.');
    find('[data-sale-product-card]').hidden = false;
    find('[data-sale-product-name]').textContent = product.title;
    find('[data-sale-compose-product]').textContent = product.title;
    find('[data-sale-product-meta]').textContent = [product.manufacturer, product.modelName, product.purchaseDate ? product.purchaseDate + ' 구매' : '구매일 미등록'].join(' · ');
    const specs = product.specifications || {};
    const entries = [['CPU', specs.cpu], ['GPU', specs.gpu], ['메모리', specs.ramGb == null ? null : specs.ramGb + 'GB'], ['저장장치', specs.storageGb == null ? null : specs.storageGb + 'GB ' + (specs.storageType || '')], ['화면', specs.display], ['운영체제', specs.os], ['배터리 상태', specs.batteryHealthPercent == null ? null : specs.batteryHealthPercent + '%'], ['보증 만료일', product.warrantyExpiresAt]];
    const list = find('[data-sale-specs]'); list.replaceChildren();
    entries.forEach(([label, value]) => {
      const item = document.createElement('div');
      const key = document.createElement('dt'); key.textContent = label;
      const text = document.createElement('dd'); text.textContent = value ?? '미등록';
      item.append(key, text); list.append(item);
    });
    list.hidden = false;
  }

  // 단계 이동은 작성 값을 유지한다. 다른 제품을 고를 때만 기존 저장·변경 확인 흐름을 사용한다.
  function setStep(next, focus = false) {
    if (busy() || pendingPublish || (next === 'compose' && !product)) return;
    step = next; status();
    if (focus) {
      const panel = find(next === 'compose' ? '[data-sale-compose-panel]' : '[data-sale-select-panel]');
      panel.tabIndex = -1;
      panel.style.scrollMarginTop = ((document.querySelector('.site-header')?.getBoundingClientRect().height || 0) + 24) + 'px';
      panel.focus({preventScroll:true}); panel.scrollIntoView({block:'start'});
    }
  }

  function beginWriting(focus = false) {
    setStep('compose', focus);
    if (step !== 'compose' || busy() || pendingPublish || aiAttempted || draftId !== null) return;
    if (find('[data-sale-title]').value.trim() || find('[data-sale-description]').value.trim()) return;
    if (Date.now() < aiRetryAt) { aiNotice('AI 요청이 잠시 제한됐어요. 직접 작성하거나 잠시 후 AI로 초안 작성을 눌러 주세요.'); return; }
    return generateAi({ automatic: true });
  }

  function photoControls(id) {
    const controls = document.createElement('div'); controls.className = 'sl-photo-controls';
    [['←', 'left'], ['→', 'right']].forEach(([label, action]) => {
      const button = document.createElement('button'); button.type = 'button'; button.textContent = label;
      button.dataset.saleMove = action; button.dataset.imageId = String(id);
      button.setAttribute('aria-label', '사진 ' + id + ' ' + ({ main:'대표로 설정', left:'앞으로 이동', right:'뒤로 이동' }[action]));
      controls.append(button);
    });
    return controls;
  }

  // 작성 화면에는 선택한 사진만, 사진 선택 팝업에는 제품의 모든 사진을 표시한다.
  function renderPhotos() {
    const strip = find('[data-sale-photos]'), choices = find('[data-sale-photo-choices]');
    strip.replaceChildren(); choices.replaceChildren();
    if (!product) return;
    for (const image of product.images) {
      const position = selected.indexOf(image.imageId);
      const button = document.createElement('button'); button.type = 'button';
      button.className = 'sl-photo' + (position >= 0 ? ' selected' : ''); button.dataset.saleImage = String(image.imageId);
      button.setAttribute('aria-label', '제품 사진 ' + image.imageId + (position >= 0 ? ' 선택 해제' : ' 선택'));
      button.setAttribute('aria-pressed', String(position >= 0));
      const url = imageUrls.get(image.imageId);
      if (url) { const img = document.createElement('img'); img.src = url; img.alt = ''; button.append(img); }
      else button.textContent = '사진 불러오는 중';
      if (position >= 0) { const order = document.createElement('i'); order.textContent = String(position + 1); button.append(order); }
      if (position === 0) { const badge = document.createElement('em'); badge.textContent = '대표'; button.append(badge); }
      choices.append(button);
    }
    for (const [position, id] of selected.entries()) {
      const card = document.createElement('div'); card.className = 'sl-selected-photo';
      const url = imageUrls.get(id);
      const picture = document.createElement(url ? 'img' : 'span');
      if (url) { picture.src = url; picture.alt = '선택한 제품 사진 ' + (position + 1); }
      else picture.textContent = '사진 불러오는 중';
      const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'sl-photo-remove';
      remove.textContent = '×'; remove.dataset.saleRemoveImage = String(id); remove.setAttribute('aria-label', '사진 ' + id + ' 판매글에서 빼기');
      card.append(picture, remove);
      if (position === 0) { const badge = document.createElement('em'); badge.textContent = '대표'; card.append(badge); }
      if (selected.length > 1) card.append(photoControls(id));
      strip.append(card);
    }
    const hero = find('[data-sale-product-picture]'); hero.replaceChildren();
    const primary = product.images.find(image => imageUrls.has(image.imageId));
    if (primary) { const img = document.createElement('img'); img.src = imageUrls.get(primary.imageId); img.alt = ''; hero.append(img); }
    else hero.textContent = '제품 사진';
    find('[data-sale-photos-empty]').hidden = product.images.length > 0;
    find('[data-sale-photos-empty]').textContent = '등록된 사진이 없어요. 새 사진을 추가해 주세요.';
    find('[data-sale-camera-count]').textContent = selected.length + '/' + maxPhotos;
    find('[data-sale-photo-count]').textContent = '선택한 사진 ' + selected.length + '장 · 최대 10장 · 첫 번째 사진이 대표 사진이에요.';
    status();
  }

  async function loadPhotoUrls(signal) {
    const current = product;
    await Promise.all(current.images.filter(image => !imageUrls.has(image.imageId)).map(async image => {
      try {
        const response = await api(image.url, {signal}); const blob = await response.blob();
        if (signal?.aborted || product !== current) return;
        imageUrls.set(image.imageId, URL.createObjectURL(blob)); renderPhotos();
      } catch (failure) {
        if (failure.name !== 'AbortError' && product === current) photoError('일부 사진을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.');
      }
    }));
  }

  function photoError(message = '') {
    const node = find('[data-sale-photo-error]'); node.textContent = message; node.hidden = !message;
  }

  // 새 사진은 기존 제품 이미지 API로 저장한다. 일부 업로드가 실패해도 성공한 사진과 작성 값을 보존한다.
  async function uploadPhotos(event) {
    const input = event.target, files = [...(input.files || [])]; input.value = '';
    if (!product || busy() || pendingPublish || !files.length) return;
    photoError();
    if (files.length + product.images.length > maxPhotos) { photoError('제품 사진은 최대 10장이에요. 등록된 사진 중에서 선택해 주세요.'); return; }
    if (files.some(file => !['image/jpeg','image/png','image/webp'].includes(file.type) || file.size <= 0 || file.size > 5 * 1024 * 1024)) {
      photoError('JPG, PNG, WEBP 이미지를 장당 5MB 이하로 추가해 주세요.'); return;
    }
    const current = product, currentId = productId;
    uploading = true; clearAi(); status();
    find('[data-sale-photo-upload-status]').textContent = '제품 사진을 추가하는 중이에요…';
    let count = 0;
    try {
      for (const file of files) {
        const previous = new Set(current.images.map(image => image.imageId));
        const body = new FormData(); body.append('file', file);
        const response = await api('/api/v1/products/' + currentId + '/images', {method:'POST', body});
        const {data} = await response.json();
        if (product !== current || productId !== currentId) return;
        if (!Array.isArray(data?.images)) throw new Error('추가한 사진 정보를 확인하지 못했어요. 사진 선택을 다시 열어 확인해 주세요.');
        current.images = data.images;
        for (const image of current.images) if (!previous.has(image.imageId) && !selected.includes(image.imageId) && selected.length < maxPhotos) selected.push(image.imageId);
        count++; change(); renderPhotos();
      }
    } catch (failure) {
      photoError((count ? count + '장은 추가했어요. 나머지 사진을 추가하지 못했어요. ' : '') + failure.message);
    } finally {
      if (product === current) {
        // 응답을 잃은 업로드도 제품에 남아 있을 수 있으므로 목록을 재확인한다. 확인만 하며 다시 업로드하지 않는다.
        try { const response = await api(productPath(currentId) + '/composition'); const {data} = await response.json(); if (product === current) current.images = data.images; }
        catch { photoError('사진 목록을 다시 확인하지 못했어요. 저장된 작성 내용은 유지했어요. 잠시 후 다시 열어 주세요.'); }
        uploading = false; renderPhotos(); loadPhotoUrls(); refreshPublication();
        find('[data-sale-photo-upload-status]').textContent = count ? count + '장 추가 완료 · 제품에도 저장됐어요.' : 'JPG, PNG, WEBP · 장당 최대 5MB · 제품당 최대 10장';
      } else uploading = false;
      status();
    }
  }

  function renderDrafts() {
    const list = find('[data-sale-drafts]'); list.replaceChildren();
    draftItems.forEach(item => {
      const row = document.createElement('li'); row.className = 'sl-draft-item';
      row.classList.toggle('is-current', item.productId === productId);
      const info = document.createElement('div'); info.className = 'sl-draft-info';
      const title = document.createElement('h3'); title.textContent = item.title || '제목 없는 임시저장본';
      const meta = document.createElement('p');
      meta.textContent = item.productTitle + ' · ' + (item.price == null ? '가격 미입력' : new Intl.NumberFormat('ko-KR').format(item.price) + '원') + ' · ' + formatTime(item.updatedAt) + ' 저장';
      info.append(title, meta);
      const actions = document.createElement('div'); actions.className = 'sl-draft-actions';
      const open = document.createElement('button'); open.type = 'button'; open.className = 'sc-btn primary';
      open.dataset.saleOpenDraft = String(item.draftId); open.textContent = '이어서 작성';
      open.setAttribute('aria-label', item.productTitle + ' 임시저장본 이어서 작성');
      const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'sc-btn sl-draft-delete';
      remove.dataset.saleDeleteDraft = String(item.draftId); remove.textContent = '삭제';
      remove.setAttribute('aria-label', item.productTitle + ' 임시저장본 삭제');
      actions.append(open, remove); row.append(info, actions); list.append(row);
    });
    find('[data-sale-drafts-more]').hidden = !draftHasNext;
    status();
  }

  // R03에서는 제품·이력을 조회해 표시하며, 작성 내용 저장과 게시 미리보기는 수행하지 않는다.
  function renderPublication(data) {
    const info = data.product;
    const specs = info.specifications || {};
    const fields = [['제조사', info.manufacturer], ['모델', info.modelName],
      ['구매일', info.purchaseDate], ['보증 만료일', info.warrantyExpiresAt],
      ['CPU', specs.cpu], ['GPU', specs.gpu], ['메모리', specs.ramGb == null ? null : specs.ramGb + 'GB'],
      ['저장장치', specs.storageGb == null ? null : specs.storageGb + 'GB ' + (specs.storageType || '')],
      ['화면', specs.display], ['운영체제', specs.os],
      ['배터리 상태', specs.batteryHealthPercent == null ? null : specs.batteryHealthPercent + '%']];
    const productList = find('[data-sale-publication-product]'); productList.replaceChildren();
    fields.forEach(([label, value]) => {
      const row = document.createElement('div');
      const name = document.createElement('dt'); name.textContent = label;
      const content = document.createElement('dd'); content.textContent = value ?? '미등록';
      row.append(name, content); productList.append(row);
    });
    productList.hidden = false;
    find('[data-sale-publication-status]').textContent = '선택한 제품의 공개 예정 정보예요. 조회해도 저장하거나 공개하지 않아요.';
    find('[data-sale-publication-history]').hidden = false;
    find('[data-sale-publication-sample]').hidden = !data.history.sample;
    const unavailable = data.history.status !== 'READY';
    find('[data-sale-publication-count]').textContent = unavailable ? '' : '유효 ' + data.history.validCount + '건 · 무효 ' + data.history.voidCount + '건';
    find('[data-sale-publication-history-status]').textContent = unavailable
      ? '이력 연동 준비 중이에요. 실제 이력 여부는 아직 확인할 수 없어요.'
      : publicationEvents.length ? '이전 소유자의 기록과 정정·무효 표시를 함께 보여드려요.' : '등록된 핵심 이력이 없어요.';
    window.PastportListingDetail.renderTimeline(find('[data-sale-publication-events]'), publicationEvents);
    find('[data-sale-publication-more]').hidden = !publicationHasNext;
  }

  async function refreshPublication(append = false) {
    if (!product || saving || deleting !== null || confirming) return;
    publicationLoad?.abort();
    const controller = new AbortController(); publicationLoad = controller;
    const requestedProduct = productId;
    const page = append ? publicationPage + 1 : 0;
    publicationLoading = true; status();
    find('[data-sale-publication-status]').textContent = '공개 예정 제품 정보와 이력을 불러오는 중이에요…';
    try {
      const response = await api(productPath(requestedProduct) + '/publication?page=' + page + '&size=20', { signal: controller.signal });
      const { data } = await response.json();
      if (controller.signal.aborted || productId !== requestedProduct) return;
      publicationEvents = data.history.status !== 'READY' ? [] : append ? [...new Map([...publicationEvents, ...data.history.items].map(item => [item.eventId, item])).values()] : data.history.items;
      publicationPage = page; publicationHasNext = data.history.page.hasNext;
      renderPublication(data);
    } catch (failure) {
      if (failure.name !== 'AbortError' && !controller.signal.aborted) {
        publicationEvents = []; publicationHasNext = false;
        find('[data-sale-publication-product]').hidden = true;
        find('[data-sale-publication-history]').hidden = true;
        find('[data-sale-publication-more]').hidden = true;
        find('[data-sale-publication-events]').replaceChildren();
        find('[data-sale-publication-status]').textContent = '공개 예정 정보를 확인하지 못했어요. 정보 새로고침으로 다시 시도해 주세요.';
      }
    } finally { if (!controller.signal.aborted) { publicationLoading = false; status(); } }
  }

  async function refreshDrafts(append = false) {
    draftListLoad?.abort();
    const controller = new AbortController(); draftListLoad = controller;
    const page = append ? draftPage + 1 : 0;
    listing = true; status();
    find('[data-sale-drafts-status]').textContent = '임시저장본을 불러오는 중이에요…';
    try {
      const response = await api('/api/v1/listing-drafts?page=' + page + '&size=20', { signal: controller.signal });
      const { data } = await response.json();
      if (controller.signal.aborted) return;
      draftItems = append ? [...new Map([...draftItems, ...data.items].map(item => [item.draftId, item])).values()] : data.items;
      draftPage = page; draftHasNext = data.page.hasNext;
      find('[data-sale-drafts-count]').textContent = '총 ' + data.page.totalElements + '개';
      find('[data-sale-drafts-status]').textContent = draftItems.length ? '' : '아직 저장한 글이 없어요. 아래에서 제품을 선택해 작성을 시작해 주세요.';
      renderDrafts();
    } catch (failure) {
      if (failure.name !== 'AbortError') find('[data-sale-drafts-status]').textContent = '저장 목록을 불러오지 못했어요. 새로고침으로 다시 시도해 주세요.';
    } finally {
      if (!controller.signal.aborted) { listing = false; status(); }
    }
  }

  // 선택한 제품의 원본 사양·사진과 기존 초안을 함께 불러와 이어서 작성할 수 있게 한다.
  async function chooseProduct(next, discard = false, startWriting = false) {
    if (busy() || pendingPublish !== null) return;
    if (!discard && dirty() && !await confirmAction('임시저장하지 않은 내용이 있어요. 저장본을 불러오면 작성 중인 내용이 바뀌어요. 계속할까요?', '불러오기')) {
      find('[data-sale-product]').value = productId || ''; return;
    }
    productLoad?.abort();
    const controller = new AbortController(); productLoad = controller;
    productId = next ? Number(next) : null;
    loading = Boolean(productId); reset(); error();
    find('[data-sale-product]').value = productId || '';
    setLocation(productId);
    if (!productId) { status(); renderDrafts(); return; }
    const option = productOptions.get(productId);
    if (option?.canCompose === false) {
      const state = option.listingStatus === 'RESERVED' ? '예약 중' : '판매 중';
      find('[data-sale-product-blocked-message]').textContent = '이 제품은 이미 ' + state + '이에요. 기존 판매글을 확인하거나 다른 제품을 선택해 주세요.';
      const link = find('[data-sale-existing-listing]');
      link.hidden = !option.listingId;
      if (option.listingId) link.href = '/?listingId=' + option.listingId;
      find('[data-sale-product-blocked]').hidden = false;
      loading = false; status(); renderDrafts(); return;
    }
    try {
      // 이미 열린 판매글이 있는 제품은 저장본·AI 초안을 불러오기 전에 안내한다.
      const response = await api(productPath(productId) + '/composition', { signal: controller.signal });
      const { data } = await response.json();
      const draftResponse = await api(productPath(productId), { signal: controller.signal }, true);
      const draft = draftResponse.status === 404 ? null : (await draftResponse.json()).data;
      if (controller.signal.aborted) return;
      product = data;
      const select = find('[data-sale-product]');
      if (![...select.options].some(option => Number(option.value) === productId)) select.add(new Option(product.title, productId));
      select.value = String(productId);
      applyDraft(draft); renderProduct(); renderPhotos(); renderDrafts();
      refreshPublication();
      loadPhotoUrls(controller.signal);
    } catch (failure) { if (failure.name !== 'AbortError') error(failure.message); }
    finally { if (!controller.signal.aborted) { loading = false; status(); if (startWriting && product) beginWriting(); } }
  }

  // 첫 저장은 POST, 기존 초안 수정은 ID·버전을 포함한 PUT으로 보내고 서버의 새 버전을 보관한다.
  async function persistDraft() {
    const rawPrice = find('[data-sale-price]').value.trim();
    if (rawPrice && !validPrice.test(rawPrice)) { find('[data-sale-price]').focus(); throw new Error('가격은 1원 이상, 최대 12자리의 정수로 입력해 주세요.'); }
    const price = rawPrice ? Number(rawPrice.replaceAll(',', '')) : null;
    const content = { title: find('[data-sale-title]').value || null, description: find('[data-sale-description]').value || null, price, conditionGrade: grade, selectedImageIds: [...selected], version };
    const savingRevision = revision;
    saving = true; status();
    try {
      const path = productPath(productId) + (draftId === null ? '' : '?draftId=' + draftId);
      const response = await api(path, { method: draftId === null ? 'POST' : 'PUT', body: JSON.stringify(content) });
      const { data } = await response.json();
      draftId = data.draftId; version = data.version; savedAt = data.updatedAt; savedRevision = savingRevision;
      await refreshDrafts();
      return data;
    }
    finally { saving = false; status(); }
  }

  async function save(event) {
    event.preventDefault();
    if (!product || busy() || aiLoading || step !== 'compose') return;
    error();
    try { await persistDraft(); } catch (failure) { error(failure.message, true); }
  }

  async function fetchPreview(page) {
    previewLoad?.abort(); const controller = new AbortController(); previewLoad = controller;
    const response = await api(productPath(productId) + '/preview?draftId=' + draftId + '&version=' + version + '&page=' + page + '&size=20', { signal: controller.signal });
    const { data } = await response.json();
    if (controller.signal.aborted) throw new DOMException('Aborted', 'AbortError');
    return data;
  }

  function renderPreview() {
    window.PastportListingDetail.render(find('[data-sale-preview-content]'), previewData);
    find('[data-sale-preview-saved]').textContent = '임시저장 ' + formatTime(previewData.savedAt) + ' · 읽기 전용';
    find('[data-sale-preview-more]').hidden = !previewData.history.page.hasNext;
    find('[data-sale-preview-status]').textContent = '';
  }

  async function openPreview() {
    if (!product || busy() || aiLoading || step !== 'compose') return;
    publicationValidation = true;
    if (!validatePublication(true)) return;
    reviewedDraft = null;
    previewing = true; status(); error();
    let stored = false;
    try {
      // 작성 중 판매 상태가 바뀌었으면 저장·미리보기보다 먼저 알려준다. 최종 게시도 별도로 재검사한다.
      await api(productPath(productId) + '/composition');
      // 저장 응답의 ID·버전으로만 조회한다. 저장 실패 시 미리보기 요청을 보내지 않는다.
      await persistDraft(); stored = true;
      previewData = await fetchPreview(0); renderPreview();
      reviewedDraft = { productId, draftId, version, revision };
      find('[data-sale-preview-error]').hidden = true;
      find('[data-sale-preview-dialog]').showModal(); previewOpen = true;
      find('[data-sale-preview-dialog]').scrollTop = 0;
      find('[data-sale-content-step]').className = '';
      find('[data-sale-preview-step]').className = 'now';
    } catch (failure) {
      if (failure.name !== 'AbortError') error((stored ? '임시저장은 완료했지만 미리보기를 열지 못했어요. ' : '') + failure.message, true);
    } finally { previewing = false; status(); }
  }

  async function morePreview() {
    if (!previewOpen || previewing || !previewData?.history.page.hasNext) return;
    previewing = true; status(); find('[data-sale-preview-more]').disabled = true;
    find('[data-sale-preview-status]').textContent = '이력을 더 불러오는 중이에요…';
    try {
      const data = await fetchPreview(previewData.history.page.number + 1);
      data.history.items = data.history.status !== 'READY' ? [] : [...new Map([...previewData.history.items, ...data.history.items].map(item => [item.eventId, item])).values()];
      previewData = data; renderPreview();
    } catch (failure) {
      if (failure.name !== 'AbortError') {
        reviewedDraft = null;
        find('[data-sale-preview-content]').replaceChildren();
        find('[data-sale-preview-more]').hidden = true;
        find('[data-sale-preview-status]').textContent = failure.message + ' 작성 화면으로 돌아가 다시 미리보기해 주세요.';
      }
    } finally { previewing = false; find('[data-sale-preview-more]').disabled = false; status(); }
  }

  async function deleteDraft(item) {
    if (busy() || pendingPublish !== null) return;
    const current = item.productId === productId;
    const message = '임시저장본을 삭제할까요? 제품과 등록된 사진은 그대로 유지돼요.' + (current && dirty() ? '\n화면에서 작성 중인 변경 내용도 지워져요.' : '');
    if (!await confirmAction(message, '삭제하기')) return;
    deleting = item.draftId; status(); error();
    let removed = false;
    try {
      await api(productPath(item.productId) + '?draftId=' + item.draftId + '&version=' + item.version, { method: 'DELETE' });
      removed = true;
      await refreshDrafts();
    } catch (failure) {
      error(failure.message);
      await refreshDrafts();
    } finally { deleting = null; status(); }
    if (removed && current) await chooseProduct(String(productId), true);
  }

  // 미리보기를 실제로 연 초안의 ID·버전만 게시한다. 응답 유실 재확인에서도 같은 요청을 재사용한다.
  async function publishListing() {
    if (!product || !previewOpen || !reviewedDraft || publishing || previewing || saving) return;
    if (reviewedDraft.productId !== productId || reviewedDraft.draftId !== draftId || reviewedDraft.version !== version || reviewedDraft.revision !== revision) return;
    find('[data-sale-preview-error]').hidden = true;
    publishing = true; status();
    try {
      if (!pendingPublish) {
        pendingPublish = { productId, draftId, version };
      }
      const response = await api('/api/v1/products/' + pendingPublish.productId + '/listings', { method:'POST', body:JSON.stringify({ draftId:pendingPublish.draftId, version:pendingPublish.version }) });
      const { data } = await response.json();
      pendingPublish = null; productId = null; reset();
      await refreshDrafts();
      window.PastportPublishedListing.open(data.listingId);
    } catch (failure) {
      // 응답 유실이면 같은 초안 ID·버전으로 재시도한다. 서버는 같은 게시 결과를 반환한다.
      if (failure.status) pendingPublish = null;
      if ([401,404,409].includes(failure.status)) reviewedDraft = null;
      const notice = find('[data-sale-preview-error]');
      notice.textContent = pendingPublish ? '게시 결과를 확인하지 못했어요. 게시 결과 다시 확인을 눌러 같은 요청의 결과를 확인해 주세요.' : failure.message + ([401,404,409].includes(failure.status) ? ' 돌아가서 확인한 뒤 미리보기를 다시 열어 주세요.' : '');
      notice.hidden = false; notice.focus({preventScroll:true}); notice.scrollIntoView({block:'nearest'});
    } finally { publishing = false; status(); }
  }

  async function loadProducts() {
    productOptionsLoading = true; status();
    try {
      const response = await api('/api/v1/listing-draft-products');
      const { data } = await response.json();
      const select = find('[data-sale-product]'); select.replaceChildren(new Option('판매할 제품을 선택해 주세요.', ''));
      productOptions = new Map(data.map(item => [Number(item.productId), item]));
      data.forEach(item => select.add(new Option((item.canCompose === false ? '[' + (item.listingStatus === 'RESERVED' ? '예약 중' : '판매 중') + '] ' : '[작성 가능] ') + item.title, item.productId)));
      select.value = productId ? String(productId) : '';
      find('[data-sale-product-empty]').hidden = data.length > 0;
    } catch (failure) {
      find('[data-sale-product]').replaceChildren(new Option('제품을 불러올 수 없어요.', '')); error(failure.message);
    } finally { productOptionsLoading = false; status(); }
  }

  async function init() {
    if (initialized || !document.querySelector('#sell [data-sale-form]')) return;
    initialized = true; root = document.querySelector('#sell');
    root.addEventListener('input', event => {
      if (event.target.matches('[data-sale-title], [data-sale-description]') && aiLoading) {
        clearAi('직접 작성한 내용을 유지했어요. 필요한 부분을 이어서 작성해 주세요.');
      }
      if (event.target.matches('[data-sale-title], [data-sale-price], [data-sale-description]')) change();
    });
    find('[data-sale-product]').addEventListener('change', event => chooseProduct(event.target.value));
    find('[data-sale-products-refresh]').addEventListener('click', async () => {
      if (busy()) return;
      await loadProducts();
      if (productId) await chooseProduct(String(productId));
    });
    find('[data-sale-next]').addEventListener('click', () => beginWriting(true));
    find('[data-sale-back]').addEventListener('click', () => setStep('product', true));
    find('[data-sale-form]').addEventListener('submit', save);
    find('[data-sale-reload]').addEventListener('click', () => chooseProduct(String(productId), false, true));
    find('[data-sale-delete]').addEventListener('click', () => deleteDraft({ draftId, productId, version }));
    find('[data-sale-drafts-refresh]').addEventListener('click', () => refreshDrafts());
    find('[data-sale-drafts-more]').addEventListener('click', () => refreshDrafts(true));
    find('[data-sale-publication-refresh]').addEventListener('click', () => refreshPublication());
    find('[data-sale-publication-more]').addEventListener('click', () => refreshPublication(true));
    find('[data-sale-publish]').addEventListener('click', openPreview);
    find('[data-sale-preview-publish]').addEventListener('click', publishListing);
    find('[data-sale-ai-generate]').addEventListener('click', () => generateAi());
    find('[data-sale-ai-retry]').addEventListener('click', () => generateAi());
    find('[data-sale-ai-close]').addEventListener('click', () => find('[data-sale-ai-dialog]').close());
    find('[data-sale-ai-manual]').addEventListener('click', writeManually);
    find('[data-sale-write-manual]').addEventListener('click', writeManually);
    find('[data-sale-photo-open]').addEventListener('click', () => { if (product && !busy() && !pendingPublish) { photoError(); renderPhotos(); find('[data-sale-photo-dialog]').showModal(); loadPhotoUrls(); } });
    for (const selector of ['[data-sale-photo-close]', '[data-sale-photo-done]']) find(selector).addEventListener('click', () => { if (!uploading) find('[data-sale-photo-dialog]').close(); });
    find('[data-sale-photo-dialog]').addEventListener('cancel', event => { if (uploading) event.preventDefault(); });
    find('[data-sale-photo-upload]').addEventListener('click', () => { if (product && !busy() && !pendingPublish) find('[data-sale-photo-files]').click(); });
    find('[data-sale-photo-files]').addEventListener('change', uploadPhotos);
    find('[data-sale-preview-more]').addEventListener('click', morePreview);
    find('[data-sale-preview-close]').addEventListener('click', () => { if (!publishing && !pendingPublish) find('[data-sale-preview-dialog]').close(); });
    find('[data-sale-preview-dialog]').addEventListener('cancel', event => { if (publishing || pendingPublish) event.preventDefault(); });
    find('[data-sale-preview-dialog]').addEventListener('close', () => {
      previewLoad?.abort(); previewData = null; previewOpen = false; reviewedDraft = null;
      find('[data-sale-preview-content]').replaceChildren();
      find('[data-sale-preview-step]').className = ''; find('[data-sale-content-step]').className = 'now';
      renderPhotos(); status();
    });
    root.addEventListener('click', event => {
      const open = event.target.closest('[data-sale-open-draft]');
      if (open) { const item = draftItems.find(draft => draft.draftId === Number(open.dataset.saleOpenDraft)); if (item) chooseProduct(String(item.productId), false, true); return; }
      const remove = event.target.closest('[data-sale-delete-draft]');
      if (remove) { const item = draftItems.find(draft => draft.draftId === Number(remove.dataset.saleDeleteDraft)); if (item) deleteDraft(item); return; }
      if (!product || loading || uploading || deleting !== null || previewing || previewOpen || publishing || pendingPublish !== null) return;
      const removedPhoto = event.target.closest('[data-sale-remove-image]');
      if (removedPhoto && !removedPhoto.disabled) {
        selected = selected.filter(id => id !== Number(removedPhoto.dataset.saleRemoveImage)); renderPhotos(); change(); return;
      }
      const gradeButton = event.target.closest('[data-sale-grade]');
      if (gradeButton && !gradeButton.disabled) {
        const next = Number(gradeButton.dataset.saleGrade); grade = grade === next ? null : next;
        root.querySelectorAll('[data-sale-grade]').forEach(button => { const on = Number(button.dataset.saleGrade) === grade; button.classList.toggle('on', on); button.setAttribute('aria-checked', String(on)); });
        change();
      }
      const image = event.target.closest('[data-sale-image]');
      if (image && !image.disabled) {
        const id = Number(image.dataset.saleImage);
        if (selected.includes(id)) selected = selected.filter(value => value !== id);
        else if (selected.length < maxPhotos) selected.push(id);
        else { photoError('판매글 사진은 최대 10장까지 선택할 수 있어요.'); return; }
        photoError();
        renderPhotos(); change();
      }
      const move = event.target.closest('[data-sale-move]');
      if (move && !move.disabled) {
        const id = Number(move.dataset.imageId); const index = selected.indexOf(id);
        const next = move.dataset.saleMove === 'main' ? 0 : index + (move.dataset.saleMove === 'left' ? -1 : 1);
        selected.splice(index, 1); selected.splice(next, 0, id); renderPhotos(); change();
      }
    });
    window.addEventListener('beforeunload', event => { if (dirty() || pendingPublish) { event.preventDefault(); event.returnValue = ''; } });
    await Promise.all([loadProducts(), refreshDrafts()]);
    const requested = new URL(window.location.href).searchParams.get('draftProduct');
    if (requested && /^[1-9]\d*$/.test(requested)) await chooseProduct(requested, false, true);
    else if (new URL(window.location.href).searchParams.get('drafts') === '1') {
      const section = find('[data-sale-drafts-section]');
      section.style.scrollMarginTop = ((document.querySelector('.site-header')?.getBoundingClientRect().height || 0) + 24) + 'px';
      section.focus({preventScroll:true}); section.scrollIntoView({block:'start'});
    }
    status();
  }

  function onPageReady() {
    const requested = new URL(window.location.href).searchParams.get('draftProduct');
    if (((requested && /^[1-9]\d*$/.test(requested)) || new URL(window.location.href).searchParams.get('drafts') === '1') && typeof show === 'function') show('sell');
    if (document.querySelector('#sell.active')) init();
  }
  document.addEventListener('pastport:page-loaded', event => { if (event.detail.id === 'sell') onPageReady(); });
  document.addEventListener('pastport:page-shown', event => {
    if (event.detail.id !== 'sell') {
      // 다른 화면으로 나가면 주소의 작성 대상을 지워, 새로고침 때 판매글 작성으로 되돌아오지 않게 한다.
      setLocation(null);
      if (initialized) { clearAi(); status(); }
      return;
    }
    if (!initialized) init();
    else if (!busy()) refreshDrafts();
  });
  if (document.querySelector('#sell [data-sale-form]')) onPageReady();
})();
