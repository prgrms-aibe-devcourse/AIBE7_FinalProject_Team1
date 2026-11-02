// 로그인·회원가입 화면 연동. 화면 HTML은 pages.js가 나중에 채워 넣으므로 document에 이벤트를 위임한다.
// Access/Refresh Token은 모두 HttpOnly 쿠키라 JS에서 접근할 수 없다. 쓰기 요청은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내 CSRF를 검사받는다.
const auth = (() => {
  let user = null;
  let sessionVersion = 0;
  let ownedCountVersion = 0;
  const session = window.PastportSession;

  /** 쓰기 요청이면 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 만들어 돌려준다. */
  const csrfHeaders = (method) => {
    if (!method || ['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())) return {};
    const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
    return match ? { 'X-XSRF-TOKEN': decodeURIComponent(match[1]) } : {};
  };

  /** 화면 이동 버튼(data-page)을 눌러 해당 화면으로 이동한다. */
  const goto = (page) => document.querySelector(`[data-page="${page}"]`)?.click();

  // 복귀 목적지는 고정된 관리자 화면만 허용한다. 다른 URL로의 이동이나 권한 판정을 대신하지 않는다.
  const adminReturn = new URL(window.location.href).searchParams.get('returnTo') === 'admin';
  const returnToAdmin = () => {
    if (!adminReturn || user?.role !== 'ADMIN') return false;
    window.location.replace('/admin'); return true;
  };

  /** 인증 API를 JSON으로 호출한다. 실패하면 상태·코드·항목별 오류를 담은 Error를 던진다. */
  const request = async (path, options = {}) => {
    const response = await fetch(path, {
      method: options.method || 'POST',
      headers: { 'Content-Type': 'application/json', ...csrfHeaders(options.method || 'POST'), ...(options.headers || {}) },
      body: options.body ? JSON.stringify(options.body) : undefined,
    });
    if (response.status === 204) return null;
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(payload?.message || '요청을 처리하지 못했어요.');
      error.status = response.status;
      error.code = payload?.code;
      error.fieldErrors = payload?.fieldErrors || [];
      throw error;
    }
    return payload?.data ?? null;
  };

  /** 로그인 여부에 따라 헤더의 계정 영역을 다시 그린다. */
  const renderHeader = () => {
    const box = document.querySelector('.landing-account');
    if (!box) return;
    box.replaceChildren();
    box.className = user ? 'landing-account is-signed-in' : 'landing-account';
    if (user) {
      const nickname = user.nickname || '사용자';
      const profile = document.createElement('button');
      profile.type = 'button';
      profile.className = 'landing-account-profile';
      profile.dataset.authMenuToggle = '';
      profile.setAttribute('aria-expanded', 'false');
      profile.setAttribute('aria-haspopup', 'menu');
      profile.setAttribute('aria-label', `${nickname}님 마이페이지`);

      const avatar = document.createElement('span');
      avatar.className = 'landing-account-avatar';
      avatar.setAttribute('aria-hidden', 'true');
      const avatarSource = readAvatar();
      if (avatarSource) {
        const image = document.createElement('img');
        image.src = avatarSource;
        image.alt = '';
        avatar.append(image);
      } else {
        avatar.textContent = [...nickname][0] || '?';
      }

      const name = document.createElement('span');
      name.className = 'landing-account-name';
      name.textContent = `${nickname}님`;

      profile.append(avatar, name);

      const logout = document.createElement('button');
      logout.type = 'button';
      logout.className = 'landing-account-logout';
      logout.dataset.authLogout = '';
      logout.textContent = '로그아웃';

      const menu = document.createElement('div');
      menu.className = 'landing-account-menu';
      menu.dataset.authMenu = '';
      menu.hidden = true;
      [
        ['mypage', '내 계정'],
        ['passport', '내 제품'],
        ['my-listings', '내 판매글'],
        ['deals', '채팅'],
      ].forEach(([page, label]) => {
        const item = document.createElement('button');
        item.type = 'button';
        item.dataset.page = page;
        item.textContent = label;
        menu.append(item);
      });
      const menuLogout = logout.cloneNode(true);
      menuLogout.className = 'landing-account-menu-logout';
      menu.append(menuLogout);
      box.append(profile, menu);
      if (user.role === 'ADMIN') {
        const admin = document.createElement('a');
        admin.className = 'landing-account-admin';
        admin.href = '/admin';
        admin.textContent = '관리자';
        box.append(admin);
      }
    } else {
      const login = document.createElement('button');
      login.dataset.page = 'auth';
      login.textContent = '로그인';
      const bar = document.createElement('span');
      bar.textContent = '|';
      const signup = document.createElement('button');
      signup.dataset.page = 'signup';
      signup.textContent = '회원가입';
      box.append(login, bar, signup);
    }
  };

  // 마이페이지: 로그인 여부에 따라 안내/계정 정보를 보여준다. 화면 HTML이 늦게 로드될 수 있어 둘 다에서 호출한다.
  /** 마이페이지에 로그인 여부별 안내 또는 계정 정보를 보여준다. */
  const renderMyPage = () => {
    ownedCountVersion++;
    const root = document.querySelector('#mypage');
    if (!root) return;
    const guest = root.querySelector('[data-mp="guest"]');
    const member = root.querySelector('[data-mp="member"]');
    if (!guest || !member) return;
    guest.hidden = !!user;
    member.hidden = !user;
    if (!user) return;
    const fields = {
      initial: [...user.nickname][0] || '',
      nickname: user.nickname,
      email: user.email,
    };
    Object.entries(fields).forEach(([key, value]) => {
      const el = member.querySelector(`[data-mp-field="${key}"]`);
      if (el) el.textContent = value;
    });
    renderAvatar(member);
    renderOwnedCount(member);
  };

  /** 현재 소유 제품 수를 제품 목록 API(이전된 제품 제외)로 세어 보여준다. 실패하면 숫자 대신 안내만 남긴다. */
  const renderOwnedCount = async (member) => {
    const el = member.querySelector('[data-mp-field="ownedCount"]');
    if (!el) return;
    const owner = user?.id;
    const requestVersion = ++ownedCountVersion;
    el.textContent = '-';
    try {
      const response = await (window.PastportApi?.fetch || fetch)('/api/v1/products?page=0&size=1', { credentials: 'same-origin', cache: 'no-store' });
      const payload = await response.json().catch(() => null);
      if (user?.id !== owner || requestVersion !== ownedCountVersion) return;
      const data = payload?.data;
      const count = Number.isSafeInteger(data?.page?.totalElements) && data.page.totalElements >= 0
        ? data.page.totalElements
        : Number.isSafeInteger(data?.summary?.registered) && data.summary.registered >= 0
          ? data.summary.registered : null;
      el.textContent = response.ok && count !== null ? String(count) : '-';
    } catch {
      if (user?.id === owner && requestVersion === ownedCountVersion) el.textContent = '-';
    }
  };

  // 프로필 사진은 서버(DB)에 저장한다. 로그인 직후 한 번 받아 오고, 받은 이미지는 object URL로 화면에 쓴다.
  let avatarSrc = null;
  let avatarTicket = 0;
  const readAvatar = () => avatarSrc;
  const setAvatarSrc = (src) => {
    if (avatarSrc?.startsWith('blob:')) URL.revokeObjectURL(avatarSrc);
    avatarSrc = src;
  };
  /** 서버에서 내 사진을 받아 온다. 사진이 없거나(204) 실패하면 닉네임 첫 글자를 보여 준다. */
  const loadAvatar = async () => {
    const ticket = ++avatarTicket;
    const owner = user?.id;
    if (!owner) { setAvatarSrc(null); return; }
    let next = null;
    try {
      const response = await authFetch('/api/v1/me/profile-image', { cache: 'no-store' });
      if (response.status === 200) next = URL.createObjectURL(await response.blob());
    } catch { /* 사진을 못 받아도 기본 이미지로 보여 준다. */ }
    if (ticket !== avatarTicket || user?.id !== owner) { if (next) URL.revokeObjectURL(next); return; }
    setAvatarSrc(next);
    renderHeader();
    const member = document.querySelector('#mypage [data-mp="member"]');
    if (member) renderAvatar(member); else renderSideAccount();
  };

  /** 저장된 프로필 사진이 있으면 이미지로, 없으면 닉네임 첫 글자를 보여준다. */
  const renderAvatar = (member) => {
    const src = readAvatar();
    const img = member.querySelector('[data-mp-img]');
    img.hidden = !src;
    if (src) img.src = src; else img.removeAttribute('src');
    member.querySelector('[data-mp-field="initial"]').hidden = !!src;
    renderSideAccount();
  };

  /** 선택한 이미지를 256px 정사각형 JPEG로 잘라 줄인 Blob으로 돌려준다. 서버도 같은 규격으로 다시 검증한다. */
  const shrinkImage = (file) => new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      const size = 256, side = Math.min(image.width, image.height);
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = size;
      canvas.getContext('2d').drawImage(image, (image.width - side) / 2, (image.height - side) / 2, side, side, 0, 0, size, size);
      URL.revokeObjectURL(url);
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('이미지를 줄이지 못했어요.'))), 'image/jpeg', 0.85);
    };
    image.onerror = () => { URL.revokeObjectURL(url); reject(new Error('이미지를 읽지 못했어요.')); };
    image.src = url;
  });

  // 프로필 사진 변경 팝업: 고른 사진을 저장 전에 원형 미리보기로 확인하고, 닉네임과 함께 보여 준다.
  // pendingAvatar: undefined=변경 없음, null=기본 이미지로, { blob, url }=새로 고른 사진
  let pendingAvatar;
  const discardPending = () => {
    if (pendingAvatar?.url) URL.revokeObjectURL(pendingAvatar.url);
    pendingAvatar = undefined;
  };
  const dialogParts = () => {
    const dialog = document.querySelector('[data-mp-dialog]');
    if (!dialog) return null;
    const q = (name) => dialog.querySelector(`[data-mp-${name}]`);
    return { dialog, img: q('pimg'), initial: q('pinitial'), name: q('pname'), msg: q('pmsg'), save: q('save') };
  };
  const paintPreview = (parts) => {
    const src = pendingAvatar === undefined ? readAvatar() : pendingAvatar?.url || null;
    parts.img.hidden = !src;
    if (src) parts.img.src = src; else parts.img.removeAttribute('src');
    parts.initial.hidden = !!src;
    parts.initial.textContent = [...(user?.nickname || '')][0] || '';
    parts.name.textContent = user?.nickname || '';
  };
  const showDialogMessage = (parts, text) => { parts.msg.textContent = text || ''; parts.msg.hidden = !text; };

  /** 선택한 변경을 서버에 저장한다. 성공하면 새 사진(또는 기본 이미지)을 화면에 반영하고 팝업을 닫는다. */
  const saveAvatar = async (parts) => {
    if (pendingAvatar === undefined) { parts.dialog.close(); return; }
    const owner = user?.id;
    parts.save.disabled = true;
    showDialogMessage(parts, '저장하는 중이에요…');
    try {
      let response;
      if (pendingAvatar === null) {
        response = await authFetch('/api/v1/me/profile-image', { method: 'DELETE' });
      } else {
        const form = new FormData();
        form.append('file', pendingAvatar.blob, 'profile.jpg');
        response = await authFetch('/api/v1/me/profile-image', { method: 'PUT', body: form });
      }
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        showDialogMessage(parts, response.status === 401 ? '로그인이 필요해요.' : body?.message || '사진을 저장하지 못했어요. 잠시 후 다시 시도해 주세요.');
        return;
      }
      if (user?.id !== owner) return;
      // 새 사진의 object URL은 화면이 계속 쓰므로 팝업을 닫을 때 해제하지 않고 넘겨 준다.
      const saved = pendingAvatar;
      pendingAvatar = undefined;
      setAvatarSrc(saved ? saved.url : null);
      renderHeader();
      const member = document.querySelector('#mypage [data-mp="member"]');
      if (member) renderAvatar(member); else renderSideAccount();
      parts.dialog.close();
    } catch {
      showDialogMessage(parts, '서버에 연결하지 못했어요. 연결 상태를 확인해 주세요.');
    } finally {
      parts.save.disabled = false;
    }
  };

  document.addEventListener('click', (event) => {
    const parts = dialogParts();
    if (!parts || !user) return;
    const target = event.target;
    if (target.closest?.('[data-mp-open]')) {
      discardPending();
      showDialogMessage(parts, '');
      paintPreview(parts);
      parts.dialog.showModal();
    } else if (target.closest?.('[data-mp-premove]')) {
      discardPending();
      pendingAvatar = null; showDialogMessage(parts, ''); paintPreview(parts);
    } else if (target.closest?.('[data-mp-cancel]') || target === parts.dialog) {
      parts.dialog.close();
    } else if (target.closest?.('[data-mp-save]')) {
      saveAvatar(parts);
    }
  });
  // 저장하지 않고 닫으면(취소·바깥 클릭·Esc) 고른 사진을 버린다.
  document.addEventListener('close', (event) => {
    if (event.target?.matches?.('[data-mp-dialog]')) discardPending();
  }, true);

  document.addEventListener('change', async (event) => {
    const input = event.target.closest?.('[data-mp-pfile]');
    const parts = dialogParts();
    if (!input || !parts || !user) return;
    const file = input.files[0];
    input.value = '';
    showDialogMessage(parts, '');
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) { showDialogMessage(parts, '10MB 이하 이미지만 고를 수 있어요.'); return; }
    try {
      const blob = await shrinkImage(file);
      discardPending();
      pendingAvatar = { blob, url: URL.createObjectURL(blob) };
      paintPreview(parts);
    } catch (e) {
      showDialogMessage(parts, e.message || '사진을 읽지 못했어요.');
    }
  });

  /** 왼쪽 사이드바 하단의 내 계정 영역을 로그인한 사용자 정보로 채운다. */
  const renderSideAccount = () => {
    const box = document.querySelector('[data-side-account]');
    if (!box) return;
    const src = user ? readAvatar() : null;
    const avatar = box.querySelector('[data-side-avatar]');
    avatar.replaceChildren();
    if (src) {
      const img = document.createElement('img');
      img.src = src; img.alt = '';
      img.style.cssText = 'width:100%;height:100%;object-fit:cover;border-radius:50%';
      avatar.append(img);
    } else {
      avatar.textContent = user ? ([...user.nickname][0] || '') : '?';
    }
    box.querySelector('[data-side-name]').textContent = user ? user.nickname : '로그인 필요';
    box.querySelector('[data-side-sub]').textContent = user ? '내 계정' : '로그인하면 확인할 수 있어요';
  };

  /** 로그인 응답으로 세션 상태를 갱신하고 화면에 반영한다. */
  const setSession = (data) => {
    const previousId = user?.id;
    user = data?.user ?? null;
    if (user?.id !== previousId) { discardPending(); setAvatarSrc(null); }
    renderHeader();
    renderSideAccount();
    renderMyPage();
    if (user && user.id !== previousId) loadAvatar();
    document.dispatchEvent(new CustomEvent('pastport:auth-changed', { detail: { signedIn: Boolean(user), userId: user?.id ?? null } }));
  };

  /** 세션 상태를 지우고 로그아웃된 모습으로 화면에 반영한다. */
  const clearSession = () => setSession(null);

  /** 폼 아래 메시지 영역에 안내 문구를 보이거나 숨긴다. */
  const message = (form, text, kind = 'error') => {
    const box = form.querySelector('.au-msg');
    if (!box) return;
    box.textContent = text || '';
    box.hidden = !text;
    box.dataset.kind = kind;
  };

  /** 요청 중에는 제출 버튼을 잠가 중복 제출을 막는다. */
  const setBusy = (form, busy) => {
    const button = form.querySelector('button[type="submit"]');
    if (button) button.disabled = busy;
  };

  /** 비밀번호가 길이·영문·숫자·특수문자 규칙을 각각 지키는지 계산한다. */
  const passwordRules = (value) => ({
    length: value.length >= 8 && value.length <= 64,
    letter: /[A-Za-z]/.test(value),
    digit: /\d/.test(value),
    special: /[^A-Za-z\d]/.test(value) && /^[\x21-\x7E]+$/.test(value),
  });

  /** 로그인 후 관리자·판매글 등 원래 진입 화면으로 돌아간다. 미인증이면 메일 재발송을 안내한다. */
  const handleLogin = async (form) => {
    const email = form.elements.email.value.trim();
    const password = form.elements.password.value;
    if (!email || !password) return message(form, '이메일과 비밀번호를 입력해 주세요.');
    const resend = form.querySelector('[data-email-resend]');
    if (resend) resend.hidden = true;
    const current = ++sessionVersion;
    setBusy(form, true);
    try {
      const data = await session.exclusive(() => current === sessionVersion
        ? request('/api/auth/login', { body: { email, password } }) : null);
      if (current !== sessionVersion || !data) return;
      form.elements.password.value = '';
      message(form, '');
      setSession(data);
      if (returnToAdmin()) return;
      if (window.PastportReceive?.resumeAfterLogin()) return;
      if (window.PastportOwnershipSend?.resumeAfterLogin()) return;
      const listingId = new URL(window.location.href).searchParams.get('listingId');
      if (/^[1-9]\d*$/.test(listingId || '') && window.PastportPublishedListing) window.PastportPublishedListing.open(listingId);
      else if (new URL(window.location.href).searchParams.get('myListings') === '1') goto('my-listings');
      else goto('landing');
    } catch (error) {
      if (current !== sessionVersion) return;
      form.elements.password.value = '';
      message(form, error.message);
      // 이메일 인증 전이면 인증 메일을 다시 받을 수 있게 한다.
      if (resend) resend.hidden = error.code !== 'AUTH_008';
    } finally {
      if (current === sessionVersion) setBusy(form, false);
    }
  };

  // Refresh Token은 1회용이라 동시에 두 번 갱신하면 한쪽이 실패한다. 진행 중인 갱신이 있으면 그 결과를 같이 기다린다.
  let refreshing = null;
  /** 현재 쿠키로 회원을 먼저 조회하고 필요할 때만 갱신한다. 직접 로그인·로그아웃 이전의 응답은 화면에 반영하지 않는다. */
  const refresh = () => {
    if (refreshing) return refreshing;
    const current = sessionVersion;
    refreshing = session.recover(signal => fetch('/api/auth/me', { credentials: 'same-origin', cache: 'no-store', signal }), () => current === sessionVersion)
      .then(async response => {
        if (!response || current !== sessionVersion) return null;
        const payload = await response.json().catch(() => null);
        if (current !== sessionVersion) return null;
        if (!response.ok) {
          const error = new Error(payload?.message || '로그인 상태를 확인해 주세요.');
          error.status = response.status;
          throw error;
        }
        const data = { user: payload?.data ?? null };
        setSession(data);
        return data;
      }).finally(() => { refreshing = null; });
    return refreshing;
  };

  // 다른 화면(판매글 작성 등)이 fetch 대신 쓰는 공통 요청 함수. 쿠키와 CSRF 헤더를 붙이고, 401이면 한 번 갱신해 다시 보낸다.
  /** 쿠키와 CSRF 헤더를 붙여 요청하고, 401이면 세션을 한 번 갱신해 다시 보낸다. */
  const authFetch = async (url, options = {}) => {
    const current = sessionVersion;
    const send = () => {
      const headers = new Headers(options.headers || {});
      Object.entries(csrfHeaders(options.method)).forEach(([name, value]) => headers.set(name, value));
      return fetch(url, { ...options, headers, credentials: 'same-origin' });
    };
    // 새로고침 직후 세션 복구가 진행 중이면 먼저 끝나기를 기다린다.
    if (!user && refreshing) await refreshing.catch(() => {});
    if (current !== sessionVersion) throw Object.assign(new Error('로그인 상태가 변경됐어요.'), { name: 'AbortError' });
    const response = await send();
    if (current !== sessionVersion || response.status !== 401) return response;
    try {
      await refresh();
    } catch {
      return response; // 세션이 끝났으면 원래 401을 그대로 돌려준다.
    }
    return current === sessionVersion ? send() : response;
  };
  window.PastportApi = { fetch: authFetch };
  window.addEventListener('pagehide', () => { sessionVersion++; });
  window.addEventListener('pageshow', event => {
    if (!event.persisted) return;
    const current = sessionVersion;
    Promise.resolve(refreshing).catch(() => {}).then(() => {
      if (current !== sessionVersion) return;
      return refresh().catch(() => { if (current === sessionVersion) clearSession(); });
    });
  });

  /** 회원가입 폼을 검증하고 가입한 뒤 로그인 화면으로 안내한다. */
  const handleSignup = async (form) => {
    const email = form.elements.email.value.trim();
    const password = form.elements.password.value;
    const nickname = form.elements.nickname.value.trim();
    if (!email || !password || !nickname) return message(form, '필수 항목을 모두 입력해 주세요.');
    if (!Object.values(passwordRules(password)).every(Boolean)) {
      return message(form, '비밀번호는 8자 이상이며 영문, 숫자, 특수문자를 모두 포함해야 해요.');
    }
    if (password !== form.elements.passwordConfirm.value) return message(form, '비밀번호 확인이 일치하지 않아요.');
    if (![...form.querySelectorAll('[data-agree-required]')].every((box) => box.checked)) {
      return message(form, '필수 약관에 동의해 주세요.');
    }
    setBusy(form, true);
    try {
      const signup = await request('/api/auth/signup', { body: { email, password, nickname } });
      form.reset();
      showPasswordMatch(form);
      message(form, '');
      const loginForm = document.querySelector('[data-auth-form="login"]');
      if (loginForm) {
        loginForm.elements.email.value = email;
        message(loginForm, `가입이 완료됐어요. 메일로 보낸 인증 링크를 누른 뒤 로그인해 주세요. ${signup?.verificationTtlMinutes ?? 30}분 안에 인증하지 않으면 가입이 취소돼요.`, 'info');
      }
      goto('auth');
    } catch (error) {
      const detail = error.fieldErrors?.[0]?.message;
      message(form, detail || error.message);
    } finally {
      setBusy(form, false);
    }
  };

  document.addEventListener('submit', (event) => {
    const form = event.target.closest?.('[data-auth-form]');
    if (!form) return;
    event.preventDefault();
    if (form.dataset.authForm === 'login') handleLogin(form);
    else if (form.dataset.authForm === 'signup') handleSignup(form);
  });

  // 비밀번호 확인 칸 아래에 일치 여부를 바로 보여준다. 확인 칸이 비어 있으면 아무것도 보여주지 않는다.
  /** 비밀번호 확인 칸 아래에 일치 여부를 보여준다. */
  const showPasswordMatch = (form) => {
    const { password, passwordConfirm } = form.elements;
    const box = form.querySelector('[data-match]');
    const match = password.value === passwordConfirm.value;
    box.textContent = !passwordConfirm.value ? '' : match ? '비밀번호가 일치해요.' : '비밀번호가 일치하지 않아요.';
    box.classList.toggle('ok', !!passwordConfirm.value && match);
    box.classList.toggle('bad', !!passwordConfirm.value && !match);
  };

  document.addEventListener('input', (event) => {
    const input = event.target;
    const form = input.closest?.('[data-auth-form="signup"]');
    if (!form) return;
    if (input.name === 'password' || input.name === 'passwordConfirm') showPasswordMatch(form);
    if (input.name !== 'password') return;
    const rules = passwordRules(input.value);
    form.querySelectorAll('[data-rule]').forEach((item) => item.classList.toggle('ok', rules[item.dataset.rule]));
  });

  document.addEventListener('change', (event) => {
    const input = event.target;
    if (!input.matches?.('[data-agree-all]')) return;
    input.closest('form').querySelectorAll('[data-agree-required]').forEach((box) => { box.checked = input.checked; });
  });

  document.addEventListener('click', async (event) => {
    if (!event.target.closest?.('[data-email-resend]')) return;
    const form = event.target.closest('form');
    const email = form.elements.email.value.trim();
    if (!email) return message(form, '이메일을 입력해 주세요.');
    try {
      await request('/api/auth/email/resend', { body: { email } });
      message(form, '인증 메일을 보냈어요. 메일의 링크를 누른 뒤 다시 로그인해 주세요.', 'info');
    } catch (error) {
      message(form, error.fieldErrors?.[0]?.message || error.message);
    }
  });
  document.addEventListener('click', (event) => {
    const account = document.querySelector('.landing-account.is-signed-in');
    if (!account) return;
    const toggle = event.target.closest?.('[data-auth-menu-toggle]');
    const menu = account.querySelector('[data-auth-menu]');
    if (toggle) {
      const open = menu.hidden;
      menu.hidden = !open;
      toggle.setAttribute('aria-expanded', String(open));
      return;
    }
    if (!event.target.closest?.('.landing-account')) {
      menu.hidden = true;
      account.querySelector('[data-auth-menu-toggle]')?.setAttribute('aria-expanded', 'false');
    }
  });
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const account = document.querySelector('.landing-account.is-signed-in');
    const menu = account?.querySelector('[data-auth-menu]');
    if (!menu || menu.hidden) return;
    menu.hidden = true;
    const toggle = account.querySelector('[data-auth-menu-toggle]');
    toggle?.setAttribute('aria-expanded', 'false');
    toggle?.focus();
  });
  document.addEventListener('click', async (event) => {
    if (!event.target.closest?.('[data-auth-logout]')) return;
    document.dispatchEvent(new CustomEvent('pastport:logout', { detail: {} }));
    const current = ++sessionVersion;
    try {
      await session.exclusive(async () => {
        if (current !== sessionVersion) return null;
        await request('/api/auth/logout');
        return true; // 성공한 204와 요청하지 않은 작업(null)을 구분한다.
      });
    } catch { /* 서버 응답과 관계없이 화면에서는 로그아웃 처리 */ }
    if (current !== sessionVersion) return;
    clearSession();
    goto('landing');
  });

  // 로그인·회원가입 화면이 같이 쓰는 일러스트는 한 파일에서 가져와 자리표시자를 바꿔 넣는다.
  const artHtml = fetch('/pages/auth-art.html').then((response) => response.text());
  document.addEventListener('pastport:page-loaded', async (event) => {
    const id = event.detail?.id;
    if (id === 'mypage') renderMyPage();
    const slot = ['auth', 'signup'].includes(id) && document.querySelector(`#${id} [data-auth-art]`);
    if (slot) slot.outerHTML = await artHtml.catch(() => '');
  });

  // 로그인한 뒤 뒤로 가기·앞으로 가기로 로그인·회원가입 화면이 열리면 안내하고 메인 화면으로 보낸다.
  // 기록이 늘지 않도록 현재 기록의 화면 이름을 먼저 홈으로 바꿔, 뒤로 가기를 다시 눌러도 이 화면에 갇히지 않게 한다.
  document.addEventListener('pastport:page-shown', (event) => {
    if (!user || !['auth', 'signup'].includes(event.detail?.id)) return;
    window.history.replaceState({ ...window.history.state, page: 'landing' }, '', window.location.href);
    goto('landing');
    alert('이미 로그인되어 있습니다.');
  });

  // 새로고침 후 HttpOnly refresh 쿠키가 남아 있으면 세션을 복구한다.
  const startupVersion = sessionVersion;
  refresh().then(() => { if (startupVersion === sessionVersion) returnToAdmin(); }).catch(() => {
    if (startupVersion === sessionVersion && adminReturn) { clearSession(); goto('auth'); }
  });

  return { get user() { return user; } };
})();
