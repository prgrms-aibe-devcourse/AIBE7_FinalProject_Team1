package org.example.springtestci.document.repository;

import java.util.List;
import org.example.springtestci.document.entity.DocumentReplacement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentReplacementRepository extends JpaRepository<DocumentReplacement, Long> {

  List<DocumentReplacement> findByDocumentIdOrderByIdDesc(Long documentId);
}
