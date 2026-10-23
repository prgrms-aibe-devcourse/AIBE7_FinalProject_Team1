(() => {
  let request = null;
  let data = null;
  let loading = false;
  let initialized = false;
  const currentId = () => new URL(window.location.href).searchParams.get('listingId');
  const find = selector => document.querySelector('#detail ' + selector);

  async function load(append = false) {
    if (!find('[data-listing-content]')) return;
    window.PastportListingManagement?.reset();
    find('[data-listing-login]').hidden = true;
    const id = currentId();
    if (!id || !/^[1-9]\d*$/.test(id)) {
      request?.abort(); data = null; loading = false;
      find('[data-listing-more]').hidden = true;
      find('[data-listing-status]').textContent = '게시된 판매글을 선택해 주세요.';
      find('[data-listing-content]').replaceChildren(); return;
    }
    request?.abort(); const controller = new AbortController(); request = controller;
    const page = append && data ? data.history.page.number + 1 : 0;
    loading = true; find('[data-listing-more]').disabled = true;
    find('[data-listing-status]').textContent = '판매글을 불러오는 중이에요…';
    if (!append) { data = null; find('[data-listing-content]').replaceChildren(); find('[data-listing-more]').hidden = true; }
    try {
      const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
      const response = await fetcher('/api/v1/listings/' + id + '?page=' + page + '&size=20', { credentials:'same-origin', cache:'no-store', signal:controller.signal });
      const payload = await response.json().catch(() => null);
      if (!response.ok) {
        const failure = new Error(response.status === 401 ? '로그인 후 판매글 상세를 확인해 주세요.' : response.status === 404 ? '이 판매글은 현재 조회할 수 없어요.' : payload?.message || '판매글을 불러오지 못했어요.');
        failure.status = response.status; throw failure;
      }
      if (!payload?.data) throw new Error('판매글 응답을 확인할 수 없어요. 다시 시도해 주세요.');
      if (controller.signal.aborted || currentId() !== id) return;
      const next = payload.data;
      if (append && data) next.history.items = [...new Map([...data.history.items, ...next.history.items].map(item => [item.eventId,item])).values()];
      data = next; window.PastportListingDetail.render(find('[data-listing-content]'), data);
      window.PastportListingManagement?.bind(data);
      find('[data-listing-status]').textContent = '게시된 판매글이에요. 판매 중·예약 중인 글은 로그인한 회원이 확인할 수 있어요.';
      find('[data-listing-more]').hidden = !data.history.page.hasNext;
    } catch (error) {
      if (error.name !== 'AbortError' && !controller.signal.aborted) {
        data = null; find('[data-listing-content]').replaceChildren(); find('[data-listing-more]').hidden = true;
        find('[data-listing-status]').textContent = error.message;
        find('[data-listing-login]').hidden = error.status !== 401;
      }
    } finally { if (!controller.signal.aborted) { loading = false; find('[data-listing-more]').disabled = false; } }
  }

  function open(id) {
    const url = new URL(window.location.href); url.searchParams.delete('draftProduct'); url.searchParams.delete('market'); url.searchParams.set('listingId', String(id));
    window.history.replaceState(window.history.state, '', url);
    if (typeof show === 'function') show('detail');
  }
  function init() {
    if (initialized || !find('[data-listing-content]')) return;
    initialized = true;
    find('[data-listing-retry]').addEventListener('click', () => load());
    find('[data-listing-login]').addEventListener('click', () => { if (typeof show === 'function') show('auth'); });
    find('[data-listing-more]').addEventListener('click', () => { if (!loading) load(true); });
    if (currentId()) open(currentId());
  }
  document.addEventListener('pastport:page-loaded', event => { if (event.detail.id === 'detail') init(); });
  document.addEventListener('pastport:page-shown', event => { if (event.detail.id === 'detail') load(); });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
  window.PastportPublishedListing = Object.freeze({ open });
})();
