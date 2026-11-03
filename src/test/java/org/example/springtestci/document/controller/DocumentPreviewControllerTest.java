package org.example.springtestci.document.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.error.GlobalExceptionHandler;
import org.example.springtestci.document.entity.DocumentType;
import org.example.springtestci.document.service.DocumentPreviewExtractionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 미리보기 추출 컨트롤러가 파일을 읽기 전에 {@code admit()}으로 거절할 요청을 걸러내는지 확인한다. 서비스 테스트는 {@code admit}·{@code
 * preview}를 직접 부르기 때문에, 컨트롤러가 {@code getBytes()}를 먼저 부르거나 {@code admit}을 건너뛰어도 잡지 못한다.
 */
@ExtendWith(MockitoExtension.class)
class DocumentPreviewControllerTest {

  private static final long USER_ID = 1L;
  private static final String URL = "/api/v1/documents/extraction-preview";
  private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

  @Mock private DocumentPreviewExtractionService previewService;

  private final AtomicInteger bytesReads = new AtomicInteger();
  private MockMvc mockMvc;

  /** 본문을 몇 번 읽었는지 세는 업로드 파일. */
  private class CountingFile extends MockMultipartFile {
    CountingFile() {
      super("file", "masked.png", "image/png", new byte[] {1, 2, 3});
    }

    @Override
    public byte[] getBytes() throws IOException {
      bytesReads.incrementAndGet();
      return super.getBytes();
    }
  }

  @BeforeEach
  void setUp() {
    // @AuthenticationPrincipal Jwt는 SecurityContext에서 풀리므로 로그인한 사용자를 넣어 둔다.
    Jwt jwt =
        Jwt.withTokenValue("token").header("alg", "none").subject(String.valueOf(USER_ID)).build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    mockMvc =
        MockMvcBuilders.standaloneSetup(new DocumentPreviewController(previewService))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)))
            .build();
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void 거절될_요청은_파일을_읽지도_AI를_부르지도_않는다() throws Exception {
    doThrow(new CommonException(ErrorCode.MASKING_NOT_CONFIRMED))
        .when(previewService)
        .admit(USER_ID, false, true);

    mockMvc
        .perform(
            multipart(URL)
                .file(new CountingFile())
                .param("maskingConfirmed", "false")
                .param("consented", "true")
                .param("documentType", "RECEIPT"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOC_004"));

    org.junit.jupiter.api.Assertions.assertEquals(0, bytesReads.get());
    verify(previewService, never()).preview(any(), any(), any(), any());
  }

  @Test
  void 받아들인_요청만_파일을_읽고_그_뒤에_AI를_부른다() throws Exception {
    // admit이 불리는 순간에 파일이 이미 읽혔는지 기록한다. 0이어야 "읽기 전에 검사"다.
    AtomicInteger readsWhenAdmitted = new AtomicInteger(-1);
    doAnswer(
            invocation -> {
              readsWhenAdmitted.set(bytesReads.get());
              return null;
            })
        .when(previewService)
        .admit(USER_ID, true, true);
    when(previewService.preview(eq(DocumentType.RECEIPT), eq("masked.png"), eq("image/png"), any()))
        .thenReturn(Map.of("modelName", "NT950XED"));

    mockMvc
        .perform(
            multipart(URL)
                .file(new CountingFile())
                .param("maskingConfirmed", "true")
                .param("consented", "true")
                .param("documentType", "RECEIPT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.modelName").value("NT950XED"));

    org.junit.jupiter.api.Assertions.assertEquals(0, readsWhenAdmitted.get());
    org.junit.jupiter.api.Assertions.assertEquals(1, bytesReads.get());
  }
}
