# P5 최신 main 통합 결과 보고서

## 기준

- main base SHA: 43bae026c3531bfc8113ef4ea89f4f05cdb6db21
- 최종 fetch에서도 팀 main이 같은 SHA임을 확인했다.
- 작업 branch: fix/p5-main-integration-20261102-v2
- 작업 HEAD SHA (구현·검증 대상): aa0cf0d08e23f37f678c26aba232233dc6acd122
- 이 보고서만 추가한 문서 커밋이 검증 대상 뒤에 이어진다. 최종 제출 HEAD는 개인 원격 브랜치의 git rev-parse HEAD로 확인한다.
- 참고한 P5 commit: b4bc3dfaac4d76ca136165349c2748c469254584
- 작업 위치: C:/Workspace/AIBE7_FinalProject_Team1-p5-integration-20261102
- 기존 작업 트리의 미추적 문서 2개는 보존하고, 사용자 합의에 따라 별도 worktree에서 작업했다.
- 기존 P5 브랜치 merge/cherry-pick이나 고위험 파일 전체 교체는 수행하지 않았다.

## 기능 판정

| 영역 | 시작 상태 | 최종 상태 | 비고 |
|---|---|---|---|
| recipient consent | MISSING | 구현 | accept는 수신자 동의만 저장하고 PENDING 유지 |
| owner approval | MISSING | 구현 | 최초 발신자인 현재 소유자 승인, 확인한 수신자·factsVersion 재검사 |
| verify authorization | PARTIAL | 보강 | main의 인증 요구 보존, 지정 수신자만 요약 조회, 종료 요청 식별자 최소화 |
| document download | PARTIAL | 보강 | 사용자·문서·버전·만료 HMAC, 실제 GET에서 현재 소유권·마스킹 상태 검사 |
| upload race | MISSING | 구현 | 제품 선행 잠금, 소유권 재검사, DB 롤백 시 신규 파일 정리 |
| pending cancel/history | PARTIAL | 구현 | 기존 API로 구매자 대기 취소, 방향별 취소 내역, 응답 유실·늦은 응답 처리 |
| feature flags | PARTIAL | 보강 | listing-history 환경변수 유지, P2/P3/P4 환경변수 추가, 공통 기본 false |

### 완료 효과와 보안

- 동의 후 owner/factsVersion·TRANSFER 이력·문서 보호·판매 초안은 바뀌지 않는다.
- approve에서 소유자 변경·버전 증가·PRIVATE 이력·증빙 보호·초안 정리·감사·이벤트를 같은 트랜잭션에서 확정한다.
- 같은 수신자의 중복 accept와 최초 발신자의 중복 approve는 효과를 추가하지 않는다.
- 동의는 24시간 만료를 연장하지 않는다. 동의 후 취소·재발급이 가능하다.
- 상태 복구는 최초 발신자와 확정 수신자에게 최소 정보만 제공한다. 이전 소유자의 관리 권한은 복구하지 않는다.
- 문서 변경은 제품→문서 잠금 순서를 사용한다. 문서 엔티티는 제품 잠금 이후 로딩해 이전 버전 캐시를 피한다.
- 다운로드는 잠금 획득 후에도 만료를 검사한다. 저장 키로 문서에 직접 접근하는 경로는 차단한다.
- 로그인·페이지 진입은 accept를 호출하지 않는다. 계정 전환·로그아웃·pagehide 시 토큰·요약을 폐기한다.

## main 보존 확인

