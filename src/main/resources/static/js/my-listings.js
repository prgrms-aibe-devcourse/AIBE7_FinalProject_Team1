// 공개 마켓에 나오지 않는 예약·종료 글도 본인 전용 API에서 읽고, 기존 판매글 관리 폼으로 연결한다.
(() => {
  const labels = { ACTIVE:'판매 중', RESERVED:'예약 중', SOLD:'판매 완료', CANCELLED:'판매 중지' };
  let initialized = false, active = false, request = null, page = 0, status = '', metadata = null;
  const find = selector => document.querySelector('#my-listings ' + selector);
  const create = (tag, text, className = '') => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };

  function locationState() {
    const params = new URL(window.location.href).searchParams;
    status = Object.hasOwn(labels, params.get('myStatus')) ? params.get('myStatus') : '';
    const number = params.get('myPage') || '0';
    page = /^\d{1,7}$/.test(number) ? Number(number) : 0;
  }
  function writeUrl() {
    const url = new URL(window.location.href);
    ['listingId', 'listingManage', 'draftProduct', 'market'].forEach(key => url.searchParams.delete(key));
    url.searchParams.set('myListings', '1');
    status ? url.searchParams.set('myStatus', status) : url.searchParams.delete('myStatus');
    page ? url.searchParams.set('myPage', String(page)) : url.searchParams.delete('myPage');
    window.history.replaceState(window.history.state, '', url);
  }
  function row(item) {
    const article = create('article', null, 'ml-row');
    const media = create('div', null, 'ml-photo');
    if (item.thumbnailUrl) {
      const image = create('img'); image.src = item.thumbnailUrl; image.alt = ''; image.loading = 'lazy';
      image.addEventListener('error', () => media.replaceChildren(create('span', '사진 없음')));
      media.append(image);
    } else media.append(create('span', '사진 없음'));
    const body = create('div', null, 'ml-info');
    const heading = create('h2', item.title);
    const state = create('span', labels[item.status] || item.status, 'ml-state'); state.dataset.status = item.status;
    const date = new Date(item.publishedAt);
    const stamp = Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('ko-KR').format(date) + ' 게시';
    const meta = create('div', null, 'ml-meta'); meta.append(state, create('span', stamp));
    body.append(heading, meta);
    const price = create('strong', new Intl.NumberFormat('ko-KR').format(item.price) + '원', 'ml-price');
    const action = create('div', null, 'ml-action');
    if (item.canManage) {
      const manage = create('button', '관리하기', 'sc-btn'); manage.type = 'button';
      manage.setAttribute('aria-label', item.title + ' 관리하기');
      manage.addEventListener('click', () => window.PastportPublishedListing.open(item.listingId, { manage:true }));
      action.append(manage);
    } else action.append(create('span', ['SOLD','CANCELLED'].includes(item.status) ? '종료된 판매글' : '관리할 수 없는 글', 'ml-readonly'));
    article.append(media, body, price, action);
    return article;
  }
  async function load() {
    if (!active || !find('[data-my-items]')) return;
    request?.abort(); const controller = new AbortController(); request = controller;
    loadDraftCount(controller);
    metadata = null;
    find('[data-my-items]').replaceChildren();
    find('[data-my-pagination]').hidden = true;
    find('[data-my-login]').hidden = true;
    find('[data-my-message]').textContent = '내 판매글을 불러오는 중이에요…';
    find('[data-my-items]').setAttribute('aria-busy', 'true');
    document.querySelectorAll('#my-listings [data-my-status]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.myStatus === status)));
    try {
      const params = new URLSearchParams({page:String(page), size:'20'});
      if (status) params.set('status', status);
      const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
      const response = await fetcher('/api/v1/my-listings?' + params, { credentials:'same-origin', cache:'no-store', signal:controller.signal });
      const payload = await response.json().catch(() => null);
      if (controller.signal.aborted || !active) return;
      if (!response.ok) {
        find('[data-my-login]').hidden = response.status !== 401;
        throw new Error(response.status === 401 ? '로그인하면 내가 게시한 판매글을 볼 수 있어요.' : payload?.message || '내 판매글을 불러오지 못했어요. 새로고침을 눌러 주세요.');
      }
      if (!Array.isArray(payload?.data?.items) || !payload.data.page) throw new Error('목록 응답을 확인할 수 없어요. 다시 시도해 주세요.');
      metadata = payload.data.page;
      // 마지막 페이지의 글 상태가 바뀌어 빈 페이지가 되면 첫 페이지에서 다시 조회한다.
      if (!payload.data.items.length && page > 0) { page = 0; writeUrl(); return load(); }
      find('[data-my-items]').replaceChildren(...payload.data.items.map(row));
      find('[data-my-message]').textContent = metadata.totalElements
        ? (status ? labels[status] + ' ' : '전체 ') + metadata.totalElements.toLocaleString('ko-KR') + '개 · 최근 게시한 순'
        : status ? labels[status] + '인 판매글이 없어요. 다른 상태도 확인해 보세요.' : '아직 게시한 판매글이 없어요. 판매글 작성에서 첫 글을 올려 보세요.';
      find('[data-my-pagination]').hidden = metadata.totalPages <= 1;
      find('[data-my-prev]').disabled = page === 0;
      find('[data-my-next]').disabled = !metadata.hasNext;
      find('[data-my-range]').textContent = (page * metadata.size + 1) + '–' + (page * metadata.size + payload.data.items.length) + ' / ' + metadata.totalElements + '개';
    } catch (error) {
      if (!controller.signal.aborted && active) find('[data-my-message]').textContent = error instanceof TypeError ? '서버에 연결하지 못했어요. 연결 상태를 확인하고 새로고침을 눌러 주세요.' : error.message;
    } finally { if (!controller.signal.aborted) find('[data-my-items]').setAttribute('aria-busy', 'false'); }
  }
  // 임시저장 진입은 기존 작성 화면의 목록을 재사용한다. 개수 조회 실패가 판매글 목록을 막지는 않는다.
  async function loadDraftCount(controller) {
    const count = find('[data-my-draft-count]');
    if (!count) return;
    count.textContent = '';
    try {
      const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
      const response = await fetcher('/api/v1/listing-drafts?page=0&size=1', {credentials:'same-origin', cache:'no-store', signal:controller.signal});
      const payload = response.ok ? await response.json() : null;
      if (!controller.signal.aborted && active && Number.isSafeInteger(payload?.data?.page?.totalElements)) count.textContent = payload.data.page.totalElements.toLocaleString('ko-KR');
    } catch { /* 개수는 다음 목록 새로고침 때 다시 읽는다. */ }
  }
  function init() {
    if (initialized || !find('[data-my-items]')) return;
    initialized = true;
    document.querySelectorAll('#my-listings [data-my-status]').forEach(button => button.addEventListener('click', () => {
      status = button.dataset.myStatus; page = 0; writeUrl(); load();
    }));
    find('[data-my-retry]').addEventListener('click', load);
    find('[data-my-prev]').addEventListener('click', () => { if (page > 0) { page--; writeUrl(); load(); } });
    find('[data-my-next]').addEventListener('click', () => { if (metadata?.hasNext) { page++; writeUrl(); load(); } });
    if (new URL(window.location.href).searchParams.get('myListings') === '1') { locationState(); show('my-listings'); }
    else if (active) load();
  }
  document.addEventListener('pastport:page-loaded', event => { if (event.detail.id === 'my-listings') init(); });
  document.addEventListener('pastport:page-shown', event => {
    const wasActive = active;
    active = event.detail.id === 'my-listings';
    if (active) { writeUrl(); load(); }
    else {
      request?.abort(); metadata = null; find('[data-my-items]')?.replaceChildren();
      if (find('[data-my-draft-count]')) find('[data-my-draft-count]').textContent = '';
      if (wasActive && event.detail.id === 'auth') return;
      const url = new URL(window.location.href); ['myListings','myStatus','myPage'].forEach(key => url.searchParams.delete(key));
      window.history.replaceState(window.history.state, '', url);
    }
  });
  // 계정 전환·로그아웃 시 숨겨진 화면에도 이전 계정의 비공개 목록을 남기지 않는다.
  document.addEventListener('pastport:auth-changed', () => { request?.abort(); find('[data-my-items]')?.replaceChildren(); if (find('[data-my-draft-count]')) find('[data-my-draft-count]').textContent = ''; metadata = null; if (active) load(); });
  window.addEventListener('pageshow', event => { if (event.persisted && active) load(); });
})();
