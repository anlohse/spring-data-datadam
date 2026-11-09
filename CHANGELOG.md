# Changelog

All notable changes are recorded here. The library is still pre-release; no version below has been published to Maven Central yet.

## Unreleased

### Added
- Separate Spring Data core, Spring Boot autoconfiguration, and Boot starter artifacts.
- Native JSON/HASH repositories, IDX/FT queries and projections, entity lifecycle support, and explicit index administration.
- Executable roadmap examples, an independent Boot consumer, and pinned Datadam/Java compatibility tests.
- GitHub Actions CI for unit/integration tests, TLS/ACL/transport failures, and Java 17/21/25 compatibility.
- Local release-candidate preparation, signed Central-layout bundles, checksums, and version-aware consumer smoke tests.
- Manual candidate workflow with optional signing and reviewable artifacts; no automatic Central upload or publication.

### Changed
- Maven coordinates and Java packages moved from `io.datadam` to `com.anlohse.datadam` on 2026-11-09. Artifact IDs remain unchanged. Consumers must update dependency group IDs, imports, and configured class names.
- Boot applications must use `com.anlohse.datadam:datadam-spring-boot-starter` to retain automatic configuration. The core artifact no longer includes Boot classes.
- The dependency-only starter now includes a documentation JAR for Maven Central requirements.

### Compatibility
- Current development version: `0.1.0-SNAPSHOT`; validation candidate: `0.1.0-rc.1` (unpublished).
- Java 17/21/25, Boot 3.5.16, and Spring Data Commons 3.5.13 are validated against the Datadam revision in `config/test-images.properties`.
- Direct Redis compatibility, Spring transactions, and reactive repositories are not promised. Existing uncertain-write, TTL durability, and multi-command atomicity limits remain unchanged.

Release dates, immutable tags, and Maven Central links will be added only after an authorized publication succeeds.
