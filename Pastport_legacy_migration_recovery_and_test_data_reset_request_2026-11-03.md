# Pastport 레거시 마이그레이션 회수·테스트 데이터 초기화 요청서

작성일: 2026-11-03  
기준 저장소: `prgrms-aibe-devcourse/AIBE7_FinalProject_Team1`  
기준 main SHA: `2d4a17fe566bc322fb98bdba412f7152f54138a2`  
대상: DB 담당자, 초기 제품·회원 스키마 작업자, 각 팀원의 로컬 저장소 보유자

---

## 0. 목적

공유 Supabase DB에는 저장소 main에서 확인할 수 없는 초기 Flyway 이력 `V2~V4`가 적용돼 있다. 또한 공유 DB의 테스트 업무 데이터가 삭제된 사용자 ID를 참조하고 있어 현재 소유권·문서·채팅 권한 검증과 FK 추가를 방해한다.

이 요청의 목적은 다음과 같다.

1. 팀원 로컬·삭제 브랜치·reflog·stash에 남아 있는 **원본 V2~V4 SQL을 회수**한다.
2. 기존에 작성된 FK 보강 migration이 있는지 회수한다.
3. 회수되지 않으면 “원본 소실”로 확정하고, 추측 복원 대신 별도 호환 정책을 설계한다.
4. 폐기 가능한 기존 테스트 업무 데이터를 삭제하고, 최신 제약과 API를 통과하는 새 테스트 데이터를 만든다.
5. 이력 정규화·스키마 보강·데이터 초기화를 복제 DB에서 검증한 뒤 공유 DB에 적용한다.

---

# 1. 현재 확인된 사실

## 1.1 공유 DB Flyway 이력

공유 DB에는 다음 레거시 migration이 기록돼 있다.

```text
V2__create_products.sql
checksum = -1898772826

V3__add_product_requirement_fields.sql
checksum = -550867487

V4__align_product_schema_with_erd.sql
checksum = 1859459753

V5__create_users.sql
checksum = -2031539899
```

## 1.2 GitHub 검색 결과

### 발견됨

```text
V5__create_users.sql
```

현재 main과 과거 제품 브랜치에서 확인된다.

### 발견되지 않음

```text
V2__create_products.sql
V3__add_product_requirement_fields.sql
V4__align_product_schema_with_erd.sql
```

다음 범위에서 exact filename과 path를 확인했으나 찾지 못했다.

```text
- 현재 팀 main
- 현재 팀 저장소의 생존 브랜치
- 공개된 팀원 fork의 기본 브랜치
- 제품 담당자의 공개 feature/product 브랜치
- GitHub code search
- 현재 저장소의 exact-path commit history
```

이 결과는 “원본이 절대 존재하지 않았다”는 뜻은 아니다. 삭제된 로컬 브랜치, reflog, stash, dangling commit, 미푸시 worktree에는 남아 있을 수 있다.

## 1.3 과거 문제는 이미 알려져 있었음

과거 커밋에는 공유 DB에 `V2(create products)`가 이미 적용돼 있어 채팅 migration의 `V2`와 checksum 충돌이 발생했다는 기록이 있다. 당시 조치는 **채팅 migration의 번호를 변경한 것**이며, 공유 DB의 V2~V4 원본 복구나 전체 이력 수렴은 수행하지 않았다.

따라서 이번 문제는 DB가 새로 뒤처진 것이 아니라:

```text
코드 측 충돌 회피는 반복해서 수행됨
공유 DB의 원본 이력·스키마 수렴은 미수행 상태로 남음
```

으로 판단한다.

---

# 2. 팀원 로컬에서 찾아야 하는 파일

## 2.1 필수 레거시 원본

파일명과 예상 Flyway checksum:

```text
src/main/resources/db/migration/V2__create_products.sql
-1898772826

src/main/resources/db/migration/V3__add_product_requirement_fields.sql
-550867487

src/main/resources/db/migration/V4__align_product_schema_with_erd.sql
1859459753
```

## 2.2 FK·제약 보강 migration

다음 중 하나라도 작성한 적이 있다면 함께 제출한다.

```text
products.owner_id          → users.id

documents.product_id       → products.id
documents.uploader_id      → users.id

document_extractions.requested_by_id → users.id
document_extractions.result_event_id → product_events.id

chat_rooms.seller_id       → users.id
chat_rooms.inquirer_id     → users.id
chat_messages.sender_id    → users.id

ownership_transfers.transfer_type IN ('DIRECT', 'INTERNAL')
```

현재 main에서 제품 owner FK와 문서 product/uploader FK를 추가하는 정식 migration은 확인되지 않았다.

---

# 3. 각 팀원이 로컬에서 수행할 검색

아래 명령은 **각자 보유한 모든 clone/worktree**에서 실행한다.

## 3.1 현재 파일 검색

### Git Bash / Linux / macOS

