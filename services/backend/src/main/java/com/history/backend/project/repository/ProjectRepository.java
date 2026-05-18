package com.history.backend.project.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.project.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByOwner_IdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID ownerId);

    Optional<Project> findByIdAndDeletedAtIsNull(UUID id);

    @Query("""
            SELECT COUNT(project) > 0
            FROM Project project
            WHERE project.owner.id = :ownerId
              AND LOWER(project.name) = LOWER(:name)
              AND project.deletedAt IS NULL
            """)
    boolean existsActiveByOwnerIdAndNameIgnoreCase(
            @Param("ownerId") UUID ownerId,
            @Param("name") String name
    );

    @Query("""
            SELECT COUNT(project) > 0
            FROM Project project
            WHERE project.owner.id = :ownerId
              AND LOWER(project.name) = LOWER(:name)
              AND project.deletedAt IS NULL
              AND project.id <> :projectId
            """)
    boolean existsActiveByOwnerIdAndNameIgnoreCaseExcludingId(
            @Param("ownerId") UUID ownerId,
            @Param("name") String name,
            @Param("projectId") UUID projectId
    );
}
