(() => {
  'use strict';

  let currentRoom = null;
  let currentUserId = null;
  let pollingTimer = null;
  let rooms = [];

  const find = (selector) => document.querySelector('#deals ' + selector);
  const findAll = (selector) => document.querySelectorAll('#deals ' + selector);

  async function api(path, options = {}) {
    const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    const response = await fetcher(path, { credentials: 'same-origin', ...options, headers });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(payload?.message || payload?.fieldErrors?.[0]?.message || '요청 처리에 실패했습니다.');
      error.status = response.status;
      throw error;
    }
    return payload?.data;
  }

  async function initUser() {
    try {
      const user = await api('/api/auth/me', { method: 'GET' });
      currentUserId = user?.id || null;
    } catch {
      currentUserId = null;
    }
  }

  async function loadRooms() {
    const container = find('[data-deals-rooms]');
    if (!container) return;
    try {
      await initUser();
      if (!currentUserId) {
        container.innerHTML = '<div style="padding:40px 20px;text-align:center;color:var(--muted)"><p style="margin-bottom:8px">로그인이 필요한 서비스입니다.</p><button type="button" class="sc-btn primary sm" data-page="auth">로그인하기</button></div>';
        return;
      }
      const data = await api('/api/v1/chat-rooms?page=0&size=50', { method: 'GET' });
      rooms = data?.items || [];
      if (rooms.length === 0) {
        container.innerHTML = '<p class="sub" style="padding:40px 20px;text-align:center;color:var(--muted)">진행 중인 대화가 없습니다.<br><small style="margin-top:6px;display:inline-block">마켓에서 관심 있는 상품에 문의해 보세요!</small></p>';
        return;
      }
      renderRoomList();
    } catch (e) {
      container.innerHTML = '<p class="sub" style="padding:20px;text-align:center;color:var(--muted)">대화방 목록을 불러오지 못했습니다.</p>';
    }
  }

  function renderRoomList() {
    const container = find('[data-deals-rooms]');
    if (!container) return;
    container.replaceChildren(...rooms.map((room) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'dl-row' + (currentRoom?.roomId === room.roomId ? ' on' : '');
      const dateStr = room.latestMessageAt ? new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit' }).format(new Date(room.latestMessageAt)) : '';
      btn.innerHTML = `
        <span class="laptop"></span>
        <span>
          <b>${escapeHtml(room.counterpartyNickname || '상대방')} <time>${dateStr}</time></b>
          <span class="t">${escapeHtml(room.listingTitle || '판매글')}</span>
          <span class="m">${escapeHtml(room.latestMessagePreview || '대화를 시작해 보세요')}</span>
          <span class="tags"><span class="sc-badge ${room.listingStatus === 'RESERVED' ? 'warn' : 'ok'}">${room.listingStatus === 'RESERVED' ? '예약 중' : '판매 중'}</span></span>
        </span>
        ${room.hasUnreadMessages ? '<i class="dl-dot" aria-label="새 메시지"></i>' : ''}
      `;
      btn.addEventListener('click', () => selectRoom(room));
      return btn;
    }));
  }

  async function selectRoom(room) {
    currentRoom = room;
    renderRoomList();

    const chatSection = find('[data-deals-chat-section]');
    const infoSection = find('[data-deals-info]');
    if (chatSection) chatSection.style.display = 'flex';
    if (infoSection) infoSection.style.display = 'block';

    const titleEl = find('[data-deals-room-title]');
    if (titleEl) {
      titleEl.innerHTML = `<b>${escapeHtml(room.listingTitle || '판매글')}</b><span>${escapeHtml(room.counterpartyNickname)}님과의 대화</span>`;
    }
    const statusEl = find('[data-deals-listing-status]');
    if (statusEl) {
      statusEl.textContent = room.listingStatus === 'RESERVED' ? '예약 중' : '판매 중';
      statusEl.className = 'sc-badge ' + (room.listingStatus === 'RESERVED' ? 'warn' : 'ok');
    }

    find('[data-deals-info-title]').textContent = room.listingTitle || '–';

    await loadMessages(room.roomId);
    await checkPurchaseRequest(room);

    startPolling();
  }

  async function loadMessages(roomId) {
    try {
      const data = await api(`/api/v1/chat-rooms/${roomId}/messages?page=0&size=50`, { method: 'GET' });
      const messages = (data?.items || []).reverse();
      renderMessages(messages);

      if (messages.length > 0) {
        const lastMsg = messages[messages.length - 1];
        api(`/api/v1/chat-rooms/${roomId}/read-position`, {
          method: 'PUT',
          body: JSON.stringify({ lastReadMessageId: lastMsg.messageId })
        }).catch(() => {});
      }
    } catch (e) {
      console.error('메시지 로드 실패:', e);
    }
  }

  function renderMessages(messages) {
    const container = find('[data-deals-messages]');
    if (!container) return;
    container.replaceChildren();

    messages.forEach((msg) => {
      const isMe = currentUserId != null && msg.senderId === currentUserId;
      const box = document.createElement('div');
      box.className = 'msg ' + (isMe ? 'me' : 'you');
      const timeStr = msg.createdAt ? new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit' }).format(new Date(msg.createdAt)) : '';
      box.innerHTML = `<p>${escapeHtml(msg.content)}</p><time>${timeStr}</time>`;
      container.appendChild(box);
    });

    container.scrollTop = container.scrollHeight;
  }

  async function checkPurchaseRequest(room) {
    const buyBtn = find('[data-deals-request-buy]');
    const infoStatus = find('[data-deals-info-trade-status]');
    const flowSteps = findAll('[data-flow-step]');

    const setStep = (step) => {
      flowSteps.forEach(el => {
        el.className = el.dataset.flowStep === step ? 'now' : '';
      });
    };

    try {
      const requests = await api(`/api/v1/purchase-requests?roomId=${room.roomId}&page=0&size=10`, { method: 'GET' });
      const items = requests?.items || [];
      const pendingOrAccepted = items.find(r => r.requestStatus === 'PENDING' || r.requestStatus === 'ACCEPTED');

      const transferArea = find('[data-deals-transfer-area]');
      if (!pendingOrAccepted) {
        setStep('inquiry');
        if (infoStatus) infoStatus.textContent = '문의 진행 중';
        if (transferArea) transferArea.hidden = true;
        if (buyBtn) {
          buyBtn.hidden = false;
          buyBtn.onclick = () => requestBuy(room.listingId, room.roomId);
        }
      } else if (pendingOrAccepted.requestStatus === 'PENDING') {
        setStep('pending');
        if (infoStatus) infoStatus.textContent = '구매 요청 대기 중';
        if (transferArea) transferArea.hidden = true;
        if (buyBtn) buyBtn.hidden = true;
        renderPendingCard(pendingOrAccepted);
      } else if (pendingOrAccepted.requestStatus === 'ACCEPTED') {
        setStep('reserved');
        if (infoStatus) infoStatus.textContent = '예약 완료 (거래 준비)';
        if (buyBtn) buyBtn.hidden = true;
        if (transferArea) {
          transferArea.hidden = false;
          const transferBtn = find('[data-deals-btn-transfer]');
          if (transferBtn) {
            transferBtn.onclick = () => handleIssueTransfer(room, pendingOrAccepted);
          }
        }
      }
    } catch {
      setStep('inquiry');
    }
  }

  async function handleIssueTransfer(room, pendingOrAccepted) {
    if (!confirm('구매자에게 전달할 일회성 소유권 이전 링크 및 QR을 생성하시겠습니까?')) return;
    try {
      // listing 상세에서 productId 조회
      const listingRes = await api(`/api/v1/listings/${room.listingId}`);
      const productId = listingRes.productId || listingRes.id;
      const transferRes = await api('/api/v1/ownership/transfers', {
        method: 'POST',
        body: JSON.stringify({
          productId: productId,
          purchaseRequestId: pendingOrAccepted.requestId
        })
      });

      const fullUrl = window.location.origin + transferRes.linkUrl;
      const copyPrompt = prompt(
        '일회성 소유권 이전 링크가 생성되었습니다 (24시간 유효)!\n구매자에게 이 링크를 전달해 수락하도록 안내하세요:\n\n' + fullUrl + '\n\n복사하시겠습니까? (확인 누르면 클립보드 복사 시도)',
        fullUrl
      );
      if (copyPrompt !== null && navigator.clipboard) {
        await navigator.clipboard.writeText(fullUrl);
        alert('소유권 이전 링크가 클립보드에 복사되었습니다.');
      }
    } catch (e) {
      alert('소유권 이전 링크 발급 실패: ' + e.message);
    }
  }

  function renderPendingCard(req) {
    const container = find('[data-deals-messages]');
    if (!container) return;
    const card = document.createElement('div');
    card.className = 'dl-act';
    card.innerHTML = `
      <i>⇄</i>
      <div><b>구매 예약 요청이 접수되었습니다</b><span>수락하면 판매글이 예약 상태로 전환되고 다른 구매자의 예약이 차단됩니다.</span></div>
      <div class="btns">
        <button type="button" class="sc-btn sm" data-trade-reject>거절</button>
        <button type="button" class="sc-btn primary sm" data-trade-accept>수락하기</button>
      </div>
    `;

    card.querySelector('[data-trade-accept]')?.addEventListener('click', async () => {
      if (!confirm('구매 예약을 수락하시겠습니까? 판매글이 예약 상태가 됩니다.')) return;
      try {
        await api(`/api/v1/purchase-requests/${req.requestId}/accept`, { method: 'POST' });
        alert('예약이 수락되었습니다!');
        if (currentRoom) selectRoom(currentRoom);
      } catch (e) {
        alert(e.message);
      }
    });

    card.querySelector('[data-trade-reject]')?.addEventListener('click', async () => {
      if (!confirm('구매 예약을 거절하시겠습니까?')) return;
      try {
        await api(`/api/v1/purchase-requests/${req.requestId}/reject`, { method: 'POST' });
        alert('예약이 거절되었습니다.');
        if (currentRoom) selectRoom(currentRoom);
      } catch (e) {
        alert(e.message);
      }
    });

    container.appendChild(card);
    container.scrollTop = container.scrollHeight;
  }

  async function requestBuy(listingId, roomId) {
    if (!confirm('이 판매글에 대해 구매 예약을 요청하시겠습니까?')) return;
    try {
      await api(`/api/v1/listings/${listingId}/purchase-requests`, {
        method: 'POST',
        body: JSON.stringify({ roomId })
      });
      alert('구매 예약 요청을 보냈습니다! 판매자의 수락을 기다려 주세요.');
      if (currentRoom) selectRoom(currentRoom);
    } catch (e) {
      alert(e.message);
    }
  }

  async function sendMessage() {
    const input = find('[data-deals-input]');
    if (!input || !currentRoom) return;
    const content = input.value.trim();
    if (!content) return;
    input.value = '';

    try {
      await api(`/api/v1/chat-rooms/${currentRoom.roomId}/messages`, {
        method: 'POST',
        body: JSON.stringify({ content })
      });
      await loadMessages(currentRoom.roomId);
    } catch (e) {
      alert('메시지 전송에 실패했습니다: ' + e.message);
    }
  }

  function startPolling() {
    stopPolling();
    pollingTimer = setInterval(() => {
      if (currentRoom && document.querySelector('#deals.active')) {
        loadMessages(currentRoom.roomId);
      }
    }, 3000);
  }

  function stopPolling() {
    if (pollingTimer) {
      clearInterval(pollingTimer);
      pollingTimer = null;
    }
  }

  function escapeHtml(str) {
    return String(str || '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
  }

  function init() {
    const sendBtn = find('[data-deals-send]');
    const input = find('[data-deals-input]');
    sendBtn?.addEventListener('click', sendMessage);
    input?.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
      }
    });

    findAll('[data-deals-tab]').forEach((tab) => {
      tab.addEventListener('click', () => {
        findAll('[data-deals-tab]').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
      });
    });
  }

  async function openRoomForListing(listingId) {
    try {
      const room = await api(`/api/v1/market/listings/${listingId}/chat-rooms`, { method: 'POST' });
      if (typeof show === 'function') show('deals');
      await loadRooms();
      const target = rooms.find(r => r.roomId === room.roomId);
      if (target) selectRoom(target);
    } catch (e) {
      alert('채팅방을 열 수 없습니다: ' + e.message);
    }
  }

  document.addEventListener('pastport:page-loaded', (e) => {
    if (e.detail.id === 'deals') {
      init();
      loadRooms();
    }
  });

  document.addEventListener('pastport:page-shown', (e) => {
    if (e.detail.id === 'deals') {
      loadRooms();
    } else {
      stopPolling();
    }
  });

  window.PastportDeals = Object.freeze({
    openRoomForListing
  });
})();
