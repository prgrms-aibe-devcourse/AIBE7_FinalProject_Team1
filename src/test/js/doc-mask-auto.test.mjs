import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

/** 브라우저 전용 스크립트를 window 대역 위에서 실행해 자동 가리기 함수를 꺼낸다. */
function load() {
  const context = { window: {}, document: {} };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(new URL('../../main/resources/static/js/doc-mask-auto.js', import.meta.url), 'utf8'), context);
  return context.window.PastportDocMaskAuto;
}

/** 단어 목록을 만든다. 같은 줄이면 y가 같고, gap은 앞 단어와의 간격이다. */
function row(y, items) {
  let x = 20;
  return items.map(([text, gap = 8]) => {
    x += gap;
    const word = { text, x0: x, y0: y, x1: x + text.length * 18, y1: y + 22 };
    x = word.x1;
    return word;
  });
}

// vm 안에서 만든 배열은 prototype이 달라 deepEqual이 실패하므로 현재 realm의 배열로 옮긴다.
const kinds = (boxes) => Array.from(boxes, (box) => box.kind).sort();

test('글자 단위로 쪼개져 읽힌 도시 이름도 주소로 찾는다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = row(222, [['서', 0], ['울', 2], ['특별시', 1], ['송파구'], ['가락동'], ['75-4번지']]);
  assert.deepEqual(kinds(findPiiBoxes(groupLines(words))), ['주소']);
});

test('표 테두리가 사이에 끼어도 고객명 옆의 이름을 찾는다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = row(180, [['고', 0], ['객', 15], ['명', 15], ['|', 10], ['박', 20], ['철수', 1], ['상호', 250]]);
  const boxes = findPiiBoxes(groupLines(words));
  assert.deepEqual(kinds(boxes), ['이름']);
  // 이름 옆 칸의 라벨(상호)까지 가리지 않는다.
  const label = words.at(-1);
  assert.ok(boxes[0].x + boxes[0].w <= label.x0);
});

test('줄이 어긋나도 단어 위치로 다시 묶어 전화번호를 찾는다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = [...row(315, [['전', 0], ['화', 2], ['번호', 1], ['010-9876-5432']]), ...row(222, [['서울특별시'], ['송파구'], ['가락동']])];
  assert.deepEqual(kinds(findPiiBoxes(groupLines(words))), ['전화번호', '주소']);
});

test('아래 줄에 따로 적힌 동·호수도 주소로 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = row(253, [['현', 0], ['대', 1], ['아', 1], ['파트', 1], ['102', 8], ['동', 3], ['405', 8], ['호', 3]]);
  assert.deepEqual(kinds(findPiiBoxes(groupLines(words))), ['주소']);
});

test('개인정보가 없는 줄은 가리지 않는다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = row(100, [['접', 0], ['수', 2], ['일', 2], ['시'], ['2009년'], ['7', 12], ['월'], ['23', 12], ['일']]);
  assert.equal(findPiiBoxes(groupLines(words)).length, 0);
});

test('라벨 없이 이메일 바로 위 줄에 적힌 이름도 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = [...row(100, [['문', 0], ['정원', 14]]), ...row(130, [['qufekfasd@naver.com', 0]])];
  assert.deepEqual(kinds(findPiiBoxes(groupLines(words))), ['이름', '이메일']);
});

test('이메일 위 줄이 한글 이름 형태가 아니거나 멀리 떨어져 있으면 이름으로 보지 않는다', () => {
  const { findPiiBoxes, groupLines } = load();
  const far = [...row(100, [['문', 0], ['정원', 14]]), ...row(300, [['qufekfasd@naver.com', 0]])];
  assert.deepEqual(kinds(findPiiBoxes(groupLines(far))), ['이메일']);
  const notName = [...row(100, [['교육기관고객', 0]]), ...row(130, [['qufekfasd@naver.com', 0]])];
  assert.deepEqual(kinds(findPiiBoxes(groupLines(notName))), ['이메일']);
});

test('사업자등록번호는 계좌번호로 가리지 않는다', () => {
  const { findPiiBoxes, groupLines } = load();
  assert.equal(findPiiBoxes(groupLines(row(100, [['206-86-50913', 0]]))).length, 0);
  assert.deepEqual(kinds(findPiiBoxes(groupLines(row(100, [['110-123-456789', 0]])))), ['계좌번호']);
});

