/** 조회 전용 관리자 화면. 응답은 텍스트로만 표시하고 화면을 떠나면 업무 데이터를 지운다. */
(() => {
  const $ = id => document.getElementById(id);
  const form = $('filters');
  let tab = 'trades', page = 0, version = 0, controller, filters = new URLSearchParams(), opener, relatedProduct;
  const labels = {
    PENDING: '구매 요청', ACCEPTED: '수락', REJECTED: '요청 거절', RESERVED: '예약', COMPLETED: '완료', CANCELLED: '취소',
    SUCCESS: '성공', SELF_CIRCUMSTANCES: '개인 사정', MUTUAL_AGREEMENT: '상호 합의', COUNTERPART_UNREACHABLE: '상대방 연락 두절', CONDITION_MISMATCH: '제품 상태 불일치',
    HISTORY_CORRECTED: '이력 정정', HISTORY_VOIDED: '이력 무효', HISTORY_VISIBILITY_CHANGED: '이력 공개 범위 변경',
    PURCHASE_REQUEST_ACCEPTED: '구매 요청 수락', PURCHASE_REQUEST_CANCELLED: '구매 요청 취소', PURCHASE_REQUEST_REJECTED: '구매 요청 거절',
    TRADE_COMPLETED: '거래 완료', TRADE_RESERVATION_CANCELLED: '예약 취소',
    RESERVATION_CHANGE_RECORDED: '예약 중 제품 이력 변경', RESERVATION_CHANGE_KEPT: '구매자 변경 확인 · 예약 유지', RESERVATION_CHANGE_CANCELLED: '구매자 변경 확인 · 예약 취소',
    BUYER_CANCELLED: '구매자 요청 취소', SELLER_REJECTED: '판매자 요청 거절',
    BEFORE_RESERVATION: '예약 전 요청 취소', UNRECORDED: '기타·사유 미기록',
    OWNERSHIP_TRANSFER_CREATED: '소유권 이전 요청', OWNERSHIP_TRANSFERRED: '소유권 이전 완료', OWNERSHIP_TRANSFER_CANCELLED: '소유권 이전 취소',
    LISTING_PUBLISHED: '판매글 게시', LISTING_UPDATED: '판매글 수정', LISTING_CANCELLED: '판매글 판매 종료', PRODUCT_INFO_CORRECTED: '제품 기본 정보 정정',
    VALID: '유효', VOID: '무효', PUBLIC: '공개', PRIVATE: '비공개', ACTIVE: '판매 중', SOLD: '판매 완료',
    PURCHASE: '구매', INSPECTION: '점검', REPAIR: '수리', PART_REPLACEMENT: '부품 교체', SPEC_CHANGE: '사양 변경', TRANSFER: '소유권 이전'
  };
  const fieldLabels = { id: 'ID', productId: '제품 ID', authorId: '작성자 ID', eventType: '이력 종류', occurredOn: '발생일', title: '제목', content: '내용', serviceProvider: '처리 업체', cost: '비용 (원)', replacedPart: '교체 부품', status: '상태', createdAt: '생성 시각', updatedAt: '변경 시각', changeType: '변경 종류', visibility: '공개 범위', requestStatus: '요청 상태', transactionStatus: '거래 상태', listingStatus: '판매글 상태', confirmedFactsVersion: '확인한 제품 버전', cancelledAt: '취소 시각', completedAt: '완료 시각', specsBefore: '변경 전 사양', specsAfter: '변경 후 사양', ramGb: 'RAM (GB)', storageGb: '저장 용량 (GB)', storageType: '저장 방식', batteryHealthPercent: '배터리 상태 (%)', cpu: 'CPU', gpu: 'GPU', display: '디스플레이', os: '운영체제' };
  const text = value => value == null ? '—' : Object.hasOwn(labels, value) ? labels[value] : String(value);
  const time = value => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false }) + ' (KST)' : '—';
  const node = (tag, value) => { const n = document.createElement(tag); if (value != null) n.textContent = value; return n; };
  const status = value => { const n = node('span', text(value)); n.className = 'status'; n.dataset.status = value || ''; return n; };
  const pair = (primary, secondary) => {
    const n = node('div'), top = node('span', primary); top.className = 'cell-primary'; n.append(top);
    if (secondary != null) { const bottom = node('span', secondary); bottom.className = 'cell-secondary'; n.append(bottom); }
    return n;
  };
  const productName = product => product?.title || product?.modelName || '제품 정보 없음';
  const productModel = product => [product?.manufacturer, product?.modelName].filter(Boolean).join(' · ') || '모델 정보 없음';
  const memberName = member => member?.nickname || member?.email || '탈퇴한 회원';
  const memberText = member => member ? [memberName(member), member.email].filter((value, index, values) => value && values.indexOf(value) === index).join('\n') : '정보 없음';
  const memberCell = (member, role) => { const n = pair((role ? role + ' · ' : '') + memberName(member), member?.email || '이메일 정보 없음'); n.className = 'member-summary'; return n; };
  const dateParts = value => {
    const date = new Date(value);
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(date).map(p => [p.type, p.value]));
    return [`${parts.year}-${parts.month}-${parts.day}`, `${parts.hour}:${parts.minute}:${parts.second}`];
  };
  const dateCell = value => value ? pair(...dateParts(value)) : pair('—');
  const factText = (key, value) => value && ['변경 시각', '생성 시각', '취소 시각', '완료 시각'].includes(key) && !Number.isNaN(Date.parse(value)) ? dateParts(value).join('\n') + ' KST' : text(value);
  const hideDetail = (restore = false) => {
    if ($('detail').open) $('detail').close();
    $('detail').hidden = true; $('detail-body').replaceChildren(); document.body?.classList.remove('detail-open');
    if (restore && opener?.isConnected) opener.focus();
    $('rows').querySelectorAll('tr').forEach(row => row.classList.remove('selected'));
  };

  /** 취소된 요청이 늦게 완료돼도 다른 탭·계정의 화면을 다시 채우지 못하게 세대를 함께 확인한다. */
  const invalidate = () => { version++; controller?.abort(); controller = new AbortController(); return version; };
  const clear = () => { hideDetail(); $('rows').replaceChildren(); $('count').textContent = ''; $('range').textContent = ''; $('updated').textContent = ''; $('previous').disabled = true; $('next').disabled = true; $('results').setAttribute('aria-busy', 'false'); $('trade-overview').hidden = true; for (const id of ['overview-statuses', 'overview-requests', 'overview-cancellations']) $(id).replaceChildren(); $('overview-message').textContent = ''; $('cancel-scope').hidden = true; $('cancel-scope-label').textContent = ''; };
  /** 401 뒤 최대 두 번만 GET한다: 공유 잠금 안의 재검사, 필요했던 갱신 뒤 최종 조회다. 403은 복구하지 않는다. */
  const request = async (path, current) => {
    const signal = controller.signal;
    const send = () => window.fetch('/api/v1/admin/' + path, { credentials: 'same-origin', cache: 'no-store', signal });
    let response = await send();
    if (current !== version) return null;
    if (response.status === 401) {
      response = await window.PastportSession.recover(send, () => current === version);
    }
    if (!response || current !== version) return null;
    if (response.status === 401 || response.status === 403) {
      invalidate(); clear(); setRelated(); $('message').textContent = '관리자 권한을 확인할 수 없어요. 홈에서 로그인한 계정을 확인해 주세요.';
      if (response.status === 401) window.location.replace('/?returnTo=admin');
      return null;
    }
    const payload = await response.json().catch(() => null);
    if (!response.ok) throw new Error(payload?.message || '기록을 불러오지 못했어요. 새로고침으로 다시 시도해 주세요.');
    return payload.data;
  };
  const fields = entries => {
    const dl = node('dl');
    for (const [key, value] of entries) { const row = node('div'); row.className = 'field'; row.append(node('dt', key), node('dd', value)); dl.append(row); }
    return dl;
  };
  const auditFields = a => [['제품', productName(a.product)], ['제조사·모델', productModel(a.product)], ['처리 주체', a.actorId == null ? '시스템' : memberText(a.actor)], ['행위', text(a.action)], ['결과', text(a.result)], ['처리 시각', time(a.createdAt)]];
  const section = (title, entries) => { const n = node('section'); n.className = 'detail-section'; n.append(node('h3', title), fields(entries)); return n; };
  const identifiers = entries => { const n = node('details'); n.className = 'identifiers'; n.append(node('summary', '기록 식별 정보'), fields(entries)); return n; };
  /** 요약에서 목록으로 이동해도 제품·회원·예약일 조건을 보존한다. 종료된 화면의 버튼은 동작하지 않는다. */
  const overviewButton = (label, count, selectedStatus, category, compact = false) => {
    const button = node('button'); button.type = 'button'; button.className = compact ? 'overview-link' : 'overview-stat';
    button.dataset.status = selectedStatus || ''; if (category) button.dataset.category = category;
    button.setAttribute('aria-pressed', String((filters.get('status') || '') === (selectedStatus || '') && (filters.get('cancelReason') || '') === (category || '')));
    button.setAttribute('aria-label', `${label} ${count.toLocaleString()}건 목록 보기`);
    button.append(node('span', label), node('strong', count.toLocaleString()));
    button.addEventListener('click', async () => {
      if (!button.isConnected || tab !== 'trades') return;
      if (selectedStatus) filters.set('status', selectedStatus); else filters.delete('status');
      if (category) filters.set('cancelReason', category); else filters.delete('cancelReason');
      $('trade-status').value = selectedStatus || ''; page = 0;
      const loading = load(), current = version; await loading;
      if (current === version) { $('results').scrollIntoView?.({ block: 'start' }); $('results').focus({ preventScroll: true }); }
    });
    return button;
  };
  const renderOverview = overview => {
    $('overview-dates').hidden = !filters.has('from') && !filters.has('to');
    if (!overview) { $('overview-message').textContent = '운영 현황을 불러오지 못했어요. 새로고침으로 다시 시도해 주세요.'; return; }
    $('overview-message').textContent = '';
    $('overview-statuses').append(overviewButton('전체 요청', overview.total), overviewButton('예약', overview.reserved, 'RESERVED'), overviewButton('완료', overview.completed, 'COMPLETED'), overviewButton('취소', overview.cancelled, 'CANCELLED'));
    $('overview-requests').append(overviewButton('구매 요청', overview.pending, 'PENDING', null, true), overviewButton('요청 거절', overview.rejected, 'REJECTED', null, true));
    if (overview.other) $('overview-requests').append(node('span', `기타 상태 ${overview.other.toLocaleString()}건`));
    const categories = overview.cancellations.filter(item => item.count > 0);
    if (categories.length) for (const item of categories) $('overview-cancellations').append(overviewButton(text(item.category), item.count, 'CANCELLED', item.category, true));
    else $('overview-cancellations').append(node('p', '이 검색 범위에는 취소된 요청·거래가 없어요.'));
    const reason = filters.get('cancelReason'); $('cancel-scope').hidden = !reason;
    $('cancel-scope-label').textContent = reason ? `선택한 취소 유형: ${text(reason)}` : '';
  };
  const setRelated = product => {
    relatedProduct = product; filters.delete('productId');
    if (product) filters.set('productId', product.id);
    $('related-scope').hidden = !product; $('related-product').textContent = product ? productName(product) : '';
  };
  const relatedLink = (productId, product) => {
    const block = node('div'), button = node('button', '이 제품의 업무 기록 보기 →');
    block.className = 'related-link'; button.type = 'button';
    button.addEventListener('click', () => changeTab('audit-logs', { ...product, id: productId })); block.append(button);
    return block;
  };
  /** 현재 확인 상태만 안내한다. 확인 시각을 추정하거나 관리자 화면에서 거래를 완료하지 않는다. */
  const progressCard = (id, current) => {
    const block = node('section'), body = node('div'), message = node('p'), retry = node('button', '확인 상태 다시 불러오기');
    block.className = 'trade-progress'; block.setAttribute('aria-label', '거래 확인 상태');
    body.setAttribute('aria-live', 'polite'); message.setAttribute('role', 'status'); message.className = 'progress-note'; retry.type = 'button'; retry.hidden = true;
    block.append(node('h3', '현재 확인 상태'), message, body, retry);
    const states = {
      WAITING_SELLER_ACCEPTANCE: ['판매자의 구매 요청 검토 대기', '구매 요청을 보낸 단계예요. 판매자의 수락 여부를 기다리고 있어요.'],
      WAITING_BUYER_CHANGE_REVIEW: ['구매자의 변경 이력 확인 대기', '구매자가 변경된 이력을 확인하고 예약 유지·취소를 선택해야 해요.'],
      WAITING_BOTH_CONFIRMATIONS: ['판매자·구매자의 완료 확인 대기', '양측이 최신 제품 내용을 확인하고 완료 확인을 진행해야 해요.'],
      WAITING_SELLER_CONFIRMATION: ['판매자의 완료 확인 대기', '구매자는 최신 내용을 확인했어요. 판매자의 완료 확인을 기다리고 있어요.'],
      WAITING_BUYER_CONFIRMATION: ['구매자의 완료 확인 대기', '판매자는 최신 내용을 확인했어요. 구매자의 완료 확인을 기다리고 있어요.'],
      CONFIRMATIONS_RECEIVED: ['양측 확인 저장됨 · 처리 상태 확인 필요', '양측이 최신 내용을 확인했지만 거래는 아직 예약 상태예요. 거래 처리 기록을 확인해 주세요.'],
      COMPLETED: ['거래 완료됨', '완료 처리 내역은 아래 거래 기록에서 확인할 수 있어요.'],
      CANCELLED: ['취소 처리됨', '취소된 요청·거래예요. 저장된 취소 사유와 처리 내역을 확인해 주세요.'],
      REJECTED: ['구매 요청 거절됨', '구매 요청이 거절 상태로 종료됐어요.'],
      CONFIRMATION_UNAVAILABLE: ['확인 상태 제공 준비 중', '이 환경에서는 거래 완료 확인 기능이 활성화되지 않아 확인 대기 단계를 표시할 수 없어요.'],
      CONNECTION_CHECK_REQUIRED: ['거래 연결 상태 확인 필요', '예약 정보와 현재 제품·판매글 상태를 함께 확인해 주세요.']
    };
    const confirmations = { NOT_CONFIRMED: '확인 전', CURRENT: '최신 내용 확인함', RECONFIRM_REQUIRED: '내용 변경 · 재확인 필요' };
    let generation = 0;
    const load = async () => {
      const run = ++generation; const active = () => current === version && run === generation && block.isConnected;
      block.setAttribute('aria-busy', 'true'); retry.hidden = true; message.textContent = '현재 확인 상태를 불러오는 중이에요.'; body.replaceChildren();
      try {
        const data = await request(`trades/${id}/progress`, current);
        if (!data || !active()) return;
        if (!Object.hasOwn(states, data.state)) throw new Error('현재 확인 상태를 불러오지 못했어요. 다시 시도해 주세요.');
        block.dataset.state = data.state;
        const [title, description] = states[data.state], heading = node('strong', title), note = node('p', description);
        heading.className = 'progress-state'; note.className = 'progress-note'; body.append(heading, note);
        if (Object.hasOwn(confirmations, data.sellerConfirmation) && Object.hasOwn(confirmations, data.buyerConfirmation)) {
          const checks = node('div'); checks.className = 'progress-checks';
          checks.append(pair('판매자 완료 확인', confirmations[data.sellerConfirmation]), pair('구매자 완료 확인', confirmations[data.buyerConfirmation])); body.append(checks);
          const changes = node('p', `구매자가 확인하지 않은 변경 이력 ${data.unreviewedChangeCount.toLocaleString()}건`); changes.className = 'progress-note'; body.append(changes);
          const scope = node('p', '현재 확인 상태 기준의 안내예요. 실제 거래 완료 시 제품·소유권 등 완료 조건을 다시 검사해요.'); scope.className = 'progress-note'; body.append(scope);
        }
        message.textContent = '';
      } catch (error) {
        if (active() && error.name !== 'AbortError') { message.textContent = error.message; retry.hidden = false; }
      } finally { if (active()) block.setAttribute('aria-busy', 'false'); }
    };
    retry.addEventListener('click', load);
    return { block, load };
  };
  /** 별도 조회가 늦거나 실패해도 기본 거래 상세는 유지한다. 닫힌 패널·이전 페이지의 응답은 버린다. */
  const timeline = (id, current) => {
    const block = node('section'), title = node('h3', '거래 타임라인'), note = node('p', '시각이 저장된 처리 내역을 오래된 순서로 표시해요. 구매 요청·완료 확인 시각은 별도로 저장되지 않아 표시하지 않아요.');
    block.className = 'timeline'; note.className = 'timeline-note'; block.append(title, note);
    const availability = node('p'), message = node('p'), list = node('ol'), paging = node('div'), range = node('span'), previous = node('button', '‹ 이전 기록'), next = node('button', '다음 기록 ›'), retry = node('button', '타임라인 다시 불러오기');
    availability.className = 'timeline-note'; availability.hidden = true; message.setAttribute('role', 'status'); message.className = 'timeline-message';
    paging.className = 'timeline-paging'; previous.type = next.type = retry.type = 'button'; retry.hidden = true; previous.disabled = next.disabled = true;
    paging.append(range, previous, next); block.append(availability, message, retry, list, paging);
    let currentPage = 0, total = 0, size = 20, generation = 0, retryPage = 0;
    const active = run => current === version && run === generation && block.isConnected;
    const loadPage = async requested => {
      const run = ++generation; retryPage = requested; previous.disabled = next.disabled = true; retry.hidden = true;
      block.setAttribute('aria-busy', 'true'); message.textContent = '타임라인을 불러오는 중이에요.';
      try {
        const data = await request(`trades/${id}/timeline?page=${requested}&size=20`, current);
        if (!data || !active(run)) return;
        const events = data.events; currentPage = events.page; total = events.total; size = events.size;
        availability.hidden = data.reviewHistoryAvailable;
        availability.textContent = '이 환경에서는 예약 중 이력 변경 확인 기능이 활성화되지 않아 해당 기록은 제공하지 않아요.';
        list.replaceChildren();
        const sources = { LISTING: '판매글 저장 기록', AUDIT: '업무 처리 기록', TRANSACTION: '거래 저장 기록', OWNERSHIP: '소유권 이전 저장 기록', CHANGE_REVIEW: '변경 확인 기록' };
        for (const item of events.items) {
          const event = node('li'), heading = node('strong', text(item.action)), timestamp = node('time', time(item.occurredAt));
          timestamp.setAttribute('datetime', item.occurredAt); timestamp.title = item.occurredAt;
          const actor = item.actorType === 'SYSTEM' ? '시스템' : item.actorType === 'UNRECORDED' ? '처리 주체 미기록' : memberText(item.actor);
          const meta = node('p', `${sources[item.source] || '저장 기록'} · ${actor}`); meta.className = 'timeline-meta';
          event.append(heading, timestamp, meta);
          if (item.reason) { const reason = node('p', text(item.reason)); reason.className = 'reason'; event.append(reason); }
          list.append(event);
        }
        message.textContent = events.items.length ? '' : '시각이 저장된 처리 내역이 없어요.';
        const first = events.items.length ? currentPage * size + 1 : 0;
        range.textContent = `${first}–${first ? currentPage * size + events.items.length : 0} / ${total}건`;
      } catch (error) {
        if (!active(run) || error.name === 'AbortError') return;
        message.textContent = error.message; retry.hidden = false;
      } finally {
        if (active(run)) { block.setAttribute('aria-busy', 'false'); previous.disabled = currentPage === 0; next.disabled = (currentPage + 1) * size >= total; }
      }
    };
    previous.addEventListener('click', () => loadPage(currentPage - 1)); next.addEventListener('click', () => loadPage(currentPage + 1)); retry.addEventListener('click', () => loadPage(retryPage));
    return { block, load: () => loadPage(0) };
  };
  const flatten = data => Object.fromEntries(Object.entries(data.values).flatMap(([key, value]) => value && typeof value === 'object' ? Object.entries(value).map(([part, detail]) => [`${fieldLabels[key] || key} · ${fieldLabels[part] || part}`, detail]) : [[fieldLabels[key] || key, value]]));
  /** 두 사본에서 실제 달라진 값을 같은 행에 놓아 정정·무효의 변경 경위를 비교한다. */
  const comparison = (before, after) => {
    const block = node('section'); block.className = 'comparison'; block.append(node('h3', '변경 내용'));
    if (!before.available || !after.available) block.append(node('p', '일부 기록은 세부 변경 정보를 제공하지 않아요.'));
    const previous = flatten(before), next = flatten(after);
    const keys = [...new Set([...Object.keys(previous), ...Object.keys(next)])].filter(key => JSON.stringify(previous[key]) !== JSON.stringify(next[key]));
    if (!keys.length) { block.append(node('p', '비교할 수 있는 변경 값이 없어요.')); return block; }
    const header = node('div'); header.className = 'comparison-head'; header.append(node('span', '항목'), node('span', '변경 전'), node('span', '변경 후')); block.append(header);
    for (const key of keys) { const row = node('div'); row.className = 'comparison-row changed'; const beforeCell = node('span', factText(key, previous[key])), afterCell = node('span', factText(key, next[key])); beforeCell.title = text(previous[key]); afterCell.title = text(next[key]); row.append(node('span', key), beforeCell, afterCell); block.append(row); }
    return block;
  };
  const open = async (id, button, ordinal) => {
    const current = invalidate();
    opener = button; $('detail-title').textContent = `${tab === 'trades' ? '거래 상세' : '업무 기록 상세'} · No. ${ordinal}`;
    $('detail').hidden = false; $('detail-body').replaceChildren(node('p', '상세 기록을 불러오는 중이에요.'));
    if ($('detail').showModal && !$('detail').open) $('detail').showModal();
    document.body?.classList.add('detail-open');
    $('rows').querySelectorAll('tr').forEach(row => row.classList.remove('selected')); button.closest('tr').classList.add('selected');
    try {
      const data = await request(`${tab}/${id}`, current);
      if (!data || current !== version) return;
      if (tab === 'trades') {
        const progress = progressCard(data.id, current);
        const history = timeline(data.id, current);
        const summary = node('div'); summary.className = 'detail-summary'; summary.append(status(data.transactionStatus || data.requestStatus), node('p', productName(data.product)));
        const content = [summary, progress.block,
          section('제품 및 당사자', [['제품', productName(data.product)], ['제조사·모델', productModel(data.product)], ['판매글', data.listingTitle || '판매글 정보 없음'], ['판매자', memberText(data.seller)], ['구매자', memberText(data.buyer)]]),
          section('거래 진행', [['요청 상태', text(data.requestStatus)], ['거래 상태', text(data.transactionStatus)], ['예약 시각', time(data.reservedAt)], ['완료 시각', time(data.completedAt)]])];
        if (data.cancelledAt || data.cancelReason) content.push(section('취소 내역', [['취소 시각', time(data.cancelledAt)], ['취소 수행자', memberText(data.cancelledBy)], ['취소 사유', text(data.cancelReason)]]));
        content.push(relatedLink(data.productId, data.product), history.block, identifiers([['거래 ID', data.id], ['제품 ID', data.productId], ['판매글 ID', data.listingId], ['판매자 회원 ID', data.sellerId], ['구매자 회원 ID', data.buyerId], ['취소 수행자 회원 ID', data.cancelledById ?? '—']]));
        $('detail-body').replaceChildren(...content);
        progress.load();
        history.load();
      } else {
        const content = [section('처리 정보', auditFields(data.record))];
        if (data.reason) { const reason = node('section'); reason.className = 'detail-section'; const p = node('p', data.reason); p.className = 'reason'; reason.append(node('h3', '처리 사유'), p); content.push(reason); }
        content.push(comparison(data.before, data.after), relatedLink(data.record.productId, data.record.product), identifiers([['기록 ID', data.record.id], ['제품 ID', data.record.productId], ['대상 ID', data.record.targetId], ['이력 ID', data.record.eventId ?? '—'], ['처리 주체 회원 ID', data.record.actorId ?? '시스템']])); $('detail-body').replaceChildren(...content);
      }
      $('detail').focus();
    } catch (error) { if (current === version && error.name !== 'AbortError') $('detail-body').replaceChildren(node('p', error.message)); }
  };
  const load = async () => {
    const current = invalidate(); clear(); $('message').textContent = '기록을 불러오는 중이에요.'; $('message').dataset.kind = 'loading'; $('results').setAttribute('aria-busy', 'true');
    $('trade-overview').hidden = tab !== 'trades';
    if (tab === 'trades') $('overview-message').textContent = '검색 범위의 현황을 집계하는 중이에요.';
    const headings = tab === 'trades' ? ['번호', '제품', '거래 당사자', '상태', '예약 시각 (KST)'] : ['번호', '업무', '제품', '처리 주체', '결과', '처리 시각 (KST)'];
    $('records-table').dataset.kind = tab;
    const header = node('tr'); headings.forEach(value => { const th = node('th', value); th.setAttribute('scope', 'col'); header.append(th); }); $('columns').replaceChildren(header);
    const query = new URLSearchParams(filters); query.set('page', page); query.set('size', '20');
    try {
      const data = await request(`${tab}?${query}`, current);
      if (!data || current !== version) return;
      if (tab === 'trades') renderOverview(data.overview);
      $('message').textContent = data.items.length ? '' : '조건에 맞는 기록이 없어요.';
      $('message').dataset.kind = data.items.length ? '' : 'empty';
      $('count').textContent = `총 ${data.total.toLocaleString()}건`;
      $('updated').textContent = new Date().toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false }) + ' 기준';
      for (const [index, item] of data.items.entries()) {
        const ordinal = page * data.size + index + 1;
        const row = node('tr'), sequence = node('td', ordinal), cell = node('td'), button = node('button');
        sequence.className = 'sequence'; row.append(sequence);
        button.append(tab === 'trades' ? pair(productName(item.product), productModel(item.product)) : pair(text(item.action), '상세 보기 →'));
        button.type = 'button'; button.className = 'row-link'; button.setAttribute('aria-label', `${ordinal}번 ${productName(item.product)} 상세 조회`); button.addEventListener('click', () => open(item.id, button, ordinal)); cell.append(button); row.append(cell);
        const participants = node('div'); participants.className = 'participants';
        participants.append(memberCell(item.seller, '판매자'), memberCell(item.buyer, '구매자'));
        const values = tab === 'trades'
          ? [participants, status(item.transactionStatus || item.requestStatus), dateCell(item.reservedAt)]
          : [pair(productName(item.product), productModel(item.product)), item.actorId == null ? pair('시스템', '자동 처리') : memberCell(item.actor), status(item.result), dateCell(item.createdAt)];
        values.forEach((value, index) => { const td = node('td'); td.dataset.label = headings[index + 2]; td.append(value); row.append(td); }); $('rows').append(row);
      }
      const first = data.total && data.items.length ? page * 20 + 1 : 0;
      $('range').textContent = `${first}–${first ? page * 20 + data.items.length : 0} / ${data.total.toLocaleString()}건`;
      $('previous').disabled = page === 0; $('next').disabled = (page + 1) * 20 >= data.total;
    } catch (error) { if (current === version && error.name !== 'AbortError') { $('message').textContent = error.message; $('message').dataset.kind = 'error'; $('overview-message').textContent = '현황을 집계하지 못했어요. 새로고침으로 다시 시도해 주세요.'; } }
    finally { if (current === version) $('results').setAttribute('aria-busy', 'false'); }
  };
  const changeTab = (value, product) => {
    tab = value; page = 0; form.reset(); filters = new URLSearchParams();
    setRelated(product);
    for (const [id, active] of [['trades-tab', tab === 'trades'], ['audits-tab', tab !== 'trades']]) {
      if (active) $(id).setAttribute('aria-current', 'page'); else $(id).removeAttribute('aria-current');
    }
    $('status-field').hidden = tab !== 'trades'; $('action-field').hidden = tab === 'trades';
    $('actor-label').textContent = tab === 'trades' ? '거래 회원 검색' : '처리 회원 검색';
    $('date-label').textContent = tab === 'trades' ? '예약일 시작 (KST)' : '처리일 시작 (KST)';
    $('scope-note').textContent = tab === 'trades' ? '예약·완료 시각은 처리 기록을 기준으로 해요. 시각이 남아 있지 않은 기록은 ‘—’로 표시해요.' : '정정·무효 기록은 상세에서 변경 전후 내용과 사유를 확인할 수 있어요.';
    const title = tab === 'trades' ? '거래·취소 기록' : '업무 변경 기록';
    $('page-title').textContent = title; $('breadcrumb').textContent = title; document.title = `${title} · PastPort 관리자`;
    $('page-description').textContent = tab === 'trades' ? '제품별 거래 진행 상황과 취소 내역을 확인해요.' : '누가 무엇을 변경했는지, 처리 내용과 사유를 확인해요.';
    $('result-title').textContent = tab === 'trades' ? '거래 목록' : '업무 기록 목록'; load();
  };
  form.addEventListener('submit', event => {
    event.preventDefault(); const cancelReason = filters.get('cancelReason'); filters = new URLSearchParams();
    for (const [key, value] of new window.FormData(form)) if (value && (key !== 'status' || tab === 'trades') && (key !== 'action' || tab !== 'trades')) filters.set(key, String(value).trim());
    if (relatedProduct) filters.set('productId', relatedProduct.id);
    if (tab === 'trades' && filters.get('status') === 'CANCELLED' && cancelReason) filters.set('cancelReason', cancelReason);
    page = 0; load();
  });
  $('reset-filters').addEventListener('click', () => changeTab(tab));
  $('trades-tab').addEventListener('click', () => changeTab('trades'));
  $('audits-tab').addEventListener('click', () => changeTab('audit-logs'));
  $('clear-related').addEventListener('click', () => changeTab(tab));
  $('clear-cancel').addEventListener('click', () => { filters.delete('cancelReason'); page = 0; load(); });
  $('refresh').addEventListener('click', load);
  $('previous').addEventListener('click', () => { if (page > 0) { page--; load(); } });
  $('next').addEventListener('click', () => { if (!$('next').disabled) { page++; load(); } });
  const closeDetail = () => { invalidate(); hideDetail(true); };
  $('close-detail').addEventListener('click', closeDetail);
  $('detail').addEventListener('cancel', event => { event.preventDefault(); closeDetail(); });
  document.addEventListener('visibilitychange', () => { if (document.hidden) { invalidate(); clear(); setRelated(); } else load(); });
  window.addEventListener('pagehide', () => { invalidate(); clear(); setRelated(); });
  window.addEventListener('pageshow', event => { if (event.persisted) load(); });
  window.addEventListener('focus', load);
  load();
})();
