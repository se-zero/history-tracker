package com.history.backend.project.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.project.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByOwner_IdOrderByCreatedAtDesc(UUID ownerId);

    // owner를 fetch join — 트랜잭션 밖(open-in-view=false)에서 소유권을 검증하는 deleteProject용.
    // lazy owner가 detached 프록시로 남아 non-ID 필드 접근 시 터지는 LazyInitializationException 차단.
    @Query("SELECT p FROM Project p JOIN FETCH p.owner WHERE p.id = :id")
    Optional<Project> findByIdWithOwner(@Param("id") UUID id);

    @Query("""
            SELECT COUNT(project) > 0
            FROM Project project
            WHERE project.owner.id = :ownerId
              AND LOWER(project.name) = LOWER(:name)
            """)
    boolean existsByOwnerIdAndNameIgnoreCase(
            @Param("ownerId") UUID ownerId,
            @Param("name") String name
    );

    @Query("""
            SELECT COUNT(project) > 0
            FROM Project project
            WHERE project.owner.id = :ownerId
              AND LOWER(project.name) = LOWER(:name)
              AND project.id <> :projectId
            """)
    boolean existsByOwnerIdAndNameIgnoreCaseExcludingId(
            @Param("ownerId") UUID ownerId,
            @Param("name") String name,
            @Param("projectId") UUID projectId
    );
}

