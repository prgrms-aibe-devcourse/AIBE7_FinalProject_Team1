// 문서 마스킹 화면의 "자동 가리기": 브라우저 안에서 글자를 읽고(OCR), 개인정보 형태의 글자 위치를 찾아 가릴 영역으로 제안한다.
// 원본 이미지는 서버로 보내지 않는다. 외부에서 받는 것은 OCR 라이브러리와 언어 데이터뿐이다.
(() => {
  'use strict';

  const TESSERACT_URL = 'https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/tesseract.min.js';
  const LANGUAGES = ['kor', 'eng'];

  // OCR이 한글을 글자 단위로 끊어 읽는 경우가 있어서 라벨은 글자 사이 공백을 허용한다. 예: "주 문 번호"
  const label = (words) => '(?:' + words.map((word) => Array.from(word).join('\\s*')).join('|') + ')';
  // 도시 이름도 "서 울 특별시"처럼 끊겨 읽히는 일이 있어 같은 방식으로 공백을 허용한다.
  const CITY = label(['서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종', '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주']);
  const CITY_SUFFIX = label(['특별시', '광역시', '특별자치시', '특별자치도', '도']);
  const NAME_LABEL = label(['성명', '이름', '고객명', '구매자', '주문자', '수령인', '받는분', '예금주']);
  const ID_LABEL = label(['주문번호', '승인번호', '결제번호', '송장번호', '운송장번호', '거래번호']);
  const DATE_LIKE = /^(?:19|20)\d{2}-\d{1,2}-\d{1,2}$/;

  // group은 가릴 부분이 정규식의 몇 번째 묶음인지(0이면 일치한 전체). accept가 false를 돌려주면 건너뛴다.
  const RULES = [
    { kind: '주민등록번호', pattern: /\d{6}\s*[-–]?\s*[1-4]\d{6}/dg },
    { kind: '카드번호', pattern: /(?:\d{4}[-\s]){3}\d{4}/dg },
    { kind: '전화번호', pattern: /(?<![\dA-Za-z])(?:0\d{1,2}[\s.-]?\d{3,4}[\s.-]\d{4}|01\d\d{7,8})/dg },
    { kind: '이메일', pattern: /[\w.+-]+@[\w-]+(?:\.[\w-]+)+/dg },
    {
      kind: '계좌번호',
      pattern: /\d{2,6}-\d{2,6}-\d{2,8}(?:-\d{1,4})?/dg,
      accept: (text) => !DATE_LIKE.test(text) && text.replace(/\D/g, '').length >= 10,
    },
    { kind: '주문번호', pattern: new RegExp(`${ID_LABEL}\\s*[:：]?\\s*([A-Za-z0-9-]{6,})`, 'dg'), group: 1 },
    // 표 영수증은 이름 칸 바로 옆에 다른 라벨(상호 등)이 붙어 있어서, 글자 사이 간격이 좁은 글자만 한 단어로 이어 붙여 이름으로 본다.
    // 라벨과 값 사이의 표 테두리가 "|", "ㅣ"로 읽혀 끼어들 수 있어 구분 문자로 함께 허용한다.
    { kind: '이름', pattern: new RegExp(`${NAME_LABEL}[\\s:：|ㅣ]*([가-힣]{2,4})(?![가-힣])`, 'dg'), group: 1 },
    // 주소는 같은 줄의 다음 표 칸(테두리 글자)까지만 가린다.
    { kind: '주소', pattern: new RegExp(`${CITY}(?:${CITY_SUFFIX})?\\s*[가-힣]{1,6}\\s*(?:시|군|구)\\s[^|ㅣ]*`, 'dg') },
    { kind: '주소', pattern: /(?:주소|배송지)\s*[:：]?\s*(.{4,})/dg, group: 1 },
    // 도로명·지번 줄 아래에 따로 적히는 동·호수 줄. 예: "현대아파트 102동 405호"
    { kind: '주소', pattern: /(?:[가-힣]+(?:아파트|빌라|맨션|타워|빌딩|오피스텔)\s*)?\d{1,4}\s*동\s*\d{1,4}\s*호/dg },
  ];

  // lines: [{ words: [{ text, x0, y0, x1, y1 }] }] → 가릴 영역 [{ x, y, w, h, kind }]
  function findPiiBoxes(lines) {
    const boxes = [];
    for (const line of lines) {
      const words = (line.words || []).filter((word) => word.text && word.text.trim()).sort((a, b) => a.x0 - b.x0);
      if (!words.length) continue;
      let text = '';
      const spans = words.map((word, index) => {
        if (index) text += isSplitSyllable(words[index - 1], word) ? '' : ' ';
        const start = text.length;
        text += word.text.trim();
        return { word, start, end: text.length };
      });
      for (const rule of RULES) {
        for (const match of text.matchAll(rule.pattern)) {
          const group = rule.group || 0;
          if (!match.indices || !match.indices[group]) continue;
          const [from, to] = match.indices[group];
          if (rule.accept && !rule.accept(text.slice(from, to))) continue;
          const hit = spans.filter((span) => span.start < to && span.end > from).map((span) => span.word);
          if (!hit.length) continue;
          boxes.push(union(hit, rule.kind));
        }
      }
    }
    return boxes.filter((box, index) => !boxes.some((other, otherIndex) => otherIndex !== index && covers(other, box, otherIndex < index)));
  }

  // OCR이 "서 울", "박 철수"처럼 한 단어를 글자 단위로 쪼개 읽는 경우, 글자 사이가 좁으면 이어 붙여야 정규식이 맞는다.
  // 표 칸 사이처럼 간격이 넓으면 별개 단어로 둔다.
  function isSplitSyllable(prev, next) {
    if (!/[가-힣]$/.test(prev.text.trim()) || !/^[가-힣]/.test(next.text.trim())) return false;
    const height = Math.max(prev.y1 - prev.y0, next.y1 - next.y0);
    // 쪼개진 글자 사이는 글자 높이의 10% 안팎(1~3px)이고 실제 띄어쓰기는 40% 이상(9px)이라, 그 사이인 30%를 기준으로 삼는다.
    return next.x0 - prev.x1 <= height * 0.3;
  }

  // Tesseract가 표 영수증에서 줄(line)을 잘못 묶어 단어를 빠뜨리는 일이 있어, 단어 목록을 세로 위치로 직접 다시 묶는다.
  function groupLines(words) {
    const sorted = words
      .filter((word) => word.text && word.text.trim())
      .sort((a, b) => a.y0 + a.y1 - (b.y0 + b.y1));
    const lines = [];
    for (const word of sorted) {
      const center = (word.y0 + word.y1) / 2;
      const height = word.y1 - word.y0;
      const line = lines.find((item) => Math.abs(item.center - center) < 0.6 * Math.min(height, item.height));
      if (!line) {
        lines.push({ center, height, words: [word] });
        continue;
      }
      line.words.push(word);
      line.center = line.words.reduce((sum, item) => sum + (item.y0 + item.y1) / 2, 0) / line.words.length;
      line.height = Math.min(line.height, height);
    }
    return lines;
  }

  function union(allWords, kind) {
    // 표 테두리가 "|", "ㅣ" 같은 글자로 읽히면 높이가 비정상적으로 커서 상자가 이웃 줄까지 번진다. 글자가 있는 단어만으로 영역을 만든다.
    const solid = allWords.filter((word) => /[가-힣A-Za-z0-9]/.test(word.text));
    const words = solid.length ? solid : allWords;
    const x0 = Math.min(...words.map((word) => word.x0));
    const y0 = Math.min(...words.map((word) => word.y0));
    const x1 = Math.max(...words.map((word) => word.x1));
    const y1 = Math.max(...words.map((word) => word.y1));
    const pad = Math.max(2, Math.round((y1 - y0) * 0.15));
    return { x: Math.max(0, x0 - pad), y: Math.max(0, y0 - pad), w: x1 - x0 + pad * 2, h: y1 - y0 + pad * 2, kind };
  }

  // outer가 inner를 완전히 덮으면 true. 크기가 같으면 먼저 나온 것만 남기도록 sameWins로 가른다.
  function covers(outer, inner, sameWins) {
    const inside = outer.x <= inner.x && outer.y <= inner.y && outer.x + outer.w >= inner.x + inner.w && outer.y + outer.h >= inner.y + inner.h;
    if (!inside) return false;
    const same = outer.w === inner.w && outer.h === inner.h && outer.x === inner.x && outer.y === inner.y;
    return same ? sameWins : true;
  }

  function loadTesseract() {
    if (window.Tesseract) return Promise.resolve(window.Tesseract);
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = TESSERACT_URL;
      script.crossOrigin = 'anonymous';
      script.onload = () => (window.Tesseract ? resolve(window.Tesseract) : reject(new Error('tesseract')));
      script.onerror = () => reject(new Error('tesseract'));
      document.head.appendChild(script);
    });
  }

  // canvas의 글자를 읽어 가릴 영역을 돌려준다. onProgress(0~1)로 진행률을 알린다.
  async function detect(canvas, onProgress) {
    const Tesseract = await loadTesseract();
    const worker = await Tesseract.createWorker(LANGUAGES, 1, {
      logger: (message) => {
        if (onProgress && message.status === 'recognizing text') onProgress(message.progress);
      },
    });
    try {
      // 표가 있는 영수증은 기본 분할 모드에서 이름·주소 칸의 단어가 통째로 빠지는 일이 있어, 페이지 전체를 자동 분할(3)로 읽는다.
      await worker.setParameters({ tessedit_pageseg_mode: '3' });
      const { data } = await worker.recognize(canvas);
      const words = (data.words || []).map((word) => ({
        text: word.text,
        x0: word.bbox.x0,
        y0: word.bbox.y0,
        x1: word.bbox.x1,
        y1: word.bbox.y1,
      }));
      return findPiiBoxes(groupLines(words));
    } finally {
      await worker.terminate();
    }
  }

  window.PastportDocMaskAuto = { detect, findPiiBoxes, groupLines };
})();
