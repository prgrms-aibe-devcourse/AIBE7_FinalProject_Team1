// 미리보기와 게시 상세가 함께 쓰는 화면 구성 함수다. 데이터를 표시하며 저장·게시 요청은 보내지 않는다.
(() => {
  const element = (tag, className = '', text = null) => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };
  const labels = { PURCHASE: '구매', INSPECTION: '점검', REPAIR: '수리', PART_REPLACEMENT: '부품 교체', SPEC_CHANGE: '사양 변경', TRANSFER: '소유권 이전' };
  const reasonLabels = { HISTORY_CORRECTED: '정정 사유', HISTORY_VOIDED: '무효 사유' };

  // 상세·미리보기에서 같은 공개 DTO를 사용한다. 사용자 입력은 HTML로 해석하지 않는다.
  function renderTimeline(container, events) {
    container.replaceChildren();
    events.forEach(event => {
      const item = element('li', 'sl-publication-event' + (event.status === 'VOID' ? ' is-void' : ''));
      const meta = element('div', 'sl-publication-event-meta');
      const date = element('time', '', event.occurredOn.replaceAll('-', '.')); date.dateTime = event.occurredOn; meta.append(date);
      [labels[event.eventType], event.previousOwner ? '이전 소유자 기록' : null,
        event.systemGenerated ? '시스템 기록' : null,
        event.status === 'VOID' ? '무효 처리' : event.corrections.length ? '정정됨' : null].filter(Boolean).forEach(label => meta.append(element('span', 'sl-publication-badge', label)));
      item.append(meta, element('h4', '', event.title));
      if (event.content) item.append(element('p', 'sl-publication-content', event.content));
      if (event.serviceProvider || event.replacedPart) item.append(element('p', '', [event.serviceProvider, event.replacedPart].filter(Boolean).join(' · ')));
      if (event.detailsStatus === 'REVIEW_PENDING') item.append(element('p', '', '상세 정보 연결 전 · 이력 유형과 날짜는 확인할 수 있어요.'));
      if (event.detailsStatus === 'EXCLUDED') item.append(element('p', '', '공개 가능한 상세 내용이 없어요. 이력 유형과 날짜는 확인할 수 있어요.'));
      // 나중에 무효화된 이력에도 과거 정정 사유가 남으므로 각 감사 기록의 처리 종류로 구분한다.
      event.corrections.forEach(correction => item.append(element('p', 'sl-publication-correction',
        (reasonLabels[correction.action] || '변경 사유') + ': ' + (correction.reason ||
          (correction.detailsStatus === 'EXCLUDED' ? '공개 제외 정보가 포함되어 표시하지 않아요.' : '상세 정보 연결 전')))));
      if (event.status === 'VOID' && !event.corrections.some(correction => correction.action === 'HISTORY_VOIDED')) {
        item.append(element('p', '', '무효 사유를 확인할 수 없어요.'));
      }
      container.append(item);
    });
  }

  function section(title, heading = 'h3') {
    const node = element('section', 'dt-sec'); node.append(element(heading, '', title)); return node;
  }

  // 휴대폰에서는 헤더가 더 높아지므로 실제 높이를 기준으로 이동한 제목을 드러낸다.
  function focusSection(node) {
    const header = document.querySelector('.site-header');
    const inset = node.closest('dialog') ? 24 : (header?.getBoundingClientRect().height || 0) + 24;
    node.style.scrollMarginTop = inset + 'px'; node.tabIndex = -1;
    node.focus({ preventScroll: true }); node.scrollIntoView({ block: 'start' });
  }

  function render(container, data) {
    const published = Boolean(data.listingId);
    const article = element('article', 'listing-detail dt listing-market-detail');
    const gallery = element('section', 'dt-gallery'); gallery.setAttribute('aria-label', '제품 사진');
    const stage = element('div', 'dt-stage');
    const frame = element('div', 'listing-image-frame');
    const count = element('span', 'listing-gallery-count'); count.setAttribute('aria-live', 'polite');
    const thumbs = element('div', 'listing-detail-thumbs');
    thumbs.setAttribute('aria-label', '사진 선택');
    let currentImage = 0;
    function showImage(index) {
      currentImage = (index + data.images.length) % data.images.length;
      const image = data.images[currentImage];
      frame.replaceChildren();
      const picture = element('img'); picture.alt = '제품 사진 ' + (currentImage + 1); picture.src = image.url;
      picture.addEventListener('error', () => {
        if (frame.children[0] !== picture) return;
        frame.replaceChildren(element('p', 'listing-image-empty', '사진을 불러오지 못했어요. 다른 사진을 선택하거나 다시 불러와 주세요.'));
      });
      frame.append(picture); count.textContent = (currentImage + 1) + ' / ' + data.images.length;
      [...thumbs.children].forEach((button, position) => button.setAttribute('aria-pressed', String(position === currentImage)));
    }
    data.images.forEach((image, index) => {
      const button = element('button', 'listing-photo-thumb');
      button.type = 'button'; button.setAttribute('aria-label', '사진 ' + (index + 1) + ' 보기');
      button.setAttribute('aria-pressed', String(index === 0));
      const picture = element('img'); picture.src = image.url; picture.alt = ''; picture.loading = 'lazy';
      button.append(picture); button.addEventListener('click', () => showImage(index)); thumbs.append(button);
    });
    stage.append(frame);
    if (data.images.length) { stage.append(count); showImage(0); }
    else frame.append(element('p', 'listing-image-empty', '등록된 제품 사진이 없어요.'));
    if (data.images.length > 1) {
      for (const [label, glyph, step] of [['이전 사진', '‹', -1], ['다음 사진', '›', 1]]) {
        const button = element('button', 'listing-gallery-arrow' + (step < 0 ? ' is-previous' : ' is-next'), glyph);
        button.type = 'button'; button.setAttribute('aria-label', label);
        button.addEventListener('click', () => showImage(currentImage + step)); stage.append(button);
      }
      gallery.addEventListener('keydown', event => {
        if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
        event.preventDefault(); showImage(currentImage + (event.key === 'ArrowRight' ? 1 : -1));
      });
    }
    thumbs.hidden = data.images.length < 2;
    gallery.append(stage, thumbs);

    const info = data.product; const specs = info.specifications || {};
    const summary = element('aside', 'listing-detail-summary');
    const meta = element('div', 'listing-detail-meta');
    meta.append(element('span', 'listing-sale-state', published ? ({ ACTIVE: '판매 중', RESERVED: '예약 중' }[data.status] || '판매 종료') : '게시 전 미리보기'));
    meta.append(element('span', '', '노트북'));
    if (data.publishedAt && Number.isFinite(Date.parse(data.publishedAt))) {
      const date = element('time', '', new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(data.publishedAt)) + ' 게시');
      date.dateTime = data.publishedAt; meta.append(date);
    }
    summary.append(meta, element(published ? 'h1' : 'h2', 'listing-detail-title', data.content.title || '제목을 입력해 주세요.'));
    summary.append(element('strong', 'listing-detail-price', data.content.price == null ? '가격 미입력' : new Intl.NumberFormat('ko-KR').format(data.content.price) + '원'));
    const grade = data.content.conditionGrade;
    summary.append(element('p', 'listing-condition', grade == null ? '상태 등급 미선택' : '상태 ' + grade + '등급 · ' + ['매우 나쁨', '나쁨', '보통', '좋음', '최상'][grade - 1]));
    summary.append(element('p', 'listing-model', [info.manufacturer, info.modelName].filter(Boolean).join(' · ')));
    const highlights = [specs.ramGb == null ? null : '메모리 ' + specs.ramGb + 'GB', specs.storageGb == null ? null : specs.storageGb + 'GB ' + (specs.storageType || ''), specs.os].filter(Boolean);
    if (highlights.length) summary.append(element('p', 'listing-spec-highlights', highlights.join(' · ')));
    const passport = element('button', 'listing-passport-link'); passport.type = 'button';
    const passportText = element('span');
    passportText.append(element('strong', '', '제품 여권·이력 확인'), element('small', '', data.history.status === 'READY' ? '유효 이력 ' + data.history.validCount + '건 · 무효 ' + data.history.voidCount + '건' : '제품 정보와 누적 이력을 함께 확인해요.'));
    const chevron = element('span', 'listing-passport-chevron', '›'); chevron.setAttribute('aria-hidden', 'true');
    passport.append(passportText, chevron); summary.append(passport);
    summary.append(element('p', 'listing-trade-note', published && data.canManage ? '내가 등록한 판매글이에요. 아래에서 판매글을 관리할 수 있어요.' : '제품 정보와 이력을 확인한 뒤 채팅으로 거래를 시작하세요.'));
    const main = element('div', 'listing-detail-body');
    const heading = published ? 'h2' : 'h3';
    const description = section('판매자 설명', heading);
    description.append(element('p', 'listing-detail-description', data.content.description || '설명이 아직 없어요.')); main.append(description);
    if (published) {
      const questions = element('div', 'listing-questions-slot'); questions.dataset.listingQuestions = ''; main.append(questions);
    }
    const product = section('제품 정보·사양', heading); const facts = element('dl', 'sl-specs');
    [['제조사', info.manufacturer], ['모델', info.modelName], ['구매일', info.purchaseDate], ['보증 만료일', info.warrantyExpiresAt],
      ['CPU', specs.cpu], ['GPU', specs.gpu], ['메모리', specs.ramGb == null ? null : specs.ramGb + 'GB'],
      ['저장장치', specs.storageGb == null ? null : specs.storageGb + 'GB ' + (specs.storageType || '')],
      ['화면', specs.display], ['운영체제', specs.os], ['배터리 상태', specs.batteryHealthPercent == null ? null : specs.batteryHealthPercent + '%']].forEach(([label, value]) => {
      const row = element('div'); row.append(element('dt', '', label), element('dd', '', value ?? '미등록')); facts.append(row);
    });
    product.append(facts); main.append(product);
    const history = section('누적 핵심 이력', heading);
    history.tabIndex = -1; history.dataset.listingHistory = '';
    passport.addEventListener('click', () => focusSection(history));
    if (data.history.sample) history.append(element('p', 'sl-publication-sample', '화면 확인용 로컬 샘플 이력이에요.'));
    if (data.history.status !== 'READY') history.append(element('p', 'sub', '이력 연동 준비 중이에요. 실제 이력 여부는 아직 확인할 수 없어요.'));
    else {
      history.append(element('p', 'sub', '유효 ' + data.history.validCount + '건 · 무효 ' + data.history.voidCount + '건'));
      if (!data.history.items.length) history.append(element('p', 'sub', '등록된 핵심 이력이 없어요.'));
      const timeline = element('ol', 'sl-publication-timeline'); timeline.setAttribute('aria-label', data.listingId ? '공개 누적 핵심 이력' : '미리보기 누적 핵심 이력');
      renderTimeline(timeline, data.history.items); history.append(timeline);
    }
    main.append(history);

    if (published && data.canManage) {
      const ownerTools = element('details', 'listing-owner-summary'); ownerTools.append(element('summary', '', 'AI 카드 요약 관리'));
      const panel = element('section', 'listing-ai-summary'); panel.dataset.listingSummary = '';
      panel.append(element('p', 'sub', 'AI 요약 상태를 확인하고 있어요.')); ownerTools.append(panel); main.append(ownerTools);
    }
    article.append(gallery, summary, main); container.replaceChildren(article);
  }

  window.PastportListingDetail = Object.freeze({ render, renderTimeline });
})();
