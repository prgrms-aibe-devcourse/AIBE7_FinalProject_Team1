package org.example.springtestci.document.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.example.springtestci.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, Long> {

  List<Document> findByProductIdOrderByIdDesc(Long productId);

  long countByProductId(Long productId);

  @Query("select d.productId from Document d where d.id = :id")
  Optional<Long> findProductIdById(@Param("id") Long id);

  /** 저장 키로 문서를 찾는다. 다운로드 시점에 현재 소유자를 다시 확인할 때 쓴다. */
  Optional<Document> findByObjectKey(String objectKey);

  /** 같은 문서에 대한 추출 요청이 동시에 들어와도 하나만 만들어지도록 문서 행을 잠근다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from Document d where d.id = :id")
  Optional<Document> findByIdForUpdate(@Param("id") Long id);
}
