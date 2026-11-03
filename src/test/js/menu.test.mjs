import test from 'node:test';
import assert from 'node:assert/strict';
import { environment } from './dom.mjs';

/** 펼침 메뉴의 실제 이벤트와 좌표 계산을 검사할 대역을 만든다. */
function screen(height = 700, anchor = { top: 80, bottom: 120, left: 20 }) {
  const env = environment();
  env.document.documentElement.clientHeight = height;
  const nav = env.add('nav', { className: 'landing-nav' });
  env.add('button', { id: 'nav-all' }, nav);
  const mega = env.add('div', { id: 'mega' }); mega.hidden = true;
  env.add('div', { id: 'mega-grid' }, mega);
  const groups = [0, 1].map(() => {
    const item = env.add('div', { className: 'nav-item' }, nav);
    const trigger = env.add('button', {}, item); trigger.rect = anchor;
    const dropdown = env.add('div', { className: 'nav-drop' }, item);
    const child = env.add('button', { 'data-page': 'deals' }, dropdown);
    return { item, trigger, dropdown, child };
  });
  env.run('menu.js');
  return { ...env, groups };
}

test('포커스가 든 메뉴를 다른 메뉴로 교체하기 전에 상위 버튼으로 복귀한다', async () => {
  const s = screen(); const [a, b] = s.groups;
  await a.item.emit('pointerenter', { pointerType: 'mouse' }); a.child.focus();
  await b.item.emit('pointerenter', { pointerType: 'mouse' });
  assert.equal(s.document.activeElement, a.trigger);
  assert.equal(a.item.getAttribute('data-submenu-open'), null);
  assert.equal(b.trigger.getAttribute('aria-expanded'), 'true');
});

test('화면 아래 공간이 부족하면 양수 높이로 위쪽에 연다', async () => {
  const s = screen(200, { top: 170, bottom: 195, left: 250 }); const a = s.groups[0];
  await a.trigger.emit('click');
  const top = parseFloat(a.dropdown.style.top), height = parseFloat(a.dropdown.style.maxHeight);
  assert.ok(height > 0 && top >= 0);
  assert.ok(top + Math.min(a.dropdown.scrollHeight + 2, height) <= 170);
  assert.ok(parseFloat(a.dropdown.style.left) + a.dropdown.rect.width <= 390);
});

test('상단 영역보다 화면이 낮아도 메뉴를 뷰포트 안에서 스크롤할 수 있다', async () => {
  const s = screen(70, { top: 0, bottom: 120, left: 20 }); const a = s.groups[0];
  await a.trigger.emit('click');
  const top = parseFloat(a.dropdown.style.top), height = parseFloat(a.dropdown.style.maxHeight);
  assert.ok(height > 0 && top >= 0 && top + height <= 70);
});

test('화살표로 연 메뉴는 Escape로 닫고 상위 버튼의 포커스를 유지한다', async () => {
  const s = screen(); const a = s.groups[0];
  await a.trigger.emit('keydown', { key: 'ArrowDown' });
  assert.equal(s.document.activeElement, a.child);
  await s.document.emit('keydown', null, { key: 'Escape' });
  assert.equal(s.document.activeElement, a.trigger);
  assert.equal(a.trigger.getAttribute('aria-expanded'), 'false');
});

