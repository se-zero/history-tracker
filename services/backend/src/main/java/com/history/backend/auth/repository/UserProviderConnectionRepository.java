package com.history.backend.auth.repository;

import java.util.UUID;

import com.history.backend.auth.domain.UserProviderConnection;
import com.history.backend.auth.domain.UserProviderConnectionId;
import com.history.backend.integration.domain.IntegrationProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserProviderConnectionRepository
        extends JpaRepository<UserProviderConnection, UserProviderConnectionId> {

    // @EmbeddedId 내부 속성은 파생 쿼리(UserIdAndProvider)가 최상위 프로퍼티로 인식하지 못해
    // (Checkpoint의 deleteByProject_IdAndId_Provider처럼 밑줄 경로가 필요) 명시 JPQL로 작성한다.
    @Query("""
            SELECT COUNT(connection) > 0
            FROM UserProviderConnection connection
            WHERE connection.id.userId = :userId AND connection.id.provider = :provider
            """)
    boolean existsByUserIdAndProvider(
            @Param("userId") UUID userId,
            @Param("provider") IntegrationProvider provider
    );
}
