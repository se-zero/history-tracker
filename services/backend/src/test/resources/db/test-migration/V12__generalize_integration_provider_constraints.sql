-- provider 열거형 CHECK 제거 — 새 연동을 붙일 때마다 마이그레이션을 강제하던 제약이다.
-- 유효한 provider 값은 애플리케이션(IntegrationProvider enum + IntegrationProviderConverter)이 보증한다.
ALTER TABLE integrations DROP CONSTRAINT chk_integrations_provider;

-- 자격증명 형태 제약을 provider 목록이 아니라 "installation 기반인가"로 재정의한다.
-- GitHub만 App installation 토큰을 쓰고(토큰은 github_installations에 캐시), 나머지 OAuth provider는
-- 자기 행에 암호화된 자격증명을 갖는다. 이 규칙은 provider가 늘어도 그대로 성립한다.
ALTER TABLE integrations DROP CONSTRAINT chk_integrations_provider_credentials;

ALTER TABLE integrations ADD CONSTRAINT chk_integrations_provider_credentials
    CHECK (
        (
            provider = 'github'
            AND installation_id IS NOT NULL
            AND encrypted_credential IS NULL
        )
        OR
        (
            provider <> 'github'
            AND installation_id IS NULL
            AND encrypted_credential IS NOT NULL
        )
    );
