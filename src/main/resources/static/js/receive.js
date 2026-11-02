(() => {
  const TTL = 15 * 60 * 1000;
  let currentToken = new URLSearchParams(window.location.search).get('token');
  if (currentToken) window.history.replaceState(null, '', window.location.pathname);
  let expiresAt = null;
  let info = null;
  let timer = null;
  let cleanupTimer = null;
  let busy = false;
  let version = 0;
  const root = () => document.querySelector('#receive .rc');
  const find = selector => root()?.querySelector(selector);
  const navigate = page => { if (typeof show === 'function') show(page); };
  const clearToken = () => {
    currentToken = null; expiresAt = null; info = null;
    clearInterval(timer); timer = null; clearTimeout(cleanupTimer); cleanupTimer = null;
    const login = find('[data-receive-login]'); if (login) login.hidden = true;
    const accept = find('[data-receive-accept]'); if (accept) accept.disabled = true;
  };
  const save = () => {
    if (!currentToken) return;
    expiresAt = Math.min(expiresAt || Date.now() + TTL, info?.expiresAt ? Date.parse(info.expiresAt) : Infinity);
    clearTimeout(cleanupTimer);
    cleanupTimer = setTimeout(() => {
      status('이전 링크 또는 로그인 이어가기 시간이 만료되었습니다. 링크를 다시 열어 주세요.'); clearToken();
    }, Math.max(0, expiresAt - Date.now()));
  };
  if (currentToken) save();
  const status = (text, allowRetry = false) => {
    const target = find('[data-receive-message]'); if (target) target.textContent = text;
    const retry = find('[data-receive-retry]'); if (retry) retry.hidden = !allowRetry;
    const accept = find('[data-receive-accept]'); if (accept) accept.disabled = true;
  };
  const authState = () => {
    const signedIn = Boolean(auth.user);
    const login = find('[data-receive-login]'); if (login) login.hidden = signedIn || !currentToken;
    if (info?.canAccept && currentToken) status(signedIn ? '제품을 실제로 받았는지 확인한 뒤 수락해 주세요.' : '로그인하면 같은 이전으로 돌아옵니다. 로그인만으로 수락되지는 않습니다.');
    const accept = find('[data-receive-accept]');
    if (accept) accept.disabled = busy || !signedIn || !info?.canAccept || !currentToken;
  };
  const render = () => {
    const title = find('[data-receive-title]'); if (title) title.textContent = info.productTitle || '소유권 이전 요청';
    const seller = find('[data-receive-seller]'); if (seller) seller.textContent = info.fromUserNickname ? `보내는 사람: ${info.fromUserNickname}` : '';
    const expiration = find('[data-receive-expiration]'); if (expiration) expiration.textContent = `링크 만료: ${new Date(info.expiresAt).toLocaleString('ko-KR')}`;
    if (info.isExpired || info.status === 'EXPIRED') { status('만료된 링크입니다. 판매자에게 새 링크를 요청하세요.'); clearToken(); return; }
    if (info.status === 'CANCELLED') { status('취소된 이전 요청입니다. 판매자에게 새 링크를 요청하세요.'); clearToken(); return; }
    if (info.status === 'COMPLETED') { status('이미 완료된 이전 요청입니다. 내 제품에서 현재 소유 제품을 확인하세요.'); clearToken(); return; }
    if (!info.canAccept) { status('현재 수락할 수 없습니다. 판매 중·소유자 변경 여부를 판매자에게 확인하세요.'); clearToken(); return; }
    save(); authState();
    const update = () => {
      if (!currentToken) return;
      const remaining = Math.min(Date.parse(info.expiresAt), expiresAt) - Date.now();
      if (remaining <= 0) { status('이전 링크 또는 로그인 이어가기 시간이 만료되었습니다. 링크를 다시 열어 주세요.'); clearToken(); return; }
      const time = find('[data-receive-time]');
      if (time) time.textContent = `${Math.floor(remaining / 3600000)}시간 ${Math.floor(remaining / 60000) % 60}분 ${Math.floor(remaining / 1000) % 60}초`;
    };
    clearInterval(timer); update(); if (currentToken) timer = setInterval(update, 1000);
  };
  const verify = async () => {
    if (!root() || busy) return;
    if (!currentToken || expiresAt <= Date.now()) { status('이전 링크가 없거나 이어가기 시간이 만료되었습니다. 판매자에게 받은 링크를 다시 열어 주세요.'); clearToken(); return; }
    const requestVersion = ++version;
    status('이전 요청을 확인하고 있습니다.');
    try {
      const response = await window.PastportApi.fetch(`/api/v1/ownership/transfers/verify?token=${encodeURIComponent(currentToken)}`, { cache: 'no-store', referrerPolicy: 'no-referrer' });
      const payload = await response.json().catch(() => null);
      if (requestVersion !== version) return;
      if (!response.ok) {
        status(response.status >= 500 ? '일시적인 서버 오류입니다. 다시 시도해 주세요.' : '유효하지 않거나 종료된 이전 링크입니다.', response.status >= 500);
        if (response.status < 500) clearToken();
        return;
      }
      info = payload.data; render();
    } catch { if (requestVersion === version) status('네트워크 연결을 확인하고 다시 시도해 주세요.', true); }
  };
  const accept = async () => {
    if (busy || !currentToken || !info?.canAccept || !auth.user) return;
    busy = true; const requestVersion = version; const token = currentToken;
    status('소유권 이전을 처리하고 있습니다.');
    try {
      const response = await window.PastportApi.fetch('/api/v1/ownership/transfers/accept', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, cache: 'no-store',
        body: JSON.stringify({ token }), referrerPolicy: 'no-referrer'
      });
      const payload = await response.json().catch(() => null);
      if (requestVersion !== version) return;
      if (!response.ok) {
        const messages = { OWN_004: '본인 소유 제품은 수락할 수 없습니다.', OWN_005: '판매 중이거나 내부 예약이 있습니다. 기존 거래 경로를 이용해 주세요.', COMMON_002: '지정된 수신자가 아닙니다. 올바른 계정으로 로그인해 주세요.' };
        status(messages[payload?.code] || (response.status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.' : response.status === 403 ? '수신자 또는 보안 확인에 실패했습니다. 계정을 확인한 뒤 다시 시도해 주세요.' : '이전 요청을 수락하지 못했습니다. 최신 상태를 다시 확인해 주세요.'), true);
        if (response.status === 401) { const login = find('[data-receive-login]'); if (login) login.hidden = false; }
        if (['OWN_001', 'OWN_002', 'OWN_005'].includes(payload?.code)) clearToken();
        return;
      }
      clearToken();
      status('소유권 이전이 완료되었습니다. 내 제품에서 제품과 기록을 확인하세요.');
      const home = find('[data-receive-home]'); if (home) home.hidden = false;
    } catch { if (requestVersion === version) status('응답을 받지 못했습니다. 중복 수락 전에 최신 상태를 다시 확인해 주세요.', true); }
    finally { busy = false; }
  };
  const init = () => {
    find('[data-receive-accept]')?.addEventListener('click', accept);
    find('[data-receive-retry]')?.addEventListener('click', verify);
    find('[data-receive-login]')?.addEventListener('click', () => { save(); navigate('auth'); });
    verify();
  };
  window.PastportReceive = { resumeAfterLogin: () => {
    if (!currentToken || expiresAt <= Date.now()) return false;
    navigate('receive'); return true;
  } };
  document.addEventListener('pastport:page-loaded', event => {
    if (event.detail.id !== 'receive') return;
    init(); if (currentToken) navigate('receive');
  });
  document.addEventListener('pastport:page-shown', event => { if (event.detail.id === 'receive') verify(); else { clearInterval(timer); timer = null; } });
  document.addEventListener('pastport:auth-changed', authState);
  document.addEventListener('pastport:logout', () => { version += 1; clearToken(); status('로그아웃되어 이전 정보가 지워졌습니다. 링크를 다시 열어 주세요.'); });
  window.addEventListener('pagehide', () => { version += 1; clearToken(); });
})();
