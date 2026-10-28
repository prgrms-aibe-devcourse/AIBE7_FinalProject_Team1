package org.example.springtestci.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
    @NotBlank @Size(min = 32) String secret,
    @DefaultValue("15m") Duration accessTokenTtl,
    @DefaultValue("14d") Duration refreshTokenTtl,
    /**
     * Access·Refresh 인증 쿠키 둘 다에 적용하는 Secure 설정. 이름은 기존 설정 키(refresh-cookie-secure)와 맞추려고 그대로 둔다.
     */
    @DefaultValue("true") boolean refreshCookieSecure) {}
