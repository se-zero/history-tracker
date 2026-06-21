package com.history.backend.project.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ProjectController: 프로젝트 HTTP API")
class ProjectControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final Instant CREATED_AT = Instant.parse("2026-05-18T01:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-05-18T02:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUpAuthentication() {
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
    }

    @Test
    @DisplayName("프로젝트 생성 → 201 Created 반환")
    void createProjectReturnsCreatedProject() throws Exception {
        when(projectService.createProject(USER_ID, "History Tracker", "GraphRAG backend"))
                .thenReturn(project("History Tracker", "GraphRAG backend"));

        mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "History Tracker",
                                  "description": "GraphRAG backend"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.ownerId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.name").value("History Tracker"))
                .andExpect(jsonPath("$.description").value("GraphRAG backend"))
                .andExpect(jsonPath("$.createdAt").value("2026-05-18T01:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-05-18T02:00:00Z"));
    }

    @Test
    @DisplayName("프로젝트 목록 조회 → 현재 사용자 프로젝트 반환")
    void listProjectsReturnsCurrentUserProjects() throws Exception {
        when(projectService.findProjects(USER_ID))
                .thenReturn(List.of(project("History Tracker", "GraphRAG backend")));

        mockMvc.perform(get("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("History Tracker"));
    }

    @Test
    @DisplayName("프로젝트 단건 조회 → 200 OK 반환")
    void getProjectReturnsProject() throws Exception {
        when(projectService.getProject(USER_ID, PROJECT_ID))
                .thenReturn(project("History Tracker", null));

        mockMvc.perform(get("/api/v1/projects/{projectId}", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.name").value("History Tracker"));
    }

    @Test
    @DisplayName("프로젝트 수정 → 수정된 프로젝트 반환")
    void updateProjectReturnsUpdatedProject() throws Exception {
        when(projectService.updateProject(USER_ID, PROJECT_ID, "History Tracker API", "Backend API"))
                .thenReturn(project("History Tracker API", "Backend API"));

        mockMvc.perform(put("/api/v1/projects/{projectId}", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "History Tracker API",
                                  "description": "Backend API"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("History Tracker API"))
                .andExpect(jsonPath("$.description").value("Backend API"));
    }

    @Test
    @DisplayName("프로젝트 삭제 → 204 No Content 반환")
    void deleteProjectReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/{projectId}", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNoContent());

        verify(projectService).deleteProject(USER_ID, PROJECT_ID);
    }

    @Test
    @DisplayName("액세스 토큰 없으면 401 Unauthorized 반환")
    void rejectMissingAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    @Test
    @DisplayName("존재하지 않는 프로젝트 조회 → 404 Not Found 반환")
    void getProjectReturnsNotFoundWhenProjectDoesNotExist() throws Exception {
        when(projectService.getProject(USER_ID, PROJECT_ID))
                .thenThrow(new NotFoundException("Project not found."));

        mockMvc.perform(get("/api/v1/projects/{projectId}", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Project not found."));
    }

    @Test
    @DisplayName("소유자가 다른 프로젝트 수정 → 403 Forbidden 반환")
    void updateProjectReturnsForbiddenWhenOwnerDoesNotMatch() throws Exception {
        when(projectService.updateProject(USER_ID, PROJECT_ID, "History Tracker API", null))
                .thenThrow(new ForbiddenException("Project access denied."));

        mockMvc.perform(put("/api/v1/projects/{projectId}", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"History Tracker API"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Project access denied."));
    }

    @Test
    @DisplayName("빈 이름으로 프로젝트 수정 → 400 Bad Request 반환")
    void updateProjectRejectsBlankName() throws Exception {
        mockMvc.perform(put("/api/v1/projects/{projectId}", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "description": "Backend API"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.fields[0].field").value("name"));
    }

    @Test
    @DisplayName("중복된 이름으로 프로젝트 수정 → 409 Conflict 반환")
    void updateProjectReturnsConflictWhenNameAlreadyExists() throws Exception {
        when(projectService.updateProject(USER_ID, PROJECT_ID, "History Tracker API", null))
                .thenThrow(new ConflictException("Project name already exists."));

        mockMvc.perform(put("/api/v1/projects/{projectId}", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"History Tracker API"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Project name already exists."));
    }

    @Test
    @DisplayName("빈 이름으로 프로젝트 생성 → 400 Bad Request 반환")
    void createProjectRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "description": "GraphRAG backend"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.fields[0].field").value("name"));
    }

    @Test
    @DisplayName("중복된 이름으로 프로젝트 생성 → 409 Conflict 반환")
    void createProjectReturnsConflictWhenNameAlreadyExists() throws Exception {
        when(projectService.createProject(USER_ID, "History Tracker", null))
                .thenThrow(new ConflictException("Project name already exists."));

        mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"History Tracker"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Project name already exists."));
    }

    private Project project(String name, String description) {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", USER_ID);

        Project project = new Project(owner, name, description);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        ReflectionTestUtils.setField(project, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(project, "updatedAt", UPDATED_AT);
        return project;
    }
}