/** 분할 모드별로 미리 정한 단어를 돌려주는 OCR 대역. */
function fakeWorker(byMode) {
  const calls = [];
  let mode;
  return {
    calls,
    setParameters: async (p) => { mode = p.tessedit_pageseg_mode; calls.push(mode); },
    recognize: async () => ({ data: { words: (byMode[mode] || []).map((w) => ({ text: w.text, bbox: { x0: w.x0, y0: w.y0, x1: w.x1, y1: w.y1 } })) } }),
  };
}

test('분할 모드 3이 글자를 거의 못 찾으면 모드 6으로 다시 읽어 합친다', async () => {
  const { recognizeAll } = load();
  const worker = fakeWorker({ 3: [], 6: row(100, [['010-9876-5432', 0]]) });
  const boxes = await recognizeAll(worker, {});
  assert.deepEqual(worker.calls, ['3', '6']);
  assert.deepEqual(kinds(boxes), ['전화번호']);
});

test('모드 3이 충분히 읽고 가릴 곳도 찾았으면 다시 읽지 않는다', async () => {
  const { recognizeAll } = load();
  const filler = Array.from({ length: 20 }, (_, i) => row(300 + i * 40, [['품목', 0]])[0]);
  const worker = fakeWorker({ 3: [...filler, ...row(100, [['010-9876-5432', 0]])] });
  const boxes = await recognizeAll(worker, {});
  assert.deepEqual(worker.calls, ['3']);
  assert.deepEqual(kinds(boxes), ['전화번호']);
});

test('두 번째 읽기가 같은 곳을 다시 찾아도 상자를 중복으로 더하지 않는다', async () => {
  const { recognizeAll } = load();
  const phone = row(100, [['010-9876-5432', 0]]);
  const worker = fakeWorker({ 3: phone, 6: phone });
  assert.equal((await recognizeAll(worker, {})).length, 1);
});

test('일련번호는 라벨 뒤의 값을 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  const korean = row(100, [['일련', 0], ['번호', 4], ['CXV5J3Y2RD', 20]]);
  const spaced = row(200, [['일', 0], ['련', 2], ['번호', 2], ['C02TQZ26SAP', 20]]);
  const english = row(300, [['Serial', 0], ['No:', 6], ['F5KD93LMQ1', 20]]);
  const boxes = findPiiBoxes(groupLines([...korean, ...spaced, ...english]));
  assert.deepEqual(kinds(boxes), ['일련번호', '일련번호', '일련번호']);
  // 라벨은 가리지 않고 값만 가린다.
  assert.ok(boxes.every((box) => box.x >= 20 + 4 * 18));
});

test('라벨 뒤에 숫자 없는 일반 단어가 오면 일련번호로 보지 않는다', () => {
  const { findPiiBoxes, groupLines } = load();
  assert.equal(findPiiBoxes(groupLines(row(100, [['Serial', 0], ['number', 8], ['printed', 8]]))).length, 0);
});

test('고객명 계열 라벨 뒤의 이름을 가리고, "고객 지원" 같은 안내 문구는 가리지 않는다', () => {
  const { findPiiBoxes, groupLines } = load();
  for (const labelWords of [['고객명'], ['고객', '성함'], ['고', '객', '명']]) {
    const words = row(100, [...labelWords.map((w, i) => [w, i ? 3 : 0]), ['홍길동', 20]]);
    assert.deepEqual(kinds(findPiiBoxes(groupLines(words))), ['이름'], labelWords.join(''));
  }
  assert.equal(findPiiBoxes(groupLines(row(100, [['고객', 0], ['지원', 10], ['문의', 10]]))).length, 0);
});

test('라벨이 깨져 읽혀도 일련번호 모양의 단독 토큰은 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  const garbled = row(100, [['Qlgils', 0], ['CORFQZELSAPR', 30]]);
  const withDigits = row(200, [['일련번호', 0], ['CXV5J3Y2RD', 20]]);
  const boxes = findPiiBoxes(groupLines([...garbled, ...withDigits]));
  assert.deepEqual(kinds(boxes), ['일련번호', '일련번호']);
});

test('깨진 라벨 "련 호" 뒤의 값도 일련번호로 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  assert.deepEqual(kinds(findPiiBoxes(groupLines(row(100, [['련', 0], ['호', 6], ['C02TQ26SAP', 12]])))), ['일련번호']);
});

