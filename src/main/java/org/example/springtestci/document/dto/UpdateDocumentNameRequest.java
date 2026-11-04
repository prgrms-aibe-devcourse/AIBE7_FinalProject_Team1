package org.example.springtestci.document.dto;

import jakarta.validation.constraints.Size;

/** 문서 표시 이름 변경 요청. 비어 있으면 이름을 지우고 화면의 기본 문서명으로 표시한다. */
public record UpdateDocumentNameRequest(@Size(max = 50) String name) {}
