package org.example.springtestci.auth.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.springtestci.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  boolean existsByNicknameIgnoreCase(String nickname);

  List<User> findByEmailVerifiedFalseAndCreatedAtBefore(Instant cutoff);
}
