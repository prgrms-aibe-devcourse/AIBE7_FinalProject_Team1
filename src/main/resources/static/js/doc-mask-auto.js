// 문서 마스킹 화면의 "자동 가리기": 브라우저 안에서 글자를 읽고(OCR), 개인정보 형태의 글자 위치를 찾아 가릴 영역으로 제안한다.
// 원본 이미지는 서버로 보내지 않는다. 외부에서 받는 것은 OCR 라이브러리와 언어 데이터뿐이다.
(() => {
  'use strict';

  const TESSERACT_URL = 'https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/tesseract.min.js';
  const LANGUAGES = ['kor', 'eng'];
  const PREP_MIN_SIDE = 1600;
  const PREP_MAX_FACTOR = 3;
  const FALLBACK_MIN_WORDS = 20;
  // 사진이 기울어 OCR이 글자를 못 읽는 경우의 재시도 각도(도). 시계 방향이 +이다. 사진은 보통 15° 안팎으로 기울어 찍히므로 큰 각도부터 본다.
  const TILT_ANGLES = [16, -16, 8, -8];
  // 이 신뢰도(%) 이상인 두 글자 이상 단어가 이 비율을 넘어야 "잘 읽었다"고 본다.
  const READABLE_CONFIDENCE = 60;
  const READABLE_SHARE = 0.4;
  // 이름 라벨 없이 이메일 바로 위 줄에 적히는 이름(예: 영수증 상단 "문 정원"). 한글 2~4자, 글자 사이 공백은 허용한다.
  const BARE_NAME = /^[가-힣](?:\s?[가-힣]){1,3}$/;
  // 사업자등록번호(000-00-00000)는 개인정보가 아니라 상점 정보라서 계좌번호로 오탐하지 않는다.
  const BUSINESS_NO = /^\d{3}-\d{2}-\d{5}$/;

  // OCR이 한글을 글자 단위로 끊어 읽는 경우가 있어서 라벨은 글자 사이 공백을 허용한다. 예: "주 문 번호"
  const label = (words) => '(?:' + words.map((word) => Array.from(word).join('\\s*')).join('|') + ')';
  // 도시 이름도 "서 울 특별시"처럼 끊겨 읽히는 일이 있어 같은 방식으로 공백을 허용한다.
  const CITY = label(['서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종', '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주']);
  const CITY_SUFFIX = label(['특별시', '광역시', '특별자치시', '특별자치도', '도']);
  // "고객" 한 단어만으로는 "고객 지원 문의" 같은 안내 문구까지 이름으로 가려서 라벨에 넣지 않는다.
  const NAME_LABEL = label(['성명', '성함', '이름', '고객명', '고객성명', '고객성함', '고객이름', '구매자', '주문자', '수령인', '받는분', '예금주']);
  const ID_LABEL = label(['주문번호', '승인번호', '결제번호', '송장번호', '운송장번호', '거래번호']);
  // 부품번호·모델번호는 제품 종류를 나타낼 뿐 개인을 특정하지 않아 가리지 않는다. 일련번호와 모양이 비슷해서 단독 토큰 규칙에서 따로 뺀다.
  const PART_LABEL = '(?:' + label(['부품번호', '모델번호', '품번']) + '|[Pp][Aa][Rr][Tt]\\s*(?:[Nn][Oo]\\.?|[Nn]umber)?)';
  const SERIAL_LABEL = '(?:' + label(['시리얼번호', '시리얼']) + '|일?\\s*련\\s*[번빈민]?\\s*호|serial\\s*(?:no\\.?|number)?|s\\/n|(?<![A-Za-z])sn(?=\\s*[:：.]))';
  const CARD_LABEL = label(['카드번호']);
  const DATE_LIKE = /^(?:19|20)\d{2}-\d{1,2}-\d{1,2}$/;

  // group은 가릴 부분이 정규식의 몇 번째 묶음인지(0이면 일치한 전체). accept가 false를 돌려주면 건너뛴다.
  const RULES = [
    { kind: '주민등록번호', pattern: /\d{6}\s*[-–]?\s*[1-4]\d{6}/dg },
    { kind: '카드번호', pattern: /(?:[\d*•●]{4}[-\s]){3}[\d*•●]{4}/dg },
    // 카드 영수증은 번호 일부를 별표로 가려 찍는다(예: 3779880********). 별표가 사진에서는 "……", "···"로 읽히고 라벨 뒤에 글자 하나가 끼기도 해서,
    // 라벨 뒤 3글자 이내의 잡음은 건너뛰고 숫자·별표·점으로 이어진 덩어리를 번호로 본다. 숫자가 4개 이상이어야 한다.
    {
      kind: '카드번호',
      pattern: new RegExp(`${CARD_LABEL}\\s*[:：]?\\s*[^\\d*•●\\s]{0,3}\\s*([\\d*•●·.…ㆍXx-]{6,})`, 'dg'),
      group: 1,
      accept: (text) => (text.match(/\d/g) || []).length >= 4,
    },
    // 라벨이 없어도 앞자리 숫자 뒤에 별표가 이어지면 가려진 카드번호 형태다.
    { kind: '카드번호', pattern: /(?<![\d*])\d{4,6}[-\s]?(?:\d{2,4}[-\s]?)?[*•●]{4,}[\d*•●-]*/dg },
    { kind: '전화번호', pattern: /(?<![\dA-Za-z])(?:0\d{1,2}[\s.-]?\d{3,4}[\s.-]\d{4}|01\d\d{7,8})/dg },
    { kind: '이메일', pattern: /[\w.+-]+@[\w-]+(?:\.[\w-]+)+/dg },
    {
      kind: '계좌번호',
      pattern: /\d{2,6}-\d{2,6}-\d{2,8}(?:-\d{1,4})?/dg,
      accept: (text) => !DATE_LIKE.test(text) && !BUSINESS_NO.test(text) && text.replace(/\D/g, '').length >= 10,
    },
    { kind: '주문번호', pattern: new RegExp(`${ID_LABEL}\\s*[:：]?\\s*([A-Za-z0-9-]{6,})`, 'dg'), group: 1 },
    // 제품 일련번호는 기기를 특정하므로 반드시 가린다. 값에 숫자가 하나도 없으면 라벨 뒤의 일반 단어(예: "number")이므로 건너뛴다.
    { kind: '일련번호', pattern: new RegExp(`${SERIAL_LABEL}\\s*[:：]?\\s*([A-Za-z0-9-]{6,})`, 'dgi'), group: 1, accept: (text) => /\d/.test(text) },
    // 사진에서는 라벨이 깨져 읽혀(예: "일련 번호" → "Qlgils") 라벨 규칙이 못 잡는 일이 많아, 라벨 없이도 일련번호 모양의 단독 토큰을 가린다.
    { kind: '일련번호', pattern: new RegExp(`(?<![A-Za-z0-9/.])(?<!${PART_LABEL}\\s*[:：]?\\s*)[A-Z0-9]{9,12}(?![A-Za-z0-9])(?!/[A-Za-z0-9])`, 'dg'), accept: isSerialShaped },
    // 표 영수증은 이름 칸 바로 옆에 다른 라벨(상호 등)이 붙어 있어서, 글자 사이 간격이 좁은 글자만 한 단어로 이어 붙여 이름으로 본다.
    // 라벨과 값 사이의 표 테두리가 "|", "ㅣ"로 읽혀 끼어들 수 있어 구분 문자로 함께 허용한다.
    { kind: '이름', pattern: new RegExp(`${NAME_LABEL}[\\s:：|ㅣ]*([가-힣]{2,4})(?![가-힣])`, 'dg'), group: 1 },
    // 주소는 같은 줄의 다음 표 칸(테두리 글자)까지만 가린다.
    { kind: '주소', pattern: new RegExp(`${CITY}(?:${CITY_SUFFIX})?\\s*[가-힣]{1,6}\\s*(?:시|군|구)\\s[^|ㅣ]*`, 'dg') },
    { kind: '주소', pattern: /(?:주소|배송지)\s*[:：]?\s*(.{4,})/dg, group: 1 },
    // 도로명·지번 줄 아래에 따로 적히는 동·호수 줄. 예: "현대아파트 102동 405호"
    { kind: '주소', pattern: /(?:[가-힣]+(?:아파트|빌라|맨션|타워|빌딩|오피스텔)\s*)?\d{1,4}\s*동\s*\d{1,4}\s*호/dg },
  ];

  // 대문자·숫자 9~12자. 숫자만이면 바코드·금액이고, 숫자 없이 글자만이면 모음이 적을 때만(영어 단어 제외) 일련번호로 본다.
  function isSerialShaped(text) {
    if (!/[A-Z]/.test(text)) return false;
    if (/\d/.test(text)) return true;
    return (text.match(/[AEIOU]/g) || []).length / text.length <= 0.3;
  }

  // 저해상도 사진은 라벨("일련번호")은 읽히는데 값(CXV5J3Y2RD)은 깨져 읽혀서 값 규칙이 못 잡는다. 라벨은 있고 값을 못 읽은 줄은 라벨 오른쪽의
  // 글자 덩어리(글자 높이의 4배 넘게 벌어지기 전까지)를 가리고, 덩어리가 좁거나 없으면 글자 높이 8배 폭까지 넓힌다. 놓치는 것보다 더 가리는 쪽이 안전하다.
  function labelOnlySerial(text, spans) {
    const found = [];
    for (const match of text.matchAll(new RegExp(SERIAL_LABEL, 'dgi'))) {
      // 영어 라벨("Serial number printed on back" 같은 문장)은 값이 없으면 안내 문구일 수 있어 한글 라벨일 때만 쓴다.
      if (/^[a-z]/i.test(match[0])) continue;
      const [from, to] = match.indices[0];
      const label = spans.filter((span) => span.start < to && span.end > from).map((span) => span.word);
      if (!label.length) continue;
      const height = Math.max(...label.map((word) => word.y1 - word.y0));
      const labelRight = Math.max(...label.map((word) => word.x1));
      const tail = [];
      let right = labelRight;
      for (const span of spans.filter((item) => item.start >= to)) {
        if (span.word.x0 - right > height * 4) break;
        tail.push(span.word);
        right = Math.max(right, span.word.x1);
      }
      const base = union(tail.length ? tail : label, '일련번호');
      const left = tail.length ? base.x : labelRight;
      // estimated: 값을 읽은 게 아니라 라벨 오른쪽을 짐작해 넓힌 상자다. 회전 재시도를 멈출 근거로 쓰지 않는다.
      found.push({ x: left, y: base.y, w: Math.max(base.x + base.w - left, height * 8), h: base.h, kind: '일련번호', estimated: true });
    }
    return found;
  }

  // lines: [{ words: [{ text, x0, y0, x1, y1 }] }] → 가릴 영역 [{ x, y, w, h, kind }]
  function findPiiBoxes(lines) {
    const boxes = [];
    let previous = null;
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
      let hasEmail = false;
      const before = boxes.length;
      for (const rule of RULES) {
        for (const match of text.matchAll(rule.pattern)) {
          const group = rule.group || 0;
          if (!match.indices || !match.indices[group]) continue;
          const [from, to] = match.indices[group];
          if (rule.accept && !rule.accept(text.slice(from, to))) continue;
          const hit = spans.filter((span) => span.start < to && span.end > from).map((span) => span.word);
          if (!hit.length) continue;
          boxes.push(union(hit, rule.kind));
          if (rule.kind === '이메일') hasEmail = true;
        }
      }
      // 값이 못 읽힌 일련번호는 라벨 오른쪽을 통째로 가린다(labelOnlySerial).
      if (!boxes.slice(before).some((box) => box.kind === '일련번호')) boxes.push(...labelOnlySerial(text, spans));
      // 이메일 바로 위 줄에 라벨 없이 적힌 이름은 구매자 이름으로 본다. 윗줄이 한글 2~4자뿐일 때만 가리고, 줄 간격이 벌어지면 건너뛴다.
      if (hasEmail && previous && BARE_NAME.test(previous.text) && Math.min(...words.map((w) => w.y0)) - previous.bottom < previous.height * 1.5) {
        boxes.push(union(previous.words, '이름'));
      }
      previous = { text, words, bottom: Math.max(...words.map((w) => w.y1)), height: Math.max(...words.map((w) => w.y1 - w.y0)) };
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
    const prepared = prepare(canvas);
    try {
      const boxes = await recognizeAll(worker, prepared.canvas);
      // 키운 이미지 위에서 찾은 좌표를 원본 크기로 되돌린다.
      const k = prepared.factor;
      return boxes.map((box) => ({ ...box, x: box.x / k, y: box.y / k, w: box.w / k, h: box.h / k }));
    } finally {
      if (prepared.canvas !== canvas) {
        prepared.canvas.width = 0;
        prepared.canvas.height = 0;
      }
      await worker.terminate();
    }
  }

  // 작은 사진은 글자가 너무 작아 OCR이 놓치므로 긴 변이 PREP_MIN_SIDE가 되도록 키우고(최대 3배), 흑백·대비를 높여 그림자의 영향을 줄인다.
  // ctx.filter를 지원하지 않는 브라우저에서는 확대만 적용된다.
  function prepare(canvas) {
    const factor = Math.max(1, Math.min(PREP_MAX_FACTOR, PREP_MIN_SIDE / Math.max(canvas.width, canvas.height)));
    const out = document.createElement('canvas');
    out.width = Math.round(canvas.width * factor);
    out.height = Math.round(canvas.height * factor);
    const ctx = out.getContext('2d');
    ctx.filter = 'grayscale(1) contrast(1.3)';
    ctx.drawImage(canvas, 0, 0, out.width, out.height);
    return { canvas: out, factor };
  }

  // 표 영수증은 자동 분할(3)이 잘 맞고, 기울어지거나 그림자가 진 사진은 3에서 글자를 거의 못 찾는다. 3으로 읽은 결과가 빈약하거나
  // 가릴 곳이 하나도 없으면 한 덩어리 글(6)로 다시 읽고, 두 결과를 합친다.
  async function recognizeAll(worker, canvas) {
    const first = await readWords(worker, canvas, '3');
    const boxes = findPiiBoxes(groupLines(first));
    // 가릴 곳을 찾았더라도 일련번호는 못 찾았고 글자도 부실하게 읽었다면(기울어진 사진에서 이메일만 읽힌 경우) 아래 재시도로 넘어간다.
    if (boxes.length && (hasLocatedSerial(boxes) || isReadable(first))) return boxes;
    const secondWords = await readWords(worker, canvas, '6');
    const merged = mergeBoxes(boxes, findPiiBoxes(groupLines(secondWords)));
    // 일련번호 값을 읽었거나 글자를 잘 읽었다면 거기서 끝낸다. 일련번호가 없는 일반 영수증이 늘 느려지지 않게, 회전 재시도는 읽기 자체가 부실할 때만 한다.
    if (hasLocatedSerial(merged) || isReadable(first) || isReadable(secondWords)) return merged;
    return mergeBoxes(merged, await recognizeTilted(worker, canvas));
  }

  // 일련번호 값을 읽어서 찾은 상자가 있는지. 라벨만 읽고 오른쪽을 짐작해 넓힌 상자(estimated)는 찾은 것으로 치지 않는다.
  function hasLocatedSerial(boxes) {
    return boxes.some((box) => box.kind === '일련번호' && !box.estimated);
  }

  // 단어가 충분하고, 그중 신뢰도가 높은 단어가 일정 비율 이상이면 글자를 제대로 읽은 것이다. 기울어진 사진은 단어 수는 있어도 잡음이 대부분이다.
  function isReadable(words) {
    if (words.length < FALLBACK_MIN_WORDS) return false;
    const real = words.filter((word) => word.text.trim().length >= 2);
    if (!real.length) return false;
    return real.filter((word) => word.conf >= READABLE_CONFIDENCE).length / real.length >= READABLE_SHARE;
  }

  // 사진을 몇 가지 각도로 돌려 다시 읽는다. 일련번호를 찾으면 거기서 멈추고, 찾은 위치는 원래 사진 좌표로 되돌린다.
  async function recognizeTilted(worker, canvas) {
    let boxes = [];
    for (const degrees of TILT_ANGLES) {
      const tilted = rotateCanvas(canvas, degrees);
      try {
        let found = [];
        for (const mode of ['3', '6']) {
          found = mergeBoxes(found, findPiiBoxes(groupLines(await readWords(worker, tilted.canvas, mode))));
          if (hasLocatedSerial(found)) break;
        }
        boxes = mergeBoxes(boxes, found.flatMap((box) => unrotateBox(box, tilted)));
        if (hasLocatedSerial(found)) break;
      } finally {
        tilted.canvas.width = 0;
        tilted.canvas.height = 0;
      }
    }
    return boxes;
  }

  // 가운데를 기준으로 돌린 캔버스를 만든다. 빈 곳은 흰색으로 채운다.
  function rotateCanvas(canvas, degrees) {
    const radians = (degrees * Math.PI) / 180;
    const sin = Math.sin(radians);
    const cos = Math.cos(radians);
    const width = Math.ceil(Math.abs(canvas.width * cos) + Math.abs(canvas.height * sin));
    const height = Math.ceil(Math.abs(canvas.width * sin) + Math.abs(canvas.height * cos));
    const out = document.createElement('canvas');
    out.width = width;
    out.height = height;
    const ctx = out.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, width, height);
    ctx.translate(width / 2, height / 2);
    ctx.rotate(radians);
    ctx.drawImage(canvas, -canvas.width / 2, -canvas.height / 2);
    return { canvas: out, sin, cos, sourceWidth: canvas.width, sourceHeight: canvas.height };
  }

  // 돌린 사진 위의 상자를 원래 좌표로 되돌린다. 기울어진 글줄을 한 상자로 감싸면 이웃 줄까지 덮이므로, 글줄 방향으로 잘게 나눠 조각마다 감싼다.
  function unrotateBox(box, tilted) {
    const { canvas, sin, cos, sourceWidth, sourceHeight } = tilted;
    const back = (x, y) => {
      const dx = x - canvas.width / 2;
      const dy = y - canvas.height / 2;
      return { x: dx * cos + dy * sin + sourceWidth / 2, y: -dx * sin + dy * cos + sourceHeight / 2 };
    };
    // 조각이 짧을수록 기울어서 생기는 위아래 번짐이 줄어든다. 글자 높이만큼씩 나눈다.
    const pieces = Math.max(1, Math.min(16, Math.ceil(box.w / box.h)));
    const step = box.w / pieces;
    const result = [];
    for (let i = 0; i < pieces; i += 1) {
      const left = box.x + step * i;
      const corners = [back(left, box.y), back(left + step, box.y), back(left, box.y + box.h), back(left + step, box.y + box.h)];
      const x0 = Math.max(0, Math.min(...corners.map((p) => p.x)));
      const y0 = Math.max(0, Math.min(...corners.map((p) => p.y)));
      const x1 = Math.min(sourceWidth, Math.max(...corners.map((p) => p.x)));
      const y1 = Math.min(sourceHeight, Math.max(...corners.map((p) => p.y)));
      if (x1 > x0 && y1 > y0) result.push({ x: x0, y: y0, w: x1 - x0, h: y1 - y0, kind: box.kind, estimated: box.estimated });
    }
    return result;
  }

  async function readWords(worker, canvas, mode) {
    await worker.setParameters({ tessedit_pageseg_mode: mode });
    const { data } = await worker.recognize(canvas);
    return (data.words || []).map((word) => ({
      text: word.text,
      conf: word.confidence,
      x0: word.bbox.x0,
      y0: word.bbox.y0,
      x1: word.bbox.x1,
      y1: word.bbox.y1,
    }));
  }

  // 두 번째 결과 중 이미 가려진 곳과 절반 넘게 겹치는 상자는 버리고 나머지만 더한다.
  function mergeBoxes(base, extra) {
    const merged = [...base];
    for (const box of extra) {
      if (!merged.some((other) => overlapRatio(other, box) > 0.5)) merged.push(box);
    }
    return merged;
  }

  function overlapRatio(a, b) {
    const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
    const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
    if (w <= 0 || h <= 0) return 0;
    return (w * h) / Math.min(a.w * a.h, b.w * b.h);
  }

  window.PastportDocMaskAuto = { detect, findPiiBoxes, groupLines, recognizeAll, mergeBoxes };
})();
