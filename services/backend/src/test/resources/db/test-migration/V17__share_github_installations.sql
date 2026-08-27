-- GitHub App 설치 접근권을 installer_user_id 단독 소유에서 조인 테이블 공유로 분리한다.
-- 백필이 없으면 배포 직후 기존 설치자 전원이 자기 설치에 대한 접근권을 잃는다
-- (인가 기준이 이 조인 테이블로 바뀌기 때문).
CREATE TABLE github_installation_users (
    installation_id UUID NOT NULL REFERENCES github_installations (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (installation_id, user_id)
);
CREATE INDEX idx_github_installation_users_user_id ON github_installation_users (user_id);

INSERT INTO github_installation_users (installation_id, user_id)
SELECT id, installer_user_id FROM github_installations;

-- installer_user_id는 이제 인가 기준이 아니라 최초 설치자 기록일 뿐이라 nullable로 바꾸고,
-- 설치자 탈퇴가 다른 멤버의 접근권까지 지우던 CASCADE를 SET NULL로 끊는다.
ALTER TABLE github_installations
    ALTER COLUMN installer_user_id DROP NOT NULL;

ALTER TABLE github_installations
    DROP CONSTRAINT fk_github_installations_installer_user_id;

ALTER TABLE github_installations
    ADD CONSTRAINT fk_github_installations_installer_user_id
        FOREIGN KEY (installer_user_id)
        REFERENCES users (id)
        ON DELETE SET NULL;
