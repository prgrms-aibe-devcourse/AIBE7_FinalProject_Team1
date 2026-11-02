# 채팅·거래 코드 흐름 안내

이 문서는 P1 채팅, P2 구매 요청·예약, P3 예약 취소·변경 확인, P4 양측 거래완료 코드의 진입점과 책임 경계를 연결한다. 기능 계약은 각 단계의 별도 API·결과 문서를 우선하며, 여기서는 코드를 읽는 순서와 외부 연동 상태를 설명한다.

## 공통 계층

```text
Controller
→ Service 인터페이스 또는 유스케이스 구현
→ Repository / 외부 포트
→ JDBC·JPA·STOMP 어댑터
```

- Controller는 검증된 HTTP 입력과 인증 사용자 ID만 전달한다.
- Service는 당사자 권한, 상태 전이, 잠금 순서와 트랜잭션 경계를 소유한다.
- Repository는 도메인 모델을 반환하며 JPA Entity를 API나 Service에 노출하지 않는다.
- 외부 포트의 시험 구현은 운영 ownership/history 연동 완료를 뜻하지 않는다.

## P1 채팅

### HTTP 흐름

1. `chat/controller/ChatController.java`가 방 생성·목록·메시지·읽음 위치 요청을 받는다.
2. `chat/service/ChatService.java`가 공개 유스케이스 계약을 정의한다.
3. `chat/service/DefaultChatService.java`가 방 참여자 권한과 판매글 상태를 검증한다.
4. `chat/repository/ChatRoomRepository.java`, `ChatMessageRepository.java`, `ChatReadPositionRepository.java`를 `chat/repository/jpa` 구현이 처리한다.
5. 판매글과 사용자 표시는 `ChatListingReader`, `ChatUserReader` 포트를 통해 필요한 공개 사본만 조회한다.

`openRoom()`은 판매글 ID와 인증 문의자 ID를 사용한다. `sendMessage()`는 메시지를 먼저 저장하고 `ChatMessageDelivery.publishAfterCommit()`으로 커밋 이후 발행을 예약한다. `updateMyReadPosition()`은 메시지가 요청한 방에 속하는지 확인한 뒤 읽음 위치를 갱신한다.

`GET /api/v1/chat-rooms`의 선택적 양수 `listingId`는 원래 판매자·문의자 참여 조건과 함께 DB 페이지 조회와 count에 적용한다. 제품의 현재 소유권으로 과거 방을 승계하지 않으며, 참여 방이 없는 판매글은 존재 여부와 관계없이 빈 페이지다. 조회 트랜잭션은 읽기 전용이며 목록 미리보기는 읽음 위치를 변경하지 않는다.

사이드바·상단·마이페이지의 `deals` 진입은 전체 목록의 첫 페이지로 돌아간다. 본인 상세·관리 화면에는 실제 상세 `listingId`의 목록을 직접 표시한다. 두 목록은 `chat-room-list.js`의 행·20개 단위 더 보기를 공유하고, 선택한 요약과 roomId를 `deals.js`에 전달한다. 구매 문의는 기존 생성/재사용 API 응답을 해당 판매글의 본인 목록으로 보완한다. 페이지 HTML 로딩 후 대화를 열며, 화면·방·계정 전환 시 요청 세대를 검증하고 기존 타이머를 종료한다. 종료된 판매글의 공개 상세 정책과 기존 방의 메시지 권한은 유지한다.

### 실시간 흐름

1. `chat/realtime/ChatRealtimeConfig.java`가 `/ws-stomp`와 `/sub` 브로커를 구성한다.
2. `ChatStompAuthorizationInterceptor.preSend()`가 CONNECT JWT와 SUBSCRIBE 목적지를 검사하며 클라이언트 SEND를 거부한다.
3. `ChatRoomSubscriptionAuthorizer.canSubscribe()`가 저장된 방의 판매자·문의자 여부를 확인한다.
4. `ChatMessageRealtimePublisher.publishAfterCommit()`이 커밋된 메시지만 `/sub/chat/room/{roomId}`로 발행한다.

