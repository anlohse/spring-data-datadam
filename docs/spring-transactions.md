# Native transactions and Spring integration

The library keeps repository operations scoped to a single entity. The stage 9 investigation used Datadam `e2e2c0ae4e9282dd4b1d815e9d893931bd41bd3e` and found that `MULTI`/`EXEC` is useful for native batches with per-command results. A transaction manager for the current repository API would have to change when those operations return results or errors.

## Verified behavior

Each block used a single connection for all its commands. State checks and writes that caused `WATCH` conflicts used a second connection.

| Situation | Observed result |
|---|---|
| `MULTI`, followed by a write and read on the same key | The server replied `QUEUED`. Before `EXEC`, a second client read the old value. The read inside `EXEC` returned the new value. |
| A command has the wrong number of arguments and is rejected while queuing | `EXECABORT`. The earlier queued write did not take effect. |
| `DISCARD` | The server discarded the queue before executing it. |
| A concurrent write changes a key watched with `WATCH` | `EXEC` returned nil and ran none of the queued commands. A write to an unwatched key allowed execution to proceed. |
| A command fails during `EXEC` | The array contained the error at that command’s position. Writes before and after the error took effect. |
| A duplicate violates a `UNIQUE` index | The server rejected the duplicate. The earlier order write took effect. |
| The inventory script receives a stale version | The script returned `0` at its position. The quantity stayed at 10 and the order write took effect. |
| The connection drops around `EXEC` | Dropping the request before it reached the server left the data unchanged. Dropping the reply after execution left both writes applied. The client saw a connection failure in both cases. |

Isolation describes what other clients can observe during the block. The server documents read and write isolation for the affected keys. Rollback would undo writes already executed after an operation fails. The tests show that execution errors leave earlier writes applied. Durability after a crash is a separate property. The server documents early commits for commands such as `IDX.QUERY`, `FT.SEARCH`, and writes involving `UNIQUE` indexes, while the block still holds its locks. The Java tests check protocol results without simulating a crash during commit. The native documentation also distinguishes a write that changes expiration from a watched key expiring naturally. [Native contract at the tested revision](https://github.com/anlohse/datadam/blob/e2e2c0ae4e9282dd4b1d815e9d893931bd41bd3e/docs/transactions.md).

## Decision on `@Transactional`

The transport borrows a connection from the pool per command. A native transaction would need the same connection from `WATCH` through `EXEC` or `DISCARD`. The pool would have to receive connections with clean session state. Binding a connection to the thread would address session ownership, while repository behavior would still need these changes.

- `save` returns the ID, version, and persisted state after acknowledgment. Under `MULTI`, the server replies `QUEUED` and confirms writes during `EXEC`. This would affect return values, post-save callbacks, and stale-version detection.
- Repository reads return entities and pages when called. A queued read would get its result during `EXEC`. A read outside the queue would be unable to see pending writes.
- `REQUIRED` would need to share a session and commit in the outer scope. `REQUIRES_NEW` would have to suspend that binding and use a different connection. `NESTED` would need savepoints, which the native protocol lacks. Even with only `REQUIRED`, errors in `EXEC` could produce partial success, and a lost reply would still require reconciliation.

The Spring transaction manager is deferred. A future explicit batch API could return per-command results and distinguish conflicts, queue rejection, partial success, and unknown outcomes. Revisiting repository integration requires read, write, callback, and propagation guarantees verifiable with the available transport and server.

## Orders and inventory

The native test suite queues an order write and an inventory update. A concurrent write to the key observed with `WATCH` can abort the queue. If the queue executes and the inventory script rejects a stale version, the order can exist without a quantity change. A lost reply leaves the outcome uncertain.

The Spring demo for item 9.1 handles these cases with an explicit checkout workflow using versioned repositories. Each order has a caller-supplied ID. The stock write saves that ID alongside the new quantity, so a read can confirm the debit after an ambiguous response. A stock rejection leaves the order with a partial outcome. Follow the [checkout walkthrough](demo-examples.md#91-orders-and-inventory).

Start Docker and use the project’s pinned image to run the integration suite. The runner creates and removes its own Datadam instance.

```powershell
mvn -B -ntp -pl datadam-demo -am -Pintegration '-Dit.test=NativeTransactionsIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```
