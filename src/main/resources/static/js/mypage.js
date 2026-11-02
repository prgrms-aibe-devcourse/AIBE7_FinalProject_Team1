// 마이페이지 요약: 채팅·판매글·제품의 최근 항목을 표로 보여 주고 상단 숫자를 채운다.
// 로그인 여부와 프로필 표시는 auth.js가 맡고, 이 파일은 목록 조회만 한다.
(() => {
  const root = document.querySelector('#mypage');
  if (!root) return;
  const LIMIT = 3;
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
    td.textContent = text;
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
  const rows = (body, items, build) => {
    body.replaceChildren(...items.map(item => {
      const tr = document.createElement('tr');
      const { page, cells } = build(item);
      tr.dataset.page = page; tr.append(...cells);
      return tr;
    }));
  };
  const stat = (key, value) => { const el = root.querySelector(`[data-mp-stat="${key}"]`); if (el) el.textContent = value; };
  const list = (key) => root.querySelector(`[data-mp-list="${key}"]`);
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
    ['chats', 'listings', 'products'].forEach(key => message(list(key), '불러오는 중이에요…'));
    ['listings', 'chats'].forEach(key => stat(key, '-'));
    await Promise.all([
      section(signal, async () => {
        const data = await get('/api/v1/chat-rooms?page=0&size=' + LIMIT, signal);
        if (stale()) return;
        stat('chats', String(data.page?.totalElements ?? data.items.length));
        if (!data.items.length) return message(list('chats'), '아직 나눈 채팅이 없어요.', { page: 'market', label: '중고 상품 둘러보기' });
        rows(list('chats'), data.items, room => ({ page: 'deals', cells: [cell(room.listingTitle || '판매글', true), cell(room.counterpartyNickname || '상대방'), cell(room.latestMessagePreview || '대화를 시작해 보세요')] }));
      }, list('chats'), '채팅 목록을 불러오지 못했어요.'),
      section(signal, async () => {
        const data = await get('/api/v1/my-listings?page=0&size=' + LIMIT, signal);
        if (stale()) return;
        stat('listings', String(data.page?.totalElements ?? data.items.length));
        if (!data.items.length) return message(list('listings'), '아직 게시한 판매글이 없어요.', { page: 'sell', label: '판매글 작성하기' });
        rows(list('listings'), data.items, item => ({ page: 'my-listings', cells: [cell(item.title || '판매글', true), cell(statusLabel[item.status] || '종료'), cell(won(item.price))] }));
      }, list('listings'), '판매글 목록을 불러오지 못했어요.'),
      section(signal, async () => {
        const data = await get('/api/v1/products', signal);
        if (stale()) return;
        if (!Array.isArray(data) || !data.length) return message(list('products'), '아직 등록한 제품이 없어요.', { page: 'register', label: '새 여권 등록하기' });
        rows(list('products'), data.slice(0, LIMIT), item => ({ page: 'home', cells: [cell(item.title || item.modelName || '제품', true), cell(item.manufacturer || '-'), cell(item.purchaseDate || '-')] }));
      }, list('products'), '제품 목록을 불러오지 못했어요.')
    ]);
  }

  document.addEventListener('pastport:page-shown', event => { if (event.detail?.id === 'mypage') load(); });
  document.addEventListener('pastport:auth-changed', event => {
    if (!root.classList.contains('active')) return;
    if (event.detail?.signedIn) load(); else generation++;
  });
})();
