# PostgreSQL logical models by service

These SQL files are read-only documentation for ERDs and domain understanding.
Never execute them to provision, initialize, migrate, validate or repair a
database. Each service's migration history remains executable runtime truth.
Owner qualifiers are visual namespaces; runtime tables use the owning database's
`public` schema. Cross-service references are logical identifiers, never physical
foreign keys.

| Owning service | Canonical logical SQL | Field dictionary | Executable migration history |
| --- | --- | --- | --- |
| Identity | [identity-service.sql](identity-service.sql) | [Identity fields](../../database/postgresql/identity-service.md) | [Liquibase](../../../identity-service/src/main/resources/db/changelog/) |
| Care | [care-service.sql](care-service.sql) | [Care fields](../../database/postgresql/care-service.md) | [Liquibase](../../../care-service/src/main/resources/db/changelog/) |
| Consultation | [consultation-service.sql](consultation-service.sql) | [Consultation fields](../../database/postgresql/consultation-service.md) | [Liquibase](../../../consultation-service/src/main/resources/db/changelog/) |
| Community | [community-service.sql](community-service.sql) | [Community fields](../../database/postgresql/community-service.md) | [Liquibase](../../../community-service/src/main/resources/db/changelog/) |
| Content/Notification | [content-notification-service.sql](content-notification-service.sql) | [Content/Notification fields](../../database/postgresql/content-notification-service.md) | [SQL migrations](../../../content-notification-service/migrations/) |

The [original SQL path](postgresql-logical-schema.sql) remains a comment-only
compatibility index and retains the proposed/historical entity guidance. It has
no executable include directives. Journal/AI and Realtime document their
MongoDB-owned aggregates in the [document model](../document/mongodb-logical-model.md).

For a material persistence change, edit the owning service's SQL and field
dictionary alongside its migration. Do not edit a shared index just to satisfy
the paired-change check. The [canonical entity index](../canonical-entities.md)
continues to distinguish active, proposed and historical entities.
