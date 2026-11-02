# P5 main convergence delta

Base B: `282ef24705e45d0eae9b8db6d6240b6bd8379fab`; reference R: `0bf4b44cbe6dc6593d6b81a734a25bf171653e9c`.
Original worktree is clean at `0da56160a901f920d9f5469b0b41112b5c22dd1a`. New worktree started clean with HEAD equal to B.
B includes #74 (public history/AI) and #75 (laptop UI/navigation), beyond the instruction's B0.

| 기능 | B | R 전용 | 충돌 | 조치 | 검증 |
|---|---|---|---|---|---|
| DIRECT 발급/검증/취소 | 기본 경로, 제품 잠금 | 정각 만료, 수신자 검증, 상태 조회, 판매 재검증 | 기존 OWN_006 차단 코드 유지 | 선택 이식 | service/HTTP/PostgreSQL |
| 완료 | owner, 감사, 동기 초안 이벤트 | facts, 실제 TRANSFER, 증빙 보호 | 구형 내부 저장소/포트 사용 금지 | 팀 InternalTradeOwnershipRepository의 저수준 메소드 재사용, OwnershipTransferred 1회 | PostgreSQL rollback/race |
| 문서/이력 | 허용 마스킹 사본 승계, PRIVATE | 업로더 외 열람 금지 가정 | 팀 문서 정책 우선 | DocumentService 유지, 보호/PRIVATE 적용 | 실제 문서 HTTP |
| QR/수신 | 기본 수신 화면 | 로컬 QR, 로그인 복귀, 재확인 | 최신 auth/DOM 보존 | P5 UI 이식, 공유 파일 함수 단위 연결 | Node/browser |
| INTERNAL/P2~P4 | 정식 팀 포트 | 구형 adapter | 중복 Bean 불허 | 팀 구현 유지 | 팀 HTTP PostgreSQL 회귀 |
| 설정/DB | 정식 migration/기본 설정 | 과거 demo migration | 복원 불허 | 변경 없음, 전용 DB만 사용 | Flyway validate |

구형 InternalTradeTransferAdapter/JdbcInternalTransferRepository, 가짜 구매 요청, #72 별도 채팅 이슈는 제외한다. 기존 QR·토큰·수신 동작은 참조본을 재사용하며 새 공개 이력/AI 계약은 B의 #74를 따른다.
