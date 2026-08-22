# ADR 0004: Service-owned PostgreSQL databases

- Status: Accepted
- Date: 2026-08-19
- Note: ADR 0007 selects Liquibase for Spring owners and `node-pg-migrate` for Node.js owners; database ownership decisions here remain accepted.

## Context

Schema-per-service inside one shared database leaves connection permissions and migration history coupled to that database. The project owner requires database-level isolation while retaining one PostgreSQL server for development and the initial deployment.

## Decision

Each business service owns a separate PostgreSQL database and database login. A shared PostgreSQL server or RDS instance is allowed, but services connect only to their own database and use its default `public` schema. Service migrations, Hibernate mappings, and Liquibase tracking tables never depend on a service-named schema in a shared database.

Database creation is an infrastructure or operator prerequisite performed through the managed database console, pgAdmin, or an approved provisioning tool. Liquibase connects directly to an existing service database and owns changes inside it; application changelogs do not create databases or require administrative-database credentials.

Identity owns `mentalbridge_identity`; its application and Liquibase migrations connect directly to that database.

## Consequences

- Database provisioning completes before the service's Liquibase migration step.
- Liquibase history exists only in the service-owned database.
- PostgreSQL extensions required by a service are installed in that service database.
- Cross-service foreign keys and queries are impossible by construction; integration continues through REST and Kafka contracts.
- A shared server remains economical for development while credentials, backups, restores, and future database moves can be managed per service.
