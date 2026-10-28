package org.example.springtestci.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  private final RateLimitInterceptor rateLimitInterceptor;

  public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor) {
    this.rateLimitInterceptor = rateLimitInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(rateLimitInterceptor)
        .addPathPatterns(
            "/api/v1/**", "/api/auth/signup", "/api/auth/refresh", "/api/auth/email/resend");
  }
}
