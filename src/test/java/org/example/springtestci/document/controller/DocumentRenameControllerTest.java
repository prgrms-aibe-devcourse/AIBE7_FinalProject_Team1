package org.example.springtestci.document.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.error.GlobalExceptionHandler;
import org.example.springtestci.document.entity.Document;
import org.example.springtestci.document.service.DocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 이름 변경 API의 인증 사용자 전달·응답 DTO·입력 길이 검증을 HTTP 요청으로 확인한다. */
@ExtendWith(MockitoExtension.class)
class DocumentRenameControllerTest {

  @Mock private DocumentService documentService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("1").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    mockMvc =
        MockMvcBuilders.standaloneSetup(new DocumentController(documentService))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC()))
            .build();
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void 문서_ID와_인증된_소유자로_이름을_변경하고_DTO를_반환한다() throws Exception {
    Document document =
        Document.register(100L, 1L, "documents/private.pdf", "application/pdf", 10, Instant.now());
    document.rename("구매 보증서");
    when(documentService.rename(5L, 1L, "구매 보증서")).thenReturn(document);

    mockMvc
        .perform(
            patch("/api/v1/documents/5/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"구매 보증서\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("구매 보증서"))
        .andExpect(jsonPath("$.data.version").value(1))
        .andExpect(jsonPath("$.data.objectKey").doesNotExist());
  }

  @Test
  void 너무_긴_이름은_서비스를_호출하기_전에_거부한다() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/documents/5/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\"}".formatted("가".repeat(51))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(documentService);
  }

  @Test
  void 다른_소유자의_문서는_찾을_수_없다는_응답으로_반환한다() throws Exception {
    when(documentService.rename(any(), any(), any()))
        .thenThrow(new CommonException(ErrorCode.RESOURCE_NOT_FOUND));

    mockMvc
        .perform(
            patch("/api/v1/documents/5/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"보증서\"}"))
        .andExpect(status().isNotFound());
  }
}
