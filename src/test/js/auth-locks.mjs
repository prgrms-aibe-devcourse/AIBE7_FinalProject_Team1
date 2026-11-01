/** 여러 VM 문서가 공유하는 Web Locks 대역이다. 실제 브라우저 잠금·쿠키 동작은 별도 검증한다. */
export function lockManager() {
  let pending = Promise.resolve();
  const requests = [];
  return {
    requests,
    request(name, options, callback) {
      requests.push({ name, options });
      const result = pending.then(() => callback({ name, mode: options.mode }));
      pending = result.catch(() => {});
      return result;
    }
  };
}
