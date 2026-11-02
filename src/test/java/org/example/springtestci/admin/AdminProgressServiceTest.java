package org.example.springtestci.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.example.springtestci.admin.dto.AdminDtos.*;
import org.example.springtestci.admin.repository.AdminProgressRepository;
import org.example.springtestci.admin.repository.AdminProgressRepository.Source;
import org.example.springtestci.admin.service.AdminProgressService;
import org.example.springtestci.admin.service.AdminReadService;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 저장된 확인 버전·종료 상태·기능 경계를 조합해 잘못된 대기 안내가 생기지 않는지 검사한다. */
class AdminProgressServiceTest {
  private final AdminReadService trades = mock(AdminReadService.class);
  private final AdminProgressRepository repository = mock(AdminProgressRepository.class);
  private final AdminProgressService service =
      new AdminProgressService(trades, repository, true, true);

  @ParameterizedTest
  @CsvSource(
      value = {
        "NULL,NULL,0,2,WAITING_BOTH_CONFIRMATIONS,NOT_CONFIRMED,NOT_CONFIRMED",
        "2,NULL,0,2,WAITING_BUYER_CONFIRMATION,CURRENT,NOT_CONFIRMED",
        "NULL,2,0,2,WAITING_SELLER_CONFIRMATION,NOT_CONFIRMED,CURRENT",
        "2,2,0,2,CONFIRMATIONS_RECEIVED,CURRENT,CURRENT",
        "1,1,0,2,WAITING_BOTH_CONFIRMATIONS,RECONFIRM_REQUIRED,RECONFIRM_REQUIRED",
        "1,2,0,2,WAITING_SELLER_CONFIRMATION,RECONFIRM_REQUIRED,CURRENT",
        "2,1,0,2,WAITING_BUYER_CONFIRMATION,CURRENT,RECONFIRM_REQUIRED",
        "2,2,3,2,WAITING_BUYER_CHANGE_REVIEW,CURRENT,CURRENT",
        "NULL,NULL,1,2,WAITING_BUYER_CHANGE_REVIEW,NOT_CONFIRMED,NOT_CONFIRMED"
      },
      nullValues = "NULL")
  void distinguishesWhoMustConfirmAndPrioritizesBuyerChangeReview(
      Long seller,
      Long buyer,
      long unreviewed,
      long current,
      ProgressState state,
      ConfirmationState sellerState,
      ConfirmationState buyerState) {
    trade("ACCEPTED", "RESERVED");
    when(repository.find(41))
        .thenReturn(Optional.of(new Source(current, seller, buyer, true, unreviewed)));
    var result = service.progress(9, 41);
    assertEquals(state, result.state());
    assertEquals(sellerState, result.sellerConfirmation());
    assertEquals(buyerState, result.buyerConfirmation());
    assertEquals(unreviewed, result.unreviewedChangeCount());
    assertEquals(current, result.factsVersion());
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "ACCEPTED,COMPLETED,COMPLETED",
        "CANCELLED,CANCELLED,CANCELLED",
        "CANCELLED,NULL,CANCELLED",
        "REJECTED,NULL,REJECTED",
        "PENDING,NULL,WAITING_SELLER_ACCEPTANCE"
      },
      nullValues = "NULL")
  void endedAndNotYetReservedRequestsNeverCompareTheNewOwnersCurrentVersion(
      String request, String transaction, ProgressState state) {
    trade(request, transaction);
    var result = service.progress(9, 41);
    assertEquals(state, result.state());
    assertEquals(ConfirmationState.NOT_APPLICABLE, result.sellerConfirmation());
    assertNull(result.factsVersion());
    assertNull(result.unreviewedChangeCount());
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true"})
  void disabledDependenciesDoNotQueryTheirTablesOrInventZeroCounts(boolean p3, boolean p4) {
    trade("ACCEPTED", "RESERVED");
    var result = new AdminProgressService(trades, repository, p3, p4).progress(9, 41);
    assertEquals(ProgressState.CONFIRMATION_UNAVAILABLE, result.state());
    assertEquals(ConfirmationState.UNAVAILABLE, result.buyerConfirmation());
    assertNull(result.unreviewedChangeCount());
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "NULL,NULL,NULL,true",
        "0,NULL,NULL,true",
        "2,3,NULL,true",
        "2,0,NULL,true",
        "2,NULL,NULL,false"
      },
      nullValues = "NULL")
  void invalidConnectionOrFutureVersionIsNotReportedAsOrdinaryUserDelay(
      Long current, Long seller, Long buyer, boolean connected) {
    trade("ACCEPTED", "RESERVED");
    when(repository.find(41))
        .thenReturn(Optional.of(new Source(current, seller, buyer, connected, 0)));
    assertEquals(ProgressState.CONNECTION_CHECK_REQUIRED, service.progress(9, 41).state());
  }

  @Test
  void authorizationIsCheckedBeforeProgressData() {
    when(trades.trade(1, 41)).thenThrow(new CommonException(ErrorCode.ACCESS_DENIED));
    assertThrows(CommonException.class, () -> service.progress(1, 41));
    verifyNoInteractions(repository);
  }

  private void trade(String request, String transaction) {
    Trade trade = mock(Trade.class);
    when(trade.requestStatus()).thenReturn(request);
    when(trade.transactionStatus()).thenReturn(transaction);
    when(trades.trade(9, 41)).thenReturn(trade);
  }
}