```bash
find .. -type f \
  \( -name 'V2__create_products.sql' \
  -o -name 'V3__add_product_requirement_fields.sql' \
  -o -name 'V4__align_product_schema_with_erd.sql' \) \
  -print
```

### PowerShell

```powershell
Get-ChildItem .. -Recurse -File -ErrorAction SilentlyContinue |
  Where-Object {
    $_.Name -in @(
      'V2__create_products.sql',
      'V3__add_product_requirement_fields.sql',
      'V4__align_product_schema_with_erd.sql'
    )
  } |
  Select-Object FullName, Length, LastWriteTime
```

## 3.2 모든 Git ref·삭제 이력 검색

```bash
git log --all --full-history --name-status -- \
  src/main/resources/db/migration/V2__create_products.sql \
  src/main/resources/db/migration/V3__add_product_requirement_fields.sql \
  src/main/resources/db/migration/V4__align_product_schema_with_erd.sql
```

```bash
git rev-list --objects --all | grep -E \
  'V(2__create_products|3__add_product_requirement_fields|4__align_product_schema_with_erd)\.sql$'
```

```bash
git reflog --all --date=iso

git stash list

git fsck --full --no-reflogs --unreachable
```

`git fsck` 결과의 dangling commit/blob을 삭제하지 않는다.

## 3.3 발견한 commit에서 파일 추출

```bash
git show <commit-sha>:src/main/resources/db/migration/V2__create_products.sql \
  > recovered/V2__create_products.sql
```

V3·V4도 같은 방식으로 추출한다.

## 3.4 FK migration 검색

```bash
git log --all -G 'FOREIGN KEY.*owner_id|REFERENCES users|fk_.*owner|fk_.*document' \
  -- src/main/resources/db scripts docs
```

```bash
git grep -n -E \
  'products.*owner_id|documents.*product_id|documents.*uploader_id|transfer_type' \
  $(git for-each-ref --format='%(refname)') -- \
  'src/main/resources/db/**' 'scripts/db/**' 2>/dev/null
```

---

# 4. 발견 파일 제출 규칙

## 4.1 제출할 것

각 파일마다 다음을 제출한다.

```text
- 파일 원문
- 발견한 로컬 경로
- 원본 commit SHA 또는 reflog/dangling object SHA
- 발견 경로: branch / stash / reflog / worktree / dangling commit
- 파일 SHA-256
- Flyway checksum 확인 결과
- 파일을 수정했는지 여부: 반드시 NO
```

## 4.2 금지

```text
- 현재 스키마를 보고 V2~V4를 새로 추측 작성
- 공백·주석·파일명 수정 후 원본이라고 제출
- checksum을 맞추기 위해 무작위 수정
- 공유 DB history를 먼저 변경
- 오래된 파일을 current main에 바로 병합
```

찾은 파일은 우선 별도 검증 브랜치에 **원문 그대로** 추가하고, 공유 DB history checksum과 비교한다.

---

# 5. 검색 결과에 따른 처리

## 5.1 V2~V4 원본이 모두 발견됨

```text
1. 파일 원문 SHA-256과 출처 기록
2. Flyway checksum을 공유 DB 값과 비교
3. 세 파일을 변경 없이 복구하는 PR 작성
4. 신규 DB migrate 검증
5. 공유 DB 복제본 strict validate 검증
6. 기존 main의 현재 dated migration과 중복 DDL 여부 검증
7. 승인 후 병합
```

checksum이 하나라도 다르면 원본으로 인정하지 않는다.

## 5.2 일부만 발견됨

발견된 파일만 복구하고, 나머지는 `MISSING_LEGACY_SOURCE`로 기록한다. 누락 파일을 추측해서 채우지 않는다.

## 5.3 모두 발견되지 않음

다음으로 확정한다.

```text
V2~V4는 DB-only 또는 미푸시 로컬 migration이며 원본 소실
```

그 후 별도 설계가 필요하다.

- 현재의 `ignore-migration-patterns: "*:*"`는 유지하지 않는다.
- 프로젝트가 사용하는 Flyway 버전에서 **missing legacy만 제한적으로 허용하는 정책**을 검증한다.
- checksum mismatch, failed, future 등 다른 불일치는 계속 실패해야 한다.
- V2~V4의 실제 DDL은 공유 DB schema inventory와 문서로 보관한다.
- 새 환경은 현재 정식 migration 체인으로 생성하고, 기존 공유 DB에만 legacy 예외를 적용한다.

정확한 Flyway 설정은 별도 PR과 테스트에서 확정한다.

---

# 6. 테스트 데이터 초기화 원칙

## 6.1 현재 데이터 판정

공유 DB에서 다음 orphan이 확인됐다.

```text
products.owner_id              2건
documents.uploader_id          3건
chat_rooms.seller_id           1건
chat_rooms.inquirer_id         1건
chat_messages.sender_id        4건
```

현존하는 업무 데이터 대부분이 삭제되었거나 다른 환경의 사용자 ID를 참조하는 테스트 데이터로 보인다.

## 6.2 권장 처리

