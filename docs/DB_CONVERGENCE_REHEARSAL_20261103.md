# DB 복원본 리허설 실행 결과 (2026-11-03)

실제 공유 DB를 백업해 격리 PostgreSQL에 복원한 복제본에서 `docs/DB_CONVERGENCE_RUNBOOK.md`의 정규화 → 정리 → migrate → 점검 순서를 실행한 결과다.
**공유 원본 DB는 변경하지 않았다.** 이 문서는 DB GO가 아니며, 공유 DB 적용은 DB 담당자의 승인 뒤 별도로 수행한다.

## 요약

- 실행한 후보 SHA: `2c218e575f662ff5fb0c93318cef8783e5df2c8e`. 이후 `main`에서 `db/`, `scripts/db/`, Java migration은 바뀌지 않았다.
- 실행 대상: 공유 DB(PostgreSQL 17.6)를 `pg_dump`한 뒤 복원한 로컬 격리 PostgreSQL 17.11(Docker)
- 백업·복원: 완료(복원 후 이력 22행, 테이블 20개 확인)
- 실행 도구: `scripts/db` `ConvergenceTool`, lineage=`legacy`
- 실행 시각: 2026-11-03 21:41~21:45 (KST)

| 항목 | 결과 |
|---|---|
| 정규화 | PASS (2701→2103, 2706→2701, 2702→2803, 2707→2804, 나머지 필드 보존) |
| 테스트 데이터 정리 | PASS (복제본 한정 실행) |
| migration·validate | PASS (10개 적용, 최종 2026110402, strict validate 32건) |
| 내부 재실행 신규 적용 | 0건 |
| 사후 구조·제약·인덱스 | `NOT_RUN` 0, 미검증 제약 0, 무효 인덱스 0 |
| 사후 무결성 위반 | 고아 FK 0, CHECK 위반 0 |
| 보존 데이터 | `users` digest 동일, 이력 22행 → 32행(기존 보존 + 신규 10행) |
| 복원본 리허설 | PASS |
| 공유 원본 변경 | NO |

## 적용 전 상태

- 이력 22행, 옛 번호 `2026102701`(purchase), `2026102706`(summary), `2026102702`(ownership), `2026102707`(slot), 미적용(`PENDING`) migration 10개
- 고아 참조: `products.owner_id` 2, `documents.uploader_id` 3, `chat_rooms.seller_id` 1, `chat_rooms.inquirer_id` 1, `chat_messages.sender_id` 4 (사용자는 1명)
- 정리 대상 행 수: products 2, documents 3, chat_messages 4, chat_rooms 1, chat_read_positions 2, product_images 2, product_field_authors 5, laptop_details 2, listing_drafts 1, draft_images 1
- plan SHA-256: NORMALIZE `06550bc8d416013a31d44dc8951373def68b652a47f8ce8fc72efbe9a2254ab8`, PURGE `477ea198464cdda8ea4358822a55a0e2aa4af575c98e68df3e086f8c12e20517` (복제본 전용 값이며 공유 DB에 재사용할 수 없다)

## 적용 후 상태

- 이력 32행, 정규화한 4건만 version/script 변경(description, type, checksum, success, 실행 시간은 보존)
- 업무 테이블은 비워졌고 `users` 1행, `user_profile_images`, `flyway_schema_history`는 보존
- 신규 제약: `fk_products_owner`, `fk_documents_product`, `fk_documents_uploader`, `ck_products_owner_id_required`, `ck_products_serial_ciphertext_required`가 생성·검증됨

## 특이사항

1. 1차 정규화는 카탈로그 가드가 중단했다(롤백, 변경 없음). 복제본의 CHECK 제약 5개(`ck_transfers_status`, `ck_purchase_requests_request_status`, `ck_purchase_requests_status_pair`, `ck_purchase_requests_transaction_status`, `ck_listing_summary_state`)가 `pg_dump → pg_restore`의 재해석 때문에 기대 정의 해시와 글자 수준에서 달랐다.
   공유 DB를 읽기 전용으로 조회하면 기대한 14개 제약이 모두 일치했으므로 공유 DB 문제가 아니다.
   복제본의 해당 5개 제약을 원본 migration 정의로 다시 만든 뒤 진행했다.
   **같은 방식(`pg_dump` 복원)으로 만든 복제본은 정규화 가드에서 같은 실패가 날 수 있다.** 복원 방식에 대한 담당자 판단이 필요하다.
2. `manifest`에 `OUT_OF_ORDER` 4건(2026102601, 2026102701, 2026102801, 2026102802)이 남는다. 모두 적용에 성공한 이력의 순서 표기이며, DB 담당자의 정상 판정 확인이 필요하다.

## 이번 리허설이 확인하지 않은 것

- 실제 공유 DB의 정규화·정리·migrate (미실행, DB 담당자 GO 필요)
- 쓰기 중단, 스토리지(파일)·Redis 정리 승인, 적용 직전 새 plan 승인
- API 인수(가입·로그인·제품·문서·채팅·거래), Gemini, S3, Redis, TLS

백업 파일, 비밀번호, 접속 문자열, 실제 사용자·문서 원문은 이 문서와 PR에 포함하지 않는다.
