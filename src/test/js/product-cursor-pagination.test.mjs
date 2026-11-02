import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const products = fs.readFileSync('src/main/resources/static/js/products.js', 'utf8');
const documents = fs.readFileSync('src/main/resources/static/js/documents.js', 'utf8');
const docMask = fs.readFileSync('src/main/resources/static/js/doc-mask.js', 'utf8');

test('내 제품의 다음 페이지 요청은 서버가 돌려준 cursor를 전달한다', () => {
  assert.match(products, /const cursor = append \? state\.productPage\.nextCursor : null/);
  assert.match(products, /params\.set\('cursorCreatedAt', cursor\.createdAt\)/);
  assert.match(products, /params\.set\('cursorProductId', String\(cursor\.productId\)\)/);
});

test('검색은 남은 페이지를 모두 받은 뒤 전체 제품에서 실행한다', () => {
  const search = products.indexOf("event.target.matches?.('[data-product-search]')");
  const loadAll = products.indexOf('await loadAllProducts()', search);
  const render = products.indexOf('renderProducts()', loadAll);
  assert.ok(search >= 0 && loadAll > search && render > loadAll);
});

test('문서 선택기와 마스킹 선택기도 다음 cursor를 이어서 요청한다', () => {
  for (const source of [documents, docMask]) {
    assert.match(source, /cursor = .*page\?\.nextCursor \|\| null/);
    assert.match(source, /params\.set\('cursorCreatedAt', cursor\.createdAt\)/);
    assert.match(source, /params\.set\('cursorProductId', String\(cursor\.productId\)\)/);
  }
});
