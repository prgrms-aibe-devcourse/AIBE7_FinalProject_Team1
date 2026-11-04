// 문서 보관함의 "AI 정보 추출" 패널: 동의 → 추출 요청 → 진행 확인 → 결과 검토·수정 → 확정/반려.
// 서버 API는 /api/v1/documents/{id}/extractions 이며, 사용자가 검토한 값만 확정 요청으로 보낸다.
(() => {
  const NOTICE_VERSION = '2026-11-02';
  // 처리 목적, 전송 대상·범위, 비동의 시 이용 범위를 함께 고지한다. 정식 고지 문구는 팀 기준이 정해지면 교체한다.
  const NOTICE = '제품 정보를 읽어 이력 후보를 만들 목적으로, 개인정보가 가려진 사본이 외부 AI(Gemini)로 전송돼요. 원본과 가려진 개인정보는 전송되지 않아요. 동의하지 않아도 마스킹·사본 저장·직접 입력은 그대로 이용할 수 있어요.';
  const TYPES = [['RECEIPT', '영수증'], ['WARRANTY', '보증서'], ['REPAIR_REPORT', '수리 명세서'], ['OTHER', '기타']];
  // key는 서버 ExtractedFields 필드명과 같다.
  const FIELDS = [
    { key: 'productName', label: '제품명', type: 'text', max: 100 },
    { key: 'modelName', label: '모델명', type: 'text', max: 150 },
    { key: 'purchaseDate', label: '구매일', type: 'date' },
    { key: 'vendor', label: '구매처', type: 'text', max: 200 },
    { key: 'price', label: '구매 금액(원)', type: 'number', min: 0 },
    { key: 'warrantyMonths', label: '보증기간(개월)', type: 'number', min: 0, max: 120 },
    { key: 'repairDate', label: '수리일', type: 'date' },
    { key: 'repairDescription', label: '수리 내용', type: 'textarea', max: 2000 },
  ];
  const POLL_MS = 2000;
  const POLL_LIMIT = 90;

  const create = (tag, text, className = '') => {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text; return node;
  };

  /** 증빙 문서를 프로필 사진 팝업과 같은 형식의 모달 대화상자로 띄운다. */
  window.PastportDocPreview ??= (() => {
    let parts = null;
    const clear = () => {
      if (!parts) return;
      parts.img.hidden = true; parts.img.removeAttribute('src'); delete parts.img.src;
      parts.frame.hidden = true; parts.frame.removeAttribute('src'); delete parts.frame.src;
      parts.msg.hidden = true; parts.msg.textContent = '';
    };
    const close = () => {
      if (!parts) return;
      if (typeof parts.dialog.close === 'function') {
        if (parts.dialog.open !== false) parts.dialog.close();
      } else {
        parts.dialog.open = false;
        parts.dialog.hidden = true;
      }
      clear();
    };
    const ensure = () => {
      if (parts?.dialog?.isConnected) return parts;
      const dialog = create('dialog', null, 'mp-dialog doc-preview-dialog');
      dialog.dataset.docPreviewDialog = ''; dialog.setAttribute('data-doc-preview-dialog', '');
      dialog.setAttribute('aria-labelledby', 'doc-preview-title');
      const title = create('h2', '증빙 문서');
      title.id = 'doc-preview-title'; title.dataset.docPreviewTitle = ''; title.setAttribute('data-doc-preview-title', '');
      const body = create('div', null, 'mp-dialog-body doc-preview-body');
      body.dataset.docPreviewBody = ''; body.setAttribute('data-doc-preview-body', '');
      const stage = create('div', null, 'doc-preview-stage');
      stage.dataset.docPreviewStage = ''; stage.setAttribute('data-doc-preview-stage', '');
      const img = create('img');
      img.dataset.docPreviewImg = ''; img.setAttribute('data-doc-preview-img', ''); img.alt = '증빙 문서 미리보기'; img.hidden = true;
      const frame = create('iframe');
      frame.dataset.docPreviewFrame = ''; frame.setAttribute('data-doc-preview-frame', ''); frame.title = '증빙 문서 미리보기'; frame.hidden = true;
      stage.append(img, frame);
      const caption = create('small', '개인정보가 가려진 증빙 사본이에요.');
      caption.dataset.docPreviewCaption = ''; caption.setAttribute('data-doc-preview-caption', '');
      body.append(stage, caption);
      const msg = create('small', '', 'mp-photo-msg');
      msg.dataset.docPreviewMsg = ''; msg.setAttribute('data-doc-preview-msg', ''); msg.setAttribute('role', 'status'); msg.hidden = true;
      const actions = create('div', null, 'mp-dialog-actions doc-preview-actions');
      const closeBtn = create('button', '닫기', 'sc-btn primary');
      closeBtn.type = 'button'; closeBtn.dataset.docPreviewClose = ''; closeBtn.setAttribute('data-doc-preview-close', '');
      actions.append(closeBtn);
      dialog.append(title, body, msg, actions);
      img.addEventListener('error', () => {
        if (!img.getAttribute('src') && !img.src) return;
        img.hidden = true;
        msg.textContent = '문서를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
        msg.hidden = false;
      });
      closeBtn.addEventListener('click', close);
      dialog.addEventListener('click', (event) => { if (event.target === dialog) close(); });
      dialog.addEventListener('close', clear);
      (document.body || document.documentElement || document.root)?.append(dialog);
      parts = { dialog, title, body, stage, img, frame, caption, msg, closeBtn };
      return parts;
    };
    const open = ({ url, title = '증빙 문서', contentType = '' } = {}) => {
      if (!url) return;
      const p = ensure();
      clear();
      p.title.textContent = title || '증빙 문서';
      const isPdf = contentType === 'application/pdf' || /\.pdf(?:$|\?)/i.test(url);
      if (isPdf) {
        p.frame.src = url; p.frame.setAttribute('src', url); p.frame.hidden = false;
      } else {
        p.img.alt = `${p.title.textContent} 미리보기`;
        p.img.src = url; p.img.setAttribute('src', url); p.img.hidden = false;
      }
      p.dialog.hidden = false;
      if (typeof p.dialog.showModal === 'function') {
        if (!p.dialog.open) p.dialog.showModal();
      } else {
        p.dialog.open = true;
      }
    };
    document.addEventListener?.('click', (event) => {
      if (!parts) return;
      if (event.target?.closest?.('[data-doc-preview-close]') || event.target === parts.dialog) close();
    });
    document.addEventListener?.('pastport:auth-changed', close);
    document.addEventListener?.('pastport:page-shown', close);
    return Object.freeze({ open, close });
  })();

  async function api(path, options = {}) {
    const response = await window.PastportApi.fetch(path, options);
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(payload?.message || '요청을 처리하지 못했어요.');
      error.status = response.status; throw error;
    }
    return payload?.data;
  }
  const json = body => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

  // item: 문서 응답. 반환한 element를 문서 행 아래에 붙이고, 펼칠 때 load()를 부른다.
  function panel(item, { onChanged } = {}) {
    const element = create('div', null, 'doc-extract'); element.hidden = true;
    let polls = 0; let timer = null;

    const message = (text, kind = '') => {
      const note = create('p', text, 'doc-extract-note' + (kind ? ' ' + kind : '')); note.setAttribute('role', kind === 'is-error' ? 'alert' : 'status');
      return note;
    };
    const stopPolling = () => { clearTimeout(timer); timer = null; };

    // AI를 쓰지 않거나 추출이 실패했을 때의 길. 제품 여권의 이력 등록에서 이 문서를 증빙으로 연결해 직접 입력한다.
    function manualEntry() {
      const box = create('div', null, 'doc-extract-manual');
      const button = create('button', '이력 등록에서 직접 입력', 'button small'); button.type = 'button';
      button.dataset.page = 'passport'; button.dataset.productId = item.productId;
      box.append(create('span', 'AI 없이 직접 입력하려면 제품 여권의 이력 등록에서 방금 올린 문서를 증빙으로 선택해 연결하세요.'), button);
      return box;
    }

    function show(...nodes) { stopPolling(); element.replaceChildren(...nodes); }

    // 추출 기록이 없거나 반려·실패했을 때: 동의를 받고 요청한다.
    function requestForm(notice) {
      const form = create('form', null, 'doc-extract-form');
      const type = create('select'); type.name = 'documentType'; type.setAttribute('aria-label', '문서 종류');
      TYPES.forEach(([value, label]) => type.add(new Option(label, value)));
      const consent = create('label', null, 'doc-extract-consent');
      const box = create('input'); box.type = 'checkbox'; box.name = 'consented';
      consent.append(box, create('span', NOTICE));
      const submit = create('button', 'AI로 정보 추출', 'button primary small'); submit.type = 'submit'; submit.disabled = true;
      const feedback = create('div');
      const blocked = item.redactionRequired;
      if (blocked) { box.disabled = true; type.disabled = true; feedback.append(message('재마스킹이 필요한 문서예요. 사본을 교체한 뒤에 추출할 수 있어요.', 'is-error')); }
      box.addEventListener('change', () => { submit.disabled = !box.checked || blocked; });
      form.addEventListener('submit', async event => {
        event.preventDefault();
        if (!box.checked || blocked) return;
        submit.disabled = true; submit.textContent = '요청 중…';
        try {
          const extraction = await api('/api/v1/documents/' + item.id + '/extractions',
            json({ consented: true, consentNoticeVersion: NOTICE_VERSION, documentType: type.value }));
          polls = 0; render(extraction);
        } catch (error) {
          // 409는 이 문서에 이미 진행 중이거나 완료된 추출이 있다는 뜻이다. 이 폼에는 반려 버튼이 없으니 기존 추출 상태를 불러온다.
          if (error.status === 409) { load(); return; }
          feedback.replaceChildren(message(error.message, 'is-error'));
          submit.textContent = 'AI로 정보 추출'; submit.disabled = !box.checked;
        }
      });
      const typeRow = create('div', null, 'doc-extract-typerow'); typeRow.append(create('span', '문서 종류'), type);
      form.append(typeRow, consent, submit, feedback);
      show(...(notice ? [notice] : []), form, manualEntry());
    }

    // AI가 읽은 값을 수정할 수 있게 보여 주고, 사용자가 확인한 값만 확정한다.
    function reviewForm(extraction) {
      const facts = extraction.extractedFacts || {};
      const form = create('form', null, 'doc-extract-review'); form.noValidate = true;
      form.append(message('AI가 문서에서 읽은 값이에요. 원본과 비교해 틀린 곳은 고치고, 읽지 못한 칸은 비워 두세요.'));
      // 값을 사본과 대조해 확인할 수 있도록 같은 화면에서 문서를 열 수 있게 한다.
      const view = create('button', '문서 보기', 'button small'); view.type = 'button'; view.setAttribute('aria-haspopup', 'dialog');
      const viewFeedback = create('div');
      view.addEventListener('click', async () => {
        try {
          const file = await api('/api/v1/documents/' + item.id + '/file-url');
          const docTitle = item.name || (item.contentType === 'application/pdf' ? 'PDF' : '이미지') + ' 문서';
          window.PastportDocPreview?.open({ url: file.url, title: docTitle, contentType: item.contentType });
        } catch (error) { viewFeedback.replaceChildren(message(error.message, 'is-error')); }
      });
      form.append(view, viewFeedback);
      const grid = create('div', null, 'doc-extract-grid');
      FIELDS.forEach(field => {
        const label = create('label', null, field.type === 'textarea' ? 'wide' : '');
        label.append(create('span', field.label));
        const input = create(field.type === 'textarea' ? 'textarea' : 'input');
        if (field.type !== 'textarea') input.type = field.type;
        input.name = field.key;
        if (field.max && field.type !== 'number') input.maxLength = field.max;
        if (field.type === 'number') { input.min = field.min; if (field.max) input.max = field.max; input.step = 1; }
        const value = facts[field.key];
        input.value = value == null ? '' : String(value);
        if (value == null) input.placeholder = '문서에서 확인되지 않음';
        label.append(input); grid.append(label);
      });
      // 확정하면 저장될 이력을 입력값에 맞춰 미리 보여 준다.
      const preview = create('div', null, 'doc-extract-preview');
      const refreshPreview = () => {
        const events = previewEvents(collect(form));
        preview.replaceChildren(create('b', '확정하면 이렇게 이력에 저장돼요'));
        if (!events.length) { preview.append(create('p', '구매일 또는 수리일을 입력하면 저장될 이력이 여기에 보여요.', 'muted')); return; }
        events.forEach(event => {
          const card = create('div', null, 'doc-extract-preview-item');
          card.append(create('strong', event.type + ' 이력 · ' + event.date), create('span', event.title));
          if (event.vendor) card.append(create('span', '구매처/업체: ' + event.vendor));
          if (event.price != null) card.append(create('span', '금액: ' + event.price.toLocaleString('ko-KR') + '원'));
          if (event.content) card.append(create('span', event.content, 'preview-content'));
          card.append(create('small', '이 문서가 증빙으로 연결되고, 연결되면 삭제할 수 없어요.'));
          preview.append(card);
        });
      };
      const feedback = create('div');
      const actions = create('div', null, 'doc-extract-actions');
      const reject = create('button', '반려', 'button small'); reject.type = 'button';
      const confirm = create('button', '확인하고 확정', 'button primary small'); confirm.type = 'submit';
      actions.append(reject, confirm);
      form.append(grid, preview, feedback, actions);
      form.addEventListener('input', refreshPreview); refreshPreview();

      reject.addEventListener('click', async () => {
        if (!window.confirm('추출 결과를 버릴까요? 이 문서는 다시 추출할 수 있어요.')) return;
        reject.disabled = confirm.disabled = true;
        try { await api('/api/v1/documents/' + item.id + '/extractions/' + extraction.id + '/reject', { method: 'POST' }); await load(); }
        catch (error) { feedback.replaceChildren(message(error.message, 'is-error')); reject.disabled = confirm.disabled = false; }
      });
      form.addEventListener('submit', async event => {
        event.preventDefault();
        const reviewed = collect(form);
        const problem = validate(reviewed);
        if (problem) { feedback.replaceChildren(message(problem, 'is-error')); return; }
        reject.disabled = confirm.disabled = true; confirm.textContent = '확정 중…';
        try {
          const done = await api('/api/v1/documents/' + item.id + '/extractions/' + extraction.id + '/confirm', json({ reviewedData: reviewed }));
          render(done); onChanged?.();
        } catch (error) {
          feedback.replaceChildren(message(error.message, 'is-error'));
          reject.disabled = confirm.disabled = false; confirm.textContent = '확인하고 확정';
        }
      });
      show(form);
    }

    function render(extraction) {
      const status = extraction?.status;
      if (!status || status === 'REJECTED') { requestForm(); return; }
      if (status === 'FAILED') { requestForm(message('문서에서 정보를 읽지 못했어요. 다시 시도하거나 제품 정보를 직접 입력해 주세요.', 'is-error')); return; }
      if (status === 'QUEUED' || status === 'PROCESSING') { waiting(extraction); return; }
      if (status === 'EXTRACTED') { reviewForm(extraction); return; }
      // CONFIRMED
      show(message(extraction.resultEventId ? '확정했어요. 확정한 내용은 제품 이력에 반영됐어요.' : '확정했어요.'));
    }

    // 진행 중인 추출은 상태가 바뀔 때까지 주기적으로 확인한다. 패널이 화면에서 사라지면 멈춘다.
    function waiting(extraction, note = message('AI가 문서를 읽는 중이에요. 잠시만 기다려 주세요…')) {
      if (!note.isConnected) element.replaceChildren(note);
      stopPolling();
      // 응답이 패널을 접은 뒤에 도착해도 새 확인을 예약하지 않는다. 다시 펼치면 load()가 상태를 새로 읽는다.
      if (element.hidden) return;
      timer = setTimeout(async () => {
        if (!element.isConnected || element.hidden) return;
        if (++polls > POLL_LIMIT) { note.textContent = '시간이 오래 걸리고 있어요. 잠시 후 문서를 다시 열어 확인해 주세요.'; return; }
        try { render(await api('/api/v1/documents/' + item.id + '/extractions/' + extraction.id)); }
        catch (error) {
          // 일시적인 네트워크 오류나 5xx에서 확인을 멈추지 않는다. 오류를 보여 주면서 한도까지 다시 확인한다.
          note.textContent = error.message + ' 잠시 후 다시 확인해요…'; note.classList.add('is-error');
          waiting(extraction, note);
        }
      }, POLL_MS);
    }

    async function load() {
      show(message('불러오는 중이에요…'));
      try { render(await api('/api/v1/documents/' + item.id + '/extractions/latest')); }
      catch (error) {
        // 추출 기록이 아직 없으면 404로 응답한다.
        if (error.status === 404) requestForm();
        else show(message(error.message, 'is-error'));
      }
    }

    return {
      element,
      toggle() {
        element.hidden = !element.hidden;
        if (element.hidden) stopPolling();
        else { polls = 0; load(); }
        return !element.hidden;
      },
    };
  }

  // 입력값을 서버 ExtractedFields 형태로 바꾼다. 비어 있으면 null이다.
  function collect(form) {
    const data = {};
    FIELDS.forEach(field => {
      const raw = form.elements[field.key].value.trim();
      data[field.key] = raw === '' ? null : field.type === 'number' ? Number(raw) : raw;
    });
    return data;
  }
  function validate(data) {
    if (Object.values(data).every(value => value == null)) return '확정할 내용이 없어요. 한 가지 이상 입력해 주세요.';
    for (const field of FIELDS.filter(item => item.type === 'number')) {
      const value = data[field.key];
      if (value == null) continue;
      if (!Number.isSafeInteger(value) || value < field.min || (field.max && value > field.max)) return field.label + ' 값을 확인해 주세요.';
    }
    // 날짜를 임의로 채우지 않으므로 구매일이나 수리일이 있어야 이력을 만들 수 있다.
    if (!data.purchaseDate && !data.repairDate) return '이력을 만들려면 구매일 또는 수리일이 필요해요. 날짜를 입력해 주세요.';
    // 서버는 이력 내용이 2000자를 넘으면 잘라 저장하지 않고 거부한다. 제품 정보 줄까지 합친 길이로 미리 확인한다.
    if (previewEvents(data).some(event => event.content.length > 2000)) return '이력에 남길 내용이 너무 길어요. 수리 내용을 줄여 주세요.';
    return '';
  }

  // 확정하면 서버가 만들 이력. DocumentProductEventWriter.plan 과 같은 규칙이라 둘을 함께 고쳐야 한다.
  function previewEvents(data) {
    const product = [];
    if (data.productName) product.push('제품명: ' + data.productName);
    if (data.modelName) product.push('모델명: ' + data.modelName);
    if (data.warrantyMonths != null) product.push('보증기간: ' + data.warrantyMonths + '개월');
    const repairLine = data.repairDescription ? '수리 내용: ' + data.repairDescription : null;
    const events = [];
    if (data.purchaseDate) {
      events.push({
        type: '구매', date: data.purchaseDate, title: data.productName ? data.productName + ' 구매' : '구매',
        content: [...product, ...(!data.repairDate && repairLine ? [repairLine] : [])].join('\n'),
        vendor: data.vendor, price: data.price,
      });
    }
    if (data.repairDate) {
      const carry = !data.purchaseDate;
      events.push({
        type: '수리', date: data.repairDate, title: '수리',
        content: [...(carry ? product : []), ...(repairLine ? [repairLine] : [])].join('\n'),
        vendor: carry ? data.vendor : null, price: carry ? data.price : null,
      });
    }
    return events;
  }

  window.PastportDocExtraction = { panel, collect, validate, previewEvents };
})();
