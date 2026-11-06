# HASH replacement server dependency

Roadmap item 4.1 requires Datadam's `HREPLACE` extension and the HASH transaction-undo correction in `hreplace.patch`.

The patch targets Datadam commit `22a82209e493d94b780cbcfb88bed2c95bc76b7b`. It adds complete HASH replacement with TTL preservation, native command ACL metadata, documentation and regression tests, and fixes undo entries so an absent batch entry cannot become a persisted deletion after a UNIQUE refusal.

Apply only to a compatible checkout that does not already include these changes:

```powershell
git -C D:/cpp/workspaces/datadam apply --check D:/java/workspaces/datadam/spring-data/server-patches/hreplace.patch
git -C D:/cpp/workspaces/datadam apply D:/java/workspaces/datadam/spring-data/server-patches/hreplace.patch
```

The current local Datadam checkout already contains the changes; do not apply the patch twice. Rebuild and test:

```powershell
docker build --target test -t datadam:hash-test D:/cpp/workspaces/datadam
./scripts/run-local.ps1 -BuildDatadam -Restart -IntegrationTests -Background
```

JSON-only users do not need HREPLACE. HASH writes deliberately have no HSET/HDEL fallback: old servers fail before mutation instead of providing an unsafe replacement contract. HASH schema/query support is provided by roadmap item 4.2 in the Java library and uses existing native IDX/FT commands; it requires no additional server patch.