test('부품번호·URL·영어 단어·숫자뿐인 번호는 일련번호로 오탐하지 않는다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = row(100, [['MC6T4KH/A', 0], ['support.apple.com/ko-kr/HT201440', 8], ['APPLECARE', 8], ['MACBOOKPRO', 8], ['1208184429', 8]]);
  assert.equal(findPiiBoxes(groupLines(words)).length, 0);
});

test('부품번호는 일련번호와 모양이 같아도 가리지 않는다', () => {
  const { findPiiBoxes, groupLines } = load();
  const cases = [
    row(100, [['부품', 0], ['번호', 4], ['SUWC2ZX3KH9', 12]]),
    row(100, [['부품번호:', 0], ['MC6T4KH2A9X', 12]]),
    row(100, [['Part', 0], ['Number', 6], ['NT950XGK9X1', 12]]),
    row(100, [['모델번호', 0], ['SM950XGKC51', 12]]),
    row(100, [['MRMG3KH123/A', 0]]),
  ];
  for (const words of cases) assert.equal(findPiiBoxes(groupLines(words)).length, 0, words.map((w) => w.text).join(' '));
});

test('같은 줄에 부품번호와 일련번호가 함께 있으면 일련번호만 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = row(100, [['부품번호', 0], ['SUWC2ZX3KH9', 12], ['일련번호', 20], ['CXV5J3Y2RD', 12]]);
  const boxes = findPiiBoxes(groupLines(words));
  assert.deepEqual(kinds(boxes), ['일련번호']);
  assert.ok(boxes[0].x >= words[3].x0 - 4);
});

test('라벨은 읽혔는데 값이 깨져 읽힌 일련번호는 라벨 오른쪽을 통째로 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  // "일련번호: CXV5J3Y2RD"가 "일 련 민호 521"처럼 읽힌 경우. 값 규칙에는 걸리지 않는다.
  const words = row(100, [['일', 0], ['련', 2], ['민호', 2], ['521', 8], ['ㅣ', 6]]);
  const boxes = findPiiBoxes(groupLines(words));
  assert.deepEqual(kinds(boxes), ['일련번호']);
  const label = words[2];
  assert.ok(boxes[0].x >= label.x1 - 1, '라벨은 가리지 않는다');
  assert.ok(boxes[0].x + boxes[0].w >= words[3].x1, '읽힌 값 조각을 덮는다');
});

test('라벨 오른쪽에 읽힌 글자가 없어도 글자 높이 8배 폭은 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = row(100, [['일련번호', 0]]);
  const boxes = findPiiBoxes(groupLines(words));
  assert.equal(boxes.length, 1);
  assert.ok(boxes[0].w >= 22 * 8);
});

test('다음 칸이 멀리 떨어져 있으면 라벨 옆 덩어리까지만 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = row(100, [['일련번호', 0], ['K8', 8], ['반품날짜', 400]]);
  const boxes = findPiiBoxes(groupLines(words));
  assert.equal(boxes.length, 1);
  assert.ok(boxes[0].x + boxes[0].w <= words[2].x0);
});

test('별표로 일부를 가린 카드번호도 가린다', () => {
  const { findPiiBoxes, groupLines } = load();
  const labeled = row(100, [['카', 0], ['드', 2], ['번호', 2], [':', 6], ['이', 6], ['7790686089……', 8]]);
  const bare = row(200, [['3779880********', 0]]);
  const grouped = row(300, [['1234-56**-****-7890', 0]]);
  assert.deepEqual(kinds(findPiiBoxes(groupLines([...labeled, ...bare, ...grouped]))), ['카드번호', '카드번호', '카드번호']);
});

test('카드번호 라벨 뒤 값은 가리되 라벨은 가리지 않는다', () => {
  const { findPiiBoxes, groupLines } = load();
  const words = row(100, [['카드번호:', 0], ['3779880********', 8]]);
  const boxes = findPiiBoxes(groupLines(words));
  assert.deepEqual(kinds(boxes), ['카드번호']);
  assert.ok(boxes[0].x >= words[1].x0 - 4);
});

test('숫자가 거의 없는 점선·승인금액은 카드번호로 보지 않는다', () => {
  const { findPiiBoxes, groupLines } = load();
  assert.equal(findPiiBoxes(groupLines(row(100, [['카드번호:', 0], ['…………', 8]]))).length, 0);
  assert.equal(findPiiBoxes(groupLines(row(200, [['승인금액', 0], ['119,000원', 8]]))).length, 0);
});
