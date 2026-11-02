// 각 화면의 HTML은 /pages/{id}.html 에 있고, 여기서 같은 id의 section에 채워 넣는다. (trade는 js/trade.js에서 렌더링)
['landing', 'home', 'passport', 'register', 'documents', 'market', 'my-listings', 'sell', 'auth', 'signup', 'docmask', 'detail', 'deals', 'receive', 'mypage'].forEach((id) => {
  fetch(`/pages/${id}.html`, { cache: 'no-cache' })
    .then((response) => {
      if (!response.ok) throw new Error(`${response.status}`);
      return response.text();
    })
    .then((html) => {
      document.querySelector(`#${id}`).innerHTML = html;
      document.dispatchEvent(new CustomEvent('pastport:page-loaded', { detail: { id } }));
    })
    .catch((error) => console.error(`페이지를 불러오지 못했습니다: ${id}`, error));
});