/** 상단 메뉴를 기준으로 분류·관련 상세 화면·모바일 하위 메뉴를 함께 구성한다. */
function sectionScreen(initialPage = 'home') {
  const env = environment();
  const header = env.add('header', { className: 'site-header' }); header.rect.bottom = 192;
  const nav = env.add('nav', { className: 'landing-nav' }, header);
  env.add('button', { id: 'nav-all' }, nav);
  const mega = env.add('div', { id: 'mega' }); mega.hidden = true;
  const grid = env.add('div', { id: 'mega-grid' }, mega);
  const definitions = [
    ['제품 여권', [['home', '제품 여권 보기', 'passport'], ['register', '새 여권 등록'], ['documents', '문서 보관함', 'docmask']]],
    ['중고거래', [['market', '중고 상품 둘러보기', 'detail'], ['sell', '판매글 작성']]],
    ['내 거래', [['my-listings', '내 판매글'], ['deals', '채팅']]],
    ['외부 소유권 이전', [['trade', '보내기'], ['receive', '받기']]]
  ];
  const groups = definitions.map(([title, links]) => {
    const item = env.add('div', { className: 'nav-item' }, nav);
    const trigger = env.add('button', {}, item); trigger.textContent = title; trigger.dataset.page = links[0][0];
    const dropdown = env.add('div', { className: 'nav-drop' }, item);
    const children = links.map(([page, label, related]) => {
      const button = env.add('button', { 'data-page': page }, dropdown); button.dataset.page = page;
      if (related) button.dataset.navRelated = related;
      const name = env.add('b', {}, button); name.textContent = label;
      env.add('small', {}, button).textContent = '이동할 화면의 설명';
      return button;
    });
    return { item, trigger, dropdown, children };
  });
  const shell = env.add('div', { className: 'app' });
  const side = env.add('aside', { id: 'side' }, shell);
  const title = env.add('h2', { id: 'side-section' }, side);
  const close = env.add('button', { 'data-section-nav-close': '' }, side);
  const sectionNav = env.add('nav', { className: 'nav', 'data-section-nav': '' }, side);
  const toggle = env.add('button', { id: 'menu' }, shell);
  env.add('section', { id: initialPage, className: 'page active' }, shell);
  env.run('menu.js');
  return { ...env, header, groups, grid, shell, side, title, close, sectionNav, toggle };
}

test('좌측은 현재 분류의 하위 메뉴만 상단과 같은 이름·순서·이동 대상으로 표시한다', async () => {
  const s = sectionScreen();
  const links = s.sectionNav.querySelectorAll('button');
  assert.equal(s.title.textContent, '제품 여권');
  assert.equal(s.side.hidden, false);
  assert.equal(s.side.style.top, '192px');
  assert.equal(s.shell.classList.contains('has-section-nav'), true);
  assert.deepEqual(links.map(button => button.textContent), s.groups[0].children.map(button => button.querySelector('b').textContent));
  assert.deepEqual(links.map(button => button.dataset.page), ['home', 'register', 'documents']);
  assert.equal(links[0].getAttribute('aria-current'), 'page');
  assert.equal(s.sectionNav.getAttribute('aria-label'), '제품 여권 하위 메뉴');
  assert.doesNotMatch(s.sectionNav.textContent, /WORKSPACE|AI 이력 검색|내 판매글|이동할 화면의 설명/);

  s.groups[0].children[1].querySelector('b').textContent = '등록 메뉴 이름 변경';
  await s.document.emit('pastport:page-shown', { id: 'register' });
  assert.equal(s.sectionNav.querySelectorAll('button')[1].textContent, '등록 메뉴 이름 변경');
});

test('분류 안에서 이동해도 상단 분류와 실제 하위 메뉴의 선택 상태를 함께 유지한다', async () => {
  const s = sectionScreen();
  await s.document.emit('pastport:page-shown', { id: 'register' });
  assert.equal(s.groups[0].trigger.classList.contains('active'), true);
  assert.equal(s.groups[0].children[0].getAttribute('aria-current'), null);
  assert.equal(s.groups[0].children[1].getAttribute('aria-current'), 'page');
  assert.deepEqual(s.sectionNav.querySelectorAll('.active').map(button => button.dataset.page), ['register']);
  assert.deepEqual(s.grid.querySelectorAll('.active').map(button => button.dataset.page), ['home', 'register']);

  await s.document.emit('pastport:page-shown', { id: 'deals' });
  assert.equal(s.title.textContent, '내 거래');
  assert.equal(s.groups[0].trigger.classList.contains('active'), false);
  assert.equal(s.groups[2].trigger.classList.contains('active'), true);
  assert.deepEqual(s.sectionNav.querySelectorAll('button').map(button => button.textContent), ['내 판매글', '채팅']);
  assert.equal(s.sectionNav.querySelector('.active').dataset.page, 'deals');
});