## P2 구매 요청·예약

1. `trade/controller/PurchaseRequestController.java`가 구매 요청 ID, 채팅방 ID와 게시 판매글 ID를 구분해 입력받는다.
2. `trade/service/PurchaseRequestService.java`가 구매자·판매자 역할과 요청 상태를 검증한다.
3. `trade/repository/PurchaseRequestRepository.java`와 `JdbcPurchaseRequestRepository.java`가 `purchase_requests`를 조회·잠금·조건부 갱신한다.
4. `PublishedListingAccess`와 `JdbcPublishedListingAccess`가 제품을 먼저 잠근 뒤 판매글을 잠그고 ACTIVE/RESERVED 상태를 갱신한다.
5. `accept()`는 요청 ACCEPTED/RESERVED, 판매글 RESERVED와 `InternalTransferPendingWriter.createPending()`을 같은 트랜잭션에서 처리한다.

`InternalTransferPendingWriter`와 `InternalTradeCompletionPort`의 정식 운영 구현은 `InternalTradeOwnershipService`다. DIRECT는 이 내부 포트를 호출하지 않는다.

## P3 예약 취소

R07 판매중지의 정식 내부 이전 취소는 ownership의 `JdbcInternalTransferCancellation`이 담당한다. 같은 거래 트랜잭션에서 INTERNAL/PENDING 한 건을 조건부 취소하고 대기 슬롯을 해제한다. 예약 중 판매중지는 자유문장 `reason`과 선택형 `tradeReason`을 분리한다. [실제 취소 연결](../LISTING_CANCELLATION_INTEGRATION.md)을 참고한다.

판매글 신규 게시의 거래 검사는 일반 환경에서 `JdbcListingPublishConstraints`가 실제 RESERVED 구매 요청과 유효한 PENDING 이전을 조회한다. 제품 잠금 안에서 검사하고 원천 미배포 시 503으로 거부한다. 외부 이전 발급도 같은 제품 잠금 아래 열린 판매글을 검사한다. [게시 조건 연동](../LISTING_PUBLISH_CONSTRAINTS.md)을 참고한다.

1. `trade/controller/TradeController.cancel()`이 API의 tradeId로 사용하는 구매 요청 ID와 취소 enum을 받는다.
2. `TradeCancellationService.cancel()`이 원래 판매자 또는 선택 구매자인지 확인한다.
3. 제품·판매글을 먼저 잠그고 구매 요청을 재조회한 뒤 `cancelLocked()`가 다음 순서로 처리한다.

```text
INTERNAL/PENDING 이전 취소
→ 구매 요청·거래 CANCELLED 및 최초 취소 메타데이터 저장
→ 일반 예약 취소인 경우 판매글 RESERVED → ACTIVE
→ 감사 기록
```

모든 단계는 같은 Spring 트랜잭션에 참여한다. `InternalTransferCancellationPort`의 정식 운영 구현은 `ownership/repository/JdbcInternalTransferCancellation.java`이며, P3 활성화 환경에서 `MANDATORY` 트랜잭션에 참여한다.

`TradeListingCanceller.cancel()`은 R07 판매 중지 트랜잭션에 `MANDATORY`로 참여한다. RESERVED 판매글을 중지할 때 선택 거래와 나머지 PENDING 요청을 정리하지만 이미 CANCELLED인 판매글을 ACTIVE로 복구하지 않는다.

## P3 변경 확인

1. 정식 history/product 연동이 `ReservationChangeService.recordChange()`에 eventId, factsVersion과 공개 안전 JSON 사본을 같은 트랜잭션으로 공급한다. 공개 생성 API는 없다.
2. `ReservationChangeReviewRepository`와 `JdbcReservationChangeReviewRepository`가 `(purchase_request_id, event_id)` 복합 키로 사본을 불변 저장한다.
3. `TradeController.findChangeReviews()`는 양측 당사자에게 목록과 미확인 건수를 반환한다.
4. `ReservationChangeService.review()`는 제품·판매글 → 구매 요청 → review 순서로 잠그고 최신 결정을 먼저 판정한다.
5. KEEP은 해당 사건만 확정한다. CANCEL은 공통 예약 취소와 review 결정을 같은 트랜잭션에서 처리한다.
6. `requireNoUnreviewedChanges()`는 향후 P4 완료 처리에서 미확인 변경이 있는 거래를 차단하는 가드다.

