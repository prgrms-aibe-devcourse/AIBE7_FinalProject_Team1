// 질문·답변은 현재 화면에서만 유지한다. 실제 조회 범위는 API가 현재 소유권·판매 상태로 다시 검사한다.
(() => {
  const panels = new Map();
  const eventNames = { PURCHASE:'구매', REPAIR:'수리', INSPECTION:'점검', PART_REPLACEMENT:'부품 교체', OWNERSHIP_TRANSFER:'소유권 이전', SPECIFICATION:'사양 변경' };
  const create = (tag, text, className = '') => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };
  function clear(scope) {
    for (const [key, panel] of panels) {
      if (scope && key !== scope) continue;
      panel.request?.abort(); clearInterval(panel.timer);
      panel.root.remove(); panels.delete(key);
    }
  }
  function evidence(item) {
    const row = create('li'), facts = item.facts || {};
    let title = item.label;
    if (item.type === 'EVENT') title = (eventNames[facts.type] || '제품 기록') + ' · ' + facts.occurredOn + ' · 이력 #' + facts.eventId;
    row.append(create('strong', title));
    const parts = [];
    if (item.type === 'EVENT') {
      parts.push(facts.status === 'VOID' ? '무효 기록 · 실제 사건 집계 제외' : '유효 기록');
      if (facts.corrected) parts.push('정정 반영');
      if (facts.previousOwner) parts.push('이전 소유자 기록');
      if (!facts.detailsReviewed) parts.push('공개된 세부 내용 없음');
      if (facts.title) parts.push(facts.title);
      if (facts.content) parts.push(facts.content);
      if (facts.replacedPart) parts.push('교체 부품: ' + facts.replacedPart);
    } else if (item.type === 'WARRANTY') parts.push('보증 만료일 ' + facts.warrantyExpiresOn + ' · 조회 기준일 ' + facts.asOf);
    else {
      parts.push([facts.manufacturer, facts.modelName].filter(Boolean).join(' '));
      const specs = facts.specifications || {};
      for (const [key, label, unit] of [['cpu','CPU',''],['gpu','GPU',''],['ramGb','메모리','GB'],['storageGb','저장 용량','GB'],['storageType','저장 방식',''],['os','운영체제',''],['batteryHealthPercent','등록 배터리 상태','%']])
        if (specs[key] != null) parts.push(label + ' ' + specs[key] + unit);
    }
    row.append(create('p', parts.filter(Boolean).join(' · '))); return row;
  }
  function mount(container, context) {
    if (!container || !/^[1-9]\d*$/.test(String(context.id)) || context.scope !== 'listings') return;
    clear(context.scope);
    const root = create('section', null, 'product-questions'); root.setAttribute('aria-label', '제품 이력 Q&A');
    root.append(create('h2', '제품 이력에 질문하기'), create('p', '등록된 사양·보증·이력을 근거로 답해요. 답변 아래에서 사용한 기록을 함께 확인해 주세요.', 'pq-help'));
    const examples = create('div', null, 'pq-examples'); examples.setAttribute('aria-label', '질문 예시');
    const form = create('form'), label = create('label', '궁금한 내용'), input = create('textarea');
    input.name = 'question'; input.maxLength = 1000; input.required = true; input.rows = 3;
    input.setAttribute('aria-label', '제품 이력 질문'); input.placeholder = '예: 현재 메모리 사양과 교체 기록을 알려줘'; label.append(input);
    const actions = create('div', null, 'pq-actions'), submit = create('button', '질문하기', 'sc-btn primary'); submit.type = 'submit';
    actions.append(submit, create('span', '최대 1,000자 · 질문할 때만 AI를 호출해요.', 'pq-help')); form.append(label, actions);
    const message = create('p', '', 'pq-message'); message.setAttribute('role', 'status'); message.setAttribute('aria-live', 'polite');
    const output = create('div', null, 'pq-output'); output.hidden = true;
    const panel = { root, request:null, timer:null, busy:false, until:0 }; panels.set(context.scope, panel);
    for (const question of ['최근 수리 기록이 있나요?', '현재 메모리 사양을 알려줘', '보증 만료일은 언제인가요?']) {
      const button = create('button', question); button.type = 'button';
      button.addEventListener('click', () => { if (!panel.busy) { input.value = question; input.focus(); } }); examples.append(button);
    }
    root.append(examples, form, message, output);
    container.append(root);
    const controls = () => {
      const seconds = Math.max(0, Math.ceil((panel.until - Date.now()) / 1000));
      submit.disabled = panel.busy || seconds > 0; input.disabled = panel.busy;
      examples.querySelectorAll('button').forEach(button => { button.disabled = panel.busy; });
      submit.textContent = panel.busy ? '등록된 기록을 확인하는 중…' : seconds ? seconds + '초 후 다시 질문' : '질문하기';
      if (!seconds) { clearInterval(panel.timer); panel.timer = null; }
    };
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (panel.busy || panel.until > Date.now() || !input.value.trim() || !form.reportValidity()) return;
      const controller = new AbortController(); panel.request = controller; panel.busy = true;
      output.replaceChildren(); output.hidden = true; message.textContent = '질문에 필요한 기록을 확인하고 있어요…'; message.classList.remove('is-error');
      root.setAttribute('aria-busy', 'true'); controls();
      try {
        const fetcher = window.PastportApi?.fetch || window.fetch.bind(window);
        const response = await fetcher('/api/v1/' + context.scope + '/' + context.id + '/ai/questions', {
          method:'POST', credentials:'same-origin', cache:'no-store', headers:{'Content-Type':'application/json'},
          body:JSON.stringify({question:input.value.trim()}), signal:controller.signal
        });
        const payload = await response.json().catch(() => null);
        if (controller.signal.aborted || !root.isConnected) return;
        if (!response.ok) {
          if (response.status === 429) {
            const wait = Number(response.headers.get('Retry-After'));
            panel.until = Date.now() + (Number.isFinite(wait) && wait > 0 ? Math.min(wait, 3600) : 60) * 1000;
            panel.timer = setInterval(controls, 1000);
          }
          if (response.status === 401 || response.status === 404) input.value = '';
          throw new Error(response.status === 401 ? '로그인 후 질문해 주세요.' : response.status === 404 ? '현재 이 제품이나 판매글에 질문할 수 없어요.' : response.status === 409 ? '확인 중 제품 정보나 이력이 바뀌었어요. 최신 내용을 확인한 뒤 다시 질문해 주세요.' : response.status === 429 ? 'AI 요청이 많아요. 표시된 시간이 지난 뒤 다시 질문해 주세요.' : response.status >= 500 ? '지금은 AI 답변을 만들지 못했어요. 잠시 후 다시 질문해 주세요. 제품과 전체 이력은 계속 확인할 수 있어요.' : payload?.fieldErrors?.[0]?.message || payload?.message || '질문을 처리하지 못했어요.');
        }
        const answer = payload?.data;
        if (typeof answer?.answer !== 'string' || !Array.isArray(answer.sources)) throw new Error('답변을 확인할 수 없어요. 다시 질문해 주세요.');
        output.append(create('h3', '등록된 기록에 따른 답변'), create('p', answer.answer, 'pq-answer'));
        if (answer.sources.length) {
          const details = create('details'), summary = create('summary', '답변에 사용한 근거 ' + answer.sources.length + '개'), list = create('ul');
          list.append(...answer.sources.map(evidence)); details.append(summary, list); output.append(details);
        } else output.append(create('p', '확인 가능한 근거가 없거나 답변 범위를 벗어난 질문이에요.', 'pq-help'));
        if (answer.sample) output.append(create('p', '샘플 이력으로 확인하는 답변이에요.', 'pq-help'));
        output.hidden = false; message.textContent = '등록된 기록만으로 설명한 답변이에요. 전체 이력도 함께 확인해 주세요.';
      } catch (error) {
        if (!controller.signal.aborted && root.isConnected) { message.textContent = error instanceof TypeError ? '서버에 연결하지 못했어요. 연결 상태를 확인하고 다시 질문해 주세요.' : error.message; message.classList.add('is-error'); }
      } finally {
        if (!controller.signal.aborted && root.isConnected) { panel.busy = false; root.setAttribute('aria-busy', 'false'); controls(); }
      }
    });
  }
  document.addEventListener('pastport:page-shown', event => {
    if (event.detail.id !== 'detail') clear('listings');
  });
  document.addEventListener('pastport:auth-changed', () => clear());
  window.addEventListener('pageshow', event => { if (event.persisted) clear(); });
  window.PastportProductQuestions = Object.freeze({mount, clear});
})();
