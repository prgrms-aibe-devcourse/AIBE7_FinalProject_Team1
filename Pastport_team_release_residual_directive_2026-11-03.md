# Pastport 배포 마무리 — 팀 잔여 작업 지시서

작성일: 2026-11-03  
기준 main SHA: `e1572f7ffdd0e72de73b5d2dfa78c20af412f5dd`  
전제: 로컬 UI 계약 수정·운영 S3 어댑터·배포 자산 PR이 병합된 뒤 실행

---

## 0. 목적

로컬에서 해결할 수 없는 공유 자원·승인·최종 인수 작업을 담당별로 분리한다.

중복 실행 방지를 위해 다음 원칙을 적용한다.

- DB 변경 담당자는 1명
- AWS IAM·버킷 변경 담당자는 1명
- 최종 릴리스 SHA는 릴리스 관리자 1명이 고정
- 기능 담당자는 자기 도메인의 E2E 승인만 수행
- 여러 LLM이나 팀원이 동일 SQL·IAM·systemd를 동시에 수정하지 않음

---

# 1. 전체 역할 분리

| 역할 | 담당 업무 | 쓰기 권한 |
|---|---|---|
| Release Manager | 최종 SHA, JAR, checksum, 배포 순서, 롤백 | Git release/AWS EC2 |
| DB Owner | 백업, 복제 검증, 이력 정규화, migration | Supabase PostgreSQL |
| Infra Owner | private S3, IAM, 도메인, TLS, Nginx 승인 | AWS |
| Secrets Owner | 운영 비밀값 생성·안전 전달 | env/secret store |
| Backend Reviewer | S3 adapter·설정·migration 코드 리뷰 | Git PR |
| Feature Owners | P1~P5 실제 기능 인수 | 테스트 계정/브라우저 |
| QA Recorder | 결과·스크린샷·실패·제외 사례 기록 | 문서 |

한 사람이 여러 역할을 맡을 수는 있지만, 같은 작업을 여러 사람이 병렬로 쓰지 않는다.

---

# 2. DB Owner 작업

## 2.1 절대 조건

- 서비스 중지 또는 아직 미기동 상태
- 전체 백업 확보
- 실제 DB와 동일한 복제/격리 환경에서 절차 선행 검증
- 실행 SQL·스크립트·SHA 기록
- 실패 시 즉시 중단

금지:

- `flyway clean`
- 근거 없는 `repair`
- `flyway_schema_history` 수동 편집
- `V2026110305` 단독 선행 적용
- 현재 main과 다른 migration 세트 사용
- 백업 없는 적용

## 2.2 적용 전 증거 수집

읽기 전용으로 저장:

```sql
SELECT installed_rank, version, description, type, script, checksum, success
FROM public.flyway_schema_history
ORDER BY installed_rank;
```

추가:

- `ownership_transfers` 컬럼·제약
- `purchase_requests` 컬럼·제약
- `transaction_change_reviews` 존재
- `product_events.visibility`
- `product_events.public_text_reviewed`
- `audit_logs.public_reason_reviewed`
- `document_replacements`
- `recipient_accepted_at`

## 2.3 기존 특수 이력

공유 DB에서 과거 확인된 상태:

```text
2026102702  create ownership tables
2026102707  add ownership pending slot
2026102701  create purchase requests
2026102706  support listing summary refresh
```

### ownership

검증된 `normalize-ownership-version.psql` 절차로:

```text
2026102702 → 2026102803
2026102707 → 2026102804
```

실제 구조·체크섬이 일치할 때만 version/script를 정규화한다.

### summary

`2701`과 `2706`이 동시에 존재하므로 기존 summary normalizer를 무조건 실행하지 않는다. 이 상태를 지원하는 승인된 수렴 절차를 DB 담당자가 먼저 확정해야 한다.

필수 보고:

- 왜 두 이력이 동시에 있는지
- 실제 요약 DDL이 존재하는지
- 현재 main validation/migrate가 어떤 결과를 내는지
- 이력을 변경하지 않고 진행 가능한지
- 변경한다면 정확히 어떤 행과 근거인지

## 2.4 적용 범위

