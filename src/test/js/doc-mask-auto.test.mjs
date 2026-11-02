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
