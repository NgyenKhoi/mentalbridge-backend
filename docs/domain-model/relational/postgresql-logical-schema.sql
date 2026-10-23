/*
 * MENTALBRIDGE CANONICAL LOGICAL DATA MODEL
 *
 * Purpose:
 * - Canonical relational reference for architecture documentation.
 * - ERD and domain modelling.
 * - Cross-service entity and relationship understanding.
 *
 * THIS FILE IS READ-ONLY AND NON-EXECUTABLE.
 *
 * Runtime database source of truth:
 * - Each service's owner-specific migration history.
 *
 * This file must never be used to provision, initialize,
 * migrate, or modify a runtime database.
 *
 * Any persisted model change introduced by a service migration
 * must update this logical model in the same pull request.
 *
 * The identity, care, consultation, community, and content qualifiers below are visual
 * owner namespaces only. Runtime PostgreSQL uses a separate owner database and
 * its default public schema. Cross-owner identifiers are logical/external
 * references and are deliberately not physical foreign keys.
 *
 * Reconciled from owner migrations through 2026-09-29. It includes the Care
 * SupportPlan, immutable Reassessment Summary, and Community feed migrations. Technical indexes and migration bookkeeping are
 * intentionally omitted; owner migrations remain authoritative for exact DDL.
 */

/*
 * SERVICE MODEL INDEX — MB-718
 *
 * This path remains the compatibility entry point. Read the owner files below
 * for canonical entity definitions; docs/domain-model/relational/README.md
 * links each model, field dictionary and executable owner migration history.
 * "Update this logical model" in the retained header means the matching owner
 * file, not this compatibility index. Feature schema edits must not add DDL here.
 *
 * - identity-service.sql
 * - care-service.sql
 * - consultation-service.sql
 * - community-service.sql
 * - content-notification-service.sql
 *
 * Every listed SQL file is read-only documentation. There are no executable
 * include directives here. Never pass this index or its owner files to a SQL
 * client, migration runner, startup hook or provisioning tool.
 */

/*
 * PROPOSED AND HISTORICAL RELATIONAL ENTITIES
 *
 * They are intentionally not expressed as CREATE TABLE statements here.
 * See ../canonical-entities.md for status and evidence. This prevents an
 * approved concept, backlog item, or removed table from appearing to be active
 * runtime persistence. MongoDB-owned aggregates are documented separately in
 * ../document/mongodb-logical-model.md and are never represented as SQL tables.
 */