- PR #89 cursor pagination: 관련 제품 Controller/Service/Repository/DTO 미변경.
- cursorCreatedAt + cursorProductId, items/page/summary, nextCursor, REPEATABLE_READ 유지.
- batch image loading: 관련 코드 미변경, 제품 Java/Node 회귀 검증에 포함.
- registration document handoff: products.js/documents.js/doc-mask.js 미변경, Node 회귀 검증에 포함.
- document replacement audit: 버전·종류·크기·SHA-256·횟수·시각·추출 반려·커밋 후 구 파일 삭제 유지.
- db/deploy: 미변경. prod/supabase의 정식+복구 locations 유지.
- Flyway normalization scripts: 파일 미변경. 검증은 전용 로컬 DB의 합성 스키마에서만 수행.
- FlywayConfig 및 V2026110202 Java migration: 미변경.
- INTERNAL 이전: 실제 HTTP/PostgreSQL 회귀 통과.
- 관리자 조회·판매 초안 제품 선택: 최종 전체 테스트에 포함.

## 신규 migration

- 파일: src/main/resources/db/migration/V2026110305__direct_transfer_recipient_consent.sql
- 버전: 2026110305. 정식·deploy·pending·Java migration 및 최신 main과 중복 없음.
- nullable recipient_accepted_at 추가와 DIRECT+지정 수신자 최소 CHECK만 추가.
- 기존 COMPLETED/PENDING DIRECT의 상태·토큰·행 데이터는 유지하고 동의 시각을 소급하지 않는다.
- PostgreSQL 적용 결과: 신규 DB, 이전 버전 DB 업그레이드, 재적용 시 무변경, 기존 INTERNAL 제약 보존 통과.
- 기존 migration 수정·rename 없음.
- 공유 DB와 AWS는 변경하지 않았다. 신규 migration의 실제 배포 적용은 DB 담당자 작업이다.

## 테스트

- spotlessCheck: 통과.
- Node: 187개 통과, 실패·skip 없음.
- unit/integration: 기본 전체 1,232개 중 1,080개 통과, 152개는 환경 조건에 따른 skip, 실패·오류 없음.
  필수 PostgreSQL 및 브라우저 인수는 아래 별도 실행으로 검증했다. 나머지 선택적 통합 테스트까지 모두 실행했다는 의미는 아니다.
- build: 통과. 실제 명령은 spotlessCheck test build이며 bootJar·jar도 생성했다.
- PostgreSQL: 111개 중 110개 통과, 브라우저 전용 1개는 별도 실행에서 통과.
  - DirectTransferPostgresTest: 54개 중 53개 통과, 브라우저 전용 1개 별도.
  - InternalTradeOwnershipHttpPostgresTest: 26개 통과.
  - H40MigrationCompatibilityTest: 4개 통과.
  - DeployMigrationPostgresTest: 4개 통과.
  - NormalizeOwnershipVersionPostgresTest: 9개 통과.
  - ListingDraftOwnershipHttpTest: 14개 통과.
- browser/manual: Chrome headless 실제 브라우저 테스트 1개 통과. 별도 수동 조작 검사는 수행하지 않았다.
  - QR 실물 디코딩, 발급·취소·재발급, 비회원 로그인 복귀, 명시 동의 후 소유자 승인, 네트워크 실패 복구.
  - 완료 후 구 소유자 링크/QR 제거, 제품 등록 3단계, 채팅 목록·방 진입·나가기.
  - uncaughtPageErrors=0.
- 문서: 실제 PostgreSQL HTTP 다운로드에서 구 소유자·교체 전 URL·제3자 서명·직접 저장 키·재마스킹 필요 상태 차단.
- 경합: 승인/취소·동의 경쟁, 승인 직전 facts/owner 변경, 업로드와 이전, SQL·flush·지연 commit 실패 롤백.
- 파일: 서명 필드 변조·만료 정각, 새 파일 롤백 정리, 구 파일 삭제 실패 시 교체 유지.

### 재현 명령과 증거

