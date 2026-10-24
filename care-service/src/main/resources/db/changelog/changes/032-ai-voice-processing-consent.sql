--liquibase formatted sql

--changeset mentalbridge:care-033-ai-voice-processing-consent runInTransaction:true
ALTER TABLE consent_decision DROP CONSTRAINT ck_consent_decision_type;
ALTER TABLE consent_decision ADD CONSTRAINT ck_consent_decision_type CHECK (consent_type IN (
    'PRIVACY_POLICY', 'AI_PROCESSING', 'AI_VOICE_PROCESSING', 'RESEARCH_DATA', 'MARKETING_NOTIFICATION'
));
