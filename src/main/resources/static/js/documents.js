// 문서 보관함: 내 제품 목록과 제품별 업로드 문서를 실제 API로 보여 준다.
(() => {
  const state = { products: [], selected: null, token: 0 };
  let openSeq = 0;
  const root = () => document.querySelector('#documents');
  const find = selector => root()?.querySelector(selector);
  const create = (tag, text, className = '') => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text; return node;
  };
  const size = bytes => bytes >= 1048576 ? (bytes / 1048576).toFixed(1) + 'MB' : Math.max(1, Math.round(bytes / 1024)) + 'KB';
  const day = value => value ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(new Date(value)) : '날짜 없음';
  const kind = type => type === 'application/pdf' ? 'PDF' : type?.startsWith('image/') ? '이미지' : '파일';

  async function api(path, options = {}) {
    const response = await window.PastportApi.fetch(path, options);
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(response.status === 401 ? '로그인 후 문서를 확인해 주세요.' : payload?.message || '요청을 처리하지 못했어요.');
      error.status = response.status; throw error;
    }
    return payload?.data;
  }
  function status(text, failed = false) {
    const node = find('[data-docs-status]'); if (!node) return;
    node.textContent = text; node.hidden = !text; node.classList.toggle('is-error', failed);
  }
  function products() {
    const box = find('[data-docs-products]'); box.replaceChildren();
    state.products.forEach(product => {
      const button = create('button', product.title, product.productId === state.selected ? 'active' : '');
      button.type = 'button';
      button.addEventListener('click', () => { state.selected = product.productId; products(); loadDocuments(); });
      box.append(button);
    });
  }
  function row(item) {
    const line = create('div', null, 'doc-row');
    // 재마스킹으로 사본을 바꾼 문서는 횟수와 마지막 교체일을 보여 준다. 이력에 연결된 뒤 바뀐 증빙을 알아볼 수 있게 한다.
    const replaced = item.replacementCount > 0 ? ' · 사본 교체 ' + item.replacementCount + '회(' + day(item.lastReplacedAt) + ')' : '';
    const info = create('div'); info.append(create('b', '문서 #' + item.id), create('small', day(item.maskConfirmedAt) + ' · ' + size(item.byteSize) + ' · v' + item.version + replaced));
    const open = create('button', '보기', 'button small'); open.type = 'button';
    open.addEventListener('click', async () => {
      try { const file = await api('/api/v1/documents/' + item.id + '/file-url'); window.open(file.url, '_blank', 'noopener'); }
      catch (error) { status(error.message, true); }
    });
    line.append(create('span', kind(item.contentType), 'pdf'), info,
      create('span', item.redactionRequired ? '재마스킹 필요' : '마스킹 확인 완료', item.redactionRequired ? 'private' : 'open'), open);
    // 개인정보가 뒤늦게 발견된 문서는 같은 문서 번호로 사본을 교체한다. 마스킹 화면이 교체 모드로 열린다.
    const remask = create('button', '다시 마스킹', 'button small'); remask.type = 'button';
    remask.dataset.page = 'docmask'; remask.dataset.docmaskProductId = item.productId; remask.dataset.docmaskReplaceId = item.id;
    line.append(remask);
    // 확정하면 이력에 연결돼 문서가 보호되므로 삭제 버튼만 거둔다. 목록을 다시 그리면 확정 안내가 사라진다.
    let removeButton = null;
    const panel = window.PastportDocExtraction?.panel(item, { onChanged: () => removeButton?.remove() });
    if (panel) {
      const extract = create('button', 'AI 추출', 'button small'); extract.type = 'button'; extract.setAttribute('aria-expanded', 'false');
      extract.addEventListener('click', () => extract.setAttribute('aria-expanded', String(panel.toggle())));
      line.append(extract);
    }
    if (item.deletable) {
      const remove = create('button', '삭제', 'button small'); remove.type = 'button';
      remove.addEventListener('click', async () => {
        if (!window.confirm('이 문서를 삭제할까요? 복구할 수 없어요.')) return;
        try { await api('/api/v1/documents/' + item.id, { method: 'DELETE' }); await loadDocuments(); }
        catch (error) { status(error.message, true); }
      });
      line.append(remove); removeButton = remove;
    }
    const holder = create('div', null, 'doc-item'); holder.append(line);
    if (panel) holder.append(panel.element);
    return holder;
  }
  async function loadDocuments() {
    const list = find('[data-docs-list]'); const token = ++state.token;
    const product = state.products.find(item => item.productId === state.selected);
    find('[data-docs-title]').textContent = product ? product.title + ' 문서' : '문서 보관함';
    list.replaceChildren();
    if (!product) { status('등록된 제품이 없어요. 제품을 먼저 등록해 주세요.'); return; }
    status('문서를 불러오는 중이에요…');
    try {
      const documents = await api('/api/v1/products/' + product.productId + '/documents');
      if (token !== state.token) return;
      status(documents.length ? '' : '아직 업로드한 문서가 없어요. 문서 업로드에서 마스킹을 확인한 뒤 올려 주세요.');
      list.replaceChildren(...documents.map(row));
    } catch (error) { if (token === state.token) status(error.message, true); }
  }
  /** 제품 선택기에 누락이 없도록 페이지 메타데이터의 hasNext가 끝날 때까지 합친다. */
  async function loadAllProducts() {
    const result = [];
    let page = 0;
    let hasNext;
    do {
      const response = await api('/api/v1/products?page=' + page + '&size=100');
      result.push(...(response?.items || []));
      hasNext = response?.page?.hasNext === true;
      page += 1;
    } while (hasNext);
    return result;
  }
  /** 문서 화면 진입 때 모든 소유 제품을 읽고 기존 선택을 유지한 채 문서 목록을 연다. */
  async function open() {
    if (!root() || !find('[data-docs-products]')) return;
    const seq = ++openSeq;
    status('문서를 불러오는 중이에요…');
    try {
      const loadedProducts = await loadAllProducts();
      if (seq !== openSeq) return;
      state.products = loadedProducts;
      if (!state.products.some(item => item.productId === state.selected)) state.selected = state.products[0]?.productId ?? null;
      products(); await loadDocuments();
    } catch (error) { if (seq === openSeq) status(error.message, true); }
  }
  document.addEventListener('pastport:page-loaded', event => { if (event.detail.id === 'documents' && root().classList.contains('active')) open(); });
  document.addEventListener('pastport:page-shown', event => { if (event.detail.id === 'documents') open(); });
  // 새로고침으로 이 화면이 복원될 때는 화면 HTML과 복원 이벤트가 이 스크립트보다 먼저 끝날 수 있다. 이미 열려 있으면 바로 시작한다.
  if (root()?.classList.contains('active')) open();
})();
