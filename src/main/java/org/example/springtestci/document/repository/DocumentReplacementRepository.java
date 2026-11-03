package org.example.springtestci.document.repository;

import java.util.List;
import org.example.springtestci.document.entity.DocumentReplacement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentReplacementRepository extends JpaRepository<DocumentReplacement, Long> {

  List<DocumentReplacement> findByDocumentIdOrderByIdDesc(Long documentId);

  /** 문서를 삭제하기 전에 그 문서의 교체 기록을 지운다. 교체 기록은 문서를 가리키는 외래키를 가진다. */
  void deleteByDocumentId(Long documentId);
}
