--liquibase formatted sql

--changeset mentalbridge:consultation-022-durable-checkout-preparation
alter table payment_transaction alter column checkout_url drop not null;

alter table payment_transaction add constraint ck_payment_checkout_pair
    check (checkout_url is not null or qr_code_url is null);