해당 데이터가 폐기 가능한 테스트 데이터라는 팀 합의가 있다면:

```text
기존 ID를 user 25로 일괄 변경하지 않음
기존 업무 테스트 데이터를 의존 관계에 따라 삭제
스키마·Flyway history는 삭제하지 않음
최신 migration과 FK/CHECK 적용
정상 가입 흐름으로 새 테스트 계정 생성
애플리케이션 API로 새 테스트 데이터 생성
```

직접 SQL seed보다 애플리케이션 API를 우선한다. 시리얼 암호화·fingerprint, 감사 기록, 상태 전이, 문서 보호 규칙을 우회하지 않기 위해서다.

## 6.3 삭제 대상과 보존 대상

### 삭제 후보 — 업무 테스트 데이터

실제 FK 그래프와 테이블 존재 여부를 복제 DB에서 확인한 뒤 순서를 결정한다.

```text
chat read/leave/message data
purchase requests / change reviews
ownership transfers / ownership records
listing images / listings
listing draft images / drafts
event-document links
document replacements / extractions / documents
product events / audit logs
product images / laptop details / products
```

### 보존

```text
flyway_schema_history
스키마·제약·인덱스
운영 설정
Supabase auth 계정 — 별도 인간 결정 없이는 삭제 금지
정상 public.users 계정
```

## 6.4 새 테스트 계정

최소 다음 역할을 정상 회원가입·이메일 인증 흐름으로 만든다.

```text
seller
buyer
external transfer recipient
admin — 필요 시 승인된 방식으로 role 지정
```

## 6.5 새 테스트 데이터

migration과 제약 적용 후 애플리케이션 API로 생성한다.

```text
- seller 소유 제품 2개
- 제품 이미지
- 마스킹 증빙
- 구매·수리 이력
- 판매글 1개 이상
- buyer와 판매자의 채팅방·메시지
- 구매 요청·예약 시나리오
- 외부 이전 PENDING/완료 시나리오
```

생성 후 orphan·중복·상태 위반 count가 0인지 확인한다.

---

# 7. FK·CHECK 보강 작업

## 7.1 기존 migration이 발견되면

원문과 적용 여부를 검증한 뒤 main에 정식 편입한다. 이미 공유 DB에 적용된 적이 있다면 history metadata도 함께 확인한다.

## 7.2 기존 migration이 없으면

새 versioned Flyway migration을 작성한다.

대상 후보:

```text
products.owner_id            → users.id
documents.product_id         → products.id
documents.uploader_id        → users.id
ownership_transfers.transfer_type CHECK ('DIRECT','INTERNAL')
```

추가 대상은 실제 DB와 코드 계약을 대조해 결정한다.

## 7.3 적용 순서

```text
1. 백업
2. 복제 DB
3. 기존 테스트 업무 데이터 삭제
4. orphan count 0 확인
5. FK/CHECK migration 적용
6. Flyway validate
7. 새 테스트 계정·업무 데이터 생성
8. 전체 회귀검사
9. 공유 DB 적용 승인
```

기존 데이터가 남은 상태에서 FK를 먼저 추가하지 않는다.

---

# 8. 팀 제출 보고 양식

```markdown
# Legacy migration recovery result

## Repository search
- repositories/clones searched:
- branches searched:
- reflogs searched:
- stashes searched:
- unreachable objects searched:

## V2
- found: YES / NO
- source:
- commit/object:
- sha256:
- Flyway checksum:
- matches shared DB: YES / NO

## V3
...

## V4
...

## FK migration search
- product owner FK:
- document product/uploader FK:
- transfer_type check:
- other:

## Conclusion
- RECOVERABLE / PARTIAL / LOST
- requested PR or follow-up:
```

---

# 9. 팀 전달용 짧은 요청문

```text
공유 Supabase에는 현재 main에서 찾을 수 없는 V2~V4 Flyway 이력이 적용돼 있습니다.

필요 파일과 checksum:
- V2__create_products.sql / -1898772826
- V3__add_product_requirement_fields.sql / -550867487
- V4__align_product_schema_with_erd.sql / 1859459753

각자 보유한 모든 로컬 clone, 삭제 브랜치, reflog, stash, worktree, dangling commit에서 원본 파일을 찾아 주세요. 발견 시 수정하지 않은 원문, commit/object SHA, SHA-256, 발견 경로를 제출해 주세요.

products.owner_id 및 documents.product_id/uploader_id FK를 추가한 migration을 작성한 적이 있다면 함께 제출해 주세요.

파일을 찾지 못하면 ‘없음’으로 답해 주세요. 현재 스키마를 보고 V2~V4를 재작성하거나 checksum을 맞추기 위한 수정은 금지합니다.

기존 공유 DB 업무 데이터는 폐기 가능한 테스트 데이터로 판단되어 별도 백업 후 전체 업무 데이터 초기화와 정상 API 기반 재생성을 진행할 예정입니다. Flyway history와 스키마는 삭제하지 않습니다.
```