최종 main의 다음을 함께 사용한다.

```text
classpath:db/migration
classpath:db/deploy
```

포함:

```text
V2026110301  purchase request 복구
V2026110302  cancellation metadata 복구
V2026110303  transaction change reviews 복구
V2026110304  history publication state 복구
V2026110305  direct transfer recipient consent
```

순서:

1. 복제 DB에 backup 복원 또는 동일 상태 재현
2. ownership 정규화
3. summary 특수 상태 처리
4. 정식+deploy migration
5. Flyway validate
6. 구조·제약·행 데이터 검증
7. 재실행 시 0건 또는 멱등 결과 확인
8. 실제 공유 DB에 동일 절차
9. 적용 후 history와 구조 저장

## 2.5 DB GO 완료 조건

- 모든 migration 성공
- Flyway validate 성공
- 기존 데이터 보존
- P2·P3 테이블·제약 존재
- P5 `recipient_accepted_at` 존재
- ownership 단일 PENDING 제약 존재
- 이력 공개 컬럼 존재
- 재실행 안전
- 결과 보고서 제출

---

# 3. Infra Owner 작업

## 3.1 사용자 콘텐츠 버킷

결정 필요:

- 기존 `pastport-dev-documents-dtp`를 운영 사용자 파일용으로 승인할지
- 별도 private 버킷을 생성할지

권고:

```text
release JAR bucket != user-content bucket
```

기존 release 버킷:

```text
pastport-dev-releases-495264909330-ap-northeast-2-an
```

사용자 파일 버킷은 제품 이미지·증빙을 담으므로 릴리스 아티팩트와 분리하는 편이 안전하다.

## 3.2 버킷 조건

- Region: `ap-northeast-2`
- Block Public Access 전체 활성
- Object Ownership: bucket owner enforced 권장
- 버전 관리 여부 결정
- 기본 암호화
- CORS는 브라우저 직접 S3 접근을 하지 않으면 불필요
- lifecycle은 팀 데이터 보존 정책 없이 임의 설정하지 않음

## 3.3 IAM

EC2 역할에 사용자 콘텐츠 prefix만 허용한다.

```text
s3:GetObject
s3:PutObject
s3:DeleteObject
```

권한 검증:

- release 버킷 권한과 분리
- 다른 bucket/prefix 접근 거부
- public ACL 생성 불가
- 정적 access key 없음

실제 역할과 정책 ARN을 결과 보고에 기록한다.

## 3.4 도메인·DNS·TLS

팀이 확정할 것:

- 최종 도메인
- DNS 관리 주체
- TLS 인증서 방식
- HTTP→HTTPS 전환 시점

Nginx 반영:

- `127.0.0.1:8080`
- 업로드 12MB 이상
- forwarded headers
- Secure Cookie 동작
- token query 로그 비노출

민감 경로:

```text
/receive?token=...
/api/v1/ownership/transfers/verify?token=...
```

로그 검수 시 실제 token 문자열이 남지 않아야 한다.

## 3.5 Infra GO 완료 조건

- private user-content bucket
- IAM 최소 권한 검증
- EC2 role로 put/get/delete smoke
- 도메인 DNS
- HTTPS
- Nginx config test
- 민감 query 로그 비노출

---

# 4. Secrets Owner 작업

운영 env 파일에 필요한 값을 안전한 경로로 전달한다.

## 4.1 추가 필수값

```ini
STORAGE_TYPE=s3
STORAGE_S3_BUCKET=<approved bucket>
STORAGE_S3_REGION=ap-northeast-2
STORAGE_S3_PREFIX=app-files
STORAGE_SIGNING_SECRET=<independent secret>
```

## 4.2 기존 확인값

```text
SUPABASE_DB_URL
SUPABASE_DB_USERNAME
SUPABASE_DB_PASSWORD
SUPABASE_URL
SUPABASE_SECRET
JWT_SECRET
PRODUCT_SERIAL_SECRET
GEMINI_API_KEY
REDIS_HOST
REDIS_PORT
LOG_FILE
```

`LOCAL_STORAGE_ROOT`는 local 구현용이며 S3 운영의 핵심 설정으로 간주하지 않는다.

