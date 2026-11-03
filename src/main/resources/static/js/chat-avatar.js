// 채팅 상대방의 프로필 사진을 보여 준다. 사진은 방 단위 API(참여자만 조회 가능)로 받고, 방마다 한 번만 받아 object URL로 재사용한다.
(() => {
  const cache = new Map(); // roomId -> Promise<string | null>

  /** 상대방 사진을 받아 object URL로 돌려준다. 사진이 없으면 null이고, 네트워크 오류는 저장하지 않아 다음에 다시 시도한다. */
  function counterpartyUrl(roomId) {
    if (!cache.has(roomId)) {
      const request = (async () => {
        const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
        const response = await fetcher(`/api/v1/chat-rooms/${roomId}/counterparty/profile-image`, { credentials: 'same-origin', cache: 'no-store' });
        return response.status === 200 ? URL.createObjectURL(await response.blob()) : null;
      })().catch(() => { cache.delete(roomId); return null; });
      cache.set(roomId, request);
    }
    return cache.get(roomId);
  }

  /** 닉네임 첫 글자를 담은 원형 아바타를 만든다. {@link fill}로 사진을 채울 수 있다. */
  function create(nickname, size = '') {
    const avatar = document.createElement('span');
    avatar.className = 'chat-avatar' + (size ? ' ' + size : '');
    avatar.setAttribute('aria-hidden', 'true');
    avatar.textContent = [...(nickname || '')][0] || '?';
    return avatar;
  }

  /** 상대방 사진이 있으면 아바타 안의 글자를 사진으로 바꾼다. 아바타가 이미 화면에서 사라졌어도 오류 없이 끝난다. */
  async function fill(avatar, roomId) {
    const url = await counterpartyUrl(roomId);
    if (!url) return;
    const image = document.createElement('img');
    image.alt = '';
    image.src = url;
    avatar.replaceChildren(image);
  }

  // 로그아웃·계정 전환 시 이전 계정에서 받은 사진을 모두 버린다.
  document.addEventListener('pastport:auth-changed', () => {
    for (const request of cache.values()) request.then((url) => { if (url) URL.revokeObjectURL(url); });
    cache.clear();
  });

  window.PastportChatAvatar = Object.freeze({ create, fill });
})();