- scripts/validate-p5-integration.ps1 -Mode basic -JavaHome <JDK17>
- scripts/validate-p5-integration.ps1 -Mode postgres -JavaHome <JDK17>
- scripts/validate-p5-integration.ps1 -Mode browser -JavaHome <JDK17> -BrowserModules <node_modules>
- PostgreSQL/Redis는 p5-integration-v2 라벨의 전용 로컬 컨테이너를 확인하고 사용했다.
- PostgreSQL: 127.0.0.1:55462/sale_ai_test, Redis: 127.0.0.1:56412. 모든 계정·문서·토큰은 합성 fixture다.
- 로컬 증거: build/postgres-final.log, build/postgres-summary.json, build/browser-final.log, build/browser-summary.json, build/basic-final.log, build/basic-summary.json.
- 브라우저 상세: build/p5-browser-evidence-20261102140551597/browser-result.json 및 스크린샷.
- 실제 비밀값이나 .env는 출력·커밋하지 않았다.

## 변경 파일

- ownership: entity/DTO/service/controller, approve·상태 API.
- document: DocumentService, DocumentUrlSigner, DocumentController, DocumentRepository, extraction 서비스, JdbcProductOwnerReader.
- storage: LocalFileController/LocalFileStorage의 문서 저장 키 우회 차단.
- frontend: ownership.js, receive.js, deals.js 및 receive/deals HTML.
- config: application.yaml, .env.example의 기본 false 기능 플래그.
- migration: 신규 V2026110305 SQL 한 파일.
- tests/docs: Java/Node/실제 브라우저 회귀, API 계약 문서, 코드 가이드, 이 보고서, 검증 스크립트.

## 통합 중 해결 사항

- 직접 Flyway를 구성하는 일부 테스트에는 main의 FlywayConfig가 적용되지 않았다.
  PostgreSQL CREATE INDEX CONCURRENTLY와 Flyway 트랜잭션 잠금이 대기하는 현상을 확인하고,
  테스트에 기존 main과 같은 flyway.postgresql.transactional.lock=false를 명시했다.
  운영 설정이나 인덱스 migration은 변경하지 않았다.
- 복구 테스트의 이전 DB fixture는 신규 0305보다 앞선 2026110202까지 적용해 재현하도록 고정했다.
  신규 버전을 먼저 적용한 뒤 더 낮은 deploy 버전을 읽으면 outOfOrder=false에서 복구가 건너뛰어지기 때문이다.
  실제 복구 검증 단계는 기존처럼 정식+deploy를 함께 읽으며, 모든 복구 DDL·제약·멱등성을 검사한다.
- 복구 migration 건수 검사는 기존 네 개 버전을 명시하고 신규 0305의 단일 적용도 별도로 검사한다.
- 이전의 accept 완료 전제 테스트는 동의→approve 및 발신자 완료 감사 주체로 갱신했다.
  기존 실패 주입·동시성·보안 검증을 삭제하거나 Disabled로 바꾸지 않았다.

## Out of scope findings

- 운영 FileStorage 어댑터 정식 제공은 별도 작업이다. 공통 load()를 사용하며 S3 의존성은 추가하지 않았다.
- 기존 local 프로필의 구매 요청 기본값 true는 유지했다. 공통 기본값과 .env.example은 false이며 운영 자동 활성화는 하지 않았다.
- DB 담당자는 기존 복구·정규화 절차에 따라 상태를 확인하고 정식+deploy locations를 함께 사용해야 한다.
  공유 DB에서 0305만 선행 적용하거나 repair/clean/수동 history 수정으로 우회해서는 안 된다.

## 미해결

- 요청한 코드·테스트 범위의 미해결 항목 없음.
- 실제 공유 DB 적용·운영 저장소 연결·기능 플래그 활성화는 수행하지 않았으며 별도 배포 검토 대상이다.

## 커밋

- 2df0919: schema/domain/DTO.
- 647a0da: service/API 및 단위 테스트.
- b5f263f: 문서 다운로드·잠금·롤백 정리 및 테스트.
- e35d3cc: 승인·취소 내역 UI, Node·브라우저 테스트.
- e07bc55: 환경변수 기능 플래그.
- aa0cf0d: PostgreSQL·migration 회귀 및 API·개발 문서.
- 이후 보고서 문서 커밋을 추가하고 origin의 같은 개인 브랜치로 push한다. 팀 main 병합은 수행하지 않는다.