test('제품 상세·문서 마스킹·판매글 상세는 연결된 상위 메뉴의 위치를 유지한다', async () => {
  const s = sectionScreen();
  for (const [page, title, selected] of [['passport', '제품 여권', 'home'], ['docmask', '제품 여권', 'documents'], ['detail', '중고거래', 'market']]) {
    await s.document.emit('pastport:page-shown', { id: page });
    assert.equal(s.title.textContent, title);
    const link = s.sectionNav.querySelector('.active');
    assert.equal(link.dataset.page, selected);
    assert.equal(link.getAttribute('aria-current'), 'location');
    assert.equal(s.groups.find(group => group.trigger.textContent === title).trigger.classList.contains('active'), true);
  }
});

test('홈·로그인·계정 화면에는 이전 분류의 메뉴나 공간을 남기지 않는다', async () => {
  const s = sectionScreen();
  for (const page of ['landing', 'auth', 'signup', 'mypage']) {
    await s.document.emit('pastport:page-shown', { id: page });
    assert.equal(s.side.hidden, true);
    assert.equal(s.shell.classList.contains('has-section-nav'), false);
    assert.equal(s.sectionNav.children.length, 0);
    assert.equal(s.title.textContent, '');
    assert.equal(s.groups.filter(group => group.trigger.classList.contains('active')).length, 0);
  }
  await s.document.emit('pastport:page-shown', { id: 'receive' });
  assert.equal(s.side.hidden, false);
  assert.equal(s.title.textContent, '외부 소유권 이전');
  assert.equal(s.sectionNav.querySelector('.active').dataset.page, 'receive');
});

test('모바일 하위 메뉴는 키보드로 열고 닫으며 페이지 이동·바깥 클릭 후 펼침 상태를 지운다', async () => {
  const s = sectionScreen();
  await s.toggle.emit('click');
  assert.equal(s.side.classList.contains('open'), true);
  assert.equal(s.toggle.getAttribute('aria-expanded'), 'true');
  assert.equal(s.document.activeElement, s.sectionNav.querySelector('button'));
  await s.document.emit('keydown', null, { key: 'Escape' });
  assert.equal(s.side.classList.contains('open'), false);
  assert.equal(s.toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(s.document.activeElement, s.toggle);

  await s.toggle.emit('click');
  await s.close.emit('click');
  assert.equal(s.side.classList.contains('open'), false);
  assert.equal(s.document.activeElement, s.toggle);
  await s.toggle.emit('click');
  await s.document.emit('click', null, { target: s.shell });
  assert.equal(s.side.classList.contains('open'), false);
  await s.toggle.emit('click');
  await s.document.emit('pastport:page-shown', { id: 'deals' });
  assert.equal(s.side.classList.contains('open'), false);
  assert.equal(s.toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(s.toggle.textContent, '내 거래 메뉴');
  await s.toggle.emit('click');
  s.document.documentElement.clientHeight = 450;
  s.header.rect.bottom = 220;
  await s.document.emit('window:resize');
  assert.equal(s.side.classList.contains('open'), true, '모바일 화면 높이만 바뀌면 메뉴를 유지한다');
  assert.equal(s.side.style.top, '220px');
  s.document.documentElement.clientWidth = 1200;
  await s.document.emit('window:resize');
  assert.equal(s.side.classList.contains('open'), false);
});

test('전체 메뉴·상단 하위 메뉴와 모바일 좌측 메뉴는 동시에 열리지 않는다', async () => {
  const s = sectionScreen();
  await s.toggle.emit('click');
  await s.groups[0].trigger.emit('click');
  assert.equal(s.side.classList.contains('open'), false);
  assert.equal(s.document.activeElement, s.toggle);
  assert.equal(s.groups[0].trigger.getAttribute('aria-expanded'), 'true');
  await s.toggle.emit('click');
  assert.equal(s.groups[0].trigger.getAttribute('aria-expanded'), 'false');
  assert.equal(s.side.classList.contains('open'), true);
  await s.document.querySelector('#nav-all').emit('click');
  assert.equal(s.side.classList.contains('open'), false);
  assert.equal(s.document.querySelector('#mega').hidden, false);
});
