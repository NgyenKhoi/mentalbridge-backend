# Environment Configuration

## Shared rules

- Configuration keys are explicit, typed, validated at startup, and documented in `.env.example` without real secrets.
- Commit `.env.example`; never commit `.env`, `.env.*` secret variants, credentials, tokens, private keys, provider payloads, or production connection strings.
- Real process/container environment variables override local `.env` values. CI and production inject configuration through their environment/secret mechanism and do not depend on a repository `.env` file.
- Required secrets have no insecure default. Safe local defaults are allowed only for non-sensitive developer infrastructure and must not make production silently unsafe.
- Do not log configuration maps or resolved secrets. Redact sensitive values in startup errors, actuator exposure, test output, and issue/PR evidence.

## Spring Boot 4 dotenv baseline

All Spring Boot services use the Spring Boot 4 integration from `me.paulschwarz` so a local `.env` becomes a Spring `PropertySource` without custom bootstrap code. The approved baseline is `springboot4-dotenv` 5.1.0, managed through its BOM. Keep the version in one parent Maven property/dependency-management entry and update it only after compatibility and build tests.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>me.paulschwarz</groupId>
            <artifactId>spring-dotenv-bom</artifactId>
            <version>${spring-dotenv.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>me.paulschwarz</groupId>
    <artifactId>springboot4-dotenv</artifactId>
    <optional>true</optional>
</dependency>
```

The library is a local-development convenience. Standard Spring precedence remains authoritative: real environment variables win over `.env`. Keep `springdotenv.ignoreIfMissing=true`, keep `springdotenv.exportToSystemProperties=false`, and explicitly set `SPRINGDOTENV_ENABLED=false` in CI/production.

Once service scaffolding introduces the first environment-backed key, the canonical capstone workflow runs Maven from the repository root, where the shared local `.env` and committed `.env.example` live. Names must be service-scoped when they are not truly shared, for example `IDENTITY_DB_URL`, `CARE_DB_URL`, and `CONSULTATION_DB_URL`. Module READMEs document the variables they consume; no module silently reuses another module's credential.

`application.yml` maps environment names into typed application properties:

```yaml
mentalbridge:
  identity:
    datasource-url: ${IDENTITY_DB_URL}
    jwt-private-key: ${IDENTITY_JWT_PRIVATE_KEY}
```

Bind related settings through validated `@ConfigurationProperties`; do not scatter string-based `@Value` lookups or direct `System.getenv` calls through business code.

## Maven and test behavior

`mvn clean install` does not itself execute the application, but any Spring context started by Maven tests automatically receives the local `.env` property source. The build must still pass on a clean machine with no `.env`:

- unit tests do not require environment configuration;
- repository/integration tests use Testcontainers and explicit test properties or `@DynamicPropertySource`;
- external provider adapters use synthetic fixtures/fakes;
- test-owned properties override developer `.env` values;
- tests verify startup fails with a clear safe message when a genuinely required runtime property is absent.

This prevents “works only with my `.env`” builds while preserving automatic local loading. CI sets `SPRINGDOTENV_ENABLED=false` and supplies only the variables required by the tested profile.

## `.env.example` review

Every configuration change updates `.env.example` and the affected module README in the same PR. Group keys by service and explain purpose, format, whether required, and safe example—not the secret value. Reviewers compare application configuration references with `.env.example` to detect missing or stale keys.

## Development-first, production-ready configuration

The current infrastructure phase may run databases and supporting dependencies locally, but service scaffolding must define production-safe configuration at the same time. Keep behavior in typed configuration and profiles rather than production-only code branches.

- Development may use safe local hosts, ports, synthetic assets, provider sandboxes/fakes, and Docker Compose credentials that are clearly non-production.
- CI and production disable dotenv loading and receive Eureka endpoints plus database, Kafka, Redis, Cloudinary, Brevo, signing, and encryption configuration from the deployment environment or secret manager.
- Production configuration supports TLS, bounded connection pools, connection/request timeouts, graceful shutdown, health/readiness probes, metrics, and redacted structured logging.
- Schema auto-creation is disabled. Liquibase owns PostgreSQL schema changes and `migrate-mongo` owns MongoDB schema/index/data migrations. Production migration execution is an explicit deployment step or a deliberately enabled single-runner job, never an uncontrolled race between application replicas.
- Missing production secrets, insecure provider modes, public access for sensitive Cloudinary assets, or placeholder endpoints fail startup. No production profile falls back to a development credential or localhost.
- External provider adapters have typed timeouts, bounded retry/circuit-breaker behavior, and sandbox/fake implementations for tests. Paid Cloudinary or Brevo APIs are not required for ordinary CI.

## Node.js status

No repository-owner preference for a NestJS `.env` library has been selected yet. Do not standardize or add a Node-specific package merely by analogy with Spring. Choose it when the first NestJS module is scaffolded, document the decision, and retain the shared secret/testing rules above.

## Dependency references

- [Spring-Dotenv project documentation](https://github.com/paulschwarz/spring-dotenv)
- [Spring Boot 4 artifact on Maven Central](https://central.sonatype.com/artifact/me.paulschwarz/springboot4-dotenv/5.1.0)
