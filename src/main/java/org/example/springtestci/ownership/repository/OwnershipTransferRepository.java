package org.example.springtestci.ownership.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.example.springtestci.ownership.entity.OwnershipTransfer;
import org.example.springtestci.ownership.entity.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OwnershipTransferRepository extends JpaRepository<OwnershipTransfer, Long> {

  Optional<OwnershipTransfer> findByTokenHash(String tokenHash);

  /** 수락·취소가 동시에 들어와도 하나만 확정되도록 행을 잠그고 조회한다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from OwnershipTransfer t where t.tokenHash = :tokenHash")
  Optional<OwnershipTransfer> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from OwnershipTransfer t where t.id = :id")
  Optional<OwnershipTransfer> findByIdForUpdate(@Param("id") Long id);

  Optional<OwnershipTransfer> findByProductIdAndStatus(Long productId, TransferStatus status);

  boolean existsByProductIdAndStatus(Long productId, TransferStatus status);
}
