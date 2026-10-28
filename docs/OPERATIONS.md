# 운영·검증 기준

비기능 요구사항(SEC-01, NFR-01, NFR-04, NFR-07) 가운데 코드가 아니라 기준과 절차로 정해 두어야 하는 항목을 모았다.
**팀 확정이 필요한 값은 "제안"으로 표시했다.** 확정되면 이 문서를 고친다.

## 1. HTTPS와 인증 쿠키 (SEC-01)

| 항목 | 기준 | 구현 위치 |
|---|---|---|
| 인증 쿠키 | HttpOnly, SameSite=Strict, 배포에서는 Secure | `AuthController`, `JwtProperties.refreshCookieSecure` |
| 배포 Secure 강제 | `prod` 프로필에서 Secure가 꺼져 있거나 로컬용 JWT 키면 서버 시작 거부 | `ProductionSecurityGuard` |
| `application-prod.yaml` | `app.jwt.refresh-cookie-secure: true` 명시 | 설정 파일 |
| HSTS | HTTPS 요청 응답에만 `Strict-Transport-Security: max-age=31536000 ; includeSubDomains` | `SecurityConfig`, `SecurityHttpTest` |
| 프록시 뒤 HTTPS 인식 | `server.forward-headers-strategy: framework` | `application.yaml` |
| CSRF | 쿠키 기반 상태 변경 요청에 `X-XSRF-TOKEN` 검증 | `SecurityConfig`, `SecurityHttpTest` |

**배포 담당이 할 일**
- TLS 종료와 HTTP→HTTPS 리다이렉트는 앱이 아니라 로드밸런서·리버스 프록시에서 한다.
- 프록시가 `X-Forwarded-Proto: https`, `X-Forwarded-Host`를 전달해야 앱이 HTTPS로 인식한다.
- 배포 후 `curl -sI https://<도메인>/ | grep -i strict-transport`로 헤더를, 로그인 응답의 `Set-Cookie`로 `Secure; HttpOnly; SameSite=Strict`를 확인한다.

## 2. 조회 성능 측정 (NFR-01)

목표: 제품·판매글 목록과 상세 조회 API는 측정 요청의 **95%가 1초 이내**.

**측정 조건 (테스트 전에 정하는 값, 제안)**

| 항목 | 값 |
|---|---|
| 환경 | 배포와 같은 사양의 스테이징. 개발자 PC 값은 참고용 |
| 데이터 | 판매글 1,000건, 제품 1,000건, 판매글당 사진 3장 |
| 동시 사용자 | 10 (기본), 30 (부하 확인) |
| 요청 수 | API마다 200건, 측정 전 워밍업 10건 제외 |
| 대상 API | `GET /api/v1/listings`, `GET /api/v1/listings/{id}`, `GET /api/v1/products`, `GET /api/v1/products/{id}` |
| 제외 | AI 처리, 파일 업로드·다운로드(별도 측정) |

**실행**
```bash
# 공개 목록
node scripts/perf/measure-latency.mjs --base https://<스테이징> --requests 200 --concurrency 10 \
  --path "/api/v1/listings?page=0&size=20"

# 로그인이 필요한 상세·제품 조회 (토큰은 환경변수로 전달)
PERF_TOKEN=<액세스 토큰> node scripts/perf/measure-latency.mjs --base https://<스테이징> \
  --path /api/v1/listings/1 --path /api/v1/products
```
출력의 표를 아래 결과 기록에 붙인다. p95가 1000ms를 넘거나 실패가 있으면 종료 코드 1이다.

**결과 기록**

| 일시 | 환경 | 데이터 | 동시 | API | p50 | p95 | p99 | 결과 |
|---|---|---|---|---|---|---|---|---|
| 2026-10-28 | 개발 PC, H2 인메모리, 빈 데이터 | 0건 | 10 | `GET /api/v1/listings` | 6ms | 12ms | 14ms | 스크립트 동작 확인용(목표 판정 아님) |

## 3. 자동 테스트와 테스트 DB (NFR-04)

- `./gradlew test`는 **`.env`를 읽지 않는다.** `build.gradle`이 `DOTENV_PATH`를 존재하지 않는 경로로 돌려 실제 DB 접속 정보가 테스트에 섞이지 않는다.
  - 일반 실행(`bootRun`, IDE)은 이전처럼 `.env`를 읽는다. 경로를 바꾸려면 `DOTENV_PATH`를 지정한다.
- 기본 테스트는 H2로 돈다. 아래 테스트는 환경변수가 있을 때만 돌고, 없으면 건너뛴다.

| 테스트 | 켜는 조건 |
|---|---|
| `*PostgresIntegrationTest`, `PurchaseRequestHttpPostgresTest` | `P2_TRADE_TEST_ENABLED=true`, `TEST_DB_URL` 등 `application-test.yaml`의 `TEST_*` |
| `AiSuggestionRedisTest` | 로컬 Redis와 해당 활성화 조건 |

- 동시성·경합(동시 예약 수락, 취소·완료 경합, 반복 이전 요청) 검증은 PostgreSQL 테스트로만 가능하다. CI에서 PostgreSQL을 띄워 위 환경변수를 켜는 것을 권한다.
- 적용된 마이그레이션(`V2026102703` 등)은 수정하지 않는다. 데모 DB(`db/demo/sale-draft`)가 미리 만드는 `product_events` 자리표시 테이블은 데모 전용 새 파일(`V2026102702_5`로 삭제, `V2026102704`로 데모 이력 재삽입)로 정리한다.

## 4. 운영 로그 (NFR-07)

**기록 항목**: 오류 종류, 결과, 발생 시각, 요청 ID. 요청 ID는 `RequestIdFilter`가 만들고 로그 패턴 `[requestId:...]`에 들어간다.

**기록하지 않는 것**: 비밀번호, 액세스·리프레시 토큰, 소유권 이전 토큰 원문, 인증 메일 링크, 증빙 문서 원문·마스킹 전 OCR 결과, 채팅 본문, 개인정보. 소스에서 이런 값을 `log.*`로 찍는 곳이 없는지 PR 리뷰에서 확인한다.

**보관·접근 기준 (제안, 팀 확정 필요)**

| 항목 | 제안 |
|---|---|
| 형식·위치 | 파일 `${LOG_FILE:./logs/app.log}`, 50MB 단위로 롤링, Git에는 올리지 않음(`logs/`) |
| 보관 기간 | 90일(`LOG_RETENTION_DAYS`로 조정), 총 2GB를 넘으면 오래된 것부터 삭제 |
| 접근 권한 | 운영 담당자만 읽기. 일반 사용자·화면에서 접근하는 경로는 만들지 않는다 |
| 삭제 | 보관 기간이 지난 파일은 롤링 정책이 자동 삭제. 수동 삭제는 운영 담당자만 |
| 업무 변경 기록 | 운영 로그와 별개로 DB 감사 기록(SEC-08)에 둔다. 일반 사용자가 수정·삭제할 수 없다 |

> 위 보관 기간·용량은 제안값이다. 법적·내부 보관 기준이 있으면 그 값으로 바꾼다.
