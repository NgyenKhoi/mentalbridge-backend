--liquibase formatted sql

--changeset mentalbridge:consultation-20261030110000-subscription-upgrade-credit-retry
alter table subscription_upgrade_credit
    drop constraint subscription_upgrade_credit_credit_id_key;
