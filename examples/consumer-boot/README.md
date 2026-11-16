# Standalone Spring Boot consumer

This standalone project resolves `com.anlohse.datadam:datadam-spring-boot-starter` from the local Maven repository. It uses Spring Boot as its parent with `<relativePath/>`. It builds outside the Datadam reactor and has no dependency on the Datadam parent or demo classes. After installing the artifacts, you can copy this directory elsewhere and run the same build.

In previous versions of this checkout, groupId and Java packages used `io.datadam`. Now dependency uses `com.anlohse.datadam` and imports use `com.anlohse.datadam.spring.*`. The smoke test reads the Maven metadata of the new groupId in the three JARs and starts auto-configuration with the new class names.

## Automated verification

In the root of the main repository:

```powershell
.\scripts\verify-consumer.ps1 -Install
```

`-Install` compiles/tests/installs the core, auto-configuration and starter. In another named Maven, the script builds this POM without `-am`, resolves the installed JARs and executes the JAR packed against a disposable Datadam. The script checks the image revision, uses your ID and ephemeral port restricted to loopback. Removes only your own container and restores the environment variables at the end.

`-Install` compiles, tests, and installs core, auto-configuration, and the starter. A separate Maven invocation builds the consumer POM without `-am`, resolves the installed JARs, and runs the packaged JAR against a disposable Datadam instance. The script checks the server image revision, runs it by image ID, and binds an ephemeral port on loopback. It removes its own container and restores the environment variables on exit.

The flow uses own IDs/SKUs and verifies CRUD, SKU UNIQUE, derived query IDX by author/page range with ordination and total, FT projection TAG/NUMERIC, versioned edition and obsolete version refusal. It confers the actual version in the three JARs and performs health CLI by PING. Clears only the three fixture IDs known prior to shipment. Record result/image/version in `target/verification.json`. The dependency tree is in `target/datadam-dependencies.txt`.

Without `-Install`, the script only validates the consumer. `-LibraryVersion` selects the previously installed version. It does not publish artifacts.

To validate an already installed candidate release, use `scripts/smoke-release.ps1 -Version 0.1.0-rc.1` on the main root. The result includes `CONSUMER_VERSION`, `CONSUMER_HEALTH_OK` and `RELEASE_SMOKE_OK`. Signature keys, Central tokens and schema password are removed from the application process environment. The wrapper restores previous values after execution. See [release flow](../../docs/ci-and-release.md).

## Run without the wrapper

First, in the main root, install the artifacts:

```powershell
mvn -B -ntp -pl datadam-spring-boot-starter -am install
```

Then, in this folder, as in a separate project:

```powershell
mvn -B -ntp -Ddatadam.version=0.1.0-SNAPSHOT clean package
docker compose up -d --no-build
$env:DATADAM_HOST = '127.0.0.1'
$env:DATADAM_PORT = '6381'
$env:CONSUMER_SCHEMA_PASSWORD = 'local-consumer-schema-password'
$jar = (Resolve-Path .\target\consumer-boot-1.0.0-SNAPSHOT.jar).Path
java '-Dloader.main=example.datadam.SchemaApplication' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher
$env:CONSUMER_SCHEMA_PASSWORD = $null
$env:CONSUMER_APP_PASSWORD = 'local-consumer-app-password'
java -jar $jar
docker compose down
```

The Compose of this consumer uses the port 6381, own users, prefix `consumer:book:` and volume `datadam-consumer_consumer-data`. `down` preserves the volume. The credentials in the Docker file are of local development. The Compose lowers the official image Datadam 0.2.0 by the digest when necessary. The automated wrapper also confers the review label and fixes the observed ID.

## Application contract

The application is CLI, without server HTTP, Actuator or Micrometer Core. The starter does not activate repositories scanning, audit or DDL on its own: `@EnableDatadamRepositories` points to `BookRepository` and bootstrap is explicit. The scheme requires Datadam with RETURNJSON; Common redis does not replace this server.

JAR uses Java release 17 and Boot 3.5.16. For other versions, refer to the main project's matrix and compatibility policy. A written transport error does not cause automatic retry. The caller must reconcile by ID/SKU. The example uses known input IDs to allow this investigation and cleaning. There is no transaction between registrations, query and exclusion.
