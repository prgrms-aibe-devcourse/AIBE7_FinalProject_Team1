# 배포용 Flyway 복구 마이그레이션 (`db/deploy`)

이 문서는 기존 배포 보완 경로의 기록이다. 2026-11-03 이후 전체 DB 수렴은 [DB_CONVERGENCE_RUNBOOK.md](DB_CONVERGENCE_RUNBOOK.md)의 명시적 계통 선택·정규화·승인 plan·strict runner를 따른다. `db/deploy`만 추가하거나 앱을 기동하는 것으로 공유 DB 수렴이 끝나지 않는다. 공유 DB 적용은 사람이 별도로 수행한다.

## 확인된 원인 (코드 기준)

1. 기존 `application.yaml`은 `ignore-migration-patterns: "*:*"`를 사용한다. Flyway 12.4.0 격리 PostgreSQL 실험에서는 checksum/type/description 불일치가 이 패턴에서 모두 validate 성공으로 나타났고, 빈 패턴에서는 각각 실패했다. 모든 버전에 일반화하지 않으며, 새 수렴 프로필은 유효 ignore 목록을 비우고 정상화를 먼저 수행한다. 같은 version을 이미 적용했다고 기록하면 새 SQL이 자동 재실행되는 것은 아니다.
2. `purchase_requests`(P2)와 `transaction_change_reviews`(P3) DDL은 자동 적용되지 않는 `db/pending`에만 있다. `db/migration`만으로 만든 DB에는 두 테이블이 없다. 코드는 두 테이블을 쓴다(`JdbcPurchaseRequestRepository`, `JdbcReservationChangeReviewRepository`, 게시 조건 확인 등).
3. 정식 `V2026103002__add_history_publication_state.sql`의 컬럼 누락 보완도 포함한다. 다만 제공된 2026-11-03 실제 history에는 3002 적용 기록이 없다. '3002 성공이나 컬럼 없음'은 별도 합성 반례이며 그 실제 DB의 관측 사실로 취급하지 않는다.

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

## 소유권 구버전(2702·2707)이 적용된 DB — 서버 기동을 막는 경우

**실험으로 확인한 사실** (격리 스키마, 구버전은 시험 전용 보관본 `src/test/resources/db/fixtures/h40-legacy-general`로 재현)

- 구버전 `V2026102702`, `V2026102707`이 적용된 DB에 현재 마이그레이션을 올리면 `V2026102803`이 `ownership_transfers`를 다시 만들려다 `relation "ownership_transfers" already exists`로 **실패한다.** 서버가 기동하지 못한다. `ignore-migration-patterns: "*:*"`로도 막을 수 없다.
- 옛 `2702`/`2707` SQL은 현재 `2803`/`2804`와 **본문이 바이트 단위로 같다**(해시 일치). 체크섬도 같다(`2702`·`2803`: -1318655273, `2707`·`2804`: -1107274211). 그래서 구조·데이터는 이미 맞고, 어긋난 것은 이력의 번호뿐이다.

**해결:** `scripts/db/normalize-ownership-version.psql`이 이력의 `version`, `script` 두 필드만 `2702→2803`, `2707→2804`로 바꾼다. 순서(`installed_rank`)·체크섬·설명·실행 시간·업무 데이터는 바꾸지 않는다. 팀의 `normalize-summary-version`과 같은 안전장치를 쓴다.

- 접속한 DB·스키마가 지정한 값과 다르면 거부한다.
- 이력 테이블을 잠그고 한 트랜잭션에서 실행한다. 조건이 하나라도 어긋나면 롤백한다.
- 체크섬·성공 여부·설명·스크립트 이름이 검증된 값과 다르거나, 기록과 실제 구조(컬럼·제약)가 다르면 변경 없이 중단한다.
- 구번호와 새 번호가 함께 있거나, 대기 슬롯 이력만 있고 테이블 이력이 없으면 중단한다.
- 이미 현재 번호이거나 소유권 이력이 없으면 아무것도 바꾸지 않는다. 두 번 실행해도 결과가 같다.

```text
psql -d 실제DB명 -U 실제사용자 -v expected_database=실제DB명 -v expected_schema=public -f scripts/db/normalize-ownership-version.psql
```

- 비밀번호는 채팅·코드·명령 인자에 넣지 않는다. 서버를 멈추고 **전체 백업을 만든 뒤** 대상 DB 담당자가 실행한다.
- **실행 순서:** 요약 정규화 스크립트(`normalize-summary-version`)는 구버전 소유권이 기록돼 있으면 "수동 검토 필요"로 일부러 중단한다. 따라서 **소유권 정규화를 먼저, 요약 정규화를 그다음**에 실행한다.
- 소유권만 충돌하는 합성 DB는 이 정규화 뒤 migrate/validate를 검증했다. V2~V5와 purchase/summary 충돌까지 함께 가진 실제 계통 전체의 성공을 뜻하지 않는다. 해당 계통은 새 실행서의 원자적 전체 정규화와 legacy compatibility locations가 필요하다.
- 검증: `NormalizeOwnershipVersionPostgresTest` 9개(변환·이후 migrate/validate 성공, 정규화 전 실패 재현, 재실행 동일, 이미 정상·이력 없음, 잘못된 스키마·체크섬·누락 이력·구조 불일치 거부).

## 이 변경이 해결하지 못하는 것 (사람이 확인)

공유 DB의 `flyway_schema_history`를 직접 보지 못했다. 아래는 이력을 확인해야 정할 수 있다.

- **어떤 경우에 해당하는지:** 위 정규화는 공유 DB에 `2702`/`2707`이 실제로 기록돼 있을 때만 필요하다. 아래 읽기 전용 조회로 먼저 확인한다. `2702`/`2707`이 없으면 이 스크립트는 변경 없이 끝난다.
- **그 밖에 같은 번호가 다른 SQL로 적용된 정식 마이그레이션:** 상태 무시와 checksum 검증을 혼동하지 말고, 아래 조회 결과를 실제 Flyway가 해석한 원본 migration 목록과 비교한다. 알 수 없는 이력은 임의 repair나 checksum 변경으로 통과시키지 않는다.
  ```sql
  SELECT installed_rank, version, description, script, checksum, success
    FROM flyway_schema_history ORDER BY installed_rank;
  ```
- **취소 메타데이터 제약(`V2026110302`):** 이미 있는 `purchase_requests` 행이 제약을 어기면 추가에 실패한다. 공유 DB에 해당 테이블 데이터가 있다면 먼저 확인한다.
- **배포 전 DB 백업**은 이 변경과 무관하게 먼저 받는다.

## 롤백

새 파일 4개와 두 프로필의 `locations` 두 줄을 되돌리면 된다. 적용된 DB에서 만들어진 구조는 남는다(테이블·컬럼은 코드가 쓰는 정식 구조와 동일하다).
