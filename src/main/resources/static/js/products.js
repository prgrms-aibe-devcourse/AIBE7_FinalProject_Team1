(() => {
  const API = '/api/v1/products';
  const allowedImageTypes = new Set(['image/jpeg', 'image/png', 'image/webp']);
  const state = { products: [], current: null, selectedId: null, loading: null, imageUrls: [] };

  const escapeHtml = (value) => String(value ?? '')
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#039;');
  const text = (value, fallback = '미입력') => value == null || value === '' ? fallback : escapeHtml(value);
  const date = (value) => value ? new Intl.DateTimeFormat('ko-KR').format(new Date(`${value}T00:00:00`)) : '미입력';
  const dateTime = (value) => value ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(new Date(value)) : '–';
  const numberOrNull = (value) => value === '' ? null : Number(value);
  const valueOrNull = (value) => value.trim() || null;
  const navigate = (page) => document.querySelector(`[data-page="${page}"]`)?.click();

  const toast = (message, kind = '') => {
    document.querySelector('.product-toast')?.remove();
    const box = document.createElement('div');
    box.className = `product-toast ${kind}`.trim();
    box.textContent = message;
    document.body.append(box);
    window.setTimeout(() => box.remove(), 3600);
  };

  const api = async (path, options = {}) => {
    const response = await window.PastportApi.fetch(path, options);
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(payload?.message || '요청을 처리하지 못했습니다.');
      error.status = response.status;
      error.fieldErrors = payload?.fieldErrors || [];
      throw error;
    }
    return payload?.data;
  };

  const authenticationMessage = (target) => {
    target.hidden = false;
    target.innerHTML = '<strong class="product-empty-title">로그인이 필요합니다.</strong>내 제품을 확인하려면 먼저 로그인해 주세요.<br><button class="button primary" type="button" data-page="auth">로그인하기</button>';
  };

  const loadProducts = async (force = false) => {
    if (state.loading && !force) return state.loading;
    state.loading = api(API)
      .then((products) => { state.products = products || []; return state.products; })
      .finally(() => { state.loading = null; });
    return state.loading;
  };

  const productChip = (product) => {
    const specs = product.specifications || {};
    const values = [specs.cpu, specs.ramGb && `${specs.ramGb}GB RAM`, specs.storageGb && `${specs.storageGb}GB ${specs.storageType || ''}`].filter(Boolean);
    return values.length ? values.map(escapeHtml).join(' · ') : '상세 사양 미입력';
  };

  const renderProducts = () => {
    const root = document.querySelector('#home');
    const list = root?.querySelector('[data-product-list]');
    const status = root?.querySelector('[data-product-list-status]');
    if (!list || !status) return;
    const query = root.querySelector('[data-product-search]')?.value.trim().toLocaleLowerCase('ko-KR') || '';
    const filtered = state.products.filter((product) => [product.title, product.manufacturer, product.modelName]
      .some((value) => String(value || '').toLocaleLowerCase('ko-KR').includes(query)));
    root.querySelector('[data-product-count]').textContent = state.products.length;
    root.querySelector('[data-purchased-count]').textContent = state.products.filter((product) => product.purchaseDate).length;
    root.querySelector('[data-product-latest]').textContent = state.products[0] ? dateTime(state.products[0].createdAt) : '–';
    list.replaceChildren();
    if (!filtered.length) {
      status.hidden = false;
      status.innerHTML = state.products.length
        ? '<strong class="product-empty-title">검색 결과가 없습니다.</strong>다른 제품명이나 제조사로 검색해 보세요.'
        : '<strong class="product-empty-title">아직 등록한 제품이 없습니다.</strong>첫 노트북을 등록하고 제품 여권을 만들어 보세요.<br><button class="button primary" type="button" data-page="register">제품 등록하기</button>';
      return;
    }
    status.hidden = true;
    list.innerHTML = filtered.map((product) => `
      <article class="product">
        <div class="laptop" aria-hidden="true"></div>
        <div><span class="eyebrow">내 노트북</span><h3>${escapeHtml(product.title)}</h3>
          <div class="meta">${escapeHtml(product.manufacturer)} · ${escapeHtml(product.modelName)} · 구매일 ${date(product.purchaseDate)}</div>
          <span class="chip">${productChip(product)}</span>
        </div>
        <div class="product-action"><button type="button" data-page="passport" data-product-id="${product.productId}">상세 보기 →</button></div>
      </article>`).join('');
  };

  const showListError = (error) => {
    const status = document.querySelector('#home [data-product-list-status]');
    if (!status) return;
    if (error.status === 401) return authenticationMessage(status);
    status.hidden = false;
    status.innerHTML = `<strong class="product-empty-title">제품을 불러오지 못했습니다.</strong>${escapeHtml(error.message)}<br><button class="button" type="button" data-product-retry>다시 시도</button>`;
  };

  const openHome = async (force = false) => {
    const status = document.querySelector('#home [data-product-list-status]');
    if (!status) return;
    status.hidden = false;
    status.textContent = '제품을 불러오는 중입니다.';
    try { await loadProducts(force); renderProducts(); } catch (error) { showListError(error); }
  };

  const revokeImages = () => {
    state.imageUrls.forEach((url) => URL.revokeObjectURL(url));
    state.imageUrls = [];
  };

  const hydrateImages = async (container) => {
    revokeImages();
    await Promise.all([...container.querySelectorAll('[data-protected-image]')].map(async (image) => {
      try {
        const response = await window.PastportApi.fetch(image.dataset.protectedImage);
        if (!response.ok) return;
        const url = URL.createObjectURL(await response.blob());
        state.imageUrls.push(url);
        image.src = url;
      } catch { /* 이미지 한 장 실패는 상세 정보 표시를 막지 않는다. */ }
    }));
  };

  const specItem = (label, value, suffix = '') => `<div class="product-spec-item"><span>${label}</span><b>${text(value)}${value == null || value === '' ? '' : escapeHtml(suffix)}</b></div>`;
  const editForm = (product) => {
    return `<form class="product-edit-form" data-product-edit-form hidden novalidate>
      <div class="product-edit-heading"><div><h2>제품 정보 수정</h2><p>제품명과 구매·보증 정보를 수정하면 변경 기록이 남습니다.</p></div><button type="button" data-product-edit-cancel aria-label="수정 닫기">×</button></div>
      <div class="product-edit-grid">
        <div class="field"><label for="edit-product-title">제품명 <b>*</b></label><input id="edit-product-title" name="title" required maxlength="200" value="${escapeHtml(product.title)}"></div>
        <div class="field"><label for="edit-product-purchase-date">구매일</label><input id="edit-product-purchase-date" name="purchaseDate" type="date" value="${escapeHtml(product.purchaseDate || '')}"></div>
        <div class="field"><label for="edit-product-warranty-date">보증 만료일</label><input id="edit-product-warranty-date" name="warrantyExpiresAt" type="date" value="${escapeHtml(product.warrantyExpiresAt || '')}"></div>
        <div class="field product-edit-reason"><label for="edit-product-reason">변경 사유 <b>*</b></label><input id="edit-product-reason" name="reason" required maxlength="500" placeholder="예: 구매 영수증 확인"></div>
      </div>
      <div class="product-form-message" data-product-form-message role="alert" hidden></div>
      <div class="product-form-actions"><button class="button" type="button" data-product-edit-cancel>취소</button><button class="button primary" type="submit" data-product-edit-submit>변경 사항 저장</button></div>
    </form>`;
  };
  const renderDetail = (product) => {
    const container = document.querySelector('#passport [data-product-detail]');
    const status = document.querySelector('#passport [data-product-detail-status]');
    if (!container || !status) return;
    state.current = product;
    status.hidden = true;
    const specs = product.specifications || {};
    const images = product.images || [];
    const firstImage = images.find((image) => image.primary) || images[0];
    container.innerHTML = `
      <div class="product-detail-hero">
        <div class="product-gallery-wrap"><div class="product-gallery">${firstImage
          ? `<img data-product-gallery-main data-protected-image="${escapeHtml(firstImage.url)}" alt="${escapeHtml(product.title)} 제품 이미지 1">`
          : '<div class="product-gallery-empty"><div class="laptop" aria-hidden="true"></div></div>'}</div>
          ${images.length > 1 ? `<div class="product-thumbnails" aria-label="제품 이미지 선택">${images.map((image, index) => `<button type="button" data-product-thumbnail data-image-index="${index}" aria-label="${index + 1}번째 이미지" aria-pressed="${image.imageId === firstImage.imageId}"><img data-protected-image="${escapeHtml(image.url)}" alt=""></button>`).join('')}</div>` : ''}</div>
        <section class="product-detail-card">
          <div class="product-detail-top"><span class="eyebrow">PRODUCT PASSPORT · 내 제품</span><button class="button" type="button" data-product-edit-open>정보 수정</button></div>
          <h1>${escapeHtml(product.title)}</h1><p class="product-model">${escapeHtml(product.manufacturer)} · ${escapeHtml(product.modelName)}</p>
          <div class="product-facts">
            <div class="product-fact"><span>구매일</span><b>${date(product.purchaseDate)}</b></div>
            <div class="product-fact"><span>보증 만료일</span><b>${date(product.warrantyExpiresAt)}</b></div>
            <div class="product-fact"><span>보증 상태</span><b>${text({ ACTIVE: '보증 중', EXPIRED: '보증 만료', UNKNOWN: '미확인' }[product.warrantyStatus])}</b></div>
            <div class="product-fact"><span>보증 조건</span><b>${text(product.warrantyTerms)}</b></div>
            <div class="product-fact"><span>시리얼 번호</span><b>${text(product.serialNumber)}</b></div>
            <div class="product-fact"><span>제품 정보 버전</span><b>v${escapeHtml(product.factsVersion)}</b></div>
            <div class="product-fact"><span>등록일</span><b>${dateTime(product.createdAt)}</b></div>
            <div class="product-fact"><span>등록 이미지</span><b>${product.images?.length || 0}장</b></div>
          </div>
          <button class="button primary" type="button" data-page="docmask" data-docmask-product-id="${escapeHtml(product.productId)}">증빙 문서 올리기</button>
        </section>
      </div>
      <section class="product-spec-card"><h2>노트북 사양</h2><div class="product-spec-list">
        ${specItem('CPU', specs.cpu)}${specItem('GPU', specs.gpu)}${specItem('RAM', specs.ramGb, 'GB')}${specItem('저장 용량', specs.storageGb, `GB ${specs.storageType || ''}`)}
        ${specItem('디스플레이', specs.display)}${specItem('배터리 상태', specs.batteryHealthPercent, '%')}${specItem('운영체제', specs.os)}${specItem('저장 방식', specs.storageType)}
      </div></section>
      ${editForm(product)}
      <section class="history-mount" data-history-root data-product-id="${product.productId}" aria-label="제품 이력"></section>
      ${images.length ? `<section class="product-image-manager"><h2>제품 이미지 관리</h2>${images.map((image, index) => `<div class="product-image-row"><span>${index + 1}번 이미지 ${image.primary ? '· 대표' : ''}</span><div><button class="button" type="button" data-image-move="up" data-image-id="${image.imageId}" ${index === 0 ? 'disabled' : ''}>앞으로</button><button class="button" type="button" data-image-move="down" data-image-id="${image.imageId}" ${index === images.length - 1 ? 'disabled' : ''}>뒤로</button><button class="button" type="button" data-image-primary data-image-id="${image.imageId}" ${image.primary ? 'disabled' : ''}>대표 지정</button><button class="button danger" type="button" data-image-delete data-image-id="${image.imageId}">삭제</button></div></div>`).join('')}</section>` : ''}
      <section class="product-detail-upload"><div><h2>제품 이미지 추가</h2><p>JPG, PNG, WEBP · 장당 최대 5MB · 제품당 최대 10장</p></div><label class="button primary" for="product-detail-images">＋ 이미지 추가</label><input id="product-detail-images" type="file" accept="image/jpeg,image/png,image/webp" multiple data-product-detail-images></section>
      <section class="ph" data-product-history data-product-id="${escapeHtml(product.productId)}"></section>`;
    hydrateImages(container);
    document.dispatchEvent(new CustomEvent('pastport:product-detail-rendered', { detail: { product } }));
  };

  const openDetail = async () => {
    const status = document.querySelector('#passport [data-product-detail-status]');
    const container = document.querySelector('#passport [data-product-detail]');
    if (!status || !container) return;
    status.hidden = false;
    status.textContent = '제품 정보를 불러오는 중입니다.';
    container.replaceChildren();
    try {
      if (!state.selectedId) {
        await loadProducts();
        if (state.products.length > 1) {
          status.hidden = true;
          document.querySelector('[data-page="home"]')?.click();
          return;
        }
        state.selectedId = state.products[0]?.productId || null;
      }
      if (!state.selectedId) {
        status.innerHTML = '<strong class="product-empty-title">등록된 제품이 없습니다.</strong>제품을 먼저 등록해 주세요.<br><button class="button primary" type="button" data-page="register">제품 등록하기</button>';
        return;
      }
      renderDetail(await api(`${API}/${state.selectedId}`));
    } catch (error) {
      if (error.status === 401) return authenticationMessage(status);
      status.innerHTML = `<strong class="product-empty-title">제품 정보를 불러오지 못했습니다.</strong>${escapeHtml(error.message)}<br><button class="button" type="button" data-product-detail-retry>다시 시도</button>`;
    }
  };

  const validateImages = (files, existing = 0) => {
    if (files.length + existing > 10) return '제품 이미지는 최대 10장까지 등록할 수 있습니다.';
    for (const file of files) {
      if (!allowedImageTypes.has(file.type)) return 'JPG, PNG, WEBP 이미지만 등록할 수 있습니다.';
      if (file.size > 5 * 1024 * 1024) return '이미지는 장당 최대 5MB까지 등록할 수 있습니다.';
    }
    return null;
  };

  const uploadImages = async (productId, files) => {
    let latest = null;
    for (const file of files) {
      const body = new FormData();
      body.append('file', file);
      latest = await api(`${API}/${productId}/images`, { method: 'POST', body });
    }
    return latest;
  };

  const specificationsPayload = (form) => ({
    cpu: valueOrNull(form.elements.cpu.value), gpu: valueOrNull(form.elements.gpu.value),
    ramGb: numberOrNull(form.elements.ramGb.value), storageGb: numberOrNull(form.elements.storageGb.value),
    storageType: form.elements.storageType.value || null, display: valueOrNull(form.elements.display.value),
    batteryHealthPercent: numberOrNull(form.elements.batteryHealthPercent.value), os: valueOrNull(form.elements.os.value),
  });
  const editablePayload = (form) => ({
    title: form.elements.title.value.trim(), purchaseDate: form.elements.purchaseDate.value || null,
    warrantyExpiresAt: form.elements.warrantyExpiresAt.value || null,
    reason: form.elements.reason.value.trim(),
  });
  const formPayload = (form) => ({
    title: form.elements.title.value.trim(), manufacturer: form.elements.manufacturer.value.trim(),
    modelName: form.elements.modelName.value.trim(), purchaseDate: form.elements.purchaseDate.value || null,
    warrantyExpiresAt: form.elements.warrantyExpiresAt.value || null,
    warrantyTerms: valueOrNull(form.elements.warrantyTerms.value), serialNumber: form.elements.serialNumber.value.trim(),
    specifications: {
      ...specificationsPayload(form),
    },
  });

  const showFormMessage = (form, message, kind = 'error') => {
    const box = form.querySelector('[data-product-form-message]');
    box.hidden = !message;
    box.textContent = message || '';
    box.dataset.kind = kind;
  };

  const submitProduct = async (form) => {
    showFormMessage(form, '');
    if (!form.checkValidity()) { form.reportValidity(); return; }
    if (form.elements.purchaseDate.value && form.elements.warrantyExpiresAt.value && form.elements.warrantyExpiresAt.value < form.elements.purchaseDate.value) {
      showFormMessage(form, '보증 만료일은 구매일보다 빠를 수 없습니다.'); return;
    }
    const files = [...form.elements.images.files];
    const imageError = validateImages(files);
    if (imageError) { showFormMessage(form, imageError); return; }
    const submit = form.querySelector('[data-product-submit]');
    submit.disabled = true;
    submit.textContent = '등록 중…';
    try {
      const product = await api(API, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(formPayload(form)) });
      state.selectedId = product.productId;
      try {
        if (files.length) await uploadImages(product.productId, files);
        toast('제품 등록이 완료되었습니다.');
      } catch (imageUploadError) {
        toast(`제품은 등록됐지만 일부 이미지를 올리지 못했습니다. ${imageUploadError.message}`, 'error');
      }
      state.products = [];
      form.reset();
      navigate('passport');
    } catch (error) {
      if (error.status === 401) return showFormMessage(form, '로그인이 필요합니다. 로그인 후 다시 시도해 주세요.');
      showFormMessage(form, error.fieldErrors?.[0]?.message || error.message);
    } finally {
      submit.disabled = false;
      submit.textContent = '제품 등록';
    }
  };

  const submitProductEdit = async (form) => {
    showFormMessage(form, '');
    if (!form.checkValidity()) { form.reportValidity(); return; }
    if (form.elements.purchaseDate.value && form.elements.warrantyExpiresAt.value && form.elements.warrantyExpiresAt.value < form.elements.purchaseDate.value) {
      showFormMessage(form, '보증 만료일은 구매일보다 빠를 수 없습니다.'); return;
    }
    const submit = form.querySelector('[data-product-edit-submit]');
    submit.disabled = true;
    submit.textContent = '저장 중…';
    try {
      const product = await api(`${API}/${state.selectedId}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(editablePayload(form)) });
      state.products = state.products.map((item) => item.productId === product.productId ? product : item);
      renderDetail(product);
      toast('제품 정보를 수정했습니다.');
    } catch (error) {
      if (error.status === 401) return showFormMessage(form, '로그인이 필요합니다. 로그인 후 다시 시도해 주세요.');
      showFormMessage(form, error.fieldErrors?.[0]?.message || error.message);
      submit.disabled = false;
      submit.textContent = '변경 사항 저장';
    }
  };

  const updateImageArrangement = async (imageIds, primaryImageId) => {
    const product = await api(`${API}/${state.selectedId}/images`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ imageIds, primaryImageId }),
    });
    renderDetail(product);
  };

  document.addEventListener('pastport:page-loaded', (event) => {
    const id = event.detail?.id;
    if (id === 'home' && document.querySelector('#home.active')) openHome();
    if (id === 'passport' && document.querySelector('#passport.active')) openDetail();
  });
  document.addEventListener('pastport:page-shown', (event) => {
    if (event.detail?.id === 'home') openHome();
    if (event.detail?.id === 'passport') openDetail();
  });
  document.addEventListener('click', async (event) => {
    const product = event.target.closest?.('[data-product-id]');
    if (product) state.selectedId = Number(product.dataset.productId);
    if (event.target.closest?.('[data-product-retry]')) openHome(true);
    if (event.target.closest?.('[data-product-detail-retry]')) openDetail();
    if (event.target.closest?.('[data-product-edit-open]')) {
      const form = document.querySelector('[data-product-edit-form]');
      if (form) { form.hidden = false; form.querySelector('input')?.focus(); form.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
    }
    if (event.target.closest?.('[data-product-edit-cancel]')) {
      const form = document.querySelector('[data-product-edit-form]');
      if (form) { form.hidden = true; showFormMessage(form, ''); }
    }
    const primary = event.target.closest?.('[data-image-primary]');
    if (primary) {
      const ids = state.current.images.map((image) => image.imageId);
      try { await updateImageArrangement(ids, Number(primary.dataset.imageId)); toast('대표 이미지를 변경했습니다.'); }
      catch (error) { toast(error.message, 'error'); }
      return;
    }
    const move = event.target.closest?.('[data-image-move]');
    if (move) {
      const ids = state.current.images.map((image) => image.imageId);
      const index = ids.indexOf(Number(move.dataset.imageId));
      const next = move.dataset.imageMove === 'up' ? index - 1 : index + 1;
      if (index >= 0 && next >= 0 && next < ids.length) [ids[index], ids[next]] = [ids[next], ids[index]];
      const primaryId = state.current.images.find((image) => image.primary)?.imageId || ids[0];
      try { await updateImageArrangement(ids, primaryId); toast('이미지 순서를 변경했습니다.'); }
      catch (error) { toast(error.message, 'error'); }
      return;
    }
    const deleteImage = event.target.closest?.('[data-image-delete]');
    if (deleteImage) {
      if (!window.confirm('이 제품 이미지를 삭제할까요?')) return;
      try {
        const product = await api(`${API}/${state.selectedId}/images/${deleteImage.dataset.imageId}`, { method: 'DELETE' });
        renderDetail(product); toast('이미지를 삭제했습니다.');
      } catch (error) { toast(error.message, 'error'); }
      return;
    }
    const thumbnail = event.target.closest?.('[data-product-thumbnail]');
    if (thumbnail) {
      const source = thumbnail.querySelector('img')?.src;
      const main = document.querySelector('[data-product-gallery-main]');
      if (source && main) main.src = source;
      document.querySelectorAll('[data-product-thumbnail]').forEach((item) => item.setAttribute('aria-pressed', String(item === thumbnail)));
    }
  }, true);
  document.addEventListener('input', (event) => {
    if (event.target.matches?.('[data-product-search]')) renderProducts();
  });
  document.addEventListener('change', async (event) => {
    if (event.target.name === 'images') {
      const files = [...event.target.files];
      const summary = event.target.closest('form')?.querySelector('[data-product-file-summary]');
      if (summary) summary.textContent = files.length ? `${files.length}장 선택됨` : '선택된 이미지 없음';
      return;
    }
    if (!event.target.matches?.('[data-product-detail-images]')) return;
    const files = [...event.target.files];
    const current = state.current?.productId === state.selectedId ? state.current.images?.length || 0 : 0;
    const error = validateImages(files, current);
    if (error) { toast(error, 'error'); event.target.value = ''; return; }
    try { await uploadImages(state.selectedId, files); toast('이미지를 추가했습니다.'); await openDetail(); }
    catch (uploadError) { toast(uploadError.message, 'error'); }
    finally { event.target.value = ''; }
  });
  document.addEventListener('submit', (event) => {
    const editFormTarget = event.target.closest?.('[data-product-edit-form]');
    if (editFormTarget) {
      event.preventDefault();
      submitProductEdit(editFormTarget);
      return;
    }
    const form = event.target.closest?.('[data-product-form]');
    if (!form) return;
    event.preventDefault();
    submitProduct(form);
  });
})();
