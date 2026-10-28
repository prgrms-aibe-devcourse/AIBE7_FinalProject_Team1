#!/usr/bin/env node
// NFR-01 조회 API 응답 시간 측정: 요청 N건을 동시 C개로 보내 p50·p95·p99와 실패 수를 출력한다.
//
// 사용법:
//   node scripts/perf/measure-latency.mjs --base http://localhost:8080 --requests 200 --concurrency 10 \
//        --path /api/v1/listings?page=0&size=20 --path /api/v1/listings/1 --token <액세스 토큰>
//
// --token은 로그인이 필요한 경로(판매글 상세, 제품 조회 등)에만 필요하다. 토큰은 인자나 로그 파일에 남기지 말고 환경변수로 전달해도 된다(PERF_TOKEN).
// 목표: 측정 요청의 95%가 1초(1000ms) 이내. 목표를 넘으면 종료 코드 1로 끝난다.

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 ? args[index + 1] : fallback;
};
const options = (name) => args.flatMap((value, index) => (value === `--${name}` ? [args[index + 1]] : []));

const base = option('base', 'http://localhost:8080').replace(/\/$/, '');
const total = Number(option('requests', '200'));
const concurrency = Number(option('concurrency', '10'));
const warmup = Number(option('warmup', '10'));
const targetMs = Number(option('target-ms', '1000'));
const token = option('token', process.env.PERF_TOKEN || '');
const paths = options('path');
if (paths.length === 0) paths.push('/api/v1/listings?page=0&size=20');

const percentile = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];

async function timeOne(path) {
  const started = performance.now();
  try {
    const response = await fetch(base + path, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
    await response.arrayBuffer();
    return { ms: performance.now() - started, ok: response.status < 400, status: response.status };
  } catch {
    return { ms: performance.now() - started, ok: false, status: 0 };
  }
}

async function measure(path) {
  for (let i = 0; i < warmup; i += 1) await timeOne(path);
  const results = [];
  let next = 0;
  const startedAt = performance.now();
  await Promise.all(
    Array.from({ length: concurrency }, async () => {
      while (next < total) {
        next += 1;
        results.push(await timeOne(path));
      }
    }),
  );
  const elapsed = (performance.now() - startedAt) / 1000;
  const sorted = results.map((r) => r.ms).sort((a, b) => a - b);
  const failures = results.filter((r) => !r.ok);
  return {
    path: path.replace(/\?.*$/, ''),
    count: results.length,
    failures: failures.length,
    statuses: [...new Set(failures.map((r) => r.status))].join(','),
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1],
    rps: results.length / elapsed,
  };
}

console.log(`# 측정 환경: base=${base} requests=${total} concurrency=${concurrency} warmup=${warmup} 시각=${new Date().toISOString()}`);
console.log('| API | 요청 | 실패 | p50(ms) | p95(ms) | p99(ms) | max(ms) | RPS | 목표 |');
console.log('|---|---|---|---|---|---|---|---|---|');
let passed = true;
for (const path of paths) {
  const r = await measure(path);
  const pass = r.failures === 0 && r.p95 <= targetMs;
  passed &&= pass;
  const failure = r.failures ? `${r.failures}(${r.statuses})` : '0';
  console.log(
    `| ${r.path} | ${r.count} | ${failure} | ${r.p50.toFixed(0)} | ${r.p95.toFixed(0)} | ${r.p99.toFixed(0)} | ${r.max.toFixed(0)} | ${r.rps.toFixed(1)} | ${pass ? '통과' : '미달'} |`,
  );
}
process.exit(passed ? 0 : 1);
