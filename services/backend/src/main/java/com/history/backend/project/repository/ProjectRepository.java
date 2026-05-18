package com.history.backend.project.repository;

import java.util.List;
import java.util.UUID;

import com.history.backend.project.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByOwner_IdOrderByCreatedAtDesc(UUID ownerId);

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