## 4.3 원칙

- `STORAGE_SIGNING_SECRET`은 JWT·제품 시리얼 키와 다르게 생성
- PR·메신저·발표 자료에 노출 금지
- `/etc/pastport/pastport.env`는 `root:root`, `600`
- env 검사 결과는 SET/MISSING만 공유
- 실제 값은 릴리스 관리자와 최소 인원만 접근

---

# 5. Backend Reviewer 작업

로컬 PR을 다음 기준으로 검수한다.

## UI PR

- paged product response 사용
- 총 제품 수를 첫 페이지 길이로 계산하지 않음
- 계정 전환 stale response 차단

## S3 PR

- IAM role credential provider
- private S3
- prefix 탈출 차단
- 문서 server-mediated download 유지
- prod에서 단일 `FileStorage` Bean
- local/test 회귀 없음
- 실제 비밀값 없음

## Deploy PR

- Flyway 비활성 override 없음
- DB GO 이전 기동 금지 명시
- checksum·rollback
- Nginx query 로그 안전
- env 값 미출력

---

# 6. Feature Owners 및 QA 작업

## 6.1 테스트 데이터

시연용 계정과 데이터는 운영 배포 후 새로 생성한다.

최소:

- 판매자
- 구매자
- 외부 이전 수신자
- 관리자
- 제품 2대 이상
- 마스킹 증빙
- 판매글
- 채팅·구매 요청

공유 개발 DB의 기존 임의 데이터에 의존하지 않는다.

## 6.2 P1~P5 E2E

### 인증

- 가입·이메일 인증·로그인·재발급·로그아웃
- Secure Cookie·CSRF

### 제품·문서

- 제품 등록·목록·마이페이지
- 이미지 업로드·조회
- 마스킹 증빙 업로드
- AI 추출·확정
- 문서 교체·다운로드

### 판매·거래

- 판매글 게시
- 채팅
- 구매 요청·취소
- 판매자 수락·거절
- 예약 취소·변경 확인
- 양측 완료

### 소유권

- 내부 거래 완료 후 소유자 변경
- 외부 QR/링크 발급
- 비회원→로그인 복귀
- 수신자 동의
- 현 소유자 최종 승인
- 구 소유자의 제품·문서 접근 차단

### 관리자

- 거래·감사 조회
- 쓰기 권한이 없는지 확인

## 6.3 브라우저

- Chrome
- Edge
- 모바일 viewport
- Safari는 사용 가능한 환경에서 확인하거나 제외 근거 기록

## 6.4 QA GO 완료 조건

- 성공·실패·제외 사례 구분
- 테스트 계정·데이터 조건 기록
- 치명적·높은 결함 0
- 시연 시나리오 재현 성공
- 스크린샷·로그에 secret/token 없음

---

# 7. Release Manager 작업

## 7.1 코드 동결

다음이 모두 병합된 main SHA를 고정한다.

- P5 PR #96
- 마이페이지 제품 계약 hotfix
- S3 FileStorage
- 배포 자산
- 승인된 최종 결함 수정

동결 SHA 이후 임의 커밋을 같은 JAR에 섞지 않는다.

## 7.2 JAR

- 고정 SHA checkout
- 전체 CI 확인
- clean build
- SHA 이름 JAR
- SHA-256
- manifest
- release S3 업로드
- EC2 다운로드 후 checksum 재확인

## 7.3 배포 순서

```text
1. CODE GO
2. DB 백업
3. DB GO
4. STORAGE GO
5. ENV GO
6. NGINX/HTTPS GO
7. JAR stage
8. current.jar 전환
9. systemd 시작
10. 내부 smoke
11. 외부 HTTPS E2E
12. QA GO
13. 발표용 URL 공개
```

## 7.4 systemd

기존:

```text
--spring.flyway.enabled=false
```

DB 절차 확정 후 제거한다.

주의:

- DB 정규화 전 제거하고 기동하지 않음
- Flyway 실패를 무시하지 않음
- 기능 플래그는 DB GO 이후 활성화

## 7.5 기능 플래그

DB GO 후:

