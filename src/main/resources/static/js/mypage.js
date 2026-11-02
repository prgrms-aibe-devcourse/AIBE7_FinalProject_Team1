// 마이페이지 요약: 채팅·판매글·제품의 최근 항목을 표로 보여 주고 상단 숫자를 채운다.
// 로그인 여부와 프로필 표시는 auth.js가 맡고, 이 파일은 목록 조회만 한다.
(() => {
  const root = document.querySelector('#mypage');
  if (!root) return;
  const LIMIT = 3, DOC_PRODUCTS = 20;
  let generation = 0, current = null;
  const statusLabel = { ACTIVE: '판매 중', RESERVED: '예약 중', SOLD: '판매 완료', CANCELLED: '판매 중지' };

  const get = async (url, signal) => {
    const response = await (window.PastportApi?.fetch || fetch)(url, { credentials: 'same-origin', cache: 'no-store', signal });
    const payload = await response.json().catch(() => null);
    if (!response.ok) throw Object.assign(new Error('load failed'), { status: response.status });
    return payload?.data;
  };
  const cell = (text, left = false) => {
    const td = document.createElement('td');
    td.textContent = text; td.title = text;
    if (left) td.className = 'mp-left';
    return td;
  };
  /** 항목이 없거나 불러오지 못했을 때 안내와 이동 버튼을 한 줄로 보여 준다. */
  const message = (body, text, button) => {
    const td = document.createElement('td');
    td.colSpan = 3; td.className = 'mp-empty-row';
    const p = document.createElement('p'); p.textContent = text; td.append(p);
    if (button) {
      const action = document.createElement('button');
      action.type = 'button'; action.className = 'sc-btn primary'; action.dataset.page = button.page; action.textContent = button.label;
      td.append(action);
    }
    const tr = document.createElement('tr'); tr.append(td);
    body.replaceChildren(tr);
  };
  /** 행 어디를 눌러도 해당 화면으로 이동한다. 첫 칸의 '상세보기'는 눌러 볼 수 있다는 표시이고, 키보드는 첫 칸 버튼으로 이동한다. */
  const rows = (body, items, build) => {
    body.replaceChildren(...items.map(item => {
      const tr = document.createElement('tr');
      const { page, cells } = build(item);
      const first = cells[0];
      const link = document.createElement('button');
      link.type = 'button'; link.className = 'mp-link-text'; link.dataset.page = page;
      const label = document.createElement('span'); label.textContent = first.textContent; link.title = first.textContent;
      const hint = document.createElement('span');
      hint.className = 'mp-hint'; hint.textContent = '상세보기'; hint.setAttribute('aria-hidden', 'true');
      link.append(label, hint); first.replaceChildren(link);
      tr.dataset.page = page; tr.append(...cells);
      return tr;
    }));
  };
  const stat = (key, value) => { const el = root.querySelector(`[data-mp-stat="${key}"]`); if (el) el.textContent = value; };
  const list = (key) => root.querySelector(`[data-mp-list="${key}"]`);
  const fileKind = (type) => (type || '').startsWith('image/') ? '이미지' : type === 'application/pdf' ? 'PDF' : '문서';
  const won = (value) => Number.isFinite(value) ? value.toLocaleString('ko-KR') + '원' : '-';

  async function section(signal, run, body, failure) {
    try { await run(); } catch (error) {
      if (signal.aborted) return;
      message(body, error.status === 401 ? '로그인이 필요해요.' : failure);
    }
  }

  async function load() {
    const ticket = ++generation;
    current?.abort(); const controller = current = new AbortController();
    const { signal } = controller;
    const stale = () => ticket !== generation;
    ['documents', 'listings', 'products'].forEach(key => message(list(key), '불러오는 중이에요…'));
    ['listings', 'documents'].forEach(key => stat(key, '-'));
    await Promise.all([
      section(signal, async () => {
        const data = await get('/api/v1/my-listings?page=0&size=' + LIMIT, signal);
        if (stale()) return;
        stat('listings', String(data.page?.totalElements ?? data.items.length));
        if (!data.items.length) return message(list('listings'), '아직 올린 판매글이 없어요. 제품 여권과 함께 첫 판매글을 올려 보세요.', { page: 'sell', label: '판매글 작성하러 가기' });
        rows(list('listings'), data.items, item => ({ page: 'my-listings', cells: [cell(item.title || '판매글', true), cell(statusLabel[item.status] || '종료'), cell(won(item.price))] }));
      }, list('listings'), '판매글 목록을 불러오지 못했어요.'),
      section(signal, async () => {
        const data = await get('/api/v1/products', signal);
        if (stale()) return;
        const products = Array.isArray(data) ? data : [];
        if (!products.length) {
          message(list('products'), '아직 등록한 제품이 없어요. 내 노트북의 기록을 한 곳에서 관리해 보세요.', { page: 'register', label: '제품 등록하러 가기' });
          message(list('documents'), '제품을 등록하면 문서를 함께 보관할 수 있어요.', { page: 'register', label: '제품 등록하러 가기' });
          stat('documents', '0');
          return;
        }
        rows(list('products'), products.slice(0, LIMIT), item => ({ page: 'home', cells: [cell(item.title || item.modelName || '제품', true), cell(item.manufacturer || '-'), cell(item.purchaseDate || '-')] }));
        // 문서는 제품별로만 조회할 수 있어 앞쪽 제품부터 모아 센다.
        const scope = products.slice(0, DOC_PRODUCTS);
        const lists = await Promise.all(scope.map(product => get('/api/v1/products/' + product.productId + '/documents', signal).then(documents => (documents || []).map(doc => ({ doc, product })), () => null)));
        if (stale()) return;
        if (lists.every(item => item === null)) throw new Error('documents failed');
        const all = lists.flat().filter(Boolean).sort((a, b) => Date.parse(b.doc.lastReplacedAt || b.doc.maskConfirmedAt || 0) - Date.parse(a.doc.lastReplacedAt || a.doc.maskConfirmedAt || 0));
        stat('documents', all.length + (products.length > scope.length ? '+' : ''));
        if (!all.length) return message(list('documents'), '아직 보관한 문서가 없어요. 영수증·보증서를 올려 두면 거래할 때 신뢰를 더할 수 있어요.', { page: 'documents', label: '문서 등록하러 가기' });
        rows(list('documents'), all.slice(0, LIMIT), ({ doc, product }) => ({ page: 'documents', cells: [cell(product.title || product.modelName || '제품', true), cell(fileKind(doc.contentType)), cell(doc.maskConfirmedAt ? '가림 확인 완료' : doc.redactionRequired ? '가림 필요' : '보관 중')] }));
      }, list('products'), '제품 목록을 불러오지 못했어요.')
    ]);
  }

  document.addEventListener('pastport:page-shown', event => { if (event.detail?.id === 'mypage') load(); });
  document.addEventListener('pastport:auth-changed', event => {
    if (!root.classList.contains('active')) return;
    if (event.detail?.signedIn) load(); else generation++;
  });
})();
