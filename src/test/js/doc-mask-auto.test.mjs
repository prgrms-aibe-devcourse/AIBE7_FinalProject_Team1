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
