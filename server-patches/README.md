# HASH replacement server dependency

Roadmap item 4.1 requires Datadam's `HREPLACE` extension and the HASH transaction-undo correction in `hreplace.patch`.

Upstream change: [Datadam PR #7](https://github.com/anlohse/datadam/pull/7).

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

## Typed native JSON projection results

Roadmap item 5.1 adds `typed-return.patch`, generated against Datadam commit `131e616e47f3174c3ba9470f20969107890557ae` (the HREPLACE change). It adds the read-only RETURNJSON option to IDX.QUERY and FT.SEARCH, retaining JSON string quotes and distinguishing JSON null from the literal string `null`. Existing RETURN and full-document reads keep their original behavior. HASH projection reads use existing RETURN.

Upstream change: [Datadam PR #9](https://github.com/anlohse/datadam/pull/9), stacked on PR #7. Merge PR #7 first, then retarget PR #9 to main. The RETURNJSON commit is `8bca196`.

```powershell
git -C D:/cpp/workspaces/datadam apply --check D:/java/workspaces/datadam/spring-data/server-patches/typed-return.patch
git -C D:/cpp/workspaces/datadam apply D:/java/workspaces/datadam/spring-data/server-patches/typed-return.patch
docker build --target test -t datadam:projection-test D:/cpp/workspaces/datadam
./scripts/run-local.ps1 -BuildDatadam -Restart -IntegrationTests -Background
```

The current local checkout already includes both patches. Do not apply them twice. JSON projection users need RETURNJSON even if they do not use HASH; old servers fail explicitly, without a full-document fallback. The final server suite passed 186 native tests plus integration/persistence.
