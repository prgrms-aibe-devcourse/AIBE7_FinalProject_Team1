package org.example.springtestci.ownership.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.example.springtestci.common.error.GlobalExceptionHandler;
import org.example.springtestci.common.security.CurrentUserIdResolver;
import org.example.springtestci.ownership.dto.AcceptTransferResponse;
import org.example.springtestci.ownership.dto.CreateTransferResponse;
import org.example.springtestci.ownership.dto.VerifyTransferResponse;
import org.example.springtestci.ownership.entity.TransferStatus;
import org.example.springtestci.ownership.service.OwnershipTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class OwnershipTransferControllerTest {

  @Mock private OwnershipTransferService service;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new OwnershipTransferController(service, new CurrentUserIdResolver()))
            .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC()))
            .build();
  }

  private TestingAuthenticationToken authentication(long userId) {
    Jwt jwt =
        Jwt.withTokenValue("mock-token")
            .header("alg", "none")
            .claim("sub", String.valueOf(userId))
            .build();
    return new TestingAuthenticationToken(jwt, null, "ROLE_USER");
  }

  @Test
  @DisplayName("소유권 이전 토큰 생성 API")
  void createTransfer() throws Exception {
    CreateTransferResponse response =
        new CreateTransferResponse(
            1L,
            10L,
            "raw-token-123",
            Instant.now().plus(1, ChronoUnit.DAYS),
            "/receive?token=raw-token-123");
    given(service.createTransfer(eq(1L), any())).willReturn(response);

    mockMvc
        .perform(
            post("/api/v1/ownership/transfers")
                .principal(authentication(1L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\": 10}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.transferId").value(1))
        .andExpect(jsonPath("$.data.productId").value(10))
        .andExpect(jsonPath("$.data.rawToken").value("raw-token-123"));
  }

  @Test
  @DisplayName("소유권 이전 토큰 검증 API")
  void verifyTransfer() throws Exception {
    VerifyTransferResponse response =
        new VerifyTransferResponse(
            1L,
            10L,
            "맥북 프로 16",
            1L,
            "판매자닉네임",
            Instant.now().plus(1, ChronoUnit.DAYS),
            TransferStatus.PENDING,
            false,
            true);
    given(service.verifyTransfer("test-token")).willReturn(response);

    mockMvc
        .perform(get("/api/v1/ownership/transfers/verify").param("token", "test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.transferId").value(1))
        .andExpect(jsonPath("$.data.productTitle").value("맥북 프로 16"))
        .andExpect(jsonPath("$.data.canAccept").value(true));
  }

  @Test
  @DisplayName("소유권 이전 수락 API")
  void acceptTransfer() throws Exception {
    AcceptTransferResponse response = new AcceptTransferResponse(1L, 10L, 2L, Instant.now());
    given(service.acceptTransfer(eq(2L), any())).willReturn(response);

    mockMvc
        .perform(
            post("/api/v1/ownership/transfers/accept")
                .principal(authentication(2L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"valid-token\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.newOwnerId").value(2));
  }

  @Test
  @DisplayName("소유권 이전 취소 API")
  void cancelTransfer() throws Exception {
    mockMvc
        .perform(post("/api/v1/ownership/transfers/1/cancel").principal(authentication(1L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }
}
