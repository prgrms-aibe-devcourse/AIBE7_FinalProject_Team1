// 외부 이전 보내기: 소유 제품을 선택해 기존 DIRECT 발급 패널을 연다. 내부 거래 완료는 거래 화면에서 처리한다.
(() => {
  let request = null, products = [], selectedId = null, returnAfterLogin = false;
  const root = () => document.querySelector('#trade');
  const find = selector => root()?.querySelector(selector);
  const active = () => Boolean(document.querySelector('#trade.active'));
  const create = (tag, text, className = '') => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };

  /** 화면·계정 전환 시 이전 사용자의 제품 목록과 공유 링크를 지우고 진행 중인 조회를 취소한다. */
  function clear() {
    request?.abort(); request = null; products = []; selectedId = null;
    window.PastportOwnership?.clear(find('[data-ownership-root]'));
    find('[data-external-products]')?.replaceChildren();
    const selected = find('[data-external-selected]'); if (selected) selected.hidden = true;
    const empty = find('[data-external-placeholder]'); if (empty) empty.hidden = false;
    const title = find('[data-external-product-title]'); if (title) title.textContent = '';
    const count = find('[data-external-count]'); if (count) count.textContent = '';
    const search = find('[data-external-search]'); if (search) search.value = '';
  }

  /** 서버가 반환한 현재 소유 제품만 선택하고, 발급 전 상태 조회를 기존 외부 이전 패널에 위임한다. */
  function selectProduct(product) {
    if (!active() || !products.includes(product)) return;
    selectedId = String(product.productId);
    const title = find('[data-external-product-title]'); title.textContent = product.title;
    find('[data-external-selected]').hidden = false;
    find('[data-external-placeholder]').hidden = true;
    find('[data-external-products]').querySelectorAll('button').forEach(button => {
      const selected = button.dataset.externalProductId === selectedId;
      button.setAttribute('aria-pressed', String(selected));
      button.querySelector('small').textContent = selected ? '선택한 제품' : '이 제품 선택';
    });
    window.PastportOwnership.mount(find('[data-ownership-root]'), product.productId);
    title.style.scrollMarginTop = (document.querySelector('.site-header')?.getBoundingClientRect().height || 0) + 16 + 'px';
    title.focus({ preventScroll: true }); title.scrollIntoView({ block: 'nearest' });
  }

  /** 입력된 검색어로 목록만 좁힌다. 검색으로 선택한 제품의 발급 상태를 바꾸지는 않는다. */
  function renderProducts() {
    const list = find('[data-external-products]');
    const query = find('[data-external-search]').value.trim().toLocaleLowerCase('ko-KR');
    list.replaceChildren();
    const matches = products.filter(product => [product.title, product.manufacturer, product.modelName]
      .some(value => String(value || '').toLocaleLowerCase('ko-KR').includes(query)));
    for (const product of matches) {
      const button = create('button', null, 'external-product'); button.type = 'button';
      button.dataset.externalProductId = String(product.productId);
      button.setAttribute('aria-pressed', String(String(product.productId) === selectedId));
      button.append(create('strong', product.title), create('span', [product.manufacturer, product.modelName].filter(Boolean).join(' · ')), create('small', String(product.productId) === selectedId ? '선택한 제품' : '이 제품 선택'));
      button.addEventListener('click', () => selectProduct(product)); list.append(button);
    }
    find('[data-external-status]').textContent = !products.length ? '외부 이전을 보낼 제품이 없습니다. 먼저 제품 여권을 등록해 주세요.'
      : !matches.length ? '검색 결과가 없습니다. 다른 제품명이나 제조사로 검색해 주세요.' : '';
    find('[data-external-register]').hidden = products.length > 0;
  }

  /** 로그인한 사용자의 제품을 조회한다. 지연 응답은 현재 화면·요청과 일치할 때만 반영한다. */
  async function loadProducts() {
    if (!active() || !find('[data-external-products]')) return;
    clear();
    const controller = new AbortController(); request = controller;
    const current = () => request === controller && !controller.signal.aborted && active();
    const status = find('[data-external-status]'); status.textContent = '내 제품을 불러오고 있습니다.';
    find('[data-external-login]').hidden = true;
    find('[data-external-retry]').hidden = true;
    find('[data-external-register]').hidden = true;
    find('[data-external-refresh]').disabled = true;
    find('[data-external-search]').disabled = true;
    find('[data-external-products]').setAttribute('aria-busy', 'true');
    try {
      const response = await window.PastportApi.fetch('/api/v1/products', { cache: 'no-store', signal: controller.signal });
      const payload = await response.json().catch(() => null);
      if (!current()) return;
      if (!response.ok) {
        status.textContent = response.status === 401 ? '로그인 후 외부 이전을 보낼 제품을 선택해 주세요.' : '내 제품을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.';
        find('[data-external-login]').hidden = response.status !== 401;
        find('[data-external-retry]').hidden = response.status === 401;
        return;
      }
      if (!Array.isArray(payload?.data)) throw new Error('Invalid product list');
      products = payload.data.filter(product => /^[1-9]\d*$/.test(String(product.productId)));
      find('[data-external-count]').textContent = products.length + '개'; renderProducts();
    } catch {
      if (current()) { status.textContent = '내 제품을 불러오지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.'; find('[data-external-retry]').hidden = false; }
    } finally {
      if (current()) {
        request = null;
        find('[data-external-refresh]').disabled = false;
        find('[data-external-search]').disabled = false;
        find('[data-external-products]').setAttribute('aria-busy', 'false');
      }
    }
  }

  document.addEventListener('click', event => {
    if (event.target.closest?.('[data-external-refresh], [data-external-retry]')) loadProducts();
    if (event.target.closest?.('[data-external-login]')) { returnAfterLogin = true; show('auth'); }
  });
  document.addEventListener('input', event => { if (event.target.matches?.('[data-external-search]')) renderProducts(); });
  document.addEventListener('pastport:page-loaded', event => { if (event.detail.id === 'trade') loadProducts(); });
  document.addEventListener('pastport:page-shown', event => {
    if (event.detail.id === 'trade') loadProducts(); else clear();
    if (!['trade', 'auth', 'signup'].includes(event.detail.id)) returnAfterLogin = false;
  });
  document.addEventListener('pastport:auth-changed', () => { clear(); if (active()) loadProducts(); });
  document.addEventListener('pastport:logout', () => { returnAfterLogin = false; clear(); });
  window.addEventListener('pagehide', () => { returnAfterLogin = false; clear(); });
  window.PastportOwnershipSend = Object.freeze({ resumeAfterLogin() {
    if (!returnAfterLogin) return false;
    returnAfterLogin = false; show('trade'); return true;
  } });
  if (active() && find('[data-external-products]')) loadProducts();
})();
