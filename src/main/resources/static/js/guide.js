// 이용 가이드는 처음에 1번만 보여 주고, 번호를 선택하면 해당 내용으로 전환한다.
(() => {
  function select(button) {
    const guide = button.closest('.gd');
    const target = document.getElementById(button.dataset.guideJump);
    if (!guide || !target || !guide.contains(target)) return;
    guide.querySelectorAll('.gd-sec').forEach((section) => { section.hidden = section !== target; });
    guide.querySelectorAll('[data-guide-jump]').forEach((tab) => {
      const selected = tab === button;
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
    });
  }

  document.addEventListener('click', (event) => {
    const button = event.target.closest?.('[data-guide-jump]');
    if (button) select(button);
  });

  document.addEventListener('keydown', (event) => {
    const button = event.target.closest?.('[data-guide-jump]');
    if (!button || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    const tabs = [...button.closest('.gd-jump').querySelectorAll('[data-guide-jump]')];
    const index = tabs.indexOf(button);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
      : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    event.preventDefault();
    select(tabs[next]);
    tabs[next].focus();
  });
})();

// 예시 화면을 누르면 새 탭 대신 같은 화면의 보기 창으로 크게 보여 준다.
// 같은 구역의 화면끼리 ‹ › 버튼, 방향키(← →)로 넘기고, 닫기 버튼·바깥 영역 클릭·Esc로 닫는다.
(() => {
  let dialog = null;
  let opener = null;
  let links = [];
  let index = 0;

  const ensure = () => {
    if (dialog) return dialog;
    dialog = document.createElement('dialog');
    dialog.className = 'gd-viewer';
    dialog.setAttribute('aria-label', '예시 화면 크게 보기');
    dialog.innerHTML = '<div class="gd-viewer-bar"><strong data-gd-viewer-title></strong>'
      + '<span class="gd-viewer-count" data-gd-viewer-count aria-live="polite"></span>'
      + '<button type="button" class="sc-btn" data-gd-viewer-close>닫기 ✕</button></div>'
      + '<div class="gd-viewer-stage">'
      + '<button type="button" class="gd-viewer-nav prev" data-gd-viewer-step="-1" aria-label="이전 화면">‹</button>'
      + '<div class="gd-viewer-body" data-gd-viewer-body><img data-gd-viewer-img alt=""></div>'
      + '<button type="button" class="gd-viewer-nav next" data-gd-viewer-step="1" aria-label="다음 화면">›</button>'
      + '</div>';
    dialog.addEventListener('click', (event) => {
      const step = event.target.closest('[data-gd-viewer-step]');
      if (step) { go(index + Number(step.dataset.gdViewerStep)); return; }
      // 이미지·막대 바깥(배경)을 누르거나 닫기 버튼을 누르면 닫는다.
      if (event.target === dialog || event.target.closest('[data-gd-viewer-close]')) dialog.close();
    });
    dialog.addEventListener('close', () => { opener?.focus?.({ preventScroll: true }); opener = null; });
    document.body.append(dialog);
    return dialog;
  };

  /** 범위 안에서만 이동하고, 이동한 화면에 맞춰 이미지·제목·번호·버튼 상태를 갱신한다. */
  function go(next) {
    if (!links.length || next < 0 || next >= links.length) return;
    index = next;
    const link = links[index];
    const image = link.querySelector('img');
    const img = dialog.querySelector('[data-gd-viewer-img]');
    img.src = link.getAttribute('href');
    img.alt = image?.alt || '';
    dialog.querySelector('[data-gd-viewer-title]').textContent = link.closest('figure')?.querySelector('figcaption b')?.textContent || '';
    dialog.querySelector('[data-gd-viewer-count]').textContent = links.length > 1 ? `${index + 1} / ${links.length}` : '';
    dialog.querySelector('[data-gd-viewer-body]').scrollTop = 0;
    const prev = dialog.querySelector('.prev'); const nextButton = dialog.querySelector('.next');
    prev.disabled = index === 0; nextButton.disabled = index === links.length - 1;
    prev.hidden = nextButton.hidden = links.length < 2;
    opener = link;
  }

  // 끝에서 이동 버튼이 비활성화되면 포커스가 사라지므로, 방향키는 보기 창이 열려 있는 동안 문서 전체에서 받는다.
  document.addEventListener('keydown', (event) => {
    if (!dialog?.open) return;
    if (event.key === 'ArrowLeft') { event.preventDefault(); go(index - 1); }
    else if (event.key === 'ArrowRight') { event.preventDefault(); go(index + 1); }
  });

  document.addEventListener('click', (event) => {
    const link = event.target.closest?.('.gd-shots a');
    if (!link) return;
    event.preventDefault();
    const box = ensure();
    links = [...link.closest('.gd-shots').querySelectorAll('a')];
    box.showModal();
    go(links.indexOf(link));
  });
})();
