ALTER TABLE integrations
    DROP CONSTRAINT IF EXISTS integrations_installation_id_fkey;

ALTER TABLE github_installations
    DROP CONSTRAINT IF EXISTS github_installations_installer_user_id_fkey;

ALTER TABLE github_installations
    ADD CONSTRAINT fk_github_installations_installer_user_id
        FOREIGN KEY (installer_user_id)
        REFERENCES users (id)
        ON DELETE CASCADE;

ALTER TABLE integrations
    ADD CONSTRAINT fk_integrations_installation_id
        FOREIGN KEY (installation_id)
        REFERENCES github_installations (id)
        ON DELETE CASCADE;
