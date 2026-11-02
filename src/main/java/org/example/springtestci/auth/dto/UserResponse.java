package org.example.springtestci.auth.dto;

import org.example.springtestci.auth.entity.User;
import org.example.springtestci.auth.entity.UserRole;

public record UserResponse(Long id, String email, String nickname, UserRole role) {

  public static UserResponse from(User user) {
    return new UserResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole());
  }
}
