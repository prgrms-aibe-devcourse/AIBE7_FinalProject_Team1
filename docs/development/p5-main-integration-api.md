# P5 최신 main 통합 API

기준: 팀 main 43bae026c3531bfc8113ef4ea89f4f05cdb6db21.
모든 이전 API와 문서 API는 인증이 필요하다. 쿠키 인증의 변경 요청에는 기존 CSRF 검증을 유지한다.

## 외부 소유권 이전

1. 현재 소유자가 기존 발급 API로 24시간 링크를 생성한다.
2. 수신자가 GET /api/v1/ownership/transfers/verify?token=... 으로 제한 요약을 확인한다.
   지정 수신자가 다르면 요약 조회 전에 거부한다. 발신자는 소유자 상태 API를 사용한다.
3. POST /api/v1/ownership/transfers/accept 에 기존 token 본문을 전송한다.
   응답은 transferId, productId, stage=AWAITING_OWNER_APPROVAL, recipientAcceptedAt, expiresAt이다.
   상태는 PENDING이며 제품 소유권·factsVersion·문서 보호·이력·초안은 바뀌지 않는다.
4. 발신자는 GET /api/v1/ownership/transfers/product/{productId} 로 수신자 ID·닉네임·동의 시각·제품명·factsVersion을 확인한다.
5. POST /api/v1/ownership/transfers/{transferId}/approve 에
   expectedRecipientId, expectedFactsVersion을 전송한다.
   현재 소유자이자 최초 발신자만 승인하며 확인 값이 바뀌면 거부한다.
   판매·예약·보관·만료·취소·미동의도 차단한다.
   성공 응답은 transferId, completedAt이다.
6. 응답 유실 시 GET /api/v1/ownership/transfers/{transferId} 로 최소 상태를 복구한다.
   최초 발신자와 확정 수신자만 조회하며 제품·문서 관리 권한을 부여하지 않는다.

동일 수신자의 중복 동의와 최초 발신자의 중복 승인은 효과를 추가하지 않는다.
동의는 만료 시각을 연장하지 않는다. 동의 후에도 승인 전 취소·재발급이 가능하다.
INTERNAL 이전 경로는 바뀌지 않는다. 과거 COMPLETED DIRECT에는 동의 시각을 소급하지 않는다.

## 문서 다운로드

GET /api/v1/documents/{documentId}/file-url 의 응답 {url, expiresAt}은 유지한다.
url은 /api/v1/documents/{documentId}/file?version=...&expires=...&sig=... 이다.
서명은 발급 사용자 ID·문서 ID·버전·만료를 포함한다.
실제 GET은 인증·현재 소유권·버전·HMAC·만료 정각·마스킹 상태·파일 존재와 가독성을 재검사한다.
이전 소유자의 URL, 교체 전 URL, 다른 사용자의 서명 재사용, 저장 키 직접 다운로드를 거부한다.
로컬 저장 키 다운로드는 제품 사진만 허용하며 기존 인증 요구도 유지한다.

## DB 및 검증

신규 정식 migration: V2026110305__direct_transfer_recipient_consent.sql.
recipient_accepted_at은 nullable이며 기존 행의 상태·토큰·동의 시각을 소급 수정하지 않는다.
기존 migration·db/deploy·Flyway 복구 설정·정규화 스크립트는 보존한다.
공유 DB나 AWS에 적용하지 않는다. 배포 시 DB 담당자가 별도 검증·적용해야 한다.

환경변수 LISTING_HISTORY_ENABLED, TRADE_PURCHASE_REQUESTS_ENABLED, TRADE_P3_ENABLED,
TRADE_P4_ENABLED의 공통 기본값은 false다. 기존 local 프로필의 구매 요청 기본값은 유지한다.

PowerShell 검증: scripts/validate-p5-integration.ps1 -Mode basic|postgres|browser -JavaHome <JDK17>.
postgres/browser는 codex.task=p5-integration-v2 라벨의 로컬 전용 PostgreSQL·Redis를 검사한다.
전용 연결은 127.0.0.1:55462/sale_ai_test와 127.0.0.1:56412이며 합성 계정만 사용한다.
browser 모드는 -BrowserModules <playwright-core와 jsqr가 설치된 node_modules 경로>가 필요하다.
