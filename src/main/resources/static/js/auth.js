// 로그인·회원가입 화면 연동. 화면 HTML은 pages.js가 나중에 채워 넣으므로 document에 이벤트를 위임한다.
// Access/Refresh Token은 모두 HttpOnly 쿠키라 JS에서 접근할 수 없다. 쓰기 요청은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내 CSRF를 검사받는다.
const auth = (() => {
  const EMAIL_KEY = 'savedEmail';
  let user = null;

  /** 저장소를 쓸 수 없는 환경에서도 예외 없이 동작하는 localStorage 래퍼. */
  const safeStorage = {
    get: (key) => { try { return localStorage.getItem(key); } catch { return null; } },
    set: (key, value) => { try { localStorage.setItem(key, value); } catch { /* 저장소를 못 쓰는 환경 */ } },
    remove: (key) => { try { localStorage.removeItem(key); } catch { /* 저장소를 못 쓰는 환경 */ } },
  };

  /** 쓰기 요청이면 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 만들어 돌려준다. */
  const csrfHeaders = (method) => {
    if (!method || ['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())) return {};
    const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
    return match ? { 'X-XSRF-TOKEN': decodeURIComponent(match[1]) } : {};
  };

  /** 화면 이동 버튼(data-page)을 눌러 해당 화면으로 이동한다. */
  const goto = (page) => document.querySelector(`[data-page="${page}"]`)?.click();

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
    if (user) {
      const name = document.createElement('button');
      name.type = 'button';
      name.dataset.page = 'mypage';
      name.textContent = `${user.nickname}님`;
      const logout = document.createElement('button');
      logout.type = 'button';
      logout.dataset.authLogout = '';
      logout.textContent = '로그아웃';
      box.append(name, document.createTextNode(' '), logout);
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
  };

  /** 로그인 응답으로 세션 상태를 갱신하고 화면에 반영한다. */
  const setSession = (data) => {
    user = data?.user ?? null;
    renderHeader();
    renderMyPage();
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

  /** 로그인 폼을 검증하고 로그인한 뒤 홈으로 이동한다. 미인증이면 메일 재발송을 안내한다. */
  const handleLogin = async (form) => {
    const email = form.elements.email.value.trim();
    const password = form.elements.password.value;
    if (!email || !password) return message(form, '이메일과 비밀번호를 입력해 주세요.');
    const resend = form.querySelector('[data-email-resend]');
    if (resend) resend.hidden = true;
    setBusy(form, true);
    try {
      const data = await request('/api/auth/login', { body: { email, password } });
      if (form.elements.saveEmail.checked) safeStorage.set(EMAIL_KEY, email);
      else safeStorage.remove(EMAIL_KEY);
      form.elements.password.value = '';
      message(form, '');
      setSession(data);
      const listingId = new URL(window.location.href).searchParams.get('listingId');
      if (/^[1-9]\d*$/.test(listingId || '') && window.PastportPublishedListing) window.PastportPublishedListing.open(listingId);
      else goto('landing');
    } catch (error) {
      form.elements.password.value = '';
      message(form, error.message);
      // 이메일 인증 전이면 인증 메일을 다시 받을 수 있게 한다.
      if (resend) resend.hidden = error.code !== 'AUTH_008';
    } finally {
      setBusy(form, false);
    }
  };

  // Refresh Token은 1회용이라 동시에 두 번 갱신하면 한쪽이 실패한다. 진행 중인 갱신이 있으면 그 결과를 같이 기다린다.
  let refreshing = null;
  /** Refresh Token 쿠키로 세션을 갱신한다. 동시에 여러 번 불려도 한 번만 요청한다. */
  const refresh = () => {
    refreshing ??= request('/api/auth/refresh')
      .then((data) => { setSession(data); return data; })
      .finally(() => { refreshing = null; });
    return refreshing;
  };

  // 다른 화면(판매글 작성 등)이 fetch 대신 쓰는 공통 요청 함수. 쿠키와 CSRF 헤더를 붙이고, 401이면 한 번 갱신해 다시 보낸다.
  /** 쿠키와 CSRF 헤더를 붙여 요청하고, 401이면 세션을 한 번 갱신해 다시 보낸다. */
  const authFetch = async (url, options = {}) => {
    const send = () => {
      const headers = new Headers(options.headers || {});
      Object.entries(csrfHeaders(options.method)).forEach(([name, value]) => headers.set(name, value));
      return fetch(url, { ...options, headers, credentials: 'same-origin' });
    };
    // 새로고침 직후 세션 복구가 진행 중이면 먼저 끝나기를 기다린다.
    if (!user && refreshing) await refreshing.catch(() => {});
    const response = await send();
    if (response.status !== 401) return response;
    try {
      await refresh();
    } catch {
      return response; // 세션이 끝났으면 원래 401을 그대로 돌려준다.
    }
    return send();
  };
  window.PastportApi = { fetch: authFetch };

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
  document.addEventListener('click', async (event) => {
    if (!event.target.closest?.('[data-auth-logout]')) return;
    try {
      await request('/api/auth/logout');
    } catch { /* 서버 응답과 관계없이 화면에서는 로그아웃 처리 */ }
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

  // 로그인 화면이 로드되면 저장된 이메일을 채운다.
  const restoreEmail = new MutationObserver(() => {
    const form = document.querySelector('[data-auth-form="login"]');
    if (!form) return;
    const saved = safeStorage.get(EMAIL_KEY);
    if (saved && !form.elements.email.value) {
      form.elements.email.value = saved;
      form.elements.saveEmail.checked = true;
    }
    restoreEmail.disconnect();
  });
  restoreEmail.observe(document.body, { childList: true, subtree: true });

  // 새로고침 후 HttpOnly refresh 쿠키가 남아 있으면 세션을 복구한다.
  refresh().catch(() => {});

  return { get user() { return user; } };
})();