## P4 양측 거래완료

1. `TradeCompletionController#getContext()`와 `#complete()`가 P3 Controller와 분리된 기능 플래그 경계에서 요청을 받는다.
2. `TradeCompletionService#getContext()`는 원래 당사자와 예약 상태를 확인하고 제품→판매글→판매글별 구매 요청 ID 순서로 잠근다.
3. `ProductService#getForListing()`의 `factsVersion`과 `ListingDetailService#get()`의 공개 projection을 같은 잠금 범위에서 조합한다. READY가 아니거나 샘플인 이력은 완료 근거로 사용하지 않는다.
4. `TradeCompletionService#complete()`는 종료 재요청을 먼저 반환하고, 진행 중 거래에는 최신 버전·정식 공개 이력·미확인 변경 0건을 다시 검사한 뒤 인증 사용자의 확인만 저장한다.
5. 양측 확인이 같은 현재 버전이면 `PurchaseRequestRepository#complete()`, `PublishedListingAccess#sell()`, `#rejectPendingByListingId()` 순서로 거래 측 상태를 확정한다.
6. `InternalTradeCompletionPort#complete()`와 `TradeAuditRecorder#tradeCompleted()`가 같은 트랜잭션에 참여한다. 기능 활성화 시 `InternalTradeOwnershipService` 한 개만 운영 완료 포트로 등록한다.

완료된 거래의 재요청은 원래 방 당사자에게 최소 상태만 반환한다. 이후 제품 소유자 변경이나 facts 버전 증가를 이유로 과거 완료를 다시 수행하지 않는다.

## DIRECT 외부 이전

`OwnershipTransferController → OwnershipTransferService`는 실제 제품 ID로 현재 소유자의 링크 발급·상태 조회·취소와 인증된 수신자의 명시 수락을 처리한다. verify는 읽기 전용이며 INTERNAL 토큰은 404로 숨기고 외부 수락·취소는 기존 409 차단 계약을 유지한다.

제품→판매글→이전 행 순서로 잠근 뒤 소유자·보관·판매·수신자·만료 정각을 재검사한다. 수신자의 accept는 동의 시각만 기록하고 PENDING을 유지한다. 최초 발신자인 현재 소유자의 approve가 확인한 수신자 ID·factsVersion을 재검사한 뒤 같은 트랜잭션에서 owner/facts·COMPLETED·슬롯 해제·실제 PK의 시스템 TRANSFER·PRIVATE·증빙 보호·감사를 확정한다. 정식 `InternalTransferHistoryWriter`가 시스템 이력 생성과 누적 공개 종료를 처리하고 `InternalTradeOwnershipRepository.protectDocuments()`가 증빙을 보호한다. `OwnershipTransferred`는 동기 초안 정리에, `ProductFactsChanged`는 커밋 후 AI 갱신에 한 번씩 발행한다. 허용 마스킹 사본의 승계와 재마스킹·업로더·삭제 제한은 현행 `DocumentService`를 따른다.

상단의 `외부 소유권 이전 → 보내기 / 받기`에서 진입한다. `trade.js`는 보내기 화면에서 현재 소유 제품 조회·선택과 로그인 후 복귀를 담당하고, 제품 선택만으로 이전을 발급하지 않는다. `ownership.js`는 활성 제품 상세 또는 보내기 화면에 발급·취소·재발급·최종 승인·로컬 QR 패널을 연결한다. 화면·제품 전환 시 이전 공유 정보를 지우고, 상태 조회 중에는 발급을 막는다. `receive.js`는 토큰 주소 정리·메모리 내 로그인 복귀·명시 동의·승인 대기·응답 유실 후 재확인을 담당한다. 수신 토큰이 있는 진입은 저장된 화면 복원보다 우선한다. Web Locks 인증 흐름과 양측 완료 확인으로 자동 이전하는 INTERNAL 거래 화면은 유지한다.

