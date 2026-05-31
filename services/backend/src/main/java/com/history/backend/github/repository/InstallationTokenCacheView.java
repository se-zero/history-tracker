package com.history.backend.github.repository;

import java.time.Instant;

public interface InstallationTokenCacheView {

    byte[] getEncryptedInstallationToken();

    Instant getInstallationTokenExpiresAt();
}
