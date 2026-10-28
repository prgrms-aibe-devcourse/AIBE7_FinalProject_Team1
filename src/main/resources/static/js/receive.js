(function () {
  let currentToken = null;
  let timerInterval = null;
  let tokenFromUrl = new URLSearchParams(window.location.search).get('token');

  const request = (path, options) => (window.PastportApi?.fetch || window.fetch.bind(window))(path, options);
  const goTo = (page) => document.querySelector(`[data-page="${page}"]`)?.click();

  async function initReceivePage() {
    const receiveRoot = document.querySelector('.rc');
    if (!receiveRoot) return;

    const token = tokenFromUrl;
    if (!token) {
      showError('유효하지 않은 링크입니다. 소유권 이전 토큰이 누락되었습니다.');
      return;
    }
    currentToken = token;
    // 일회용 토큰이 주소창·히스토리에 남지 않도록 읽은 즉시 지운다.
    window.history.replaceState(null, '', window.location.pathname);

    try {
      const res = await request(`/api/v1/ownership/transfers/verify?token=${encodeURIComponent(token)}`);
      const data = await res.json().catch(() => ({}));

      if (!res.ok || !data.success) {
        showError(data.message || '소유권 이전 정보를 찾을 수 없습니다.');
        return;
      }

      renderTransferInfo(data.data);
    } catch (err) {
      console.error(err);
      showError('서버와의 통신 중 오류가 발생했습니다.');
    }
  }

  function renderTransferInfo(info) {
    const prodTitleEl = document.querySelector('.rc-prod h2');
    const prodDescEl = document.querySelector('.rc-prod p');
    const timeEl = document.querySelector('.rc-time b');
    const acceptBtn = document.querySelector('.rc button.primary');
    const asideEl = document.querySelector('.rc-state');

    if (prodTitleEl) prodTitleEl.textContent = info.productTitle;
    if (prodDescEl) prodDescEl.textContent = `판매자: ${info.fromUserNickname} · 소유권 이전 대기 중`;

    // 만료 시간 카운트다운
    if (timeEl && info.expiresAt) {
      startCountdown(info.expiresAt, timeEl);
    }

    if (!info.canAccept) {
      if (acceptBtn) {
        acceptBtn.disabled = true;
        acceptBtn.textContent = info.isExpired ? '만료된 이전 요청입니다' : '수락할 수 없는 상태입니다';
      }
    } else {
      if (acceptBtn) {
        acceptBtn.disabled = false;
        acceptBtn.onclick = handleAccept;
      }
    }
  }

  function startCountdown(expiresAtStr, el) {
    if (timerInterval) clearInterval(timerInterval);
    const target = new Date(expiresAtStr).getTime();

    function update() {
      const now = Date.now();
      const diff = target - now;
      if (diff <= 0) {
        el.textContent = '만료됨';
        clearInterval(timerInterval);
        const acceptBtn = document.querySelector('.rc button.primary');
        if (acceptBtn) {
          acceptBtn.disabled = true;
          acceptBtn.textContent = '만료된 이전 요청입니다';
        }
        return;
      }
      const hours = Math.floor(diff / (1000 * 60 * 60));
      const mins = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
      const secs = Math.floor((diff % (1000 * 60)) / 1000);
      el.textContent = `${String(hours).padStart(2, '0')}:${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
    }
    update();
    timerInterval = setInterval(update, 1000);
  }

  async function handleAccept() {
    if (!currentToken) return;

    const acceptBtn = document.querySelector('.rc button.primary');
    if (acceptBtn) {
      acceptBtn.disabled = true;
      acceptBtn.textContent = '소유권 이전 처리 중…';
    }

    try {
      const res = await request('/api/v1/ownership/transfers/accept', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ token: currentToken })
      });

      const data = await res.json().catch(() => ({}));
      if (!res.ok || !data.success) {
        alert(data.message || '소유권 이전에 실패했습니다.');
        if (acceptBtn) {
          acceptBtn.disabled = false;
          acceptBtn.textContent = '제품을 받았어요 · 소유권 이전 수락';
        }
        return;
      }

      alert('축하합니다! 제품 소유권 이전이 성공적으로 완료되었습니다.\n내 기기 목록으로 이동합니다.');
      goTo('mypage');
    } catch (err) {
      console.error(err);
      alert('소유권 이전 요청 중 오류가 발생했습니다.');
      if (acceptBtn) {
        acceptBtn.disabled = false;
        acceptBtn.textContent = '제품을 받았어요 · 소유권 이전 수락';
      }
    }
  }

  function showError(msg) {
    const card = document.querySelector('.rc .sc-card');
    if (!card) return;
    const box = document.createElement('div');
    box.style.cssText = 'text-align:center;padding:40px 20px';
    const mark = document.createElement('span');
    mark.style.cssText = 'font-size:40px;color:var(--danger)';
    mark.textContent = '×';
    const title = document.createElement('h2');
    title.style.margin = '16px 0 8px';
    title.textContent = '이전 요청을 확인할 수 없습니다';
    const text = document.createElement('p');
    text.style.cssText = 'color:var(--muted);margin-bottom:24px';
    text.textContent = msg;
    const back = document.createElement('button');
    back.type = 'button';
    back.className = 'sc-btn primary';
    back.textContent = '마켓으로 돌아가기';
    back.addEventListener('click', () => goTo('market'));
    box.append(mark, title, text, back);
    card.replaceChildren(box);
  }

  // 화면 HTML은 비동기로 채워지므로 로드가 끝난 뒤에 초기화한다.
  document.addEventListener('pastport:page-loaded', (event) => {
    if (event.detail.id !== 'receive') return;
    if (tokenFromUrl && typeof show === 'function') show('receive');
    initReceivePage();
  });
})();
