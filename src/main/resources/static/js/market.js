// 랜딩 등 기존 시안의 칩·관심 표시. 실제 판매글 목록은 아래 R06 모듈에서 처리한다.
document.addEventListener('click', (event) => {
  const chip = event.target.closest('[data-chip]');
  if (chip) {
    chip.parentElement.querySelectorAll('[data-chip]').forEach((item) => item.classList.toggle('active', item === chip));
    return;
  }
  const wish = event.target.closest('[data-wish]');
  if (wish) {
    const on = wish.getAttribute('aria-pressed') !== 'true';
    wish.setAttribute('aria-pressed', String(on));
    wish.textContent = on ? '♥' : '♡';
    return;
  }
  const grade = event.target.closest('[data-grade]');
  if (grade) grade.parentElement.querySelectorAll('[data-grade]').forEach((item) => item.classList.toggle('on', item === grade));
});

// R06 공개 목록: 필터·페이지 복원, 실제 게시글 카드와 상세 진입.
(() => {
  const fields = ['manufacturer', 'minPrice', 'maxPrice', 'cpu', 'gpu', 'minRamGb', 'minStorageGb', 'storageType', 'os', 'minConditionGrade'];
  const sorts = ['LATEST', 'PRICE_ASC', 'PRICE_DESC'];
  const defaults = () => ({ page: 0, size: 20, sort: 'LATEST', ...Object.fromEntries(fields.map(name => [name, ''])) });
  let state = defaults(), root = null, form = null, request = null, initialized = false, active = false, page = null;
  const find = selector => root.querySelector(selector);
  const element = (tag, className, text) => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };
  function restore() {
    const params = new URL(window.location.href).searchParams;
    if (params.get('market') !== '1') return;
    fields.forEach(name => { state[name] = params.get(name) || ''; });
    const number = params.get('page'), size = params.get('size');
    if (/^\d+$/.test(number || '') && Number(number) <= 2147483647) state.page = Number(number);
    if (/^\d+$/.test(size || '') && Number(size) >= 1 && Number(size) <= 100) state.size = Number(size);
    if (sorts.includes(params.get('sort'))) state.sort = params.get('sort');
  }
  function fill() {
    fields.forEach(name => { form.elements[name].value = state[name]; });
    find('[data-market-sort]').value = state.sort;
    if (['cpu', 'gpu', 'minRamGb', 'minStorageGb', 'storageType', 'os'].some(name => state[name])) find('.mk-spec-fields').open = true;
    const size = find('[data-market-size]');
    if (![...size.options].some(option => option.value === String(state.size))) size.add(new Option(state.size + '개', state.size));
    size.value = String(state.size);
    const normalize = value => value.replace(/[\s-]+/g, '').toUpperCase();
    root.querySelectorAll('[data-market-manufacturer]').forEach(button => {
      const selected = normalize(button.dataset.marketManufacturer) === normalize(state.manufacturer);
      button.classList.toggle('active', selected); button.setAttribute('aria-pressed', String(selected));
    });
  }
  function query() {
    const params = new URLSearchParams({ page: String(state.page), size: String(state.size), sort: state.sort });
    fields.forEach(name => { if (state[name]) params.set(name, state[name]); });
    return params;
  }
  function remember() {
    const url = new URL(window.location.href);
    [...fields, 'page', 'size', 'sort', 'listingId', 'draftProduct'].forEach(name => url.searchParams.delete(name));
    url.searchParams.set('market', '1');
    query().forEach((value, name) => url.searchParams.set(name, value));
    window.history.replaceState(window.history.state, '', url);
  }
  function message(text, failed = false) {
    const node = find('[data-market-message]'); node.textContent = text;
    node.classList.toggle('mk-error', failed); node.setAttribute('role', failed ? 'alert' : 'status');
  }
  function card(item) {
    const link = element('a', 'mk-card'); link.href = '/?listingId=' + item.listingId;
    link.addEventListener('click', event => {
      if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || !window.PastportPublishedListing) return;
      event.preventDefault(); window.PastportPublishedListing.open(item.listingId);
    });
    const thumb = element('div', 'mk-thumb');
    const placeholder = () => thumb.replaceChildren(element('span', 'mk-photo-empty', '사진을 표시할 수 없어요'));
    if (item.thumbnailUrl) {
      const image = element('img', ''); image.src = item.thumbnailUrl; image.alt = item.title + ' 대표 사진';
      image.loading = 'lazy'; image.decoding = 'async'; image.addEventListener('error', placeholder); thumb.append(image);
    } else placeholder();
    const body = element('div', 'mk-body');
    body.append(element('b', 'price', new Intl.NumberFormat('ko-KR').format(item.price) + '원'), element('h3', '', item.title));
    body.append(element('p', 'mk-meta', [item.manufacturer, item.modelName, '상태 ' + item.conditionGrade + '등급'].filter(Boolean).join(' · ')));
    const specs = item.specifications || {};
    const summary = [specs.cpu, specs.ramGb == null ? null : specs.ramGb + 'GB RAM', specs.storageGb == null ? null : specs.storageGb + 'GB ' + (specs.storageType || '')].filter(Boolean);
    body.append(element('p', 'mk-meta', summary.join(' · ') || '제품 사양 미등록'));
    if (specs.gpu || specs.os) body.append(element('p', 'mk-meta', [specs.gpu, specs.os].filter(Boolean).join(' · ')));
    const date = new Date(item.publishedAt);
    if (Number.isFinite(date.getTime())) body.append(element('p', 'mk-date', new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(date) + ' 게시'));
    link.append(thumb, body); return link;
  }
  // 필터·정렬·페이지 조건을 실제 목록 API에 보내고 응답 카드로 마켓 화면을 갱신한다.
  async function load() {
    if (!initialized || !active) return;
    request?.abort(); const controller = new AbortController(); request = controller;
    remember(); page = null;
    const grid = find('[data-market-items]'); grid.replaceChildren(); grid.setAttribute('aria-busy', 'true');
    find('[data-market-total]').textContent = '조회 중';
    find('[data-market-prev]').disabled = true; find('[data-market-next]').disabled = true;
    find('[data-market-pagination]').hidden = true; find('[data-market-retry]').hidden = true;
    message('판매글을 불러오는 중이에요…');
    try {
      const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
      const response = await fetcher('/api/v1/listings?' + query(), { credentials: 'same-origin', cache: 'no-store', signal: controller.signal });
      const payload = await response.json().catch(() => null);
      if (!response.ok) throw new Error(payload?.fieldErrors?.[0]?.message || payload?.message || '판매글을 불러오지 못했어요.');
      if (!payload?.data) throw new Error('판매글 응답을 확인할 수 없어요. 다시 시도해 주세요.');
      if (controller.signal.aborted || !active) return;
      page = payload.data.page;
      grid.replaceChildren(...payload.data.items.map(card));
      find('[data-market-total]').textContent = '총 ' + page.totalElements + '개';
      message(page.totalElements === 0 ? '조건에 맞는 판매 중인 글이 없어요. 필터를 바꿔보세요.' : payload.data.items.length === 0 ? '이 페이지에는 판매글이 없어요. 이전 페이지를 확인해 주세요.' : '판매 중인 글만 표시해요. 상세 내용은 로그인한 회원이 확인할 수 있어요.');
      find('[data-market-page]').textContent = (page.number + 1) + ' / ' + Math.max(1, page.totalPages) + ' 페이지';
      find('[data-market-pagination]').hidden = page.totalElements === 0 && page.number === 0;
      find('[data-market-prev]').disabled = page.number === 0; find('[data-market-next]').disabled = !page.hasNext;
    } catch (failure) {
      if (failure.name !== 'AbortError' && !controller.signal.aborted && active) {
        find('[data-market-total]').textContent = '조회 실패';
        message(failure.message || '목록 조회에 실패했어요. 다시 시도해 주세요.', true);
        find('[data-market-retry]').hidden = false;
      }
    } finally { if (!controller.signal.aborted) grid.setAttribute('aria-busy', 'false'); }
  }
  function apply() {
    if (!form.reportValidity()) return;
    const next = { ...state, page: 0, sort: find('[data-market-sort]').value, size: Number(find('[data-market-size]').value) };
    fields.forEach(name => { next[name] = form.elements[name].value.trim(); });
    for (const name of ['minPrice', 'maxPrice']) {
      const raw = next[name];
      if (raw && !/^(?:\d{1,12}|\d{1,3}(?:,\d{3}){1,3})$/.test(raw)) {
        message('가격은 0 이상, 최대 12자리의 정수로 입력해 주세요.', true); form.elements[name].focus(); return;
      }
      next[name] = raw.replaceAll(',', '');
    }
    if (next.minPrice && next.maxPrice && Number(next.minPrice) > Number(next.maxPrice)) {
      message('최소 가격은 최대 가격보다 클 수 없어요.', true); form.elements.minPrice.focus(); return;
    }
    for (const name of ['minRamGb', 'minStorageGb']) {
      if (next[name] && (!/^\d+$/.test(next[name]) || Number(next[name]) < 1 || Number(next[name]) > 2147483647)) {
        message('메모리와 저장 용량은 1 이상의 정수로 입력해 주세요.', true); form.elements[name].focus(); return;
      }
    }
    state = next; fill(); load();
  }
  function init() {
    if (initialized || !document.querySelector('#market [data-market-form]')) return;
    root = document.querySelector('#market'); form = find('[data-market-form]'); initialized = true;
    restore(); fill();
    form.addEventListener('submit', event => { event.preventDefault(); apply(); });
    find('[data-market-reset]').addEventListener('click', () => { state = defaults(); fill(); load(); });
    find('[data-market-retry]').addEventListener('click', load);
    find('[data-market-sort]').addEventListener('change', apply); find('[data-market-size]').addEventListener('change', apply);
    find('[data-market-prev]').addEventListener('click', () => { if (page && page.number > 0) { state.page = page.number - 1; load(); } });
    find('[data-market-next]').addEventListener('click', () => { if (page?.hasNext) { state.page = page.number + 1; load(); } });
    root.querySelectorAll('[data-market-manufacturer]').forEach(button => button.addEventListener('click', () => { form.elements.manufacturer.value = button.dataset.marketManufacturer; apply(); }));
    const params = new URL(window.location.href).searchParams;
    if (root.classList.contains('active')) { active = true; load(); }
    else if (params.get('market') === '1' && !params.has('listingId') && !params.has('draftProduct') && typeof show === 'function') show('market');
  }
  document.addEventListener('pastport:page-loaded', event => { if (event.detail.id === 'market') init(); });
  document.addEventListener('pastport:page-shown', event => {
    if (event.detail.id === 'market') { active = true; if (initialized) load(); else init(); }
    else if (active) {
      active = false; request?.abort();
      const url = new URL(window.location.href); url.searchParams.delete('market');
      window.history.replaceState(window.history.state, '', url);
    }
  });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
