package org.example.springtestci.history.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.history.repository.HistoryRepository;
import org.example.springtestci.history.repository.HistoryRepository.TransferEventIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultInternalTransferHistoryWriterTest {

  private static final Instant COMPLETED_AT = Instant.parse("2026-10-30T01:02:03Z");
  private static final InternalTransferHistoryWriter.Command COMMAND =
      new InternalTransferHistoryWriter.Command(10L, 20L, 30L, 40L, COMPLETED_AT);

  @Mock private HistoryRepository histories;

  private DefaultInternalTransferHistoryWriter writer;

  @BeforeEach
  void setUp() {
    writer = new DefaultInternalTransferHistoryWriter(histories, Clock.systemUTC());
  }

  @Test
  void storesOnePrivateTransferEventAfterTheOwnerChanged() {
    when(histories.findTransferEvent(20L)).thenReturn(Optional.empty());
    when(histories.isCurrentOwner(10L, 40L)).thenReturn(true);

    writer.recordCompletedTransfer(COMMAND);

    OffsetDateTime completedAt = COMPLETED_AT.atOffset(ZoneOffset.UTC);
    verify(histories).insertTransferEvent(10L, 20L, LocalDate.of(2026, 10, 30), completedAt);
    verify(histories).makeAllPrivate(10L);
  }

  @Test
  void repeatedCompletionKeepsTheExistingTransferEvent() {
    when(histories.findTransferEvent(20L))
        .thenReturn(Optional.of(new TransferEventIdentity(50L, 10L)));

    writer.recordCompletedTransfer(COMMAND);

    verify(histories, never())
        .insertTransferEvent(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(histories, never()).makeAllPrivate(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void rejectsRecordingBeforeTheRecipientBecomesTheCurrentOwner() {
    when(histories.findTransferEvent(20L)).thenReturn(Optional.empty());
    when(histories.isCurrentOwner(10L, 40L)).thenReturn(false);

    assertThrows(CommonException.class, () -> writer.recordCompletedTransfer(COMMAND));

    verify(histories, never())
        .insertTransferEvent(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsAReusedTransferIdForAnotherProduct() {
    when(histories.findTransferEvent(20L))
        .thenReturn(Optional.of(new TransferEventIdentity(50L, 999L)));

    assertThrows(CommonException.class, () -> writer.recordCompletedTransfer(COMMAND));
  }

  @Test
  void verifiesAnExistingTransferEventForTheSameProduct() {
    when(histories.findTransferEvent(20L))
        .thenReturn(Optional.of(new TransferEventIdentity(50L, 10L)));

    assertTrue(writer.hasCompletedTransfer(10L, 20L));
  }
}
