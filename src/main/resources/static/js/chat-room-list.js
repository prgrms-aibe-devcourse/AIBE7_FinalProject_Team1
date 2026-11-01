(() => {
  'use strict';

  let actorGeneration = 0;
  document.addEventListener('pastport:auth-changed', () => { actorGeneration++; });

  // 전체·판매글별 목록은 같은 API·행·페이지 처리를 사용한다. 읽음 갱신은 대화를 연 화면만 담당한다.
  async function api(path, options = {}) {
    const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
    const response = await fetcher(path, { credentials: 'same-origin', cache: 'no-store', ...options,
      headers: { 'Content-Type': 'application/json', ...options.headers } });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(payload?.message || '요청 처리에 실패했습니다.');
      error.status = response.status;
      throw error;
    }
    return payload?.data;
  }

  function row(room, selected, onSelect) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'dl-row' + (selected === room.roomId ? ' on' : '');
    button.dataset.roomId = room.roomId;
    const icon = document.createElement('span'); icon.className = 'laptop'; icon.setAttribute('aria-hidden', 'true');
    const body = document.createElement('span');
    const name = document.createElement('b');
    name.textContent = room.counterpartyNickname || '상대방';
    const time = document.createElement('time');
    if (room.latestMessageAt) {
      time.dateTime = room.latestMessageAt;
      time.textContent = new Intl.DateTimeFormat('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(room.latestMessageAt));
    }
    name.append(time);
    const title = document.createElement('span');
    title.className = 't'; title.textContent = room.listingTitle || '판매글';
    const preview = document.createElement('span');
    preview.className = 'm'; preview.textContent = room.latestMessagePreview || '대화를 시작해 보세요';
    body.style.minWidth = '0';
    body.append(name, title, preview); button.append(icon, body);
    if (room.hasUnreadMessages) {
      const unread = document.createElement('i');
      unread.className = 'dl-dot'; unread.setAttribute('aria-label', '새 메시지'); button.append(unread);
    }
    button.addEventListener('click', () => onSelect(room));
    return button;
  }

  function mount(root, { listingId = null, onSelect }) {
    let generation = 0, request = null, disposed = false, busy = false;
    let items = [], page = null, selected = null;
    const empty = listingId ? '아직 이 판매글에 들어온 문의가 없어요' : '아직 나눈 채팅이 없어요';
    function render() {
      root.replaceChildren(...items.map(room => row(room, selected, onSelect)));
      if (!items.length) {
        const box = document.createElement('div'); box.className = 'dl-empty';
        const text = document.createElement('b'); text.textContent = empty; box.append(text);
        if (!listingId) {
          const hint = document.createElement('p'); hint.textContent = '마음에 드는 상품을 찾아 판매자에게 문의해 보세요.';
          const market = document.createElement('button'); market.type = 'button'; market.className = 'sc-btn primary';
          market.dataset.page = 'market'; market.textContent = '마켓 둘러보기'; box.append(hint, market);
        }
        root.append(box);
      }
      if (page?.hasNext) {
        const more = document.createElement('button');
        more.type = 'button'; more.className = 'sc-btn'; more.textContent = '더 보기'; more.dataset.chatMore = '';
        more.disabled = busy; more.addEventListener('click', () => load(true)); root.append(more);
      }
    }
    async function load(append = false) {
      if (disposed || (append && busy)) return;
      request?.abort(); request = new AbortController();
      const signal = request.signal, ticket = ++generation, actor = actorGeneration;
      const valid = () => !disposed && !signal.aborted && ticket === generation && actor === actorGeneration;
      busy = true;
      if (!append) { items = []; page = null; root.textContent = '대화방을 불러오는 중이에요…'; }
      else render();
      try {
        const params = new URLSearchParams({ page: String(append ? page.number + 1 : 0), size: '20' });
        if (listingId) params.set('listingId', String(listingId));
        const data = await api('/api/v1/chat-rooms?' + params, { signal });
        if (!valid()) return;
        items = append ? [...new Map([...items, ...data.items].map(room => [room.roomId, room])).values()] : data.items;
        page = data.page; busy = false; render();
      } catch (error) {
        if (!valid()) return;
        busy = false;
        if (append) render(); else root.replaceChildren();
        const message = document.createElement('p');
        message.setAttribute('role', 'status');
        message.textContent = error.status === 401 ? '로그인이 필요한 서비스입니다.' : '대화방 목록을 불러오지 못했습니다.';
        const retry = document.createElement('button');
        retry.type = 'button'; retry.className = 'sc-btn';
        retry.textContent = error.status === 401 ? '로그인하기' : '다시 시도';
        if (error.status === 401) retry.dataset.page = 'auth';
        else retry.addEventListener('click', () => load(append));
        root.append(message, retry);
      }
    }
    return {
      load,
      select(roomId) { selected = roomId; render(); },
      markRead(roomId) {
        items.forEach(room => { if (room.roomId === roomId) room.hasUnreadMessages = false; });
        render();
      },
      dispose() { disposed = true; generation++; request?.abort(); root.replaceChildren(); }
    };
  }

  window.PastportChatRoomList = Object.freeze({ api, mount });
})();
