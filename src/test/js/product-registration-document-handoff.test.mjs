import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const registerPage = fs.readFileSync('src/main/resources/static/pages/register.html', 'utf8');
const products = fs.readFileSync('src/main/resources/static/js/products.js', 'utf8');
const docMask = fs.readFileSync('src/main/resources/static/js/doc-mask.js', 'utf8');

test('등록 화면은 자동 입력 미제공과 등록 후 문서 연결을 정확히 안내한다', () => {
  assert.match(registerPage, /자동 입력은 아직 제공하지 않아요/);
  assert.match(registerPage, /제품 등록 후 이 문서의 마스킹 화면으로 이동합니다/);
  assert.doesNotMatch(registerPage, /개인정보 마스킹 후 자동 입력/);
});

test('선택한 원본은 제품 생성 뒤에만 마스킹 화면으로 인계한다', () => {
  const createCall = products.indexOf("await api(API, { method: 'POST'");
  const documentTypeCheck = products.indexOf("!['image/jpeg', 'image/png'].includes(documentFile.type)");
  const prepareCall = products.indexOf('PastportDocMask.prepare(product.productId, documentFile)');
  assert.ok(createCall >= 0, '제품 생성 호출이 있어야 한다');
  assert.ok(documentTypeCheck >= 0 && documentTypeCheck < createCall, '증빙 형식은 제품 생성 전에 검사해야 한다');
  assert.ok(prepareCall > createCall, '문서 인계는 제품 생성 성공 뒤여야 한다');
  assert.match(docMask, /prepare\(productId, file\)/);
  assert.match(docMask, /if \(file\) await open\(file\)/);
  assert.ok(products.indexOf("navigate('docmask')") > prepareCall, '마스킹 화면 이동은 문서 인계 뒤에 있어야 한다');
});

test('제품 목록 로딩 실패 시 등록 화면에서 인계한 원본을 유지한다', () => {
  const loadedGuard = docMask.indexOf('if (!loaded) return;');
  const clearPending = docMask.indexOf('pendingFile = null;', loadedGuard);
  assert.ok(loadedGuard >= 0, '제품 목록 로딩 실패 분기가 있어야 한다');
  assert.ok(clearPending > loadedGuard, '제품 목록 성공을 확인한 뒤에만 인계 원본을 비워야 한다');
});
