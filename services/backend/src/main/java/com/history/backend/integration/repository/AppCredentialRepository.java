package com.history.backend.integration.repository;

import com.history.backend.integration.domain.AppCredential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppCredentialRepository extends JpaRepository<AppCredential, String> {
}
