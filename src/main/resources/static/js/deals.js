(() => {
  'use strict';

  let currentRoom = null;
  let currentUserId = null;
  let polling = null;
  let roomList = null;
  let pendingEntry = null;
  let opening = null;
  let roomSequence = 0;
  let roomScope = null;
  let roomsGeneration = 0;
  let roomsController = null;
  let sessionGeneration = 0;
  let openGeneration = 0;
  let openController = null;

  const find = (selector) => document.querySelector('#deals ' + selector);
  const findAll = (selector) => document.querySelectorAll('#deals ' + selector);

  /** 인증된 API를 호출한다. 방·계정 전환의 취소 신호와 15초 제한으로 끝나지 않는 조회를 정리한다. */
  async function api(path, options = {}) {
    const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    const timeout = AbortSignal.timeout(15000);
    const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout;
    const response = await fetcher(path, { credentials: 'same-origin', ...options, headers, signal });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(payload?.message || payload?.fieldErrors?.[0]?.message || '요청 처리에 실패했습니다.');
      error.status = response.status;
      error.code = payload?.code;
      throw error;
    }
    return payload?.data;
  }

  /** 요청 당시 세션의 사용자만 조회한다. 공유 상태 갱신은 세대를 검사한 호출자가 맡는다. */
  async function initUser(signal) {
    try {
      const user = await api('/api/auth/me', { method: 'GET', signal });
      return user?.id || null;
    } catch (error) {
      if (error.status === 401) return null;
      throw error;
    }
  }

  /** 마지막 목록 조회의 성공·실패만 표시한다. 계정 변경 전에 시작한 응답은 화면과 사용자 ID를 바꾸지 못한다. */
  async function loadRooms(entry = null) {
    const container = find('[data-deals-rooms]');
    if (!container || !document.querySelector('#deals.active')) return false;
    const generation = ++roomsGeneration;
    roomsController?.abort();
    const controller = new AbortController();
    roomsController = controller;
    const isCurrent = () => generation === roomsGeneration && !controller.signal.aborted;
    roomList?.dispose(); roomList = null;
    closeRoom();
    try {
      const userId = await initUser(controller.signal);
      if (!isCurrent()) return false;
      currentUserId = userId;
      if (!currentUserId) {
        container.innerHTML = '<div class="dl-empty"><b>로그인이 필요한 서비스입니다.</b><p>로그인하면 판매자·구매자와 나눈 채팅을 볼 수 있어요.</p><button type="button" class="sc-btn primary" data-page="auth">로그인하기</button></div>';
        return false;
      }
      find('[data-deals-scope]').textContent = entry ? (entry.listingTitle || '이 판매글') + ' · 채팅' : '전체 채팅';
      find('[data-deals-all]').hidden = !entry;
      const list = window.PastportChatRoomList.mount(container, { listingId: entry?.listingId, onSelect: selectRoom });
      roomList = list;
      await list.load();
      if (!isCurrent() || roomList !== list) return false;
      if (entry?.roomId) await selectRoom(entry);
      return true;
    } catch (e) {
      if (!isCurrent() || e.name === 'AbortError') return false;
      container.innerHTML = '<div class="dl-empty"><b>대화방 목록을 불러오지 못했습니다.</b><p>잠시 후 다시 시도해 주세요.</p></div>';
      return false;
    }
  }

  // 글이 삭제됐거나(상태 없음) 판매 중지된 방은 구매 요청·소유권 이전을 막는다. 대화는 명세대로 계속할 수 있다.
  const isClosed = (status) => !status || status === 'CANCELLED';
  const statusLabel = (status) => ({ ACTIVE: '판매 중', RESERVED: '예약 중', SOLD: '판매 완료', CANCELLED: '판매 중지' }[status] || '삭제·종료된 글');
  const statusClass = (status) => (status === 'RESERVED' ? 'warn' : status === 'ACTIVE' ? 'ok' : '');

  /** 로그아웃·세션 만료 시 조회 세대를 먼저 바꾸고 이전 계정의 목록·대화·이동 요청을 취소한다. */
  function resetDeals() {
    sessionGeneration++;
    roomsGeneration++;
    roomsController?.abort();
    cancelOpen();
    pendingEntry = null;
    roomList?.dispose(); roomList = null;
    closeRoom();
    currentUserId = null;
    find('[data-deals-rooms]')?.replaceChildren();
    find('[data-deals-messages]')?.replaceChildren();
    find('[data-deals-room-title]')?.replaceChildren();
    const label = find('[data-deals-scope]'); if (label) label.textContent = '전체 채팅';
    const all = find('[data-deals-all]'); if (all) all.hidden = true;
    const input = find('[data-deals-input]'); if (input) input.value = '';
    const title = find('[data-deals-info-title]'); if (title) title.textContent = '';
    const chatSection = find('[data-deals-chat-section]'); if (chatSection) chatSection.style.display = 'none';
    const infoSection = find('[data-deals-info]'); if (infoSection) infoSection.style.display = 'none';
  }

  /**
   * 대화방 나가기: 확인을 받은 뒤 서버에 나가기를 요청한다. 내 목록에서 방이 사라지고 상대방에게는 나갔다는 안내가 남는다.
   * 서버 요청이 성공한 뒤에만 화면을 닫아, 실패하면 대화가 그대로 남는다.
   */
  /** 브라우저 기본 confirm 대신 페이지 안 팝업으로 나가기를 한 번 더 확인한다. 일부 내장 브라우저는 confirm을 조용히 막는다. */
  function confirmLeave() {
    return new Promise((resolve) => {
      const overlay = document.createElement('div');
      overlay.className = 'dl-confirm';
      overlay.innerHTML = '<div class="dl-confirm-box" role="dialog" aria-modal="true" aria-label="채팅방 나가기 확인">'
        + '<b>채팅방을 나갈까요?</b>'
        + '<p>나가면 이 채팅방과 대화 내용이 삭제돼요.<br>상대방에게는 나갔다는 안내가 표시됩니다.</p>'
        + '<div class="dl-confirm-actions"><button type="button" class="sc-btn" data-confirm-cancel>취소</button>'
        + '<button type="button" class="sc-btn danger" data-confirm-ok>나가기</button></div></div>';
      const onKey = (e) => { if (e.key === 'Escape') done(false); };
      const done = (value) => { overlay.remove(); document.removeEventListener('keydown', onKey); resolve(value); };
      overlay.addEventListener('click', (e) => {
        if (e.target === overlay || e.target.closest('[data-confirm-cancel]')) done(false);
        else if (e.target.closest('[data-confirm-ok]')) done(true);
      });
      document.addEventListener('keydown', onKey);
      document.body.appendChild(overlay);
      overlay.querySelector('[data-confirm-cancel]')?.focus();
    });
  }

  async function leaveRoom() {
    const scope = roomScope;
    if (!scope || !roomCurrent(scope)) return;
    if (!(await confirmLeave())) return;
    try {
      await api(`/api/v1/chat-rooms/${scope.room.roomId}`, { method: 'DELETE' });
    } catch (e) {
      if (e.status === 401) { resetDeals(); return; }
      // alert가 막힌 환경에서도 보이도록 대화창 안에도 실패 안내를 남긴다.
      const box = find('[data-deals-messages]');
      if (box) {
        const fail = document.createElement('div');
        fail.className = 'msg-system';
        fail.textContent = '채팅방을 나가지 못했어요: ' + e.message;
        box.appendChild(fail);
        box.scrollTop = box.scrollHeight;
      }
      return;
    }
    closeRoom();
    find('[data-deals-messages]')?.replaceChildren();
    const chatSection = find('[data-deals-chat-section]'); if (chatSection) chatSection.style.display = 'none';
    const infoSection = find('[data-deals-info]'); if (infoSection) infoSection.style.display = 'none';
    loadRooms();
  }

  /** 서버가 허용한 대화 목록을 그린다. 판매글 상태와 상대방 문자열은 화면 출력용으로만 사용한다. */
  function renderRoomList() {
    roomList?.select(currentRoom?.roomId);
  }

  /** 선택한 방마다 조회 범위를 새로 만든다. 이전 방의 응답과 finally 처리는 새 방의 갱신을 막지 못한다. */
  async function selectRoom(room) {
    stopPolling();
    roomScope?.controller.abort();
    const sequence = ++roomSequence;
    currentRoom = room;
    const scope = { room, sequence, userId: currentUserId, controller: new AbortController(), sending: false };
    roomScope = scope;
    find('[data-deals-messages]')?.replaceChildren();
    const input = find('[data-deals-input]'); if (input) input.value = '';
    const send = find('[data-deals-send]'); if (send) send.disabled = false;
    window.PastportTradeCompletion?.clear();
    find('[data-deals-completion]')?.setAttribute('hidden', '');
    find('.dl')?.classList.add('dl-has-room');
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
      statusEl.textContent = statusLabel(room.listingStatus);
      statusEl.className = 'sc-badge ' + statusClass(room.listingStatus);
    }

    find('[data-deals-info-title]').textContent = room.listingTitle || '–';
    find('[data-deals-info-price]').textContent = '–';
    const buyBtn = find('[data-deals-request-buy]');
    if (buyBtn) buyBtn.hidden = true;

    await loadMessages(scope);
    if (!roomCurrent(scope)) return;
    await checkPurchaseRequest(scope);

    if (roomCurrent(scope)) startPolling(scope);
  }

  /** 방·사용자·세대가 모두 유지되는 요청만 현재 대화를 갱신할 수 있다. */
  function roomCurrent(scope) {
    return !!scope && scope === roomScope && scope.sequence === roomSequence && scope.userId === currentUserId && !scope.controller.signal.aborted;
  }

  /** 선택 당시 방의 메시지와 읽음 위치를 갱신한다. 취소되거나 이전 계정의 401 응답은 무시한다. */
  async function loadMessages(scope) {
    if (!roomCurrent(scope)) return;
    const roomId = scope.room.roomId;
    try {
      const data = await api(`/api/v1/chat-rooms/${roomId}/messages?page=0&size=50`, { method: 'GET', signal: scope.controller.signal });
      const messages = (data?.items || []).reverse();
      if (!roomCurrent(scope)) return;
      renderMessages(messages);

      if (messages.some((m) => !m.system)) {
        const lastMsg = messages.filter((m) => !m.system).pop();
        await api(`/api/v1/chat-rooms/${roomId}/read-position`, {
          method: 'PUT',
          signal: scope.controller.signal,
          body: JSON.stringify({ lastReadMessageId: lastMsg.messageId })
        });
        if (roomCurrent(scope)) {
          scope.room.hasUnreadMessages = false;
          roomList?.markRead(roomId);
        }
      }
    } catch (e) {
      if (!roomCurrent(scope) || e.name === 'AbortError') return;
      if (e.status === 401) { resetDeals(); return; }
      console.error('메시지 로드 실패:', e);
    }
  }

  /** 현재 방의 메시지를 이스케이프해 표시하고 최신 메시지 위치로 스크롤한다. */
  function renderMessages(messages) {
    const container = find('[data-deals-messages]');
    if (!container) return;
    container.replaceChildren();

    messages.forEach((msg) => {
      if (msg.system) {
        const notice = document.createElement('div');
        notice.className = 'msg-system';
        notice.textContent = msg.content;
        container.appendChild(notice);
        return;
      }
      const isMe = currentUserId != null && msg.senderId === currentUserId;
      const box = document.createElement('div');
      box.className = 'msg ' + (isMe ? 'me' : 'you');
      const timeStr = msg.createdAt ? new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit' }).format(new Date(msg.createdAt)) : '';
      box.innerHTML = `<p>${escapeHtml(msg.content)}</p><time>${timeStr}</time>`;
      container.appendChild(box);
    });

    // 상대방이 나갔다는 안내가 있으면 더 이상 메시지를 보낼 수 없다.
    const partnerLeft = messages.some((m) => m.system && m.senderId !== currentUserId);
    const input = find('[data-deals-input]');
    const send = find('[data-deals-send]');
    if (input) { input.disabled = partnerLeft; input.placeholder = partnerLeft ? '상대방이 나간 채팅방이에요' : '메시지를 입력하세요'; }
    if (send && partnerLeft) send.disabled = true;

    container.scrollTop = container.scrollHeight;
  }

  /** 송수신 방향으로 판매자·구매자 역할을 구분한다. 현재 방에만 예약·완료 조작을 연결한다. */
  async function checkPurchaseRequest(scope) {
    if (!roomCurrent(scope)) return;
    const { room, userId } = scope;
    const isCurrent = () => roomCurrent(scope);
    const buyBtn = find('[data-deals-request-buy]');
    if (buyBtn) buyBtn.hidden = true;
    // 종료된 글에서도 대화는 유지하되, 이전 방의 예약·완료 조작은 남기지 않는다.
    if (isClosed(room.listingStatus)) {
      window.PastportTradeCompletion?.clear();
      find('[data-deals-messages]')?.querySelector('[data-deals-pending-card]')?.remove();
      const status = find('[data-deals-info-trade-status]');
      if (status) status.textContent = '종료된 거래';
      return;
    }
    try {
      const [sent, received] = await Promise.all(['SENT', 'RECEIVED'].map(direction => api(`/api/v1/purchase-requests?direction=${direction}&roomId=${room.roomId}&page=0&size=100`, { method: 'GET', signal: scope.controller.signal })));
      if (!isCurrent()) return;
      const sentItems = sent?.items || [];
      const receivedItems = received?.items || [];
      const items = [...sentItems, ...receivedItems].sort((a, b) => b.requestId - a.requestId);
      const req = items.find(item => item.requestStatus === 'PENDING' || item.requestStatus === 'ACCEPTED');
      room.listingStatus = req?.listingStatus || sent?.roomContext?.listingStatus || received?.roomContext?.listingStatus || room.listingStatus;
      let isSeller = !!req && receivedItems.some(item => item.requestId === req.requestId);
      const status = find('[data-deals-info-trade-status]');
      const setStep = step => findAll('[data-flow-step]').forEach(node => node.classList.toggle('now', node.dataset.flowStep === step));
      const badge = find('[data-deals-listing-status]');
      if (badge) { badge.textContent = statusLabel(room.listingStatus); badge.className = 'sc-badge ' + statusClass(room.listingStatus); }
      renderRoomList();
      if (!req) {
        window.PastportTradeCompletion?.clear();
        setStep('inquiry');
        if (status) status.textContent = ['SOLD', 'CANCELLED'].includes(room.listingStatus) ? '종료된 거래' : '문의 진행 중';
        if (room.listingStatus === 'ACTIVE') {
          const detail = await api(`/api/v1/listings/${room.listingId}`, { method: 'GET', signal: scope.controller.signal });
          if (!isCurrent()) return;
          isSeller = detail.canManage;
          const price = find('[data-deals-info-price]');
          if (price) price.textContent = detail.content?.price == null ? '–' : new Intl.NumberFormat('ko-KR').format(detail.content.price) + '원';
          if (buyBtn) { buyBtn.hidden = isSeller; buyBtn.onclick = () => requestBuy(room.listingId, room.roomId); }
        }
      } else if (req.requestStatus === 'PENDING') {
        window.PastportTradeCompletion?.clear();
        setStep('pending');
        if (status) status.textContent = isSeller ? '구매 요청 확인 필요' : '판매자 수락 대기 중';
        renderPendingCard(req, isSeller, scope);
      } else {
        const done = req.transactionStatus === 'COMPLETED';
        setStep(done ? 'completed' : 'reserved');
        if (status) status.textContent = done ? '거래 완료' : '예약 완료';
        await window.PastportTradeCompletion?.mount({
          request: req, role: isSeller ? 'seller' : 'buyer', userId, roomId: room.roomId,
          title: room.listingTitle || '이 제품', isCurrent,
          onChanged: () => isCurrent() ? checkPurchaseRequest(scope) : Promise.resolve()
        });
      }
    } catch (error) {
      if (!isCurrent()) return;
      window.PastportTradeCompletion?.clear();
      const status = find('[data-deals-info-trade-status]');
      if (status) status.textContent = error.message || '거래 상태를 확인하지 못했어요.';
    }
  }

  /** 현재 방의 판매자에게만 예약 수락·거절을 제공한다. 서버가 실제 권한과 예약 상태를 최종 검사한다. */
  function renderPendingCard(req, isSeller, scope) {
    const container = find('[data-deals-messages]');
    if (!container) return;
    container.querySelector('[data-deals-pending-card]')?.remove();
    if (!isSeller) return;
    const card = document.createElement('div');
    card.className = 'dl-act';
    card.dataset.dealsPendingCard = '';
    card.innerHTML = `
      <i>⇄</i>
      <div><b>구매 예약 요청이 접수되었습니다</b><span>수락하면 판매글이 예약 상태로 전환되고 다른 구매자의 예약이 차단됩니다.</span></div>
      <div class="btns">
        <button type="button" class="sc-btn sm" data-trade-reject>거절</button>
        <button type="button" class="sc-btn primary sm" data-trade-accept>수락하기</button>
      </div>
    `;

    card.querySelector('[data-trade-accept]')?.addEventListener('click', async () => {
      if (!roomCurrent(scope)) return;
      if (!confirm('구매 예약을 수락하시겠습니까? 판매글이 예약 상태가 됩니다.')) return;
      try {
        await api(`/api/v1/purchase-requests/${req.requestId}/accept`, { method: 'POST', signal: scope.controller.signal });
        if (!roomCurrent(scope)) return;
        alert('예약이 수락되었습니다!');
        if (currentRoom) selectRoom(currentRoom);
      } catch (e) {
        if (!roomCurrent(scope)) return;
        alert(e.message);
      }
    });

    card.querySelector('[data-trade-reject]')?.addEventListener('click', async () => {
      if (!roomCurrent(scope)) return;
      if (!confirm('구매 예약을 거절하시겠습니까?')) return;
      try {
        await api(`/api/v1/purchase-requests/${req.requestId}/reject`, { method: 'POST', signal: scope.controller.signal });
        if (!roomCurrent(scope)) return;
        alert('예약이 거절되었습니다.');
        if (currentRoom) selectRoom(currentRoom);
      } catch (e) {
        if (!roomCurrent(scope)) return;
        alert(e.message);
      }
    });

    container.appendChild(card);
    container.scrollTop = container.scrollHeight;
  }

  /** 선택한 방·판매글의 구매 예약을 요청하고 같은 방에 남아 있을 때만 결과를 표시한다. */
  async function requestBuy(listingId, roomId) {
    const scope = roomScope;
    if (!scope || !roomCurrent(scope) || scope.room.roomId !== roomId) return;
    if (!confirm('이 판매글에 대해 구매 예약을 요청하시겠습니까?')) return;
    try {
      await api(`/api/v1/market/listings/${listingId}/purchase-requests`, {
        method: 'POST',
        signal: scope.controller.signal,
        body: JSON.stringify({ roomId })
      });
      if (!roomCurrent(scope)) return;
      alert('구매 예약 요청을 보냈습니다! 판매자의 수락을 기다려 주세요.');
      if (currentRoom) selectRoom(currentRoom);
    } catch (e) {
      if (!roomCurrent(scope)) return;
      alert(e.message);
    }
  }

  /** 입력한 메시지를 선택 당시 방에 보낸다. 계정·방이 바뀌면 늦은 결과는 화면에 반영하지 않는다. */
  async function sendMessage() {
    const input = find('[data-deals-input]');
    const scope = roomScope;
    if (!input || !scope || !roomCurrent(scope) || scope.sending) return;
    const content = input.value.trim();
    if (!content) return;
    input.value = '';
    scope.sending = true;
    const button = find('[data-deals-send]'); if (button) button.disabled = true;

    try {
      await api(`/api/v1/chat-rooms/${scope.room.roomId}/messages`, {
        method: 'POST',
        signal: scope.controller.signal,
        body: JSON.stringify({ content })
      });
      await loadMessages(scope);
    } catch (e) {
      if (!roomCurrent(scope)) return;
      if (e.status === 401) resetDeals();
      alert('메시지 전송에 실패했습니다: ' + e.message);
    } finally {
      scope.sending = false;
      if (roomCurrent(scope) && button) button.disabled = false;
    }
  }

  /** 방마다 별도 busy 상태를 소유한다. 오래된 폴링의 finally가 새 방의 요청 상태를 변경하지 않는다. */
  function startPolling(scope) {
    stopPolling();
    const poll = { busy: false, timer: null };
    polling = poll;
    poll.timer = setInterval(async () => {
      if (polling !== poll || !roomCurrent(scope) || !document.querySelector('#deals.active') || poll.busy) return;
      poll.busy = true;
      try {
        await loadMessages(scope);
        if (polling === poll && roomCurrent(scope)) await checkPurchaseRequest(scope);
      } finally { poll.busy = false; }
    }, 3000);
  }

  /** 선택한 방의 조회를 취소하고 완료 확인 화면도 정리한다. 서버 대화 기록은 유지한다. */
  function closeRoom() {
    stopPolling();
    roomSequence++;
    roomScope?.controller.abort();
    roomScope = null;
    currentRoom = null;
    window.PastportTradeCompletion?.clear();
    find('.dl')?.classList.remove('dl-has-room');
    const chat = find('[data-deals-chat-section]');
    const info = find('[data-deals-info]');
    if (chat) chat.style.display = 'none';
    if (info) info.style.display = 'none';
    renderRoomList();
  }

  /** 주기 호출만 중지한다. 진행 중 응답은 해당 방의 취소 신호와 세대로 별도 차단한다. */
  function stopPolling() {
    if (polling) clearInterval(polling.timer);
    polling = null;
  }

  /** 서버의 제목·닉네임·메시지를 HTML 문자열에 넣기 전에 특수문자를 이스케이프한다. */
  function escapeHtml(str) {
    return String(str || '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
  }

  /** 페이지가 로드되면 대화 이동·전송 버튼을 연결한다. */
  function init() {
    const sendBtn = find('[data-deals-send]');
    if (!sendBtn || sendBtn.dataset.bound) return;
    sendBtn.dataset.bound = 'true';
    find('[data-deals-back]')?.addEventListener('click', closeRoom);
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

  /** 화면 이동 중이거나 이전 계정에서 시작한 대화방 생성의 후속 이동을 무효화한다. */
  function cancelOpen() {
    openGeneration++;
    openController?.abort();
    opening = null;
  }

  /** 상세에서 시작한 문의만 연다. POST와 목록 조회 후에도 같은 계정·이동 요청인지 확인한다. */
  async function openRoomForListing(listingId) {
    if (opening) return;
    cancelOpen();
    const generation = openGeneration;
    const session = sessionGeneration;
    const sourceId = new URL(window.location.href).searchParams.get('listingId');
    if (sourceId !== String(listingId) || !document.querySelector('#detail.active')) return;
    const controller = new AbortController();
    openController = controller;
    opening = { generation };
    const isCurrent = () => generation === openGeneration && session === sessionGeneration && !controller.signal.aborted;
    try {
      const room = await api(`/api/v1/market/listings/${listingId}/chat-rooms`, { method: 'POST', signal: controller.signal });
      if (!isCurrent() || new URL(window.location.href).searchParams.get('listingId') !== sourceId || !document.querySelector('#detail.active')) return;
      const data = await api(`/api/v1/chat-rooms?listingId=${listingId}&page=0&size=20`, { signal: controller.signal });
      if (!isCurrent() || new URL(window.location.href).searchParams.get('listingId') !== sourceId || !document.querySelector('#detail.active')) return;
      const summary = data.items.find(item => item.roomId === room.roomId);
      if (!summary) throw new Error('대화방 정보를 확인하지 못했어요. 다시 시도해 주세요.');
      openSummary(summary);
    } catch (e) {
      if (!isCurrent() || e.name === 'AbortError') return;
      alert('채팅방을 열 수 없습니다: ' + e.message);
    } finally {
      if (opening?.generation === generation) opening = null;
    }
  }

  /** 판매자의 판매글별 목록에서 고른 방을 같은 범위로 연다. 전체 채팅 진입은 이 범위를 초기화한다. */
  function openSummary(room) {
    cancelOpen();
    pendingEntry = room;
    if (typeof show === 'function') show('deals');
  }

  /** 최초 HTML 로드가 늦어도 진입 대상을 유지하고, 화면이 준비되면 한 번만 목록을 연다. */
  function activate() {
    if (!document.querySelector('#deals.active') || !find('[data-deals-rooms]')) return;
    const entry = pendingEntry; pendingEntry = null;
    init();
    return loadRooms(entry);
  }

  document.addEventListener('pastport:page-loaded', (e) => {
    if (e.detail.id === 'deals') {
      activate();
    }
  });

  document.addEventListener('pastport:page-shown', (e) => {
    if (e.detail.id === 'deals') {
      activate();
    } else {
      resetDeals();
    }
  });

  document.addEventListener('pastport:auth-changed', (event) => {
    resetDeals();
    if (event.detail?.signedIn && document.querySelector('#deals.active')) activate();
  });

  // 나가기 버튼은 화면 HTML이 다시 그려져도 동작하도록 문서 전체에서 위임해 처리한다.
  document.addEventListener('click', (e) => {
    if (e.target.closest?.('#deals [data-deals-leave]')) leaveRoom();
  });

  window.PastportDeals = Object.freeze({
    openRoomForListing, openSummary
  });
})();
