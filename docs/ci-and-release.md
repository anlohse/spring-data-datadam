# CI and release preparation — 8.2

The release destination is **Maven Central via the Central Portal**. The project prepares a verifiable release candidate. Publication is a separate step. These scripts and workflows do not upload artifacts, create tags or GitHub releases, or publish automatically.

Since November 9, 2026, the Maven groupId and Java packages use **com.anlohse.datadam** under the **com.anlohse** namespace. Update dependencies, imports, and entry points previously using `io.datadam`. Rebuild older candidates to include the new classes, resources, and Boot registrations, even if their groupId is already current. The bundle builder also verifies the groupId in the manifest and the Maven coordinates in the POMs.

## CI

`.github/workflows/ci.yml` runs on pushes to main, pull requests, and manual dispatch. It uses Ubuntu 24.04, PowerShell 7, Java 25, actions pinned by commit SHA, and read-only permissions. The CI and release candidate workflows use the official Datadam 0.2.0 image. The runner pulls the image by digest when needed and checks the OCI revision label before running the tests. [Datadam 0.2.0 release](https://github.com/anlohse/datadam/releases/tag/v0.2.0).

`scripts/run-ci.ps1` performs the same steps locally:

```powershell
.\scripts\run-ci.ps1

# To reconstruct locally the revision fixed from C++:
.\scripts\run-ci.ps1 -BuildDatadam -Source D:/cpp/workspaces/datadam
```

The runner starts a disposable server with ACL of the demo in ephemeral/loopback port, executes `mvn install -Pintegration`, checks the artifacts and packages/executes the independent consumer. The suite includes Java 17/21/25, TLS/ACL, restarts, loss of response and recovery of backfill. Failure tests create their own containers. At the end, it removes only the resources that created and restore the environment. It does not alter the local server of the demo or cleans its volume. The Maven execution can overwrite the target JARs: use another checkout if the demo is running from them.

Workflow provides JUnit reports, matrix logs, and manifests as 14-day validation artifacts. Runner reuses the downloaded image between the suite server, disposable containers, and the consumer. Inspection credentials TLS are configured only on Windows. Linux uses its normal truststore.

## Prepare a release candidate

```powershell
.\scripts\prepare-release.ps1 -Version 0.1.0-rc.1
# With real key configured outside the repository, for a candidate intended for publication:
.\scripts\prepare-release.ps1 -Version 0.1.0-rc.1 -Sign
```

The script copies the source to `target/releases/<version>-<UUID>/source`, changes the POM version and SCM tag only in that copy, installs the parent and three library artifacts, and runs a smoke test against those JARs. The development checkout keeps its version. Unit tests run again for the candidate version. The full CI suite must pass on the same code before the release is approved.

It is allowed to prepare a local preview with changes that have not yet been committed; `manifest.json` records `dirtySource=true` and the commit of origin. This preview is not a public release. Committing the changes, reviewing and preparing a clean checkout again are mandatory steps of the checklist. The bundle should not contain the demo or the consumer, only the four POMs and the nine JARs library/sources/documentation.

Outputs:

- `bundle/`: POMs/JARs and signatures when `-Sign` was used.
- `manifest.json`: version, commit, checkout status, destination, hashes SHA-256 and `published=false`.
- `consumer-verification.json`: startup, loaded version, health by PING and business operations.
- `central-repository/` and `central-bundle.zip`: layout `com/anlohse/datadam/<artifact>/<version>`, with checksums MD5/SHA1/SHA256/SHA512 for each POM/JAR and payload signatures when signed.
- `central-bundle.json`: hash of ZIP, payload count and `portalValidation=NOT_RUN`/`submitted=false`.

Use `new-central-bundle.ps1 -Manifest <file> -RequireSignatures` to build another bundle from a signed candidate at a new output path. The script verifies the payload and signature hashes before assembly. The hashes detect accidental changes and provide no proof of the signer's identity. The `signed=true` field records that the signing profile completed. The Central Portal runs its own checks. Local validation also verified the signatures cryptographically with a disposable key.

The starter does not expose classes of API, but provides a `-javadoc.jar` with HTML explaining the dependencies and pointing to the documentation of the core/auto-configuration. This file meets the format required for packaging artifacts JAR. Check the [Central requirements](https://central.sonatype.org/publish/requirements/) and [layout for uploading bundles](https://central.sonatype.org/publish/publish-portal-upload/).

## Smoke test — example 8.2.1

```powershell
# After installing/preparing the candidate version:
.\scripts\smoke-release.ps1 -Version 0.1.0-rc.1
```

The consumer outside the reactor compiles with the selected version and checks, in runtime, `pom.properties` of the JARs core/autoconfigure/starter. Divergent versions fail before starting the business flow. The process starts the Spring context, does a health PING by the pool and performs registration/reading, pagination IDX, FT projection, UNIQUE and version control, with own data and cleaning by known IDs.

Expected exit: `CONSUMER_VERSION 0.1.0-rc.1`, `CONSUMER_HEALTH_OK ping=PONG`, `CONSUMER_OK ...` and `RELEASE_SMOKE_OK ...`. This health is a verification CLI. There is no endpoint HTTP/Actuator in the consumer. The schema password is removed before the application process. Signature credentials and Central tokens are also removed from the environment from the consumer validation processes and restored at completion.

## Candidate workflow and secrets

`.github/workflows/release-candidate.yml` runs only when manually dispatched on main. It runs the full CI suite, prepares the requested version, and produces the bundle for review. `sign=false` is the default. For `sign=true`, configure the **release-preparation** environment with required reviewers and restrict access to main before first use. The maintainer sets up the environment in the repository settings.

Use the secrets `MAVEN_GPG_KEY` (openPGP private key exported) and `MAVEN_GPG_PASSPHRASE` in that environment. Profile `release-sign` uses Maven GPG Plugin 3.2.8, sign BC and `bestPractices=true`. Does not depend on a GnuPG executable on the runner and does not receive password by argument/POM. The key is only available at the signature step. See [Official signature documentation](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html).

Never commit keys, passphrases, local settings, or tokens. The `config/maven-settings.xml.example` template references `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` for Maven tools that require a Portal token. It contains no credentials. The current scripts do not read it because publication uses a manual ZIP upload in the Portal. Use a Central Portal token for Central authentication. [Publication token documentation](https://central.sonatype.org/publish/generate-portal-token/).

## Release checklist

### Before approval

- [ ] Check the namespace **com.anlohse** in the Central Portal account/organization using the anlohse.com domain. The project groupId is **com.anlohse.datadam**. Ownership of the domain still needs to be verified on the Portal.
- [ ] Configure the environment reviewers and the actual signature key. Publish the public key in the keyserver accepted by the Central. The disposable key of this validation is not for release.
- [ ] Commit/review delivery, with messages/PR in English. Confirm clean checkout and IC approved for exact commit.
- [ ] Choose unpublished version. Review `CHANGELOG.md`, compatibility policy and update guide.
- [ ] Prepare again with `-Sign`. Check nine JARs/four POMs, 13 signatures, checksums and smoke test version.
- [ ] Verify `sourceCommit`, `dirtySource=false`, the version, the SCM tag, and the ZIP's SHA-256 hash. Create the reviewed tag `v<version>` pointing to this commit according to the maintainer's approved process.
- [ ] Obtain explicit authorisation for the whole **version + commit/tag + bundle hash + Maven Central**.

### Publication — separate and pending

- [ ] Only after authorization, enter the [Central Portal](https://central.sonatype.com) and send the revised signed ZIP. Upload already sends the artifacts to an external service. It is not executed by the runners of this project.
- [ ] Checking the validations of the Portal. Being in VALIDATED does not amount to being published. Review before the Publish action.
- [ ] Publish explicitly in the Portal, wait for confirmation, and verify all four Maven coordinates and their classifiers.
- [ ] Run the standalone consumer with a clean local Maven repository so it resolves the version from Central. Record the links and results and update the changelog and checklist.

Publications are immutable: you should not fix a version already published overwriting your files. The usage playback after uploading and the Portal approval were not performed in this delivery. Workflow files still need to be committed/sent so they can run on GitHub. The validation recorded here is the local playback of the runner and the static analysis of the YAMLs.

## Upgrade applications

Change the old Boot dependency for `com.anlohse.datadam:datadam-spring-boot-starter` and keep the same version in the three artifacts. Do not mix the core of SNAPSHOT above with the new auto-configuration. For local upgrade, install the artifacts before building the consumer. For published version, remove waste from the candidate version of the validation cache and confirm its origin in the Central.

Read [changelog](../CHANGELOG.md), [policy of API](public-api-and-compatibility.md) and transport limits, TLS, TTL and scripts before updating. There is no C++ change at this stage. The required native revision remains fixed in the image contract. A local candidate version does not change the SNAPSHOT version of the development nor constitutes an announced release.

## Validation evidence

In November 8, 2026, the runner was played on Windows in an isolated copy: 395 approved tests and 564 unit reruns in the Docker matrix. The YAMLs passed in the actionlint and the scripts in the PowerShell parser. The local candidate 0.1.0-rc.1 had 13 cryptographically verified signatures with disposable key (delete after the test). The ZIP contains 13 payloads, 13 signatures and 52 checksums. The refusals of divergent version, altered hash, absent signature and SNAPSHOT passed. [Evidence and hashes](validation/2026-11-08-ci-release.json).

This bundle has changes not yet committed and was signed with test key, so it should not be published. No upload to the Central, external namespace verification, host execution of the workflow or publication was performed. The pending stage is explicitly open on the roadmap.
