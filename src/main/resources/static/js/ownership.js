(() => {
  const API = '/api/v1/ownership/transfers';
  let productId = null;
  let transfer = null;
  let shareUrl = null;
  let version = 0;
  let busy = false;
  let timer = null;
  let accountId = auth.user?.id == null ? null : String(auth.user.id);
  const root = () => document.querySelector('[data-ownership-root]');
  const api = async (path, options = {}) => {
    const response = await window.PastportApi.fetch(path, { ...options, cache: 'no-store' });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(payload?.message || '요청을 처리하지 못했습니다. 다시 시도해 주세요.');
      error.code = payload?.code;
      error.status = response.status;
      throw error;
    }
    return payload.data;
  };
  const clearShare = () => { shareUrl = null; root()?.querySelector('[data-ownership-share]')?.replaceChildren(); };
  const reset = () => { version += 1; clearInterval(timer); timer = null; clearShare(); productId = null; transfer = null; busy = false; root()?.replaceChildren(); };
  const button = (label, action, disabled = false) => {
    const element = document.createElement('button');
    element.type = 'button'; element.className = 'button'; element.textContent = label;
    element.disabled = disabled || busy;
    element.addEventListener('click', action);
    return element;
  };
  const message = (text) => { const target = root()?.querySelector('[data-ownership-message]'); if (target) target.textContent = text; };
  const render = () => {
    const target = root();
    if (!target || !productId) return;
    target.replaceChildren();
    const heading = document.createElement('h2'); heading.textContent = '외부 소유권 이전';
    const note = document.createElement('p');
    note.textContent = '수신자가 로그인해 수락하면 수신자와 제품을 확인한 뒤 최종 승인해 주세요. 발급 후 24시간 안에 승인해야 하며, 승인 전까지 취소할 수 있습니다.';
    const status = document.createElement('p'); status.dataset.ownershipMessage = ''; status.setAttribute('role', 'status');
    const pending = transfer?.status === 'PENDING' && !transfer.expired;
    status.textContent = transfer
      ? `${transfer.expired ? '만료됨' : ({ PENDING: '수락 대기 중', COMPLETED: '이전 완료', CANCELLED: '취소됨', EXPIRED: '만료됨' }[transfer.status] || transfer.status)} · 만료 ${new Date(transfer.expiresAt).toLocaleString('ko-KR')}`
      : '현재 대기 중인 외부 이전이 없습니다.';
    const actions = document.createElement('div'); actions.className = 'ownership-actions';
    if (pending && transfer.recipientAcceptedAt) {
      const recipient = document.createElement('p');
      recipient.textContent = `${transfer.productTitle} · 수신자 ${transfer.recipientNickname || '회원'} (#${transfer.recipientId}) · 제품 버전 ${transfer.factsVersion} · 수락 ${new Date(transfer.recipientAcceptedAt).toLocaleString('ko-KR')} · 최종 승인 대기`;
      target.append(recipient);
      actions.append(button('확인한 수신자에게 최종 이전 승인', () => approve()));
    }
    actions.append(button('상태 새로고침', () => load()));
    if (pending) actions.append(button('이전 요청 취소', () => cancel()), button('취소 후 새 링크 발급', () => reissue()));
    else actions.append(button('새 링크 · QR 발급', () => issue()));
    const share = document.createElement('div'); share.dataset.ownershipShare = '';
    target.append(heading, note, status, actions, share);
    if (pending && !shareUrl) {
      const lost = document.createElement('p'); lost.textContent = '원본 링크는 다시 조회할 수 없습니다. 링크를 잃었다면 기존 요청을 취소한 뒤 새로 발급하세요.'; share.append(lost);
    }
    if (shareUrl) renderShare(share);
  };
  const renderShare = (target) => {
    const link = document.createElement('input'); link.type = 'text'; link.readOnly = true; link.className = 'ownership-link';
    link.setAttribute('aria-label', '일회용 소유권 이전 공유 링크'); link.value = shareUrl;
    const copy = button('링크 복사', async () => {
      try { await navigator.clipboard.writeText(shareUrl); message('복사했습니다. 신뢰하는 수신자에게만 공유하세요.'); }
      catch { link.select(); message('주소를 선택했습니다. 직접 복사해 주세요.'); }
    });
    target.append(link, copy);
    try {
      const code = qrcode(0, 'M'); code.addData(shareUrl); code.make();
      const image = document.createElement('img'); image.className = 'ownership-qr'; image.alt = '공유 링크와 동일한 일회용 이전 QR';
      image.src = code.createDataURL(5, 20); target.append(image);
    } catch { message('QR 생성에 실패했습니다. 표시된 링크는 사용할 수 있습니다.'); }
  };
  const load = async () => {
    if (!productId || busy) return;
    const requestVersion = ++version;
    const selected = productId;
    try {
      const result = await api(`${API}/product/${selected}`);
      if (requestVersion !== version || selected !== productId) return;
      if (transfer?.transferId !== result.transferId || result.status !== 'PENDING' || result.expired) clearShare();
      transfer = result; render();
    } catch (error) {
      if (requestVersion !== version || selected !== productId) return;
      if ([401, 403, 404].includes(error.status)) {
        transfer = null; clearShare(); render();
        if (error.status !== 404) message(error.message);
      }
      else { render(); message(error.message); }
    }
  };
  const mutate = async (operation) => {
    if (busy || !productId) return;
    busy = true;
    const selected = productId;
    const requestVersion = ++version;
    render();
    try { await operation(selected, requestVersion); }
    catch (error) {
      if (requestVersion === version && selected === productId) {
        if (error.code === 'OWN_005') message('판매 중이거나 내부 예약이 있습니다. 내 거래에서 기존 거래를 진행해 주세요.');
        else message(error.message);
      }
    } finally { if (requestVersion === version && selected === productId) { busy = false; root()?.querySelectorAll('button').forEach(element => { element.disabled = false; }); } }
  };
  const issueRequest = async (selected, requestVersion) => {
    const result = await api(API, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ productId: selected }) });
    if (requestVersion !== version || selected !== productId) return;
    const url = new URL(result.linkUrl, window.location.origin);
    if (url.origin !== window.location.origin || url.pathname !== '/receive' || url.searchParams.get('token') !== result.rawToken || url.username || url.password || url.hash) throw new Error('공유 링크가 올바르지 않습니다. 상태를 새로고침하고 요청을 취소해 주세요.');
    shareUrl = url.href;
    transfer = { transferId: result.transferId, productId: result.productId, expiresAt: result.expiresAt, status: 'PENDING', expired: false };
    render();
  };
  const issue = () => mutate(issueRequest);
  const approve = () => {
    if (!transfer?.recipientAcceptedAt || !confirm(`${transfer.productTitle}\n${transfer.recipientNickname || '회원'} (#${transfer.recipientId})에게 소유권과 증빙을 이전할까요?`)) return;
    const confirmed = { ...transfer };
    mutate(async (selected, requestVersion) => {
      try {
        await api(`${API}/${confirmed.transferId}/approve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expectedRecipientId: confirmed.recipientId, expectedFactsVersion: confirmed.factsVersion }) });
      } catch (error) {
        const result = await api(`${API}/${confirmed.transferId}`);
        if (result.status !== 'COMPLETED') throw error;
      }
      if (requestVersion !== version || selected !== productId) return;
      clearShare(); transfer = { ...confirmed, status: 'COMPLETED' }; render();
      message('이전이 완료되었습니다. 내 제품 목록을 새로고침해 주세요.');
    });
  };
  const cancelRequest = async (selected, requestVersion) => {
    const transferId = transfer.transferId;
    await api(`${API}/${transferId}/cancel`, { method: 'POST' });
    if (requestVersion !== version || selected !== productId) return;
    clearShare(); transfer = { ...transfer, status: 'CANCELLED' }; render();
  };
  const cancel = () => { if (confirm('이 외부 이전 요청을 취소할까요? 기존 링크는 사용할 수 없습니다.')) mutate(cancelRequest); };
  const reissue = () => {
    if (!confirm('기존 요청을 취소하고 새 링크를 발급할까요?')) return;
    mutate(async (selected, requestVersion) => { await cancelRequest(selected, requestVersion); if (requestVersion === version && selected === productId) await issueRequest(selected, requestVersion); });
  };
  document.addEventListener('pastport:product-detail-rendered', (event) => {
    reset(); productId = event.detail.product.productId; render(); load();
    timer = setInterval(() => { if (transfer?.status === 'PENDING') load(); }, 5000);
  });
  document.addEventListener('pastport:page-shown', (event) => { if (event.detail.id !== 'passport') reset(); });
  document.addEventListener('pastport:logout', reset);
  document.addEventListener('pastport:auth-changed', () => {
    const nextAccount = auth.user?.id == null ? null : String(auth.user.id);
    if (nextAccount !== accountId) reset();
    accountId = nextAccount;
  });
  window.addEventListener('pagehide', reset);
})();
