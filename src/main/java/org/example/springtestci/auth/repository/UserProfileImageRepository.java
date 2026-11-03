package org.example.springtestci.auth.repository;

import org.example.springtestci.auth.entity.UserProfileImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileImageRepository extends JpaRepository<UserProfileImage, Long> {}
