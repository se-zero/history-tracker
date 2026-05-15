package com.history.backend.auth.repository;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByProviderAndProviderUserIdAndDeletedAtIsNull(String provider, String providerUserId);

    Optional<User> findFirstByProviderAndProviderUserIdOrderByCreatedAtDesc(String provider, String providerUserId);
}
