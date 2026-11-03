package org.example.springtestci.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.example.springtestci.common.security.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private RefreshTokenStore store;

  @BeforeEach
  void setUp() {
    JwtProperties properties =
        new JwtProperties(
            "test-secret-test-secret-test-secret-1234",
            Duration.ofMinutes(15),
            Duration.ofDays(14),
            true);
    store = new RefreshTokenStore(redisTemplate, properties);
  }

  @Test
  void issueStoresTheUserIdUnderAHashedKeyWithTheConfiguredTtl() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    String token = store.issue(7L);

    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(valueOperations)
        .set(
            key.capture(),
            org.mockito.ArgumentMatchers.eq("7"),
            org.mockito.ArgumentMatchers.eq(Duration.ofDays(14)));
    assertTrue(key.getValue().startsWith("auth:refresh:"));
    assertFalse(key.getValue().contains(token));
  }

  @Test
  void consumeReturnsTheUserIdAndRemovesTheToken() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.getAndDelete(anyString())).thenReturn("7");

    assertEquals(Optional.of(7L), store.consume("token"));
  }

  @Test
  void consumeReturnsEmptyForAnUnknownToken() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.getAndDelete(anyString())).thenReturn(null);

    assertEquals(Optional.empty(), store.consume("token"));
  }

  @Test
  void revokeDeletesTheHashedKeyAndEndsTheOwnersSession() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    ArgumentCaptor<String> removed = ArgumentCaptor.forClass(String.class);
    when(valueOperations.getAndDelete(removed.capture())).thenReturn("7", (String) null);

    store.revoke("token");

    assertTrue(removed.getAllValues().get(0).startsWith("auth:refresh:"));
    assertFalse(removed.getAllValues().get(0).contains("token"));
    verify(redisTemplate).delete("auth:session:7");
  }

  @Test
  void issueRefreshesTheLoginLeaseAndRemembersTheCurrentToken() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    store.issue(7L);

    verify(valueOperations).set("auth:session:7", "1", RefreshTokenStore.SESSION_LEASE);
    verify(valueOperations)
        .set(
            org.mockito.ArgumentMatchers.eq("auth:session-token:7"),
            org.mockito.ArgumentMatchers.startsWith("auth:refresh:"),
            org.mockito.ArgumentMatchers.eq(Duration.ofDays(14)));
  }

  @Test
  void startSessionIsRefusedWhileTheAccountIsAlreadyLoggedIn() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent("auth:session:7", "1", RefreshTokenStore.SESSION_LEASE))
        .thenReturn(false);

    assertFalse(store.startSession(7L));

    verify(valueOperations, org.mockito.Mockito.never()).getAndDelete(anyString());
  }

  @Test
  void startSessionSucceedsOnceAndRevokesTheTokenLeftOnAnotherDevice() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent("auth:session:7", "1", RefreshTokenStore.SESSION_LEASE))
        .thenReturn(true);
    when(valueOperations.getAndDelete("auth:session-token:7")).thenReturn("auth:refresh:old");

    assertTrue(store.startSession(7L));

    verify(redisTemplate).delete("auth:refresh:old");
  }

  @Test
  void endSessionRemovesTheLeaseAndTheCurrentToken() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.getAndDelete("auth:session-token:7")).thenReturn("auth:refresh:cur");

    store.endSession(7L);

    verify(redisTemplate).delete("auth:refresh:cur");
    verify(redisTemplate).delete("auth:session:7");
  }
}
