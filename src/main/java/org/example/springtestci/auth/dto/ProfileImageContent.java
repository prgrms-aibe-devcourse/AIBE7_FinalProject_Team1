package org.example.springtestci.auth.dto;

import java.time.Instant;

/** 프로필 사진 응답용 값. 내용 바이트와 형식, 마지막 변경 시각만 담는다. */
public record ProfileImageContent(byte[] content, String contentType, Instant updatedAt) {}
