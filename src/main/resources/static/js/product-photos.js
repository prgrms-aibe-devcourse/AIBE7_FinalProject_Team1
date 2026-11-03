// 내 제품 사진 편집. 소유권·판매글 사용 여부는 기존 제품 이미지 API가 최종 확인한다.
(() => {
  let product = null, busy = false, version = 0, paint = 0, pendingDelete = null;
  const urls = new Map();
  const find = selector => document.querySelector('#passport ' + selector);
  const dialog = () => find('[data-product-photos-dialog]');
  const create = (tag, text, className = '') => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };
  const message = (text = '', failed = false) => {
    const node = find('[data-product-photos-message]');
    node.textContent = text; node.classList.toggle('is-error', failed);
    node.setAttribute('role', failed ? 'alert' : 'status');
  };
  function controls() {
    dialog().querySelectorAll('button,input').forEach(node => {
      node.disabled = busy || node.dataset.unavailable === 'true';
    });
    const input = find('[data-product-photo-files]');
    input.disabled = busy || (product?.images?.length || 0) >= 10;
    input.closest('label').classList.toggle('is-disabled', input.disabled);
    dialog().setAttribute('aria-busy', String(busy));
  }
  async function api(path, options = {}) {
    const response = await window.PastportApi.fetch(path, options);
    const payload = await response.json().catch(() => null);
    if (!response.ok) throw new Error(payload?.message || '사진을 변경하지 못했어요. 다시 시도해 주세요.');
    if (!payload?.data) throw new Error('제품 응답을 확인하지 못했어요. 다시 열어 주세요.');
    return payload.data;
  }
  function clearUrls() { urls.forEach(url => URL.revokeObjectURL(url)); urls.clear(); }
  function close(force = false) {
    if (busy && !force) { message('사진을 변경하고 있어요. 잠시만 기다려 주세요.'); return; }
    version++; paint++; busy = false; pendingDelete = null;
    if (dialog()) {
      dialog().close(); find('[data-product-photo-files]').value = '';
      find('[data-product-photos-list]').replaceChildren();
      find('[data-product-photos-delete]').hidden = true;
    }
    clearUrls();
  }
  // 늦게 도착한 비공개 이미지 응답이 다른 제품이나 닫힌 편집창에 남지 않도록 한다.
  async function picture(image, img, currentPaint) {
    if (urls.has(image.imageId)) { img.src = urls.get(image.imageId); return; }
    try {
      const response = await window.PastportApi.fetch(image.url);
      if (!response.ok) throw new Error('image');
      const blob = await response.blob();
      if (currentPaint !== paint || !dialog()?.open) return;
      const url = URL.createObjectURL(blob); urls.set(image.imageId, url); img.src = url;
    } catch {
      if (currentPaint === paint && dialog()?.open) img.replaceWith(create('span', '사진을 불러오지 못했어요.', 'product-photo-unavailable'));
    }
  }
  function render(focusKey) {
    const currentPaint = ++paint, root = find('[data-product-photos-list]'); root.replaceChildren();
    const images = product?.images || [];
    find('[data-product-photos-count]').textContent = images.length + ' / 10장';
    if (!images.length) root.append(create('p', '등록된 사진이 없어요. 사진 추가로 제품 모습을 남겨 주세요.', 'product-photos-empty'));
    images.forEach((image, index) => {
      const item = create('article', null, 'product-photo-item');
      const frame = create('div', null, 'product-photo-frame');
      const img = create('img'); img.alt = '제품 사진 ' + (index + 1); frame.append(img);
      const caption = create('div', null, 'product-photo-caption');
      caption.append(create('b', image.primary ? '대표 사진' : '사진 ' + (index + 1)));
      const remove = create('button', '삭제', 'product-photo-remove'); remove.type = 'button';
      remove.dataset.photoAction = 'remove'; remove.dataset.photoId = image.imageId;
      remove.dataset.photoKey = 'remove-' + image.imageId;
      remove.setAttribute('aria-label', '사진 ' + (index + 1) + ' 삭제'); caption.append(remove);
      const actions = create('div', null, 'product-photo-actions');
      for (const [action, text, label, unavailable] of [
        ['primary', '대표로', '대표 사진으로 지정', image.primary],
        ['left', '←', '왼쪽으로 이동', index === 0],
        ['right', '→', '오른쪽으로 이동', index === images.length - 1],
      ]) {
        const button = create('button', text); button.type = 'button';
        button.dataset.photoAction = action; button.dataset.photoId = image.imageId;
        button.dataset.photoKey = action + '-' + image.imageId;
        button.dataset.unavailable = String(!!unavailable);
        button.setAttribute('aria-label', '사진 ' + (index + 1) + ' ' + label); actions.append(button);
      }
      item.append(frame, caption, actions); root.append(item); picture(image, img, currentPaint);
    });
    controls();
    if (focusKey) ([...root.querySelectorAll('button')].find(node => node.dataset.photoKey === focusKey) || find('[data-product-photo-files]')).focus({preventScroll:true});
  }
  function apply(data) {
    product = data;
    document.dispatchEvent(new CustomEvent('pastport:product-images-updated', {detail:{product:data}}));
  }
  // 변경은 한 번에 하나씩 전송한다. 서버가 거절하면 현재 사진과 순서를 유지한다.
  async function change(action, imageId, focusKey) {
    if (busy || !product || !dialog()?.open) return;
    const images = product.images || [], index = images.findIndex(image => image.imageId === imageId);
    if (index < 0) return;
    const ids = images.map(image => image.imageId), target = index + (action === 'left' ? -1 : 1);
    if ((action === 'left' || action === 'right') && (target < 0 || target >= ids.length)) return;
    if (action === 'left' || action === 'right') [ids[index], ids[target]] = [ids[target], ids[index]];
    const primary = action === 'primary' ? imageId : (images.find(image => image.primary) || images[0]).imageId;
    if (action === 'primary') { ids.splice(index, 1); ids.unshift(imageId); }
    const token = version, id = product.productId; busy = true; controls(); message('사진을 변경하고 있어요…');
    try {
      const data = await api('/api/v1/products/' + id + '/images' + (action === 'delete' ? '/' + imageId : ''),
        action === 'delete' ? {method:'DELETE'} : {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({imageIds:ids,primaryImageId:primary})});
      if (token !== version) return;
      if (action === 'delete') { if (urls.has(imageId)) URL.revokeObjectURL(urls.get(imageId)); urls.delete(imageId); }
      apply(data); message('사진 변경을 반영했어요.');
    } catch (error) { if (token === version) message(error.message, true); }
    finally {
      if (token === version) { busy = false; pendingDelete = null; find('[data-product-photos-delete]').hidden = true; render(focusKey); }
    }
  }
  async function add(files) {
    if (busy || !product || !files.length || !dialog()?.open) return;
    const error = (product.images?.length || 0) + files.length > 10 ? '제품 사진은 최대 10장까지 등록할 수 있어요.'
      : files.some(file => !['image/jpeg','image/png','image/webp'].includes(file.type)) ? 'JPG, PNG, WEBP 사진을 선택해 주세요.'
      : files.some(file => file.size > 5 * 1024 * 1024) ? '사진은 장당 최대 5MB까지 올릴 수 있어요.' : '';
    if (error) { message(error, true); return; }
    const token = version, id = product.productId; let added = 0;
    busy = true; controls(); message('사진을 추가하고 있어요…');
    try {
      for (const file of files) {
        const body = new FormData(); body.append('file', file);
        const data = await api('/api/v1/products/' + id + '/images', {method:'POST',body});
        if (token !== version) return;
        apply(data); added++;
      }
      message(added + '장 추가했어요.');
    } catch (error) {
      if (token === version) message((added ? added + '장은 추가했어요. ' : '') + error.message, true);
    } finally { if (token === version) { busy = false; render(); } }
  }
  document.addEventListener('pastport:product-detail-rendered', event => {
    if (product?.productId !== event.detail.product.productId) close(true);
    product = event.detail.product;
    if (dialog()?.open && !busy) render();
  });
  document.addEventListener('pastport:page-shown', event => { if (event.detail.id !== 'passport') { close(true); product = null; } });
  document.addEventListener('pastport:auth-changed', () => { close(true); product = null; });
  window.addEventListener('pagehide', () => { close(true); product = null; });
  document.addEventListener('click', event => {
    if (event.target.closest?.('[data-product-images-edit]')) {
      if (!product || !dialog() || busy) return;
      message(); pendingDelete = null; find('[data-product-photos-delete]').hidden = true;
      dialog().showModal(); render(); return;
    }
    if (event.target.closest?.('[data-product-photos-close]')) { close(); return; }
    if (event.target.closest?.('[data-product-photo-keep]')) { pendingDelete = null; find('[data-product-photos-delete]').hidden = true; return; }
    if (event.target.closest?.('[data-product-photo-delete-confirm]')) { if (pendingDelete != null) change('delete', pendingDelete, 'remove-' + pendingDelete); return; }
    const button = event.target.closest?.('[data-photo-action]');
    if (!button || busy) return;
    const id = Number(button.dataset.photoId);
    if (button.dataset.photoAction === 'remove') {
      pendingDelete = id; find('[data-product-photos-delete]').hidden = false;
      find('#product-photo-delete-title').textContent = ((product.images || []).findIndex(image => image.imageId === id) + 1) + '번 사진을 삭제할까요?';
      const preview = find('[data-product-photo-delete-image]');
      if (urls.has(id)) { preview.src = urls.get(id); preview.hidden = false; } else preview.hidden = true;
      find('[data-product-photo-keep]').focus(); return;
    }
    change(button.dataset.photoAction, id, button.dataset.photoKey);
  });
  document.addEventListener('change', event => {
    if (!event.target.matches?.('[data-product-photo-files]')) return;
    const files = [...event.target.files]; event.target.value = ''; add(files);
  });
  function init() {
    const node = dialog(); if (!node || node.dataset.bound) return; node.dataset.bound = 'true';
    node.addEventListener('cancel', event => { if (busy) { event.preventDefault(); message('사진을 변경하고 있어요. 잠시만 기다려 주세요.'); } });
    node.addEventListener('close', () => { paint++; clearUrls(); find('[data-product-photos-list]').replaceChildren(); find('[data-product-images-edit]')?.focus({preventScroll:true}); });
  }
  document.addEventListener('pastport:page-loaded', event => { if (event.detail.id === 'passport') init(); });
  init();
})();
