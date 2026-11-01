(() => {
  'use strict';
  const lockName = 'pastport.auth.cookies';
  let pending = Promise.resolve();
  let expired = false;

  /** 같은 출처의 로그인·로그아웃·갱신 응답까지 직렬화한다. 토큰·회원 정보를 탭 사이에 저장하거나 전달하지 않는다. */
  const serialize = task => {
    const run = async () => {
      const locks = window.navigator?.locks;
      if (!locks?.request) return task(false);
      let started = false;
      try {
        return await locks.request(lockName, { mode: 'exclusive' }, () => {
          started = true;
          return task(true);
        });
      } catch (error) {
        // 잠금 자체가 금지된 환경만 축소 동작한다. 실행한 인증 요청은 오류가 나도 반복하지 않는다.
        if (!started && ['SecurityError', 'NotSupportedError'].includes(error.name)) return task(false);
        throw error;
      }
    };
    const result = pending.then(run, run);
    pending = result.catch(() => {});
    return result;
  };

  const exclusive = task => serialize(async coordinated => {
    const result = await task(coordinated);
    expired = false;
    return result;
  });

  /** check는 부작용 없는 GET이다. 잠금 안에서 먼저 재검사해 다른 탭이 복구했다면 회전 쿠키를 다시 쓰지 않는다. */
  const recover = (check, current = () => true) => serialize(async coordinated => {
    if (!current()) return null;
    let response = await check();
    if (!current()) return null;
    if (response.status !== 401) { expired = false; return response; }
    // 만료가 확인된 세션은 후속 401마다 다시 회전하지 않는다. 다른 탭의 복구 여부는 위 GET으로 계속 확인한다.
    if (expired) return response;
    // 공유 잠금이 없으면 자동 회전 대신 현재 쿠키 재검사와 직접 로그인으로 복구한다.
    if (!coordinated) return response;
    const seed = await window.fetch('/', { credentials: 'same-origin', cache: 'no-store' });
    if (!current()) return null;
    const token = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
    if (!seed.ok || !token) return response;
    try {
      // 시작한 회전은 화면 이탈로 취소하지 않는다. Set-Cookie 수신까지 잠금을 유지한다.
      await window.fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'X-XSRF-TOKEN': decodeURIComponent(token[1]) } });
    } catch { /* 갱신 응답을 잃어도 현재 쿠키로 읽기를 한 번 재검사한다. */ }
    if (!current()) return null;
    response = await check();
    expired = response.status === 401;
    return current() ? response : null;
  });

  window.PastportSession = Object.freeze({ exclusive, recover });
})();
