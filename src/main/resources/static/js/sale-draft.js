// 판매글 작성 화면: 제품 선택 → 임시저장 → 미리보기 → 게시 API를 연결한다. 작성 내용은 서버에 비공개로 저장한다.
(() => {
  'use strict';
  let root;
  let productId = null;
  let product = null;
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
  let dragImage = null;
  let aiLoading = false;
  let aiResult = null;
  let aiRequest = null;
  let aiSequence = 0;
  let aiRetryAt = 0;
  let aiRetryTimer = null;
  const imageUrls = new Map();
  const find = selector => root.querySelector(selector);
  const dirty = () => revision !== savedRevision;
  const busy = () => saving || loading || deleting !== null || confirming || previewing || previewOpen || publishing;
  const productPath = id => '/api/v1/products/' + id + '/listing-draft';
  const formatTime = value => new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value));
  const validPrice = /^(?:[1-9]\d{0,11}|[1-9]\d{0,2}(?:,\d{3}){1,3})$/;

  async function api(url, options = {}, allowMissing = false) {
    const headers = new Headers(options.headers || {});
    const csrf = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-Token';
    if (csrf && options.method && options.method !== 'GET') headers.set(csrfHeader, csrf);
    if (options.body) headers.set('Content-Type', 'application/json');
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
    // 하단 버튼에서 실패하면 같은 위치에서 이유를 읽도록 초점을 옮기고 필요한 만큼만 스크롤한다.
    if (message && atActions) {
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
      ['photos', '[data-sale-photos-field]', !selected.length ? product?.images.length
        ? '대표 사진을 선택해 주세요. 첫 번째로 선택한 사진이 대표 사진이 돼요.'
        : '대표 사진이 필요해요. 내 제품에서 제품 사진을 등록한 뒤 다시 불러와 선택해 주세요.' : '']
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
    const saved = find('[data-sale-saved]');
    if (deleting !== null) saved.textContent = '임시저장본을 삭제하는 중이에요…';
    else if (loading) saved.textContent = '제품과 저장본을 불러오는 중이에요…';
    else if (saving) saved.textContent = '비공개로 저장하는 중이에요…';
    else if (savedAt) saved.textContent = '마지막 임시저장 ' + formatTime(savedAt) + (dirty() ? ' · 저장하지 않은 변경이 있어요.' : ' · 비공개 저장 완료');
    else saved.textContent = dirty() ? '작성한 내용을 임시저장해 주세요.' : '아직 임시저장하지 않았어요.';
    find('[data-sale-save]').disabled = !product || blocked;
    find('[data-sale-save]').textContent = saving ? '저장 중…' : '임시저장';
    find('[data-sale-product]').disabled = blocked;
    find('[data-sale-reload]').hidden = !product;
    find('[data-sale-reload]').disabled = blocked;
    find('[data-sale-delete]').hidden = draftId === null;
    find('[data-sale-delete]').disabled = blocked;
    find('[data-sale-drafts-refresh]').disabled = listing || blocked;
    find('[data-sale-drafts-more]').disabled = listing || blocked;
    find('[data-sale-publication-refresh]').disabled = !product || publicationLoading || busy();
    find('[data-sale-publication-more]').disabled = publicationLoading || busy();
    find('[data-sale-preview]').disabled = !product || blocked;
    find('[data-sale-publish]').disabled = !product || busy();
    find('[data-sale-publish]').textContent = publishing ? '게시 중…' : pendingPublish ? '게시 결과 다시 확인' : '게시하기';
    find('[data-sale-preview]').textContent = previewing ? '미리보기 준비 중…' : '미리보기';
    find('[data-sale-ai-generate]').disabled = !product || blocked || aiLoading || Date.now() < aiRetryAt;
    find('[data-sale-ai-generate]').textContent = aiLoading ? 'AI 제안 생성 중…' : aiResult ? '다시 생성' : 'AI 제안 생성';
    find('[data-sale-ai-apply]').disabled = !aiResult || !product || blocked || aiLoading;
    find('[data-sale-ai-manual]').disabled = !product || blocked;
    find('[data-sale-ai-result]').setAttribute('aria-busy', String(aiLoading));
    root.querySelectorAll('[data-sale-open-draft], [data-sale-delete-draft]').forEach(button => { button.disabled = blocked; });
    root.querySelectorAll('[data-sale-title], [data-sale-price], [data-sale-description], [data-sale-grade]').forEach(input => { input.disabled = !product || loading || deleting !== null || previewing || previewOpen || publishing || pendingPublish !== null; });
    root.querySelectorAll('[data-sale-image]').forEach(button => { button.disabled = loading || deleting !== null || previewing || previewOpen || publishing || pendingPublish !== null; });
    root.querySelectorAll('[data-sale-move]').forEach(button => {
      const index = selected.indexOf(Number(button.dataset.imageId));
      button.disabled = loading || deleting !== null || previewing || previewOpen || publishing || pendingPublish !== null || index < 0 || (button.dataset.saleMove === 'right' ? index === selected.length - 1 : index === 0);
    });
  }

  function change() {
    revision++; counters(); status();
    if (publicationValidation) validatePublication();
  }

  function clearAi(message = '제품을 선택하면 AI 제안을 요청할 수 있어요.') {
    aiSequence++; aiRequest?.abort(); aiRequest = null; aiLoading = false; aiResult = null;
    find('[data-sale-ai-result]').hidden = true;
    find('[data-sale-ai-title]').textContent = ''; find('[data-sale-ai-description]').textContent = '';
    find('[data-sale-ai-status]').textContent = message;
  }

  // AI 제안을 별도로 받아 보여준다. 사용자가 적용하기 전에는 작성 폼과 저장본을 바꾸지 않는다.
  async function generateAi() {
    if (!product || busy() || pendingPublish || aiLoading || Date.now() < aiRetryAt) return;
    clearAi('등록된 제품 정보와 전체 핵심 이력으로 AI 제안을 만들고 있어요. 직접 작성은 계속할 수 있어요.');
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
      aiResult = data;
      find('[data-sale-ai-title]').textContent = data.title;
      find('[data-sale-ai-description]').textContent = data.description;
      find('[data-sale-ai-sample]').hidden = !data.sample;
      find('[data-sale-ai-result]').hidden = false;
      find('[data-sale-ai-status]').textContent = '제안이 준비됐어요. 내용을 확인하고 적용해 주세요. 작성 중인 내용은 그대로 유지했어요.';
    } catch (failure) {
      if (sequence !== aiSequence || productId !== requestedProduct) return;
      if (failure.status === 429) {
        aiRetryAt = Date.now() + failure.retryAfter * 1000;
        clearTimeout(aiRetryTimer);
        aiRetryTimer = setTimeout(() => status(), failure.retryAfter * 1000 + 50);
      }
      find('[data-sale-ai-status]').textContent = failure.name === 'AbortError'
        ? 'AI 응답을 기다리는 시간이 초과됐어요. 재시도하거나 직접 작성해 주세요.'
        : failure.status || failure.message?.startsWith('AI 결과') ? failure.message : 'AI에 연결하지 못했어요. 재시도하거나 직접 작성해 주세요.';
    } finally {
      clearTimeout(deadline);
      if (sequence === aiSequence) { aiLoading = false; aiRequest = null; status(); }
    }
  }

  async function applyAi() {
    if (!aiResult || busy() || pendingPublish || aiLoading || aiResult.productId !== productId) return;
    const candidate = aiResult;
    const title = find('[data-sale-title]'); const description = find('[data-sale-description]');
    if ((title.value.trim() || description.value.trim())
        && !await confirmAction('현재 화면의 제목과 설명을 AI 제안으로 바꿀까요? 저장된 임시저장본은 바뀌지 않아요.', '제안 적용', 'AI 제안 적용')) return;
    if (candidate !== aiResult || candidate.productId !== productId) return;
    title.value = candidate.title; description.value = candidate.description;
    change();
    find('[data-sale-ai-status]').textContent = '화면의 제목·설명에 적용했어요. 확인·수정한 뒤 임시저장해 주세요. 아직 저장되거나 게시되지 않았어요.';
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
    root.querySelectorAll('[data-sale-grade]').forEach(button => { button.classList.remove('on'); button.setAttribute('aria-checked', 'false'); });
    find('[data-sale-product-step]').className = 'now';
    find('[data-sale-content-step]').className = '';
    publicationValidation = false; validatePublication();
    counters(); status();
  }

  function setLocation(id) {
    const url = new URL(window.location.href);
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
    find('[data-sale-ai-status]').textContent = 'AI 제안 생성을 누르거나 제목과 설명을 직접 작성해 주세요.';
    find('[data-sale-product-card]').hidden = false;
    find('[data-sale-product-name]').textContent = product.title;
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
    find('[data-sale-product-step]').className = 'done';
    find('[data-sale-content-step]').className = 'now';
  }

  function renderPhotos() {
    const grid = find('[data-sale-photos]'); grid.replaceChildren();
    const ordered = [...selected.map(id => product.images.find(image => image.imageId === id)).filter(Boolean), ...product.images.filter(image => !selected.includes(image.imageId))];
    ordered.forEach(image => {
      const position = selected.indexOf(image.imageId);
      const card = document.createElement('div'); card.className = 'sl-photo-choice';
      const choice = document.createElement('button'); choice.type = 'button';
      choice.className = 'sl-photo' + (position >= 0 ? ' selected' : '') + (position === 0 ? ' main' : '');
      choice.dataset.saleImage = String(image.imageId);
      choice.setAttribute('aria-pressed', String(position >= 0));
      choice.setAttribute('aria-label', '제품 사진 ' + image.imageId + (position >= 0 ? ' 선택 해제' : ' 선택'));
      choice.draggable = position >= 0;
      const url = imageUrls.get(image.imageId);
      if (url) { const img = document.createElement('img'); img.src = url; img.alt = ''; choice.append(img); }
      else { const placeholder = document.createElement('div'); placeholder.className = 'laptop'; placeholder.setAttribute('aria-hidden', 'true'); choice.append(placeholder); }
      if (position >= 0) { const order = document.createElement('i'); order.textContent = String(position + 1); choice.append(order); }
      if (position === 0) { const main = document.createElement('em'); main.textContent = '대표'; choice.append(main); }
      card.append(choice);
      if (position >= 0) {
        const controls = document.createElement('div'); controls.className = 'sl-photo-controls';
        [['대표로', 'main', position === 0], ['←', 'left', position === 0], ['→', 'right', position === selected.length - 1]].forEach(([label, action, disabled]) => {
          const button = document.createElement('button'); button.type = 'button'; button.textContent = label;
          button.dataset.saleMove = action; button.dataset.imageId = String(image.imageId); button.disabled = disabled;
          button.setAttribute('aria-label', '사진 ' + image.imageId + ' ' + ({ main: '대표로 설정', left: '앞으로 이동', right: '뒤로 이동' }[action]));
          controls.append(button);
        });
        card.append(controls);
      }
      grid.append(card);
    });
    find('[data-sale-photos-empty]').hidden = product.images.length > 0;
    find('[data-sale-photos-empty]').textContent = '등록된 제품 사진이 없어요. 사진 없이도 임시저장할 수 있어요.';
    find('[data-sale-photo-count]').textContent = '선택한 사진 ' + selected.length + '장' + (selected.length ? ' · 첫 번째 사진이 대표 사진이에요.' : '');
    status();
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
  async function chooseProduct(next, discard = false) {
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
    try {
      const draftResponse = await api(productPath(productId), { signal: controller.signal }, true);
      const draft = draftResponse.status === 404 ? null : (await draftResponse.json()).data;
      // Product ownership is checked again even when no accessible draft exists.
      const response = await api(productPath(productId) + '/composition', { signal: controller.signal });
      const { data } = await response.json();
      if (controller.signal.aborted) return;
      product = data;
      const select = find('[data-sale-product]');
      if (![...select.options].some(option => Number(option.value) === productId)) select.add(new Option(product.title, productId));
      select.value = String(productId);
      applyDraft(draft); renderProduct(); renderPhotos(); renderDrafts();
      refreshPublication();
      product.images.forEach(async image => {
        try {
          const photo = await api(image.url, { signal: controller.signal });
          const blob = await photo.blob();
          if (controller.signal.aborted) return;
          imageUrls.set(image.imageId, URL.createObjectURL(blob)); renderPhotos();
        } catch (failure) {
          if (failure.name !== 'AbortError' && !controller.signal.aborted) error('일부 사진을 불러오지 못했어요. 저장본을 다시 불러오거나 잠시 후 다시 시도해 주세요.');
        }
      });
    } catch (failure) { if (failure.name !== 'AbortError') error(failure.message); }
    finally { if (!controller.signal.aborted) { loading = false; status(); } }
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
    if (!product || busy()) return;
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
    if (!product || busy()) return;
    previewing = true; status(); error();
    let stored = false;
    try {
      // 저장 응답의 ID·버전으로만 조회한다. 저장 실패 시 미리보기 요청을 보내지 않는다.
      await persistDraft(); stored = true;
      previewData = await fetchPreview(0); renderPreview();
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

  // 입력을 먼저 임시저장한 뒤 그 초안의 ID·버전으로 게시한다. 성공하면 반환된 listingId의 상세로 이동한다.
  async function publishListing() {
    if (!product || busy()) return;
    error();
    if (!pendingPublish) {
      publicationValidation = true;
      if (!validatePublication(true)) return;
      if (!await confirmAction('판매글을 게시할까요? 선택한 제품 사진과 개인정보를 제외한 누적 핵심 이력이 함께 공개돼요. 게시 후 공개 제품 정보·이력으로 AI 상태 요약을 생성해요. 사진·증빙·채팅은 AI에 보내지 않아요.', '게시하기', '판매글 게시 확인')) return;
    }
    publishing = true; status();
    try {
      if (!pendingPublish) {
        await persistDraft();
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
      error(pendingPublish ? '게시 결과를 확인하지 못했어요. 게시 결과 다시 확인을 눌러 같은 요청의 결과를 확인해 주세요.' : failure.message, true);
    } finally { publishing = false; status(); }
  }

  async function loadProducts() {
    try {
      const response = await api('/api/v1/listing-draft-products');
      const { data } = await response.json();
      const select = find('[data-sale-product]'); select.replaceChildren(new Option('판매할 제품을 선택해 주세요.', ''));
      data.forEach(item => select.add(new Option(item.title, item.productId)));
      find('[data-sale-product-empty]').hidden = data.length > 0;
    } catch (failure) {
      find('[data-sale-product]').replaceChildren(new Option('제품을 불러올 수 없어요.', '')); error(failure.message);
    }
  }

  async function init() {
    if (initialized || !document.querySelector('#sell [data-sale-form]')) return;
    initialized = true; root = document.querySelector('#sell');
    root.addEventListener('input', event => { if (event.target.matches('[data-sale-title], [data-sale-price], [data-sale-description]')) change(); });
    find('[data-sale-product]').addEventListener('change', event => chooseProduct(event.target.value));
    find('[data-sale-form]').addEventListener('submit', save);
    find('[data-sale-reload]').addEventListener('click', () => chooseProduct(String(productId)));
    find('[data-sale-delete]').addEventListener('click', () => deleteDraft({ draftId, productId, version }));
    find('[data-sale-drafts-refresh]').addEventListener('click', () => refreshDrafts());
    find('[data-sale-drafts-more]').addEventListener('click', () => refreshDrafts(true));
    find('[data-sale-publication-refresh]').addEventListener('click', () => refreshPublication());
    find('[data-sale-publication-more]').addEventListener('click', () => refreshPublication(true));
    find('[data-sale-preview]').addEventListener('click', openPreview);
    find('[data-sale-publish]').addEventListener('click', publishListing);
    find('[data-sale-ai-generate]').addEventListener('click', generateAi);
    find('[data-sale-ai-apply]').addEventListener('click', applyAi);
    find('[data-sale-ai-manual]').addEventListener('click', () => {
      clearAi('제목과 설명을 직접 작성해 주세요. AI 제안은 언제든 다시 요청할 수 있어요.');
      status(); find('[data-sale-title]').focus();
    });
    find('[data-sale-preview-more]').addEventListener('click', morePreview);
    find('[data-sale-preview-close]').addEventListener('click', () => find('[data-sale-preview-dialog]').close());
    find('[data-sale-preview-dialog]').addEventListener('close', () => {
      previewLoad?.abort(); previewData = null; previewOpen = false;
      find('[data-sale-preview-content]').replaceChildren();
      find('[data-sale-preview-step]').className = ''; find('[data-sale-content-step]').className = 'now';
      renderPhotos(); status();
    });
    root.addEventListener('click', event => {
      const open = event.target.closest('[data-sale-open-draft]');
      if (open) { const item = draftItems.find(draft => draft.draftId === Number(open.dataset.saleOpenDraft)); if (item) chooseProduct(String(item.productId)); return; }
      const remove = event.target.closest('[data-sale-delete-draft]');
      if (remove) { const item = draftItems.find(draft => draft.draftId === Number(remove.dataset.saleDeleteDraft)); if (item) deleteDraft(item); return; }
      if (!product || loading || deleting !== null || previewing || previewOpen || publishing || pendingPublish !== null) return;
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
        else if (selected.length < 20) selected.push(id);
        else { error('사진은 최대 20장까지 선택할 수 있어요.'); return; }
        renderPhotos(); change();
      }
      const move = event.target.closest('[data-sale-move]');
      if (move && !move.disabled) {
        const id = Number(move.dataset.imageId); const index = selected.indexOf(id);
        const next = move.dataset.saleMove === 'main' ? 0 : index + (move.dataset.saleMove === 'left' ? -1 : 1);
        selected.splice(index, 1); selected.splice(next, 0, id); renderPhotos(); change();
      }
    });
    root.addEventListener('dragstart', event => { const image = event.target.closest('[data-sale-image]'); if (image && image.draggable && !busy()) { dragImage = Number(image.dataset.saleImage); event.dataTransfer.setData('text/plain', String(dragImage)); } });
    root.addEventListener('dragover', event => { const image = event.target.closest('[data-sale-image]'); if (!busy() && image && selected.includes(Number(image.dataset.saleImage))) event.preventDefault(); });
    root.addEventListener('drop', event => {
      const image = event.target.closest('[data-sale-image]'); const target = Number(image?.dataset.saleImage);
      if (busy() || !selected.includes(dragImage) || !selected.includes(target)) return;
      event.preventDefault(); const index = selected.indexOf(target); selected = selected.filter(id => id !== dragImage); selected.splice(index, 0, dragImage); dragImage = null; renderPhotos(); change();
    });
    root.addEventListener('dragend', () => { dragImage = null; });
    window.addEventListener('beforeunload', event => { if (dirty() || pendingPublish) { event.preventDefault(); event.returnValue = ''; } });
    await Promise.all([loadProducts(), refreshDrafts()]);
    const requested = new URL(window.location.href).searchParams.get('draftProduct');
    if (requested && /^[1-9]\d*$/.test(requested)) await chooseProduct(requested);
    status();
  }

  function onPageReady() {
    const requested = new URL(window.location.href).searchParams.get('draftProduct');
    if (requested && /^[1-9]\d*$/.test(requested) && typeof show === 'function') show('sell');
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
