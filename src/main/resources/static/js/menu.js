// 전체메뉴(메가 메뉴): 상단 네비게이션의 그룹을 읽어 한 번에 펼쳐 보여준다.
(() => {
  const toggle = document.querySelector('#nav-all');
  const mega = document.querySelector('#mega');
  const grid = document.querySelector('#mega-grid');
  if (!toggle || !mega || !grid) return;

  const groups = [...document.querySelectorAll('.landing-nav .nav-item')].filter((item) => item.querySelector('.nav-drop'));
  grid.append(
    ...groups.map((item) => {
      const column = document.createElement('div');
      column.className = 'mega-col';
      const title = item.querySelector(':scope > button').cloneNode(true);
      title.className = 'mega-title';
      column.append(title, ...[...item.querySelectorAll('.nav-drop button')].map((button) => button.cloneNode(true)));
      return column;
    })
  );

  // 이용 가이드는 하위 메뉴가 없어 위 묶음에 포함되지 않으므로, 전체메뉴에는 마이페이지 앞에 한 묶음으로 추가한다.
  const guideColumn = document.createElement('div');
  guideColumn.className = 'mega-col';
  const guideTitle = document.createElement('button');
  guideTitle.type = 'button'; guideTitle.className = 'mega-title'; guideTitle.dataset.page = 'guide'; guideTitle.textContent = '이용 가이드';
  const guideItem = document.createElement('button');
  guideItem.type = 'button'; guideItem.dataset.page = 'guide';
  guideItem.innerHTML = '<b>서비스 이용 방법</b><small>등록부터 거래·소유권 이전까지 순서대로</small>';
  guideColumn.append(guideTitle, guideItem);
  grid.append(guideColumn);

  // 마이페이지는 상단 메뉴에는 두지 않고(계정 영역에서 이동) 전체메뉴에만 한 묶음으로 추가한다.
  const mypageColumn = document.createElement('div');
  mypageColumn.className = 'mega-col';
  const mypageTitle = document.createElement('button');
  mypageTitle.type = 'button'; mypageTitle.className = 'mega-title'; mypageTitle.dataset.page = 'mypage'; mypageTitle.textContent = '마이페이지';
  const mypageItem = document.createElement('button');
  mypageItem.type = 'button'; mypageItem.dataset.page = 'mypage';
  mypageItem.innerHTML = '<b>내 정보 · 활동 요약</b><small>문서·판매글·제품을 한눈에 확인</small>';
  mypageColumn.append(mypageTitle, mypageItem);
  grid.append(mypageColumn);

  const side = document.querySelector('#side');
  const sectionNav = document.querySelector('[data-section-nav]');
  const sectionTitle = document.querySelector('#side-section');
  const sectionToggle = document.querySelector('#menu');
  const alignSectionNav = () => {
    const header = document.querySelector('.site-header');
    if (side && header) side.style.top = Math.max(0, header.getBoundingClientRect().bottom) + 'px';
  };
  const belongsTo = (button, page) => button.dataset.page === page || (button.dataset.navRelated || '').split(/\s+/).includes(page);
  const markCurrent = (button, page) => {
    const current = belongsTo(button, page);
    button.classList.toggle('active', current);
    if (current) button.setAttribute('aria-current', button.dataset.page === page ? 'page' : 'location');
    else button.removeAttribute('aria-current');
  };

  function closeSectionNav(returnFocus = false) {
    const wasOpen = side?.classList.contains('open');
    const restoreFocus = wasOpen && (returnFocus || side.contains(document.activeElement));
    side?.classList.remove('open');
    sectionToggle?.setAttribute('aria-expanded', 'false');
    if (restoreFocus) sectionToggle?.focus({ preventScroll: true });
  }

  /** 상단 하위 메뉴를 그대로 사용해 명칭·순서·이동 대상이 별도로 어긋나지 않게 한다. */
  function renderSectionNav(page) {
    closeSectionNav();
    alignSectionNav();
    const selected = groups.find(item => [...item.querySelectorAll('.nav-drop button')].some(button => belongsTo(button, page)));
    for (const item of groups) {
      item.querySelector(':scope > button').classList.toggle('active', item === selected);
      item.querySelectorAll('.nav-drop button').forEach(button => markCurrent(button, page));
    }
    grid.querySelectorAll('button').forEach(button => markCurrent(button, page));
    grid.querySelectorAll('.mega-title').forEach(button => {
      button.classList.toggle('active', Boolean(selected) && button.dataset.page === selected.querySelector(':scope > button').dataset.page);
      button.removeAttribute('aria-current');
    });
    if (!side || !sectionNav || !sectionTitle) return;
    side.hidden = !selected;
    side.closest('.app')?.classList.toggle('has-section-nav', Boolean(selected));
    sectionNav.replaceChildren();
    const title = selected?.querySelector(':scope > button').textContent.trim() || '';
    sectionTitle.textContent = title;
    sectionNav.setAttribute('aria-label', title + ' 하위 메뉴');
    if (sectionToggle) sectionToggle.textContent = title ? title + ' 메뉴' : '현재 메뉴 열기';
    if (!selected) return;
    for (const source of selected.querySelectorAll('.nav-drop button')) {
      const button = document.createElement('button');
      button.type = 'button'; button.dataset.page = source.dataset.page;
      button.textContent = (source.querySelector('b') || source).textContent.trim();
      if (source.dataset.navRelated) button.dataset.navRelated = source.dataset.navRelated;
      markCurrent(button, page);
      sectionNav.append(button);
    }
  }

  /** 전체 메뉴와 개별 하위 메뉴가 동시에 펼쳐지지 않도록 한다. */
  const setOpen = (open) => {
    if (open) { closeDropdown(); closeSectionNav(); }
    mega.hidden = !open;
    toggle.setAttribute('aria-expanded', String(open));
    toggle.classList.toggle('open', open);
  };

  let activeGroup = null;
  let openedByHover = false;
  /** 숨길 하위 메뉴 안에 키보드 포커스가 있으면 먼저 상위 버튼으로 돌려준다. */
  const closeDropdown = () => {
    if (!activeGroup) return;
    const group = activeGroup;
    activeGroup = null;
    openedByHover = false;
    const trigger = group.querySelector(':scope > button');
    if (group.querySelector('.nav-drop').contains(document.activeElement)) trigger.focus({ preventScroll: true });
    group.removeAttribute('data-submenu-open');
    trigger.setAttribute('aria-expanded', 'false');
  };

  /** 가로 스크롤 밖에 메뉴를 고정한다. 아래 공간이 부족하면 위로 열고, 낮은 화면에서도 스크롤 공간을 확보한다. */
  const openDropdown = (item, hover = false) => {
    closeSectionNav();
    closeDropdown();
    setOpen(false);
    const trigger = item.querySelector(':scope > button');
    const dropdown = item.querySelector('.nav-drop');
    const anchor = trigger.getBoundingClientRect();
    const viewportWidth = document.documentElement.clientWidth;
    const viewportHeight = document.documentElement.clientHeight;
    const edge = Math.min(8, viewportHeight / 4);
    const viewportSpace = Math.max(1, viewportHeight - edge * 2);
    dropdown.style.maxHeight = 'none';
    item.setAttribute('data-submenu-open', '');
    const naturalHeight = dropdown.scrollHeight + 2;
    const below = Math.max(0, viewportHeight - edge - anchor.bottom);
    const above = Math.max(0, Math.min(viewportHeight - edge, anchor.top) - edge);
    const minimum = Math.min(100, naturalHeight, viewportSpace);
    const opensAbove = below < minimum && above > below;
    let available = Math.min(viewportSpace, opensAbove ? above : below);
    let top = opensAbove ? anchor.top - Math.min(naturalHeight, available) : anchor.bottom;
    if (available < minimum) { available = viewportSpace; top = edge; }
    top = Math.max(edge, Math.min(top, viewportHeight - edge - Math.min(naturalHeight, available)));
    dropdown.style.top = `${top}px`;
    dropdown.style.maxHeight = `${available}px`;
    dropdown.style.left = `${Math.max(8, Math.min(anchor.left, viewportWidth - dropdown.getBoundingClientRect().width - 8))}px`;
    trigger.setAttribute('aria-expanded', 'true');
    activeGroup = item;
    openedByHover = hover;
  };

  groups.forEach((item, index) => {
    const trigger = item.querySelector(':scope > button');
    const dropdown = item.querySelector('.nav-drop');
    dropdown.id ||= `nav-submenu-${index}`;
    trigger.type = 'button';
    trigger.setAttribute('aria-expanded', 'false');
    trigger.setAttribute('aria-controls', dropdown.id);
    trigger.addEventListener('click', (event) => {
      // 상위 항목은 메뉴를 열고, 실제 페이지 이동은 하위 항목에서 처리한다.
      event.preventDefault();
      event.stopPropagation();
      if (activeGroup === item && !openedByHover) closeDropdown();
      else openDropdown(item);
    });
    item.addEventListener('pointerenter', (event) => {
      if (event.pointerType === 'mouse' && activeGroup !== item) openDropdown(item, true);
    });
    item.addEventListener('pointerleave', () => {
      if (activeGroup === item && !item.contains(document.activeElement)) closeDropdown();
    });
    item.addEventListener('focusout', (event) => {
      if (activeGroup === item && !item.contains(event.relatedTarget)) closeDropdown();
    });
    trigger.addEventListener('keydown', (event) => {
      if (event.key !== 'ArrowDown') return;
      event.preventDefault();
      openDropdown(item);
      dropdown.querySelector('button')?.focus();
    });
    dropdown.addEventListener('click', (event) => {
      if (event.target.closest('[data-page]')) closeDropdown();
    });
  });

  document.querySelector('.landing-nav').addEventListener('scroll', closeDropdown);
  window.addEventListener('resize', () => {
    closeDropdown();
    alignSectionNav();
    if (document.documentElement.clientWidth > 780) closeSectionNav();
  });
  window.addEventListener('scroll', closeDropdown);
  document.addEventListener('pastport:page-shown', event => { closeDropdown(); renderSectionNav(event.detail.id); });

  sectionToggle?.addEventListener('click', () => {
    if (!side || side.hidden) return;
    if (side.classList.contains('open')) { closeSectionNav(true); return; }
    closeDropdown(); setOpen(false);
    side.classList.add('open'); sectionToggle.setAttribute('aria-expanded', 'true');
    sectionNav?.querySelector('button')?.focus({ preventScroll: true });
  });
  document.querySelector('[data-section-nav-close]')?.addEventListener('click', () => closeSectionNav(true));

  toggle.addEventListener('click', () => setOpen(mega.hidden));
  mega.addEventListener('click', (event) => {
    if (event.target.closest('[data-page]')) setOpen(false);
  });
  document.addEventListener('click', (event) => {
    if (side?.classList.contains('open') && !side.contains(event.target) && !sectionToggle?.contains(event.target)) closeSectionNav();
    if (activeGroup && !activeGroup.contains(event.target)) closeDropdown();
    if (!mega.hidden && !event.target.closest('#mega, #nav-all')) setOpen(false);
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && side?.classList.contains('open')) closeSectionNav(true);
    if (event.key === 'Escape' && activeGroup) {
      const trigger = activeGroup.querySelector(':scope > button');
      closeDropdown();
      trigger.focus();
    }
    if (event.key === 'Escape' && !mega.hidden) {
      setOpen(false);
      toggle.focus();
    }
  });
  renderSectionNav([...document.querySelectorAll('.page')].find(page => page.classList.contains('active'))?.id || 'landing');
})();