문서 업로드·교체·삭제·추출 요청·확정·반려는 제품을 먼저 잠그고 소유권을 재검사한 뒤 문서를 잠근다. 제품 ID는 scalar 조회로 구해 잠금 전의 문서 버전이 영속성 컨텍스트에 남지 않게 한다. 다운로드는 사용자·문서 ID·버전·만료를 묶은 HMAC URL을 사용하고 같은 잠금 아래 현재 소유권·재마스킹 필요 여부·파일 가독성을 검사한다. 신규 사본은 DB 롤백 시 삭제하고 구 사본은 커밋 후 삭제하며 교체 감사는 같은 트랜잭션에서 유지한다. 공통 `FileStorage.load()`를 사용하며 운영 저장소 어댑터의 정식 제공은 별도 작업이다.

## 내 판매글 진입

`GET /api/v1/my-listings`는 JWT의 판매자 ID로만 조회한다. `MyListingController → MyListingService → MyListingQueryRepository`에서 ACTIVE/RESERVED/SOLD/CANCELLED를 페이지로 반환하고, 선택한 상태의 건수와 목록을 함께 계산한다. 공개 마켓의 ACTIVE 필터는 그대로 유지한다.

상단 메뉴·마켓 상단·마이페이지·판매글 상세에서 내 판매글로 이동한다. 목록의 `관리하기`는 기존 상세 조회와 관리 API로 이어지며, 현재 소유자가 아니거나 제품이 보관 처리됐거나 판매글이 종료됐으면 관리 버튼과 제품 사진 URL을 제공하지 않는다. 관리 API 자체의 권한·상태 검사도 유지한다. 종료된 글은 과거 판매글의 제목·가격·상태만 확인할 수 있고 새로운 소유자의 제품 정보는 조회하지 않는다.

`my-listings.js`는 상태·페이지를 URL에 보관하고 계정 변경·화면 이탈 시 요청을 중단하고 비공개 목록을 비운다. 예약 취소나 소유권 이전 등 상태 변경은 이 화면에서 직접 수행하지 않고 기존 관리 API에 위임한다.

## 관리자 거래 확인 상태 조회

`GET /api/v1/admin/trades/{id}/progress`는 `AdminController → AdminProgressService → AdminReadService / AdminProgressRepository`로 이어진다.
현재 관리자 역할·P2 활성·구매 요청 존재를 확인하고 읽기 전용 트랜잭션으로 같은 시점의 버전·미확인 건수를 읽는다.
당사자용 완료 context는 제품 잠금을 취하므로 이 관리자 조회에서 호출하지 않는다.
종료 상태를 먼저 판정한 뒤 예약 거래에만 변경 이력 확인과 최신 버전의 양측 완료 확인 대기를 계산한다.
P3/P4 비활성 상태와 현재 확인 전 상태를 구분하고, 양측 확인이 있어도 실제 완료나 완료 가능 상태를 추정하지 않는다.

## 주석 유지 기준

- 클래스 주석은 계층 역할, 권한과 트랜잭션 책임을 설명한다.
- 주요 API·업무·저장 메소드는 입력 식별자의 의미, 결과, 거부 조건과 위임 대상을 설명한다.
- 잠금·조건부 갱신·커밋 후 발행처럼 순서가 정확성에 영향을 주는 곳은 이유와 순서를 남긴다.
- Javadoc은 선언의 첫 어노테이션보다 앞에 둔다.
- 단순 getter, 생성자 대입이나 코드 그대로인 설명은 반복하지 않는다.
- 기능 변경과 주석 전용 변경은 논리적으로 분리한다.
