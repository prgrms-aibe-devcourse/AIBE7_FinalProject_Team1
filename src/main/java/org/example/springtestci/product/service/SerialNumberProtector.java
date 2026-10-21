package org.example.springtestci.product.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 일련번호를 두 가지 형태로 보호한다.
 *
 * <ul>
 *   <li>지문(HMAC-SHA256): 같은 기기의 중복 등록을 막는 유니크 검사용. 원문을 되돌릴 수 없다.
 *   <li>암호문(AES-256-GCM): 소유권 확인 등 정말 필요할 때만 복호화하기 위한 보관용.
 * </ul>
 *
 * 마스터 시크릿에서 용도별 키를 따로 만들어, 지문 키와 암호화 키가 같지 않게 한다.
 */
@Component
public class SerialNumberProtector {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec fingerprintKey;
  private final SecretKeySpec encryptionKey;
  private final SecureRandom secureRandom = new SecureRandom();

  /** 마스터 시크릿에서 지문 키와 암호화 키를 분리해 생성한다. */
  public SerialNumberProtector(@Value("${app.product.serial-secret}") String secret) {
    if (secret == null || secret.length() < 32) {
      throw new IllegalStateException("app.product.serial-secret must be at least 32 characters");
    }
    byte[] master = secret.getBytes(StandardCharsets.UTF_8);
    this.fingerprintKey = new SecretKeySpec(derive(master, "serial-fingerprint"), "HmacSHA256");
    this.encryptionKey = new SecretKeySpec(derive(master, "serial-encryption"), "AES");
  }

  /** 공백과 대소문자 차이로 같은 기기가 다른 번호로 등록되지 않게 정규화한다. */
  public static String normalize(String serialNumber) {
    return serialNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }

  /** 제조사 표기 차이로 동일 제품의 중복 검사가 우회되지 않도록 정규화한다. */
  public static String normalizeManufacturer(String manufacturer) {
    return manufacturer.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
  }

  /** 정규화된 일련번호의 결정적 HMAC 지문을 반환한다. */
  public String fingerprint(String serialNumber) {
    return HexFormat.of().formatHex(hmac(fingerprintKey, normalize(serialNumber)));
  }

  /** 정규화된 일련번호를 임의 IV가 포함된 AES-GCM 암호문으로 변환한다. */
  public String encrypt(String serialNumber) {
    try {
      byte[] iv = new byte[IV_BYTES];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(normalize(serialNumber).getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Failed to encrypt serial number", exception);
    }
  }

  /** 저장된 AES-GCM 암호문을 검증하고 정규화된 일련번호를 복원한다. */
  public String decrypt(String ciphertext) {
    try {
      byte[] combined = Base64.getDecoder().decode(ciphertext);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          encryptionKey,
          new GCMParameterSpec(TAG_BITS, combined, 0, IV_BYTES));
      return new String(
          cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Failed to decrypt serial number", exception);
    }
  }

  /** 마스터 키와 용도 문자열로부터 용도별 키 재료를 유도한다. */
  private static byte[] derive(byte[] master, String purpose) {
    return hmac(new SecretKeySpec(master, "HmacSHA256"), purpose);
  }

  /** 지정한 키로 메시지의 HMAC-SHA256 값을 계산한다. */
  private static byte[] hmac(SecretKeySpec key, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HmacSHA256 not available", exception);
    }
  }
}
