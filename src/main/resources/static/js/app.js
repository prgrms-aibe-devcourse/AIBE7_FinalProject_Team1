const pageNames={home:'내 제품',passport:'제품 여권',register:'새 제품 등록',documents:'문서 보관함',ai:'AI 이력 검색',market:'중고 거래',sell:'판매글 작성',auth:'로그인',signup:'회원가입',mypage:'마이페이지',docmask:'문서 마스킹 확인',detail:'판매글 상세',deals:'내 거래',receive:'이전 받기',trade:'거래 · 소유권 이전'};
pageNames.landing='홈';

const pendingPages = new Set(['ai', 'trade']);

const show=(name)=>{
  if (pendingPages.has(name)) {
    alert(`[${pageNames[name] || name}] 기능은 현재 준비 중입니다.\n현재 구현된 기능(내 제품, 새 제품 등록, 문서 보관함, 문서 마스킹, 중고 거래, 판매글 작성, 로그인)을 이용해 주세요.`);
    return;
  }
  if(document.activeElement instanceof HTMLElement)document.activeElement.blur();
  const target=name;
  document.querySelectorAll('.page').forEach(page=>page.classList.toggle('active',page.id===target));
  document.querySelectorAll('[data-page]').forEach(button=>button.classList.toggle('active',button.dataset.page===name));
  document.querySelector('#crumb').textContent=pageNames[name];
  document.querySelector('#side-crumb').textContent=pageNames[name];
  document.querySelector('#side').classList.remove('open');
  window.scrollTo(0,0);
  document.dispatchEvent(new CustomEvent('pastport:page-shown',{detail:{id:target}}));
};

async function loadLandingListings() {
  const container = document.querySelector('[data-landing-items]');
  if (!container) return;
  try {
    const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
    const response = await fetcher('/api/v1/listings?page=0&size=4&sort=LATEST', { credentials: 'same-origin', cache: 'no-store' });
    const payload = await response.json().catch(() => null);
    if (!response.ok || !payload?.data?.items) {
      container.innerHTML = '<p class="sub" style="grid-column:1/-1;text-align:center;padding:30px 0;color:var(--muted)">판매글을 불러오지 못했습니다.</p>';
      return;
    }
    const items = payload.data.items;
    if (items.length === 0) {
      container.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:40px 0;color:var(--muted)"><p style="font-size:15px;margin-bottom:8px">현재 등록된 판매글이 없습니다.</p><button type="button" class="sc-btn primary" data-page="sell">첫 판매글 작성하기</button></div>';
      return;
    }
    container.replaceChildren(...items.map(item => {
      const card = document.createElement('article');
      card.className = 'mk-card';
      const link = document.createElement('a');
      link.className = 'mk-card-link';
      link.href = '#';
      link.setAttribute('aria-label', item.title + ' 상세 보기');
      link.addEventListener('click', (e) => {
        e.preventDefault();
        if (window.PastportPublishedListing?.open) {
          window.PastportPublishedListing.open(String(item.listingId));
        } else if (typeof show === 'function') {
          show('detail');
        }
      });

      const thumb = document.createElement('div');
      thumb.className = 'mk-thumb tone-silver';
      if (item.thumbnailUrl) {
        const img = document.createElement('img');
        img.src = item.thumbnailUrl;
        img.alt = item.title;
        img.loading = 'lazy';
        thumb.appendChild(img);
      } else {
        const span = document.createElement('span');
        span.className = 'laptop';
        thumb.appendChild(span);
      }

      const body = document.createElement('div');
      body.className = 'mk-body';
      const price = document.createElement('b');
      price.className = 'price';
      price.textContent = new Intl.NumberFormat('ko-KR').format(item.price) + '원';
      const title = document.createElement('h3');
      title.textContent = item.title;
      const meta = document.createElement('p');
      meta.className = 'mk-meta';
      meta.textContent = [item.manufacturer, item.modelName, '상태 ' + item.conditionGrade + '등급'].filter(Boolean).join(' · ');

      body.append(price, title, meta);
      link.append(thumb, body);
      card.append(link);
      return card;
    }));
  } catch (e) {
    container.innerHTML = '<p class="sub" style="grid-column:1/-1;text-align:center;padding:30px 0;color:var(--muted)">판매글을 불러오지 못했습니다.</p>';
  }
}

document.addEventListener('pastport:page-loaded', (e) => { if (e.detail.id === 'landing') loadLandingListings(); });
document.addEventListener('pastport:page-shown', (e) => { if (e.detail.id === 'landing') loadLandingListings(); });

document.addEventListener('click',(event)=>{const target=event.target.closest('[data-page]');if(target)show(target.dataset.page)});
document.querySelector('#menu').addEventListener('click',()=>document.querySelector('#side').classList.toggle('open'));
