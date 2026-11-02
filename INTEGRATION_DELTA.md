# P5 main convergence delta

Base B: `282ef24705e45d0eae9b8db6d6240b6bd8379fab`; reference R: `0bf4b44cbe6dc6593d6b81a734a25bf171653e9c`.
Original worktree is clean at `0da56160a901f920d9f5469b0b41112b5c22dd1a`. New worktree started clean with HEAD equal to B.
B includes #74 (public history/AI) and #75 (laptop UI/navigation), beyond the instruction's B0.

| 기능 | B | R 전용 | 충돌 | 조치 | 검증 |
|---|---|---|---|---|---|
| DIRECT 발급/검증/취소 | 기본 경로, 제품 잠금 | 정각 만료, 수신자 검증, 상태 조회, 판매 재검증 | 기존 OWN_005 차단 코드 유지 | 선택 이식 | service/HTTP/PostgreSQL |
| 완료 | owner, 감사, 동기 초안 이벤트 | facts, 실제 TRANSFER, 증빙 보호 | 구형 내부 저장소/포트 사용 금지 | 팀 InternalTradeOwnershipRepository의 저수준 메소드 재사용, OwnershipTransferred 1회 | PostgreSQL rollback/race |
| 문서/이력 | 허용 마스킹 사본 승계, PRIVATE | 업로더 외 열람 금지 가정 | 팀 문서 정책 우선 | DocumentService 유지, 보호/PRIVATE 적용 | 실제 문서 HTTP |
| QR/수신 | 기본 수신 화면 | 로컬 QR, 로그인 복귀, 재확인 | 최신 auth/DOM 보존 | P5 UI 이식, 공유 파일 함수 단위 연결 | Node/browser |
| INTERNAL/P2~P4 | 정식 팀 포트 | 구형 adapter | 중복 Bean 불허 | 팀 구현 유지 | 팀 HTTP PostgreSQL 회귀 |
| 설정/DB | 정식 migration/기본 설정 | 과거 demo migration | 복원 불허 | 변경 없음, 전용 DB만 사용 | Flyway validate |

구형 InternalTradeTransferAdapter/JdbcInternalTransferRepository, 가짜 구매 요청, #72 별도 채팅 이슈는 제외한다. 기존 QR·토큰·수신 동작은 참조본을 재사용하며 새 공개 이력/AI 계약은 B의 #74를 따른다.

서버 변경은 OwnershipTransferService의 발급/verify/accept/cancel/ownerStatus, DIRECT guard·DTO·privacy filter·정각 만료, ProductRepository.updateOwner의 facts 증가다. 완료 시 정식 저장소의 insertTransferEvent/makeHistoryPrivate/protectDocuments와 기존 동기 초안 이벤트를 사용한다. 공유 프런트는 index의 자산 등록, products의 상세 마운트/선택 캡처, auth의 로그인 복귀/로그아웃 이벤트만 연결했다. deals.js와 기존 migration·application 설정·DocumentService는 변경하지 않았다.

팀 B의 게시 서비스는 유효 PENDING DIRECT가 있으면 게시를 거부한다. 경합 검증은 실제 게시가 먼저 성공하면 발급이 거부되는 흐름과 완료가 먼저 잠그면 게시가 거부되는 흐름을 확인하며, 발급 후 ACTIVE 상태가 생긴 경우의 수락 재검사도 별도로 유지한다. B의 취소 시험 한 곳은 INTERNAL verify=200/accept=410이라는 오래된 기대를 가지고 있어 기존 서비스의 실제 404/409 계약으로 정확히 보정했다. R의 INTERNAL 수락/취소=404는 이식하지 않았다.

현재 캐시/페이지 로딩 순서에서 receive HTML이 스크립트보다 먼저 준비될 수 있어 기존 DOM에서도 초기화한다. 현재 버튼 스타일이 hidden 속성을 덮는 충돌은 ownership-actions 안에서만 차단했다. 브라우저 시험은 현재 하위 메뉴 이동과 모바일 목록 복귀 UI를 실제로 사용한다.

후속 충돌 해결: 팀 main `759575f92b51df9bf782a96365fddee37b754ecf`의 정식 `InternalTransferHistoryWriter`를 DIRECT에도 연결했다. 이력 생성·PRIVATE 처리는 해당 포트가 한 번 수행하고, 기존 effects 저장소는 문서 보호에 사용한다. P5 guard·Clock·flush·동기 초안 정리·커밋 후 이벤트는 유지한다. 서비스/시험 생성자에 양측 의존성을 함께 주입하고, 단위 시험은 owner 갱신→flush→이력 포트→문서 보호→초안 이벤트→감사 순서를 확인한다. 브라우저의 제조사 입력은 main의 선택/직접 입력 UI에 맞췄다.