```ini
LISTING_HISTORY_ENABLED=true
TRADE_PURCHASE_REQUESTS_ENABLED=true
TRADE_P3_ENABLED=true
TRADE_P4_ENABLED=true
```

기동 실패 시 플래그만 끄고 스키마 문제를 숨기지 않는다.

## 7.6 롤백

롤백은 두 층으로 분리한다.

### 애플리케이션 롤백

- 이전 JAR symlink 복구
- 서비스 재시작

### DB 롤백

migration 이후 구 JAR이 새 스키마와 호환되는지 사전에 확인한다. 호환되지 않으면 단순 JAR 롤백으로 해결되지 않으므로 백업 복원 계획이 필요하다.

---

# 8. GO / NO-GO 표

| Gate | 담당 | 증거 | 상태 |
|---|---|---|---|
| CODE | Release/Reviewer | CI + 고정 SHA | 대기 |
| DB | DB Owner | backup + migration report | 대기 |
| STORAGE | Infra/Backend | private S3 E2E | 대기 |
| SECRETS | Secrets Owner | env check OK | 대기 |
| HTTPS | Infra | TLS + cookie + log check | 대기 |
| ARTIFACT | Release | JAR SHA-256 | 대기 |
| SMOKE | Release | 내부 curl/log | 대기 |
| E2E | Feature/QA | P1~P5 report | 대기 |

모든 Gate가 GO일 때만 외부 공개한다.

---

# 9. 팀 전달 메시지

```text
[Pastport 최종 배포 잔여 작업 분장]

P5 통합은 main에 병합됐고, 로컬에서 처리 가능한 마이페이지 API 계약 수정·운영 S3 저장소 어댑터·배포 스크립트/설정은 별도 PR로 정리합니다.

공유 자원 작업은 중복 변경을 막기 위해 아래처럼 단일 담당으로 진행해 주세요.

1) DB Owner 1명
- 전체 백업
- 복제 DB에서 ownership 이력 정규화와 2701/2706 특수 상태 검증
- db/migration + db/deploy + V2026110305 적용
- validate 및 구조/데이터 보존 결과 제출
- clean/repair/임의 history 수정 금지

2) Infra Owner 1명
- private 사용자 파일 S3 버킷과 EC2 IAM 최소 권한
- 도메인·DNS·HTTPS
- Nginx 민감 token query 로그 비노출

3) Secrets Owner 1명
- STORAGE_SIGNING_SECRET 등 운영 값 안전 전달
- 실제 값은 PR/메신저/로그에 공유 금지

4) 기능 담당/QA
- 배포 후 P1~P5 전체 E2E와 시연 시나리오 승인

5) Release Manager
- 최종 main SHA 고정
- JAR checksum·S3 스테이징·EC2 전환·롤백 관리

각 담당은 자기 영역 이외의 DB/IAM/systemd를 동시에 수정하지 말아 주세요. 모든 GO 게이트가 확인되기 전에는 외부 URL을 공개하지 않습니다.
```

---

# 10. 팀 작업 완료 보고 양식

```markdown
## Code
- final SHA:
- CI:

## DB
- backup:
- pre-history:
- normalization:
- migrations:
- validate:
- post-history:

## Storage
- bucket:
- region:
- prefix:
- IAM role/policy:
- E2E:

## HTTPS
- domain:
- certificate:
- cookie:
- token log:

## Artifact
- JAR:
- SHA-256:
- release S3:
- EC2 path:
- previous release:

## E2E
- AUTH:
- PROD/DOC/HIST:
- SALE/CHAT/TX:
- OWN:
- ADMIN:

## Decision
- GO / NO-GO
- blockers:
```
팀 측 요청 범위는 다음으로 한정합니다.
공유 Supabase DB의 백업·이력 수렴·migration 검증
마이페이지 API 계약 및 S3 FileStorage 코드 PR 검토
최종 main SHA와 CI 결과 확정
배포 후 P1~P5 기능 인수 및 시연 검증
S3 어댑터의 애플리케이션 코드는 팀 저장소에서 관리하되, 실제 AWS 버킷·IAM·EC2 연결은 배포 담당 측에서 처리합니다. AWS 관련 변경을 병렬로 진행하지 말아 주세요