const pageNames={home:'제품 여권',passport:'제품 여권',register:'새 제품 등록',documents:'문서 보관함',ai:'AI 이력 검색',market:'중고 거래',sell:'판매글 작성',auth:'로그인',signup:'회원가입',mypage:'마이페이지',docmask:'문서 마스킹 확인',detail:'판매글 상세',deals:'내 거래',receive:'이전 받기',trade:'거래 · 소유권 이전'};
pageNames.landing='홈';
pageNames.guide='이용 가이드';
pageNames['my-listings']='내 거래';

const pendingPages = new Set(['ai', 'trade']);
let historyPosition = Number.isSafeInteger(window.history.state?.pastportIndex) ? window.history.state.pastportIndex : 0;
let activeHistoryUrl = window.location.href;
let restoringHistory = false;

// mode: 'push'(기본) 새 기록 추가 / 'replace' 현재 기록 교체(새로고침 복원) / 'pop' 뒤로·앞으로 가기라 기록을 건드리지 않음.
const show=(name,mode='push')=>{
  if (pendingPages.has(name)) {
    alert(`[${pageNames[name] || name}] 기능은 현재 준비 중입니다.\n현재 구현된 기능(내 제품, 새 제품 등록, 문서 보관함, 문서 마스킹, 중고 거래, 판매글 작성, 로그인)을 이용해 주세요.`);
    return false;
  }
  if(window.PastportListingManagement?.beforeLeave?.() === false)return false;
  if(document.activeElement instanceof HTMLElement)document.activeElement.blur();
  const target=name;
  document.querySelectorAll('.page').forEach(page=>page.classList.toggle('active',page.id===target));
  document.querySelectorAll('[data-page]').forEach(button=>button.classList.toggle('active',button.dataset.page===name));
  document.querySelector('#crumb').textContent=pageNames[name];
  document.querySelector('#side').classList.remove('open');
  window.scrollTo(0,0);
  try{sessionStorage.setItem('pastport.page',name);}catch{}
  if(mode!=='pop'){
    // 주소(검색 조건 등)는 그대로 두고 화면 이름만 기록에 남긴다. 같은 화면을 다시 누르면 기록을 늘리지 않는다.
    if(mode==='replace'||window.history.state?.page===name)window.history.replaceState({...window.history.state,page:name,pastportIndex:historyPosition},'',window.location.href);
    else window.history.pushState({page:name,pastportIndex:++historyPosition},'',window.location.href);
  }
  document.dispatchEvent(new CustomEvent('pastport:page-shown',{detail:{id:target}}));
  activeHistoryUrl=window.location.href;
  return true;
};

// 새로고침하면 보던 화면을 그대로 복원한다. 화면 HTML이 채워진 뒤에 열어야 각 화면의 로딩 로직이 정상 동작한다.
// 주소에 판매글·작성 대상이 있으면 해당 화면이 직접 복원하므로 건드리지 않는다.
const restorePage=(()=>{
  let saved=null;
  try{saved=sessionStorage.getItem('pastport.page');}catch{}
  const params=new URL(window.location.href).searchParams;
  const deepLink=['listingId','draftProduct','market'].some(key=>params.has(key))||params.get('returnTo')==='admin';
  if(!saved||deepLink||saved==='landing'||pendingPages.has(saved)||!pageNames[saved])return null;
  return saved;
})();
if(restorePage)document.addEventListener('pastport:page-loaded',event=>{if(event.detail.id===restorePage)show(restorePage,'replace');},{once:false});

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
    const total = document.querySelector('[data-landing-total]');
    if (total) total.textContent = new Intl.NumberFormat('ko-KR').format(payload.data.page?.totalElements ?? items.length);
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

// 랜딩 정보 섹션은 화면에 들어올 때 한 번 부드럽게 나타난다. 관찰자를 쓸 수 없으면 그냥 보여 준다.
function revealLandingSections() {
  const targets = document.querySelectorAll('#landing .lpx-reveal');
  if (!targets.length || !('IntersectionObserver' in window)) return;
  document.querySelector('#landing').classList.add('lpx-motion');
  const observer = new IntersectionObserver((entries) => entries.forEach((entry) => {
    if (entry.isIntersecting) { entry.target.classList.add('is-in'); observer.unobserve(entry.target); }
  }), { rootMargin: '0px 0px -8% 0px' });
  targets.forEach((target) => observer.observe(target));
}

document.addEventListener('pastport:page-loaded', (e) => { if (e.detail.id === 'landing') { loadLandingListings(); revealLandingSections(); } });
document.addEventListener('pastport:page-shown', (e) => { if (e.detail.id === 'landing') loadLandingListings(); });

// 제품 검색: 검색어와 카테고리를 주소에 담아 마켓을 열면, 마켓이 같은 조건으로 바로 조회한다.
const goSearch=(keyword,category)=>{
  const params=new URLSearchParams({market:'1'});
  if(keyword)params.set('keyword',keyword);
  if(category)params.set('category',category);
  window.location.href='/?'+params.toString();
};
document.addEventListener('submit',(event)=>{
  const form=event.target.closest?.('[data-landing-search]');
  if(!form)return;
  event.preventDefault();
  goSearch(form.elements.keyword.value.trim(),form.elements.category?.value||'');
});
document.addEventListener('click',(event)=>{
  const submit=event.target.closest?.('[data-landing-search-submit]');
  if(submit)submit.closest('form').requestSubmit();
  const chip=event.target.closest?.('[data-landing-keyword]');
  if(chip)goSearch(chip.dataset.landingKeyword,'');
});
document.addEventListener('keydown',(event)=>{
  if(event.key==='Enter'&&event.target.matches?.('[data-landing-search-submit]'))event.target.closest('form').requestSubmit();
});

document.addEventListener('click',(event)=>{const target=event.target.closest('[data-page]');if(target)show(target.dataset.page)});
// 브라우저 뒤로가기·앞으로가기: 기록에 담긴 화면을 다시 연다. 첫 기록에도 화면 이름을 심어 둔다.
window.history.replaceState({...window.history.state,page:window.history.state?.page||'landing',pastportIndex:historyPosition},'',window.location.href);
window.addEventListener('popstate',(event)=>{
  if(restoringHistory){restoringHistory=false;return;}
  const page=event.state?.page;
  if(!page||!pageNames[page]||pendingPages.has(page))return;
  const nextPosition=event.state?.pastportIndex;
  if(show(page,'pop')===false){
    // 이탈을 취소하면 URL과 방문 기록도 원래 위치로 되돌려, 다음 뒤로가기의 방향을 유지한다.
    if(Number.isSafeInteger(nextPosition)&&nextPosition!==historyPosition){restoringHistory=true;window.history.go(historyPosition-nextPosition);}
    else window.history.replaceState({...window.history.state,page:document.querySelector('.page.active')?.id,pastportIndex:historyPosition},'',activeHistoryUrl);
  }else if(Number.isSafeInteger(nextPosition))historyPosition=nextPosition;
});
