--liquibase formatted sql

--changeset mentalbridge:care-007-profile-preference-defaults runInTransaction:true
ALTER TABLE user_profile
    ALTER COLUMN locale SET DEFAULT 'vi-VN',
    ALTER COLUMN timezone SET DEFAULT 'Asia/Ho_Chi_Minh',
    ALTER COLUMN reminder_enabled SET DEFAULT false;

UPDATE user_profile
SET locale = 'vi-VN',
    timezone = 'Asia/Ho_Chi_Minh',
    reminder_enabled = false
WHERE locale <> 'vi-VN'
   OR timezone <> 'Asia/Ho_Chi_Minh'
   OR reminder_enabled;

--rollback ALTER TABLE user_profile ALTER COLUMN reminder_enabled SET DEFAULT true;
