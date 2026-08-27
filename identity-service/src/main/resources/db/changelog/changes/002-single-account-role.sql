--liquibase formatted sql

--changeset mentalbridge:identity-007-single-account-role runInTransaction:true
ALTER TABLE account ADD COLUMN role_code varchar(32);

UPDATE account AS target
SET role_code = (
    SELECT assignment.role_code
    FROM account_role AS assignment
    WHERE assignment.account_id = target.id
);

ALTER TABLE account
    ALTER COLUMN role_code SET NOT NULL,
    ADD CONSTRAINT fk_account_role_code FOREIGN KEY (role_code) REFERENCES role(code);

CREATE UNIQUE INDEX ux_account_single_admin
    ON account (role_code)
    WHERE role_code = 'ADMIN';

DROP TABLE account_role;
