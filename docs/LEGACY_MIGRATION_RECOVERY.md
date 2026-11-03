# 레거시 마이그레이션 V2~V4 복구 기록

공유 Supabase DB에는 `main`에서 찾을 수 없던 Flyway 이력 `V2`~`V4`가 적용돼 있다. 이 문서는 원본을 찾은 경위와 검증 결과, 그리고 원본을 `db/migration`이 아닌 `db/legacy`에 둔 이유를 기록한다. 공유 DB는 변경하지 않았다.

## 원본 출처

세 파일은 팀원 wuuk의 커밋 `4865d81`(2026-10-14, `feat: 제품 등록 조회 수정 기능 구현`)에서 처음 추가됐다. 이후 병합 과정에서 브랜치 끝의 파일 목록에서는 빠졌지만 Git 이력에는 남아 있다. 아래 파일은 `git show 4865d81:<경로>`로 추출한 원문 그대로이며 수정하지 않았다.

| 파일 | Git blob | Flyway 체크섬 | 공유 DB 기록과 일치 |
| --- | --- | --- | --- |
| `V2__create_products.sql` | `71e1507` | -1898772826 | 예 |
| `V3__add_product_requirement_fields.sql` | `3f4a5dd` | -550867487 | 예 |
| `V4__align_product_schema_with_erd.sql` | `a58f12c` | 1859459753 | 예 |

- 체크섬은 Flyway가 파일에서 계산한 값(테스트의 `MigrationInfo.getChecksum()`)이다.
- 초기 복구 시 DB 담당자 요청서의 값을 사용했으며, 2026-11-03 실제 공유 Supabase의 `flyway_schema_history` 읽기 전용 조회에서 V2~V5의 version, script, checksum, success가 동일함을 재확인했다. V2~V5는 모두 `success=true`다.
- `V5__create_users.sql`은 이미 `db/migration`에 있고 체크섬(-2031539899)이 요청서 값과 같다.

## 왜 `db/migration`이 아니라 `db/legacy`인가

원본을 `db/migration`에 그대로 넣으면 신규 DB 생성이 깨진다. 실제로 넣고 PostgreSQL에서 실행해 확인했다.

- `V2`는 `electronic_products`를 만들고 `V4`가 이를 `products`로 바꾼다. 그 결과 `V2026101200`(데모 픽스처, `db/demo/sale-draft`)이 실행될 때 `products`가 이미 있어 `CREATE TABLE products`가 `relation already exists`로 실패한다. `H40MigrationCompatibilityTest.freshGeneralAndDemoUseGeneralOwnershipAndCardSummaryVersions`가 이 경로에서 실패했다.
- 같은 테스트는 V2~V4가 없는 기준선(`upstream/main` 2d4a17f)에서는 통과했다.
- 정식 `V2026101303__create_product_tables.sql`은 공유 DB에 `products`가 이미 있다는 전제로 `IF NOT EXISTS`를 쓴다. 신규 환경은 이 파일로 같은 구조를 만든다. V2~V4를 기본 경로에 넣으면 신규 환경의 제품 테이블 생성 경로가 둘이 되어 `owner_id` 널 허용 여부 같은 세부 구조가 달라질 수 있다.

그래서 원본은 기본 Flyway 경로 밖인 `src/main/resources/db/legacy/`에 보관한다. 이 위치는 어떤 프로필도 읽지 않으므로 이번 변경은 실행 중인 동작을 바꾸지 않는다.

## 검증 결과

PostgreSQL 17(임시 컨테이너, 격리 스키마)에서 실행했다.

- `LegacyProductMigrationChecksumTest`
  - V2~V5의 체크섬이 위 표와 같다.
  - `db/migration`에는 V2~V4가 없다.
  - (PostgreSQL) V2~V5 레거시 원본과 현재 canonical migration 체인을 함께 적용한 격리 DB는 `db/migration` + `db/legacy`를 읽을 때 무시 패턴 없이 strict `validate`가 통과하고, `db/legacy` 없이 `db/migration`만 읽으면 applied-but-unresolved 오류로 `FlywayValidateException`이 난다.
- 기존 P5 게이트(`DirectTransfer`, `InternalTradeOwnership`, `H40MigrationCompatibility`, `DeployMigration`, `NormalizeOwnershipVersion`, `ListingDraftOwnershipHttp`)도 함께 실행했다. 결과는 PR 설명에 적었다.

이 시험은 V2~V5 원본 누락 문제를 검증한다. 공유 DB의 2701/2706 및 2702/2707 특수 이력 정규화, 최신 0201~0202·0301~0305 적용, 테스트 데이터 초기화와 FK/CHECK 보강은 별도 DB remediation 범위다. PR #105는 공유 DB 수렴이나 DB GO를 제공하지 않는다.

## 아직 하지 않은 것 (팀 결정 필요)

- **`db/legacy`를 어떤 프로필에서 읽을지.** 공유 DB를 쓰는 프로필(`supabase`, `prod`)의 `spring.flyway.locations`에 `classpath:db/legacy`를 더하면 V2~V4가 "코드에 없는 이력"이 아니게 되어 `ignore-migration-patterns: "*:*"`를 줄일 길이 열린다. 다만 신규 `prod` DB에서는 V2~V4가 먼저 실행되므로 그 구조를 검증한 뒤에 정해야 한다. 이번 PR에서는 설정을 바꾸지 않았다.
- **`ignore-migration-patterns: "*:*"` 제거.** 위 결정과 공유 DB 복제본의 strict `validate` 검증이 선행돼야 한다.
- **FK·CHECK 보강**(`products.owner_id`, `documents.product_id`·`uploader_id`, `ownership_transfers.transfer_type`). 이 저장소의 어느 ref에서도 기존에 작성된 migration을 찾지 못했다. 새 migration이 필요하며, 기존 테스트 데이터 정리 뒤에 적용해야 한다.
- **테스트 데이터 초기화.** 공유 DB를 변경하는 작업이므로 백업과 복제 DB 검증 뒤 DB 담당자가 진행한다. 이 PR은 DB를 건드리지 않는다.
