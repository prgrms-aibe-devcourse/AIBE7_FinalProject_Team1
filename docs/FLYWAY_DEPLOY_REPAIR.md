# 배포용 Flyway 복구 마이그레이션 (`db/deploy`)

AWS 최초 배포 전 점검에서 공유 Supabase DB와 `main`의 Flyway 이력이 맞지 않는 문제가 확인됐다. 이 문서는 **코드 저장소 쪽에서 막을 수 있는 부분**과 **아직 사람이 확인해야 하는 부분**을 나눠 적는다. 공유 DB는 이 변경으로 초기화하거나 수정하지 않는다. 서버가 뜰 때 새 마이그레이션 4개가 추가로 적용될 뿐이다.

## 확인된 원인 (코드 기준)

1. `application.yaml`의 `ignore-migration-patterns: "*:*"` 때문에 Flyway가 이력 불일치로 멈추지 않는다. 대신 DB가 어떤 번호를 이미 적용한 것으로 기록하고 있으면 그 번호의 새 SQL은 **실행되지 않고 조용히 건너뛴다**. 같은 번호가 다른 SQL을 가리키는 경우가 이렇게 드러난다.
2. `purchase_requests`(P2)와 `transaction_change_reviews`(P3) DDL은 자동 적용되지 않는 `db/pending`에만 있다. `db/migration`만으로 만든 DB에는 두 테이블이 없다. 코드는 두 테이블을 쓴다(`JdbcPurchaseRequestRepository`, `JdbcReservationChangeReviewRepository`, 게시 조건 확인 등).
3. 정식 `V2026103002__add_history_publication_state.sql`이 만드는 컬럼 3개(`product_events.visibility`, `product_events.public_text_reviewed`, `audit_logs.public_reason_reviewed`)가 그 번호를 이미 적용한 DB에는 없다.

## 이 변경이 하는 일

배포 프로필(`supabase`, `prod`)만 `classpath:db/migration`에 더해 `classpath:db/deploy`를 읽는다. 모두 이미 있으면 건너뛰는(멱등) SQL이다.

| 버전 | 내용 |
| --- | --- |
| `V2026110301` | `purchase_requests` 테이블·인덱스 |
| `V2026110302` | 취소 메타데이터 제약 `ck_purchase_requests_cancellation_metadata` |
| `V2026110303` | `transaction_change_reviews` 테이블·인덱스 |
| `V2026110304` | 이력 공개·검토 컬럼 3개, 공개 상태 제약, 인덱스 |

- 번호가 현재 가장 큰 정식 버전(`2026110201`) 뒤라서 `outOfOrder` 없이 순서대로 적용된다. P3의 FK(`product_events`)는 정식 `V2026102801` 뒤에 실행되므로 순서 문제가 없다.
- `local` 프로필, 기존 Postgres 통합 테스트(정식 + `db/pending` 방식), `db/pending` 파일, 이미 적용됐을 수 있는 기존 마이그레이션은 **수정하지 않았다**. 체크섬이 바뀌는 파일이 없다.

## 검증

`DeployMigrationPostgresTest`(환경 변수 `DEPLOY_DDL_TEST_ENABLED=true`와 `DEPLOY_DDL_TEST_DB_URL/USER/PASSWORD`가 있을 때만 실행)가 격리 스키마에서 확인한다.

- 빈 DB에 정식 + deploy를 적용하면 위 구조가 모두 생긴다.
- 기존 방식(정식 + `db/pending`)으로 이미 만든 DB에 deploy를 적용해도 "이미 있음"으로 실패하지 않는다.
- 이력은 적용됐지만 컬럼이 빠진 DB(공유 DB 상황)에 deploy를 적용하면 컬럼이 채워진다.
- 두 번 적용해도 결과가 같다.

## 이 변경이 해결하지 못하는 것 (사람이 확인)

공유 DB의 `flyway_schema_history`를 직접 보지 못했다. 아래는 이력을 확인해야 정할 수 있다.

- **`V2026102803`(소유권 테이블)과 구버전 `2702`/`2707` 충돌:** 공유 DB에 구버전이 적용돼 있으면 `ownership_transfers`가 이미 있어 새 `2803`의 `CREATE TABLE`이 실패한다. `FLYWAY_VERSION_ALIGNMENT.md`의 "중단하고 별도 검토" 조건에 해당한다. 이 경우는 이력 정규화가 먼저다.
- **그 밖에 같은 번호가 다른 SQL로 적용된 정식 마이그레이션:** 이번에 보완한 것은 확인된 3종(P2·P3·이력 공개 컬럼)뿐이다. 다른 번호가 같은 방식으로 건너뛰어졌다면 같은 방식의 보완이 필요하다. 아래 읽기 전용 조회로 어떤 번호가 적용됐는지 비교한다.
  ```sql
  SELECT installed_rank, version, description, script, checksum, success
    FROM flyway_schema_history ORDER BY installed_rank;
  ```
- **취소 메타데이터 제약(`V2026110302`):** 이미 있는 `purchase_requests` 행이 제약을 어기면 추가에 실패한다. 공유 DB에 해당 테이블 데이터가 있다면 먼저 확인한다.
- **배포 전 DB 백업**은 이 변경과 무관하게 먼저 받는다.

## 롤백

새 파일 4개와 두 프로필의 `locations` 두 줄을 되돌리면 된다. 적용된 DB에서 만들어진 구조는 남는다(테이블·컬럼은 코드가 쓰는 정식 구조와 동일하다).
