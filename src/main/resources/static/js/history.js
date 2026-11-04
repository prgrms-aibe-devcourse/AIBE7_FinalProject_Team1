(() => {
  const typeLabels = {
    PURCHASE: '구매',
    INSPECTION: '점검',
    REPAIR: '수리',
    PART_REPLACEMENT: '부품 교체',
    SPEC_CHANGE: '사양 변경',
    TRANSFER: '소유권 이전',
  };
  const typeIcons = {
    PURCHASE: '●', INSPECTION: '✓', REPAIR: '＋', PART_REPLACEMENT: '↻', SPEC_CHANGE: '⌁', TRANSFER: '⇄',
  };
  const specLabels = {
    cpu: 'CPU', gpu: 'GPU', ramGb: 'RAM', storageGb: '저장 용량', storageType: '저장 방식',
    display: '디스플레이', batteryHealthPercent: '배터리 상태', os: '운영체제',
  };
  const sourceLabels = {
    CURRENT_OWNER: '내 기록',
    PREVIOUS_OWNER: '이전 소유자 기록',
    SYSTEM: '자동 기록',
  };
  const editableTypes = ['PURCHASE', 'INSPECTION', 'REPAIR', 'PART_REPLACEMENT', 'SPEC_CHANGE'];
  const state = {
    productId: null, events: [], filter: 'ALL', editingId: null, requestVersion: 0,
    documents: null, documentRequestVersion: 0, documentPreviews: new Map(),
  };

  const escapeHtml = (value) => String(value ?? '')
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#039;');
  const valueOrNull = (value) => value.trim() || null;
  const numberOrNull = (value) => value === '' ? null : Number(value);
  const today = () => {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  };
  const formatDate = (value) => value
    ? new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(`${value}T00:00:00`))
    : '날짜 미상';
  const formatCost = (value) => value == null ? null : `${new Intl.NumberFormat('ko-KR').format(value)}원`;

  const api = async (path, options = {}) => {
    const response = await window.PastportApi.fetch(path, options);
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(payload?.message || '이력 요청을 처리하지 못했습니다.');
      error.status = response.status;
      error.fieldErrors = payload?.fieldErrors || [];
      throw error;
    }
    return payload?.data;
  };

  const root = () => document.querySelector('#passport [data-history-root]');
  const endpoint = (suffix = '') => `/api/v1/products/${state.productId}/events${suffix}`;

  const shell = () => `
    <div class="history-heading">
      <div><h2>제품 이력</h2><p>소유자가 바뀌어도 이어지는 기록이에요.</p></div>
      <button class="button primary" type="button" data-history-create>＋ 이력 등록</button>
    </div>
    <div class="history-summary" aria-label="이력 요약">
      <div><span>전체 기록</span><strong data-history-count>0</strong><em>건</em></div>
      <div><span>수리 · 교체</span><strong data-history-maintenance>0</strong><em>건</em></div>
      <div><span>최근 기록</span><strong class="history-recent" data-history-recent>–</strong></div>
    </div>
    <div class="history-toolbar" aria-label="이력 필터">
      <button type="button" class="active" data-history-filter="ALL">전체</button>
      <button type="button" data-history-filter="MAINTENANCE">수리 · 교체</button>
      <button type="button" data-history-filter="INSPECTION">점검</button>
      <button type="button" data-history-filter="SPEC_CHANGE">사양 변경</button>
      <button type="button" data-history-filter="TRANSFER">소유권 이전</button>
    </div>
    <div data-history-form-slot></div>
    <div class="history-status" data-history-status role="status">이력을 불러오는 중입니다.</div>
    <div class="history-timeline" data-history-list aria-live="polite"></div>`;

  const specFields = (values = {}, eventType = 'INSPECTION') => {
    const selected = Object.keys(specLabels).find((key) => values[key] != null) || '';
    return `
    <div class="history-spec-fields" data-history-spec-fields>
      <div class="history-field history-wide"><label data-history-spec-picker-label>${eventType === 'PART_REPLACEMENT' ? '교체 후 달라진 사양' : '변경할 사양'} <b>*</b></label><select name="specKey" data-history-spec-picker><option value="">항목을 선택해 주세요</option>${Object.entries(specLabels).map(([key, label]) => `<option value="${key}" ${selected === key ? 'selected' : ''}>${label}</option>`).join('')}</select></div>
      <div class="history-field history-wide" data-history-spec-field="cpu" hidden><label>변경 후 CPU</label><input name="cpu" maxlength="200" value="${escapeHtml(values.cpu || '')}" placeholder="예: Apple M3"></div>
      <div class="history-field history-wide" data-history-spec-field="gpu" hidden><label>변경 후 GPU</label><input name="gpu" maxlength="200" value="${escapeHtml(values.gpu || '')}" placeholder="예: RTX 4060"></div>
      <div class="history-field history-wide" data-history-spec-field="ramGb" hidden><label>변경 후 RAM (GB)</label><input name="ramGb" type="number" min="1" value="${escapeHtml(values.ramGb ?? '')}" placeholder="예: 32"></div>
      <div class="history-field history-wide" data-history-spec-field="storageGb" hidden><label>변경 후 저장 용량 (GB)</label><input name="storageGb" type="number" min="1" value="${escapeHtml(values.storageGb ?? '')}" placeholder="예: 1024"></div>
      <div class="history-field history-wide" data-history-spec-field="storageType" hidden><label>변경 후 저장 방식</label><select name="storageType"><option value="">선택해 주세요</option>${['SSD', 'HDD', 'MIXED'].map((item) => `<option value="${item}" ${values.storageType === item ? 'selected' : ''}>${item}</option>`).join('')}</select></div>
      <div class="history-field history-wide" data-history-spec-field="batteryHealthPercent" hidden><label>변경 후 배터리 상태 (%)</label><input name="batteryHealthPercent" type="number" min="0" max="100" value="${escapeHtml(values.batteryHealthPercent ?? '')}" placeholder="예: 95"></div>
      <div class="history-field history-wide" data-history-spec-field="display" hidden><label>변경 후 디스플레이</label><input name="display" maxlength="200" value="${escapeHtml(values.display || '')}" placeholder="예: 14인치 Liquid Retina"></div>
      <div class="history-field history-wide" data-history-spec-field="os" hidden><label>변경 후 운영체제</label><input name="os" maxlength="200" value="${escapeHtml(values.os || '')}" placeholder="예: macOS"></div>
      <p class="history-spec-help" data-history-spec-help></p>
    </div>`;
  };

  const formTemplate = (event = null) => {
    const editing = !!event;
    const selectedType = event?.eventType || 'INSPECTION';
    const specs = event?.specsAfter || {};
    return `<form class="history-form" data-history-form novalidate>
      <div class="history-form-head"><div><h3>${editing ? '이력 정정' : '새 이력 등록'}</h3><p>${editing ? '수정 사유와 함께 변경 내용이 보존됩니다.' : '제품에 있었던 일을 정확하게 기록해 주세요.'}</p></div><button type="button" data-history-cancel aria-label="닫기">×</button></div>
      <div class="history-form-grid">
        <div class="history-field"><label>이력 유형 <b>*</b></label><select name="eventType" required ${editing ? 'disabled' : ''}>${editableTypes.map((type) => `<option value="${type}" ${selectedType === type ? 'selected' : ''}>${typeLabels[type]}</option>`).join('')}</select></div>
        <div class="history-field"><label>발생일 <b>*</b></label><input name="occurredOn" type="date" required max="${today()}" value="${escapeHtml(event?.occurredOn || today())}"></div>
        <div class="history-field history-wide"><label>제목 <b>*</b></label><input name="title" required maxlength="200" value="${escapeHtml(event?.title || '')}" placeholder="예: 공식 서비스센터 정기 점검"></div>
        <div class="history-field history-wide"><label>상세 내용</label><textarea name="content" maxlength="2000" rows="4" placeholder="점검 결과와 작업 내용을 남겨 주세요.">${escapeHtml(event?.content || '')}</textarea></div>
        <div class="history-field"><label>서비스 업체</label><input name="serviceProvider" maxlength="200" value="${escapeHtml(event?.serviceProvider || '')}" placeholder="예: 제조사 공식 센터"></div>
        <div class="history-field"><label>비용 (원)</label><input name="cost" type="number" min="0" step="1" value="${escapeHtml(event?.cost ?? '')}" placeholder="0"></div>
        <div class="history-field history-wide" data-history-part-field><label>교체 부품</label><input name="replacedPart" maxlength="200" value="${escapeHtml(event?.replacedPart || '')}" placeholder="예: 배터리, SSD"></div>
      </div>
      ${specFields(specs, selectedType)}
      ${editing ? '' : `<div class="history-document-field">
        <div><b>증빙 문서</b><span>영수증, 보증서, 수리 내역서를 이력에 연결할 수 있습니다.</span></div>
        <button class="button" type="button" data-history-document-select aria-expanded="false">증빙 문서 선택</button>
        <div class="history-document-selection" data-history-document-selection aria-live="polite" hidden>
          <span data-history-document-status></span>
          <div data-history-document-options></div>
        </div>
      </div>`}
      ${editing ? '<div class="history-field history-reason"><label>정정 사유 <b>*</b></label><input name="reason" required maxlength="500" placeholder="예: 서비스 명세서 내용으로 바로잡음"></div>' : ''}
      <div class="history-form-message" data-history-form-message role="alert" hidden></div>
      <div class="history-form-actions"><button class="button" type="button" data-history-cancel>취소</button><button class="button primary" type="submit">${editing ? '정정 내용 저장' : '이력 등록'}</button></div>
    </form>`;
  };

  const meaningfulSpecs = (before, after) => {
    if (!after) return [];
    return Object.keys(specLabels).filter((key) => before?.[key] !== after[key]);
  };
  const specValue = (key, value) => {
    if (value == null || value === '') return '미입력';
    if (key === 'ramGb' || key === 'storageGb') return `${value}GB`;
    if (key === 'batteryHealthPercent') return `${value}%`;
    return String(value);
  };
  const details = (event) => {
    const facts = [];
    if (event.serviceProvider) facts.push(`<span>업체 <b>${escapeHtml(event.serviceProvider)}</b></span>`);
    if (event.cost != null) facts.push(`<span>비용 <b>${formatCost(event.cost)}</b></span>`);
    if (event.replacedPart) facts.push(`<span>교체 부품 <b>${escapeHtml(event.replacedPart)}</b></span>`);
    if (event.documentIds?.length) facts.push(`<span>연결 문서 <b>${event.documentIds.length}개</b></span>`);
    const changedSpecs = meaningfulSpecs(event.specsBefore, event.specsAfter);
    return `${facts.length ? `<div class="history-facts">${facts.join('')}</div>` : ''}${changedSpecs.length ? `<div class="history-spec-changes">${changedSpecs.map((key) => `<div><span>${specLabels[key]}</span><del>${escapeHtml(specValue(key, event.specsBefore?.[key]))}</del><i>→</i><b>${escapeHtml(specValue(key, event.specsAfter?.[key]))}</b></div>`).join('')}</div>` : ''}`;
  };
  const changeHistory = (event) => {
    const changes = event.changes?.length ? event.changes
      : event.changeAction ? [{ action: event.changeAction, reason: event.changeReason, changedAt: event.changedAt }] : [];
    if (!changes.length) return '';
    return `<ol class="history-change-list" aria-label="정정 및 무효 처리 기록">${changes.map((change) => {
      const voided = change.action === 'HISTORY_VOIDED';
      return `<li data-change-action="${escapeHtml(change.action)}"><b>${voided ? '무효화' : '정정'}</b><span>${escapeHtml(change.reason || '사유 미입력')}</span>${change.changedAt ? `<time datetime="${escapeHtml(change.changedAt)}">${escapeHtml(new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(new Date(change.changedAt)))}</time>` : ''}</li>`;
    }).join('')}</ol>`;
  };
  const card = (event) => {
    const voided = event.status === 'VOID';
    const canChange = event.editable === true;
    return `<article class="history-event ${voided ? 'is-void' : ''}" data-history-event-id="${event.eventId}">
      <div class="history-marker" data-type="${event.eventType}" aria-hidden="true">${typeIcons[event.eventType] || '●'}</div>
      <div class="history-event-body">
        <div class="history-event-top"><div><time datetime="${escapeHtml(event.occurredOn)}">${formatDate(event.occurredOn)}</time><span class="history-type">${typeLabels[event.eventType] || event.eventType}</span><span class="history-source" data-source="${escapeHtml(event.source || 'SYSTEM')}">${sourceLabels[event.source] || sourceLabels.SYSTEM}</span>${voided ? '<span class="history-void-badge">무효</span>' : ''}</div>${canChange ? `<div class="history-actions"><button type="button" data-history-edit="${event.eventId}">정정</button><button type="button" data-history-void="${event.eventId}">무효화</button></div>` : ''}</div>
        <h3>${escapeHtml(event.title)}</h3>
        ${event.content ? `<p>${escapeHtml(event.content)}</p>` : ''}
        ${details(event)}
        ${changeHistory(event)}
      </div>
    </article>`;
  };

  const filteredEvents = () => state.events.filter((event) => {
    if (state.filter === 'ALL') return true;
    if (state.filter === 'MAINTENANCE') return ['REPAIR', 'PART_REPLACEMENT'].includes(event.eventType);
    return event.eventType === state.filter;
  });
  const renderList = () => {
    const container = root();
    if (!container) return;
    const list = container.querySelector('[data-history-list]');
    const status = container.querySelector('[data-history-status]');
    const events = filteredEvents();
    container.querySelector('[data-history-count]').textContent = state.events.length;
    container.querySelector('[data-history-maintenance]').textContent = state.events.filter((item) => ['REPAIR', 'PART_REPLACEMENT'].includes(item.eventType) && item.status !== 'VOID').length;
    const latest = state.events.reduce((current, event) => !current || event.occurredOn > current.occurredOn ? event : current, null);
    container.querySelector('[data-history-recent]').textContent = latest ? formatDate(latest.occurredOn) : '–';
    if (!events.length) {
      list.replaceChildren();
      status.hidden = false;
      status.innerHTML = state.events.length
        ? '<strong>선택한 유형의 이력이 없습니다.</strong><span>다른 필터를 선택해 보세요.</span>'
        : '<strong>아직 등록된 이력이 없습니다.</strong><span>첫 점검이나 수리 기록을 남겨 제품 여권을 완성해 보세요.</span>';
      return;
    }
    status.hidden = true;
    list.innerHTML = events.map(card).join('');
  };

  const showForm = (event = null) => {
    const container = root();
    if (!container) return;
    state.editingId = event?.eventId || null;
    container.querySelector('[data-history-form-slot]').innerHTML = formTemplate(event);
    syncConditionalFields(container.querySelector('[data-history-form]'));
    container.querySelector('[data-history-form]')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    container.querySelector('[name="title"]')?.focus({ preventScroll: true });
  };
  const closeForm = () => {
    state.editingId = null;
    state.documentRequestVersion += 1;
    root()?.querySelector('[data-history-form-slot]')?.replaceChildren();
  };
  /** 이벤트 유형에 맞는 부품·사양 입력 영역과 필수 상태를 동기화한다. */
  const syncConditionalFields = (form) => {
    if (!form) return;
    const type = form.elements.eventType.value;
    form.querySelector('[data-history-part-field]').hidden = type !== 'PART_REPLACEMENT';
    const specificationFields = form.querySelector('[data-history-spec-fields]');
    specificationFields.hidden = !['PART_REPLACEMENT', 'SPEC_CHANGE'].includes(type);
    const picker = form.elements.specKey;
    picker.required = type === 'SPEC_CHANGE';
    form.querySelector('[data-history-spec-picker-label]').firstChild.textContent = type === 'PART_REPLACEMENT' ? '교체 후 달라진 사양 ' : '변경할 사양 ';
    form.querySelector('[data-history-spec-help]').textContent = type === 'PART_REPLACEMENT'
      ? '실제 부품을 교체했고 제품 사양도 달라진 경우에만 선택해 주세요. 교체 부품명만 기록해도 됩니다.'
      : '부품 교체 사실을 기록하지 않고 현재 사양값만 바로잡거나 변경할 때 사용합니다.';
    syncSelectedSpec(form);
  };
  /** 선택한 사양 입력만 표시하되 다른 사양에 입력한 값은 함께 제출할 수 있도록 보존한다. */
  const syncSelectedSpec = (form) => {
    const selected = form.elements.specKey.value;
    form.dataset.selectedSpec = selected;
    const specificationFields = form.querySelector('[data-history-spec-fields]');
    form.querySelectorAll('[data-history-spec-field]').forEach((field) => {
      field.hidden = field.dataset.historySpecField !== selected;
      const input = field.querySelector('input, select');
      if (input) {
        input.required = !specificationFields.hidden
          && Boolean(selected)
          && field.dataset.historySpecField === selected;
      }
    });
  };
  /** 현재 이력 유형에서 사용 가능한 사양 입력값만 API 요청 객체로 만든다. */
  const specsPayload = (form) => {
    if (!['PART_REPLACEMENT', 'SPEC_CHANGE'].includes(form.elements.eventType.value)) return null;
    const values = {
      cpu: valueOrNull(form.elements.cpu.value), gpu: valueOrNull(form.elements.gpu.value),
      ramGb: numberOrNull(form.elements.ramGb.value), storageGb: numberOrNull(form.elements.storageGb.value),
      storageType: form.elements.storageType.value || null, display: valueOrNull(form.elements.display.value),
      batteryHealthPercent: numberOrNull(form.elements.batteryHealthPercent.value), os: valueOrNull(form.elements.os.value),
    };
    return Object.fromEntries(Object.entries(values).filter(([, value]) => value != null));
  };
  const payload = (form) => ({
    ...(state.editingId ? {} : { eventType: form.elements.eventType.value }),
    occurredOn: form.elements.occurredOn.value,
    title: form.elements.title.value.trim(),
    content: valueOrNull(form.elements.content.value),
    serviceProvider: valueOrNull(form.elements.serviceProvider.value),
    cost: numberOrNull(form.elements.cost.value),
    replacedPart: form.elements.eventType.value === 'PART_REPLACEMENT' ? valueOrNull(form.elements.replacedPart.value) : null,
    specsAfter: specsPayload(form),
    ...(state.editingId ? { reason: form.elements.reason.value.trim() } : {
      documentIds: [...form.querySelectorAll('[data-history-document-option]:checked')]
        .map((input) => Number(input.value)),
    }),
  });
  const formMessage = (form, message) => {
    const box = form.querySelector('[data-history-form-message]');
    box.hidden = !message;
    box.textContent = message || '';
  };

  const documentLabel = (document) => {
    const kind = document.contentType === 'application/pdf' ? 'PDF'
      : document.contentType?.startsWith('image/') ? '이미지' : '파일';
    const date = document.maskConfirmedAt
      ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(document.maskConfirmedAt))
      : '확인일 없음';
    return `${document.name || kind + ' 문서'} · ${date}`;
  };

  const renderDocumentOptions = (form) => {
    const selection = form.querySelector('[data-history-document-selection]');
    const status = selection?.querySelector('[data-history-document-status]');
    const options = selection?.querySelector('[data-history-document-options]');
    if (!selection || !status || !options) return;
    const documents = (state.documents || []).filter((document) =>
      document.maskConfirmedAt && !document.redactionRequired);
    status.textContent = documents.length
      ? '이력에 함께 보관할 문서를 선택해 주세요.'
      : '연결 가능한 문서가 없습니다. 마스킹 확인을 마친 문서를 먼저 등록해 주세요.';
    options.innerHTML = documents.map((document) => `
      <label class="history-document-option">
        <input type="checkbox" value="${document.id}" data-history-document-option>
        <span class="history-document-thumb" data-history-document-thumb="${document.id}" aria-hidden="true">불러오는 중</span>
        <span>${escapeHtml(documentLabel(document))}</span>
        <button type="button" class="history-document-view" data-history-document-view="${document.id}" data-document-name="${escapeHtml(document.name || (document.contentType === 'application/pdf' ? 'PDF 문서' : '이미지 문서'))}" aria-haspopup="dialog" hidden>크게 보기</button>
      </label>`).join('');
    hydrateDocumentPreviews(options);
  };

  /** 연결할 문서를 알아볼 수 있게 마스킹된 사본을 내려받아 미리보기 주소를 만든다. 같은 문서는 한 번만 받는다. */
  const documentPreview = (documentId) => {
    if (!state.documentPreviews.has(documentId)) {
      const loading = (async () => {
        const file = await api(`/api/v1/documents/${documentId}/file-url`);
        const response = await window.PastportApi.fetch(file.url);
        if (!response.ok) throw new Error('미리보기를 불러오지 못했습니다.');
        const blob = await response.blob();
        return { url: URL.createObjectURL(blob), type: blob.type };
      })();
      loading.catch(() => state.documentPreviews.delete(documentId));
      state.documentPreviews.set(documentId, loading);
    }
    return state.documentPreviews.get(documentId);
  };
  /** 다른 제품으로 바뀌면 이전 제품 문서의 미리보기 주소를 해제한다. */
  const releaseDocumentPreviews = () => {
    state.documentPreviews.forEach((loading) => loading.then(({ url }) => URL.revokeObjectURL(url), () => {}));
    state.documentPreviews = new Map();
  };
  const hydrateDocumentPreviews = (container) => {
    container.querySelectorAll('[data-history-document-thumb]').forEach(async (thumb) => {
      const id = Number(thumb.dataset.historyDocumentThumb);
      try {
        const preview = await documentPreview(id);
        if (!thumb.isConnected) return;
        if (preview.type.startsWith('image/')) {
          const image = document.createElement('img');
          image.src = preview.url;
          image.alt = '';
          thumb.replaceChildren(image);
        } else {
          thumb.textContent = preview.type === 'application/pdf' ? 'PDF' : '파일';
        }
        container.querySelector(`[data-history-document-view="${id}"]`)?.removeAttribute('hidden');
      } catch {
        if (thumb.isConnected) thumb.textContent = '미리보기 없음';
      }
    });
  };

  const toggleDocuments = async (button) => {
    const form = button.closest('[data-history-form]');
    const selection = form?.querySelector('[data-history-document-selection]');
    const status = selection?.querySelector('[data-history-document-status]');
    if (!form || !selection || !status) return;
    if (!selection.hidden) {
      selection.hidden = true;
      button.setAttribute('aria-expanded', 'false');
      return;
    }
    selection.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    if (state.documents) { renderDocumentOptions(form); return; }
    const version = ++state.documentRequestVersion;
    status.textContent = '문서를 불러오는 중입니다.';
    try {
      const documents = await api(`/api/v1/products/${state.productId}/documents`);
      if (version !== state.documentRequestVersion || !form.isConnected) return;
      state.documents = documents || [];
      renderDocumentOptions(form);
    } catch (error) {
      if (version !== state.documentRequestVersion || !form.isConnected) return;
      status.textContent = error.message;
    }
  };

  const load = async (productId) => {
    const container = root();
    if (!container) return;
    const version = ++state.requestVersion;
    state.productId = Number(productId);
    state.events = [];
    state.filter = 'ALL';
    state.editingId = null;
    state.documents = null;
    state.documentRequestVersion += 1;
    releaseDocumentPreviews();
    container.innerHTML = shell();
    try {
      const events = await api(endpoint());
      if (version !== state.requestVersion || state.productId !== Number(productId)) return;
      state.events = events || [];
      renderList();
    } catch (error) {
      if (version !== state.requestVersion) return;
      const status = root()?.querySelector('[data-history-status]');
      if (status) status.innerHTML = `<strong>이력을 불러오지 못했습니다.</strong><span>${escapeHtml(error.message)}</span><button class="button" type="button" data-history-retry>다시 시도</button>`;
    }
  };

  const submit = async (form) => {
    formMessage(form, '');
    if (!form.checkValidity()) { form.reportValidity(); return; }
    const body = payload(form);
    if (body.eventType === 'SPEC_CHANGE' && !Object.keys(body.specsAfter || {}).length) {
      formMessage(form, '사양 변경 내용을 하나 이상 입력해 주세요.'); return;
    }
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    const original = button.textContent;
    button.textContent = '저장 중…';
    try {
      const suffix = state.editingId ? `/${state.editingId}` : '';
      await api(endpoint(suffix), { method: state.editingId ? 'PATCH' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      closeForm();
      state.events = await api(endpoint());
      renderList();
    } catch (error) {
      formMessage(form, error.fieldErrors?.[0]?.message || error.message);
      button.disabled = false;
      button.textContent = original;
    }
  };

  const voidEvent = async (eventId) => {
    const reason = window.prompt('이 기록을 무효화하는 사유를 입력해 주세요.\n무효화된 기록도 감사 이력으로 보존됩니다.');
    if (reason == null) return;
    if (!reason.trim()) { window.alert('무효화 사유를 입력해 주세요.'); return; }
    try {
      await api(endpoint(`/${eventId}/void`), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason: reason.trim() }) });
      state.events = await api(endpoint());
      renderList();
    } catch (error) { window.alert(error.message); }
  };

  document.addEventListener('pastport:product-detail-rendered', (event) => load(event.detail.product.productId));
  document.addEventListener('change', (event) => {
    const form = event.target.closest?.('[data-history-form]');
    if (form && event.target.name === 'eventType') syncConditionalFields(form);
    if (form && event.target.name === 'specKey') syncSelectedSpec(form);
  });
  document.addEventListener('submit', (event) => {
    const form = event.target.closest?.('[data-history-form]');
    if (!form) return;
    event.preventDefault();
    submit(form);
  });
  document.addEventListener('click', (event) => {
    if (event.target.closest?.('[data-history-create]')) { showForm(); return; }
    if (event.target.closest?.('[data-history-cancel]')) { closeForm(); return; }
    if (event.target.closest?.('[data-history-retry]')) { load(state.productId); return; }
    const documentSelect = event.target.closest?.('[data-history-document-select]');
    if (documentSelect) { toggleDocuments(documentSelect); return; }
    const documentView = event.target.closest?.('[data-history-document-view]');
    if (documentView) {
      event.preventDefault();
      documentPreview(Number(documentView.dataset.historyDocumentView))
        .then(({ url, type }) => window.PastportDocPreview?.open({
          url,
          title: documentView.dataset.documentName || '증빙 문서',
          contentType: type,
        }))
        .catch((error) => window.alert(error.message));
      return;
    }
    const filter = event.target.closest?.('[data-history-filter]');
    if (filter) {
      state.filter = filter.dataset.historyFilter;
      root()?.querySelectorAll('[data-history-filter]').forEach((button) => button.classList.toggle('active', button === filter));
      renderList(); return;
    }
    const edit = event.target.closest?.('[data-history-edit]');
    if (edit) { showForm(state.events.find((item) => item.eventId === Number(edit.dataset.historyEdit))); return; }
    const voidButton = event.target.closest?.('[data-history-void]');
    if (voidButton) voidEvent(Number(voidButton.dataset.historyVoid));
  });
})();
