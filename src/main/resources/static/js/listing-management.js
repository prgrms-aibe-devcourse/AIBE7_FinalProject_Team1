// 게시된 판매글만 수정한다. 임시저장 API와 제품·이력 수정 API는 사용하지 않는다.
(() => {
  let initialized = false, current = null, saved = null, selected = [], busy = false, stale = false, generation = 0, request = null;
  const find = selector => document.querySelector('#detail ' + selector);
  const statuses = { ACTIVE:'판매 중', RESERVED:'예약 중', SOLD:'판매 완료', CANCELLED:'판매 중지' };
  const create = (tag, text, className = '') => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text; return node;
  };
  function message(text, failed = false) {
    const node = find('[data-listing-edit-message]'); node.textContent = text;
    node.classList.toggle('is-error', failed); node.setAttribute('role', failed ? 'alert' : 'status');
  }
  async function api(path, options = {}) {
    const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
    const response = await fetcher(path, { credentials:'same-origin', cache:'no-store', ...options });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(response.status === 401 ? '로그인 후 다시 시도해 주세요.' : response.status === 404 ? '현재 이 판매글을 관리할 수 없어요.' : payload?.fieldErrors?.[0]?.message || payload?.message || '요청을 처리하지 못했어요.');
      error.status = response.status; error.code = payload?.code; throw error;
    }
    if (!payload?.data) throw new Error('서버 응답을 확인할 수 없어요. 최신 내용을 다시 불러와 주세요.');
    return payload.data;
  }
  function controls() {
    find('[data-listing-edit-fields]').disabled = busy || stale || !saved?.canEdit;
    find('[data-listing-cancel-fields]').disabled = busy || stale || !saved?.canCancel;
    find('[data-listing-edit-reload]').disabled = busy;
    find('[data-listing-edit-close]').disabled = busy;
  }
  function photos() {
    const root = find('[data-listing-edit-photos]'); root.replaceChildren();
    if (!saved) return;
    const images = new Map(saved.availableImages.map(image => [image.imageId, image]));
    const order = [...selected, ...[...images.keys()].filter(id => !selected.includes(id))];
    order.forEach(id => {
      const index = selected.indexOf(id), item = create('div', null, 'listing-edit-photo');
      const label = create('label'), checkbox = document.createElement('input');
      checkbox.type = 'checkbox'; checkbox.checked = index >= 0;
      checkbox.setAttribute('aria-label', '제품 사진 ' + id + ' 공개 선택');
      checkbox.addEventListener('change', () => { selected = checkbox.checked ? [...selected, id] : selected.filter(value => value !== id); photos(); });
      label.append(checkbox, create('span', index < 0 ? '미선택' : index === 0 ? '1 · 대표 사진' : (index + 1) + ' · 선택 사진'));
      const image = images.get(id);
      if (image) {
        const picture = document.createElement('img'); picture.src = image.url; picture.alt = '제품 사진 ' + id; picture.loading = 'lazy';
        picture.addEventListener('error', () => picture.replaceWith(create('span', '사진을 불러오지 못했어요.'))); label.append(picture);
      } else label.append(create('span', '사용할 수 없는 사진 · 선택을 해제해 주세요.'));
      item.append(label);
      for (const [text, offset] of [['위로', -1], ['아래로', 1]]) {
        const button = create('button', text, 'sc-btn'); button.type = 'button';
        button.setAttribute('aria-label', '제품 사진 ' + id + ' ' + text);
        button.disabled = index < 0 || index + offset < 0 || index + offset >= selected.length;
        button.addEventListener('click', () => { [selected[index], selected[index + offset]] = [selected[index + offset], selected[index]]; photos(); }); item.append(button);
      }
      root.append(item);
    });
  }
  function fill(data) {
    saved = data; selected = [...data.selectedImageIds]; stale = false;
    const form = find('[data-listing-edit-form]');
    form.elements.title.value = data.content.title;
    form.elements.price.value = data.content.price;
    form.elements.conditionGrade.value = String(data.content.conditionGrade);
    form.elements.description.value = data.content.description;
    find('[data-listing-cancel-form]').reset();
    find('[data-listing-edit-state]').textContent = statuses[data.status] + ' · 내용 버전 ' + data.contentVersion + (data.canEdit ? ' · 저장하면 즉시 공개 내용에 반영돼요.' : ' · 현재 상태에서는 판매 조건을 수정할 수 없어요.');
    find('[data-listing-edit-close]').textContent = data.canCancel ? '상세로 돌아가기' : '목록으로';
    photos(); controls();
  }
  function failure(error) {
    if (error.status === 409) stale = true;
    if (error.status === 401 || error.status === 404) { saved = null; selected = []; find('[data-listing-edit-form]').reset(); find('[data-listing-cancel-form]').reset(); find('[data-listing-edit-photos]').replaceChildren(); }
    message(error.message + (error.status === 409 ? ' 입력은 유지했어요. 최신 내용을 불러온 뒤 다시 확인해 주세요.' : ''), true);
  }
  async function open() {
    if (!current || busy) return;
    request?.abort(); const controller = new AbortController(); request = controller;
    const token = ++generation, id = current; busy = true;
    find('[data-listing-editor]').hidden = false; find('[data-listing-content]').hidden = true;
    find('[data-listing-more]').hidden = true; controls(); message('편집 정보를 불러오는 중이에요…');
    try {
      const data = await api('/api/v1/listings/' + id + '/management', { signal:controller.signal });
      if (token !== generation || controller.signal.aborted) return;
      fill(data); message(data.canEdit ? '제품 정보·사양·누적 이력은 해당 관리 화면에서 변경해 주세요.' : '예약 중에는 판매 중지만 가능해요. 종료된 글은 기록으로 보존돼요.');
    } catch (error) { if (token === generation && error.name !== 'AbortError') failure(error); }
    finally { if (token === generation) { busy = false; controls(); } }
  }
  // 읽어 둔 내용 버전을 함께 보내 다른 탭의 수정 내용을 덮어쓰지 않고, 성공하면 상세를 다시 조회한다.
  async function save(event) {
    event.preventDefault(); if (!saved?.canEdit || busy || stale) return;
    const form = event.currentTarget; if (!form.reportValidity()) return;
    const price = form.elements.price.value.trim();
    if (!/^(?:\d{1,12}|\d{1,3}(?:,\d{3}){1,3})$/.test(price) || Number(price.replaceAll(',', '')) < 1) { message('가격은 1원 이상, 최대 12자리 정수로 입력해 주세요.', true); return; }
    if (!form.elements.title.value.trim() || !form.elements.description.value.trim() || selected.length === 0) { message('제목·설명과 공개할 사진을 입력해 주세요.', true); return; }
    const body = { version:saved.contentVersion, title:form.elements.title.value.trim(), description:form.elements.description.value.trim(), price:Number(price.replaceAll(',', '')), conditionGrade:Number(form.elements.conditionGrade.value), selectedImageIds:[...selected] };
    const token = generation, id = current; busy = true; controls(); message('수정 내용을 저장하는 중이에요…');
    try {
      await api('/api/v1/listings/' + id, { method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body) });
      if (token !== generation) return;
      window.PastportPublishedListing.open(id);
    } catch (error) { if (token === generation) failure(error); }
    finally { if (token === generation) { busy = false; controls(); } }
  }
  // 판매 중지 API 성공 후 공개 내용을 비우고 기록 보존 상태를 표시한다. 제품 자체를 삭제하지 않는다.
  async function cancel() {
    find('[data-listing-cancel-dialog]').close();
    if (!saved?.canCancel || busy || stale) return;
    const reason = find('[data-listing-cancel-form]').elements.reason.value.trim();
    const token = generation, id = current; busy = true; controls(); message('판매 중지를 처리하는 중이에요…');
    try {
      await api('/api/v1/listings/' + id + '/cancel', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({version:saved.contentVersion,reason}) });
      if (token !== generation) return;
      saved.canEdit = false; saved.canCancel = false; saved.status = 'CANCELLED';
      find('[data-listing-manage]').hidden = true;
      find('[data-listing-content]').replaceChildren();
      find('[data-listing-edit-state]').textContent = '판매 중지 · 기록 보존';
      find('[data-listing-edit-close]').textContent = '목록으로';
      find('[data-listing-status]').textContent = '판매글 공개가 종료됐어요. 목록으로 돌아가면 중지된 글은 표시되지 않아요.';
      message('판매를 중지했어요. 제품·누적 이력과 과거 판매글 기록은 보존돼요. 재판매는 새 판매글로 진행해 주세요.');
    } catch (error) { if (token === generation) failure(error); }
    finally { if (token === generation) { busy = false; controls(); } }
  }
  async function deleteListing() {
    if (!current || busy) return;
    if (!confirm('이 판매글을 완전히 삭제할까요? 삭제 후에는 복구할 수 없습니다.')) return;
    const token = generation, id = current; busy = true; controls(); message('판매글을 삭제하는 중이에요…');
    try {
      await api('/api/v1/listings/' + id, { method: 'DELETE' });
      alert('판매글이 삭제되었습니다.');
      if (typeof show === 'function') show('market');
    } catch (error) { if (token === generation) failure(error); }
    finally { if (token === generation) { busy = false; controls(); } }
  }
  function reset() {
    generation++; request?.abort(); current = null; saved = null; selected = []; busy = false; stale = false;
    if (!find('[data-listing-editor]')) return;
    find('[data-listing-editor]').hidden = true; find('[data-listing-manage]').hidden = true;
    find('[data-listing-content]').hidden = false;
    find('[data-listing-cancel-dialog]').close();
    find('[data-listing-edit-form]').reset(); find('[data-listing-cancel-form]').reset();
    find('[data-listing-edit-photos]').replaceChildren();
  }
  function bind(data) {
    init(); current = String(data.listingId);
    find('[data-listing-manage]').hidden = !data.canManage;
  }
  function init() {
    if (initialized || !find('[data-listing-editor]')) return; initialized = true;
    find('[data-listing-manage]').addEventListener('click', open);
    find('[data-listing-edit-reload]').addEventListener('click', open);
    find('[data-listing-edit-close]').addEventListener('click', () => {
      if (busy || !current) return;
      if (saved && !saved.canCancel && typeof show === 'function') show('market');
      else window.PastportPublishedListing.open(current);
    });
    find('[data-listing-edit-form]').addEventListener('submit', save);
    find('[data-listing-cancel-form]').addEventListener('submit', event => {
      event.preventDefault(); if (busy || stale || !saved?.canCancel) return;
      if (!event.currentTarget.reportValidity() || !event.currentTarget.elements.reason.value.trim()) { message('판매 중지 사유를 입력해 주세요.', true); return; }
      find('[data-listing-cancel-dialog]').showModal();
    });
    find('[data-listing-cancel-back]').addEventListener('click', () => find('[data-listing-cancel-dialog]').close());
    find('[data-listing-cancel-confirm]').addEventListener('click', cancel);
    find('[data-listing-delete-button]')?.addEventListener('click', deleteListing);
  }
  document.addEventListener('pastport:page-loaded', event => { if (event.detail.id === 'detail') init(); });
  document.addEventListener('pastport:page-shown', event => { if (event.detail.id !== 'detail') reset(); });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
  window.PastportListingManagement = Object.freeze({ bind, reset });
})();
