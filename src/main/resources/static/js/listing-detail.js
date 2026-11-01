// 미리보기와 게시 상세가 함께 쓰는 화면 구성 함수다. 데이터를 표시하며 저장·게시 요청은 보내지 않는다.
(() => {
  const element = (tag, className = '', text = null) => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };
  const labels = { PURCHASE: '구매', INSPECTION: '점검', REPAIR: '수리', PART_REPLACEMENT: '부품 교체', SPEC_CHANGE: '사양 변경', TRANSFER: '소유권 이전' };

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
      event.corrections.forEach(correction => item.append(element('p', 'sl-publication-correction',
        (event.status === 'VOID' ? '무효·정정 사유: ' : '정정 사유: ') + (correction.reason ||
          (correction.detailsStatus === 'EXCLUDED' ? '공개 제외 정보가 포함되어 표시하지 않아요.' : '상세 정보 연결 전')))));
      if (event.status === 'VOID' && !event.corrections.length) item.append(element('p', '', '무효 사유 확인 중'));
      container.append(item);
    });
  }

  function section(title) {
    const node = element('section', 'dt-sec'); node.append(element('h3', '', title)); return node;
  }

  function render(container, data) {
    const article = element('article', 'listing-detail dt');
    const main = element('div');
    const gallery = element('div', 'dt-gallery');
    const stage = element('div', 'dt-stage');
    const thumbs = element('div', 'listing-detail-thumbs');
    function showImage(image, index) {
      stage.replaceChildren();
      const picture = element('img'); picture.alt = '선택한 제품 사진 ' + (index + 1); picture.src = image.url;
      picture.addEventListener('error', () => { stage.replaceChildren(element('p', 'sub', '사진을 불러오지 못했어요. 닫은 뒤 다시 미리보기해 주세요.')); });
      stage.append(picture);
      [...thumbs.children].forEach((button, position) => button.setAttribute('aria-pressed', String(position === index)));
    }
    data.images.forEach((image, index) => {
      const button = element('button', 'sc-btn', index === 0 ? '대표 사진' : '사진 ' + (index + 1));
      button.type = 'button'; button.setAttribute('aria-pressed', String(index === 0));
      button.addEventListener('click', () => showImage(image, index)); thumbs.append(button);
    });
    if (data.images.length) showImage(data.images[0], 0);
    else stage.append(element('p', 'sub', '선택한 사진이 없어요.'));
    gallery.append(stage, thumbs); main.append(gallery);

    const description = section('판매자 설명');
    description.append(element('p', 'listing-detail-description', data.content.description || '설명이 아직 없어요.')); main.append(description);
    const info = data.product; const specs = info.specifications || {};
    const product = section('제품 정보·사양'); const facts = element('dl', 'sl-specs');
    [['제조사', info.manufacturer], ['모델', info.modelName], ['구매일', info.purchaseDate], ['보증 만료일', info.warrantyExpiresAt],
      ['CPU', specs.cpu], ['GPU', specs.gpu], ['메모리', specs.ramGb == null ? null : specs.ramGb + 'GB'],
      ['저장장치', specs.storageGb == null ? null : specs.storageGb + 'GB ' + (specs.storageType || '')],
      ['화면', specs.display], ['운영체제', specs.os], ['배터리 상태', specs.batteryHealthPercent == null ? null : specs.batteryHealthPercent + '%']].forEach(([label, value]) => {
      const row = element('div'); row.append(element('dt', '', label), element('dd', '', value ?? '미등록')); facts.append(row);
    });
    product.append(facts); main.append(product);
    const history = section('누적 핵심 이력');
    if (data.history.sample) history.append(element('p', 'sl-publication-sample', '화면 확인용 로컬 샘플 이력이에요.'));
    if (data.history.status !== 'READY') history.append(element('p', 'sub', '이력 연동 준비 중이에요. 실제 이력 여부는 아직 확인할 수 없어요.'));
    else {
      history.append(element('p', 'sub', '유효 ' + data.history.validCount + '건 · 무효 ' + data.history.voidCount + '건'));
      if (!data.history.items.length) history.append(element('p', 'sub', '등록된 핵심 이력이 없어요.'));
      const timeline = element('ol', 'sl-publication-timeline'); timeline.setAttribute('aria-label', data.listingId ? '공개 누적 핵심 이력' : '미리보기 누적 핵심 이력');
      renderTimeline(timeline, data.history.items); history.append(timeline);
    }
    main.append(history);

    if (data.listingId && data.canManage) {
      const summary = section('AI 카드 요약 관리');
      summary.classList.add('listing-ai-summary'); summary.dataset.listingSummary = '';
      summary.append(element('p', 'sub', 'AI 요약 상태를 확인하고 있어요.'));
      main.append(summary);
    }

    const aside = element('aside', 'listing-detail-summary sc-card');
    aside.append(element('span', 'sc-badge', data.listingId ? ({ ACTIVE: '판매 중', RESERVED: '예약 중' }[data.status] || '판매 종료') : '게시 전 미리보기'));
    aside.append(element('h2', '', data.content.title || '제목을 입력해 주세요.'));
    aside.append(element('strong', 'listing-detail-price', data.content.price == null ? '가격 미입력' : new Intl.NumberFormat('ko-KR').format(data.content.price) + '원'));
    const grade = data.content.conditionGrade;
    aside.append(element('p', 'sub', grade == null ? '상태 등급 미선택' : '상태 ' + grade + '등급 · ' + ['매우 나쁨', '나쁨', '보통', '좋음', '최상'][grade - 1]));
    aside.append(element('p', 'sub', [info.manufacturer, info.modelName].filter(Boolean).join(' · ')));
    article.append(main, aside); container.replaceChildren(article);
  }

  window.PastportListingDetail = Object.freeze({ render, renderTimeline });
})();
