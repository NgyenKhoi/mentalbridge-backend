--liquibase formatted sql

--changeset mentalbridge:identity-011-staging-admin-seed context:seed runInTransaction:true
INSERT INTO account (email, password_hash, role_code, status, email_verified_at)
SELECT 'admin@mentalbridge.online', '$2a$12$fHs05rbKpwj9BU1iWZ6.ZudQHS.UbJPIy9ZVME4mUauGfIYUZX.G2', 'ADMIN', 'ACTIVE', now()
WHERE NOT EXISTS (SELECT 1 FROM account WHERE role_code = 'ADMIN');
