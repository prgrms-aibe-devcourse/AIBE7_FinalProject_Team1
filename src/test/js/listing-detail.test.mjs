import test from 'node:test';
import assert from 'node:assert/strict';
import { environment } from './dom.mjs';

function listing(overrides = {}) {
  return {
    listingId: 6, status: 'ACTIVE', canManage: false, publishedAt: '2026-11-01T00:00:00Z',
    content: { title: '점검한 노트북 판매', price: 12341234, conditionGrade: 4, description: '충전 동작을 확인했습니다.' },
    images: [{ imageId: 1, url: '/fixture/1' }, { imageId: 2, url: '/fixture/2' }, { imageId: 3, url: '/fixture/3' }],
    product: { manufacturer: 'Samsung', modelName: 'nt960x', specifications: { ramGb: 16, storageGb: 512, storageType: 'SSD', os: '윈도우' } },
    history: { status: 'READY', sample: false, validCount: 0, voidCount: 0, items: [], page: { hasNext: false } },
    ...overrides
  };
}

function screen(data = listing()) {
  const env = environment(() => { throw new Error('렌더링은 요청을 보내지 않아야 한다'); });
  const root = env.add('div'); env.run('listing-detail.js');
  env.window.PastportListingDetail.render(root, data);
  return { ...env, root, $: selector => root.querySelector(selector) };
}

test('사진 다음에 제목·가격이 오고 판매자 설명과 여권이 이어진다', () => {
  const s = screen();
  const article = s.$('.listing-market-detail');
  assert.equal(article.children[0].className, 'dt-gallery');
  assert.equal(article.children[1].className, 'listing-detail-summary');
  assert.equal(article.children[2].className, 'listing-detail-body');
  assert.equal(s.$('h1').textContent, '점검한 노트북 판매');
  assert.equal(s.$('.listing-detail-price').textContent, '12,341,234원');
  assert.match(s.$('.listing-detail-meta').textContent, /2026년 11월 1일/);
  assert.match(s.$('.listing-spec-highlights').textContent, /메모리 16GB.*512GB SSD/);
  assert.ok(s.$('[data-listing-questions]'));
  assert.equal(s.calls.length, 0);
});

test('사진 버튼·썸네일·방향키는 같은 사진 번호와 선택 상태를 유지한다', async () => {
  const s = screen();
  assert.equal(s.$('.listing-gallery-count').textContent, '1 / 3');
  await s.$('.is-next').emit('click');
  assert.equal(s.$('.listing-gallery-count').textContent, '2 / 3');
  const thumbs = s.root.querySelectorAll('.listing-photo-thumb');
  assert.equal(thumbs[1].getAttribute('aria-pressed'), 'true');
  await thumbs[2].emit('click');
  assert.equal(s.$('.listing-image-frame').children[0].src, '/fixture/3');
  await s.$('.dt-gallery').emit('keydown', { key: 'ArrowRight' });
  assert.equal(s.$('.listing-gallery-count').textContent, '1 / 3');
  await s.$('.is-previous').emit('click');
  assert.equal(s.$('.listing-gallery-count').textContent, '3 / 3');
  assert.equal(s.calls.length, 0);
});

test('사진을 불러오지 못해도 다른 사진을 선택할 수 있다', async () => {
  const s = screen();
  await s.$('.listing-image-frame').children[0].emit('error');
  assert.match(s.$('.listing-image-frame').textContent, /다른 사진을 선택/);
  assert.ok(s.$('.is-next'));
  await s.$('.is-next').emit('click');
  assert.equal(s.$('.listing-image-frame').children[0].src, '/fixture/2');
});

test('이전 사진의 늦은 실패가 새로 선택한 사진을 지우지 않는다', async () => {
  const s = screen();
  const previous = s.$('.listing-image-frame').children[0];
  await s.$('.is-next').emit('click');
  await previous.emit('error');
  assert.equal(s.$('.listing-image-frame').children[0].src, '/fixture/2');
  assert.equal(s.$('.listing-gallery-count').textContent, '2 / 3');
});

test('사진 없음과 한 장뿐인 상태는 가짜 사진이나 불필요한 전환 버튼 없이 표시한다', () => {
  const empty = screen(listing({ images: [] }));
  assert.match(empty.$('.listing-image-frame').textContent, /등록된 제품 사진이 없어요/);
  assert.equal(empty.$('.listing-gallery-count'), null);
  const single = screen(listing({ images: [{ imageId: 1, url: '/fixture/1' }] }));
  assert.equal(single.$('.listing-detail-thumbs').hidden, true);
  assert.equal(single.$('.is-next'), null);
  assert.equal(single.$('.listing-gallery-count').textContent, '1 / 1');
});

test('제품 여권 버튼은 해당 이력으로 이동하고 제목을 HTML로 해석하지 않는다', async () => {
  const data = listing(); data.content.title = '<img src=x onerror=alert(1)>';
  const s = screen(data);
  assert.equal(s.$('h1').innerHTML, '');
  assert.match(s.$('h1').textContent, /<img/);
  const header = s.add('header', { className: 'site-header' }); header.rect.height = 192;
  await s.$('.listing-passport-link').emit('click');
  const history = s.$('[data-listing-history]');
  assert.equal(s.document.activeElement, history);
  assert.equal(history.lastScroll.block, 'start');
  assert.equal(history.style.scrollMarginTop, '216px');
});

test('판매자 전용 요약 관리는 판매자에게만 있고 미리보기는 거래 화면을 만들지 않는다', () => {
  const owner = screen(listing({ canManage: true }));
  assert.ok(owner.$('.listing-owner-summary'));
  assert.ok(owner.$('[data-listing-summary]'));
  assert.equal(screen().$('.listing-owner-summary'), null);
  const data = listing(); delete data.listingId;
  const preview = screen(data);
  assert.equal(preview.$('h1'), null);
  assert.equal(preview.$('[data-listing-questions]'), null);
  assert.equal(preview.$('.listing-owner-summary'), null);
  assert.match(preview.$('.listing-sale-state').textContent, /게시 전 미리보기/);
});

test('판매자 요약 영역에는 하단과 중복되는 받은 채팅 버튼을 만들지 않는다', () => {
  const s = screen(listing({ canManage: true }));
  assert.equal(s.$('.listing-owner-chats-link'), null);
  assert.ok(s.$('.listing-passport-link'));
});

test('핵심 이력을 질문보다 먼저 보여 주며 전체 사양은 접어서 보관한다', () => {
  const s = screen();
  const blocks = s.$('.listing-detail-body').children;
  assert.equal(blocks[0].querySelector('h2').textContent, '판매자 설명');
  assert.equal(blocks[1], s.$('[data-listing-history]'));
  assert.equal(blocks[2], s.$('[data-listing-questions]'));
  assert.equal(blocks[3], s.$('.listing-full-specs'));
  assert.equal(blocks[3].open, false);
});
