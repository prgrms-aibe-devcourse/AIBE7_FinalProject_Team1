# DB convergence: reviewed source and human execution

Status: actual clone rehearsal and shared DB application are **PENDING**. No DB GO is granted by the agent.
The 2026-11-03 snapshot is historical evidence, never a deletion authorization or current catalog.
This package does not connect to Supabase, AWS, EC2, S3 or any deployed service automatically.

## Supported lineages and policy

| Input | Runner lineage | Runtime profiles, in this order | Locations |
|---|---|---|---|
| New or existing dated + deploy | dated | prod,supabase,db-convergence | migration, deploy, convergence |
| V2–V5 + old purchase/summary + ownership, after normalization | legacy | prod,supabase,db-legacy-convergence | migration, deploy, convergence, legacy, compatibility/purchase |
| Existing pending P2/P3 test DB | pending | test only; explicitly select runner locations | migration, deploy, convergence, pending/trade-p2, pending/trade-p3 |
| PostgreSQL demonstration fixture | demo | isolated test only | migration, deploy, convergence, demo/sale-draft |

All location names above have `classpath:db/` prefix. Ordinary local/H2 and existing deployment defaults are retained.
Production convergence profiles explicitly use `public` for Flyway, Hibernate and pooled JDBC search_path. Use
CONVERGENCE_SCHEMA=public for human production execution. Arbitrary generated schemas are supported only by isolated
test configurations; do not use a custom-schema maintenance result as proof that the production public schema converged.
Both convergence runtime profiles and the runner use **empty ignore patterns** and `outOfOrder=false`.
Both set PostgreSQL `transactional-lock=false` so the existing nontransactional concurrent-index Java migration
uses the same advisory-lock mode in the maintenance runner and application startup.
The checker accepts only the exact production profile combinations above, plus its existing `prod,supabase` value.
Use a convergence profile for a converged DB; the existing default `*:*` policy is not the convergence contract.
On Flyway 12.4.0, isolated probes observed `*:*` accepting checksum, type and description mismatches while empty patterns
rejected each one. Explicit `*:future` accepted a synthetic future row; empty patterns rejected it. Do not generalize
these observations to other dependency versions. The XML records the probes and the effective empty configuration.
Do not add legacy/compatibility locations to a new DB. The runner rejects legacy migration unless V2/V3/V4/2103 are already applied.
Do not combine compatibility/purchase and pending/trade-p2: they intentionally resolve the same version.
Pending P3 was historically applied after the dated chain with a one-time out-of-order test setup. It is not an alternative production profile.

V2103 compatibility is copied from the existing pending original, with Flyway checksum **-778179566**.
It is resolved, already applied history after normalization; its unconditional CREATE statements are never scheduled against that DB.
Existing versioned SQL and Java files are unchanged. New V2026110402 is isolated in `db/convergence` so old fixture contracts are not silently changed.
V2026110401 and both Java migrations are included in the candidate. Java migrations inherit the null checksum contract of BaseJavaMigration;
file SHA-256 identifies their source, rather than inventing a Flyway checksum.

Normalization changes **only version and script**, preserving rank, description, type, checksum, success and execution time:

| Old | New | Checksum |
|---|---|---:|
| 2026102701 purchase | 2026102103 purchase | -778179566 |
| 2026102706 summary | 2026102701 summary | 620449880 |
| 2026102702 ownership | 2026102803 ownership | -1318655273 |
| 2026102707 slot | 2026102804 slot | -1107274211 |

The runner verifies every applied identity against source-resolved Flyway metadata before normalization, then
runs the existing ownership normalizer and the new purchase-summary normalizer in one transaction.
The old summary normalizer cannot handle simultaneous purchase/summary history; **do not run it for this lineage**.
Unknown/duplicate/failed/mixed history is refused; ownership's already canonical state is supported, and a completely normalized rerun is a no-op.
A partially moved purchase/summary pair is refused. Never insert history rows or change checksums manually.
`verify-normalization-catalog.sql` checks validated FK/CHECK/UNIQUE definitions, not just their names.
Its definition MD5 values identify `pg_get_constraintdef` output obtained by executing the preserved SQL on PostgreSQL 17.6;
they are catalog fingerprints, not Flyway checksums or security signatures. PostgreSQL major versions other than 17
are deliberately refused for this normalization contract until separately verified.
The missing installed_rank=17 in the snapshot is not reconstructed.

## Tools, inputs and review package

Requires JDK 17 and the checked-in Gradle wrapper. No new external dependency is introduced.
`scripts/db/java/org/example/db/ConvergenceTool.java` is a separate source set: it starts no Spring application,
scheduler, mail, Gemini, Redis or storage client and reads no dotenv. Secrets are taken only from explicitly provided environment variables.
Use a clean process, removing inherited DB/Supabase/AWS/AI/Spring overrides. Do not read the repository's real `.env`.
Supply credentials using an approved secret prompt/manager with command echo/transcript disabled, not literal shell commands, URLs, PRs or chat.

| Environment input | Meaning |
|---|---|
| CONVERGENCE_DB_URL | JDBC PostgreSQL URL, without credentials in the URL |
| CONVERGENCE_DB_USER / CONVERGENCE_DB_PASSWORD | Approved maintenance role and password, supplied privately |
| CONVERGENCE_DATABASE / CONVERGENCE_SCHEMA | Exact expected DB and schema; no default target |
| CONVERGENCE_TARGET_ID | Human-confirmed Supabase project ref or unique isolated instance ID |
| CONVERGENCE_URL_SHA256 | SHA-256 of the exact JDBC URL, independently checked by operator |
| CONVERGENCE_PLAN | Restricted local plan file path; generated by plan action |
| CONVERGENCE_APPROVED_PLAN_SHA256 | Hash explicitly approved by operator/reviewer, not automatically copied by a script |

`postgres/public` is insufficient project identity. The target ID and URL digest prevent accidental plan reuse;
they do not prove an operator entered the correct project. Confirm Dashboard project ref, host and maintenance connection together.
The URL must not contain password/token material. Flyway can log host/DB metadata; keep raw operational logs access restricted.
Plans contain counts and digests, not business text/object keys, but are still restricted operator evidence.
Keep plans outside the source tree. Plan expiry is 30 minutes; regenerate and review if expired.

From the reviewed source root, use `./gradlew` on Unix or `./gradlew.bat` on Windows:

```text
./gradlew dbConvergence -PdbLineage=legacy -PdbAction=manifest --console=plain
./gradlew dbConvergence -PdbLineage=legacy -PdbAction=check --console=plain
./gradlew dbConvergence -PdbLineage=legacy -PdbAction=plan -PdbPlanOperation=NORMALIZE --console=plain
```

`check` and `plan` use a read-only, repeatable-read transaction. The checker emits labeled count/digest lines, logical
orphan and business checks, CHECK violations (`IS FALSE`, matching SQL CHECK semantics), complete history identities,
columns, constraints/validation, indexes/validity, grants, RLS and function grants. Missing child **or parent** columns
produce `NOT_RUN/MISSING_PREREQUISITE`, never a zero. Stricter business checks use `IS NOT TRUE` for nulls.
An FK count alone does not certify same-product, same-trade or participant rules; run the API acceptance separately.
It does not independently certify every business rule in the application.

`manifest` resolves SQL and Java through the actual Flyway dependency (12.4.0 at the base), prints states and effective
locations/ignore/outOfOrder, plus per-file SHA-256. Flyway checksum and SHA-256 are different fields.
Before final delivery run `scripts/db/package-convergence.ps1 -Destination <new-restricted-directory>` from PowerShell.
It requires a clean committed source tree and produces source.zip, SOURCE.txt (full SHA), and SHA256SUMS over the
actual extracted bytes. Verify these hashes before use; do not hash the manifest into itself.
Build/run inside the extracted source using its wrapper. Capture the final head externally rather than committing a self-referential SHA.

## Human application order — HUMAN_ACTION_REQUIRED

| Step | Action/file/command | Success / next gate | Stop condition |
|---|---|---|---|
| 1 | Record project ref, URL fingerprint, candidate full SHA, executor and reviewer | Target agreed | Any mismatch or unresolved provenance |
| 2 | Obtain restorable backup, restore an isolated clone, run this same package there | Actual clone rehearsal PASS | No backup/restore proof or incomplete schema/data inventory |
| 3 | Stop **all** writers: team IDE bootRun, workers, apps, EC2 service | Maintenance window confirmed | Any uncontrolled writer |
| 4 | Run manifest and check; review all history, DDL, unknown objects and counts | Current evidence, all required queries ran | Unknown lineage, failed/missing/duplicate history, invalid index, missing prerequisites |
| 5 | Generate plan with `-PdbAction=plan -PdbPlanOperation=NORMALIZE`; approve target, history version/script scope and source hashes | Approval recorded | Missing/unknown operation, historical counts used as approval, plan stale/drift |
| 6 | Set approved hash privately; `./gradlew dbConvergence -PdbLineage=legacy -PdbAction=normalize` | All four identities canonical, other fields unchanged | Guard/timeout/error: transaction rolls back; recheck before retry |
| 7 | Generate **new** plan with `-PdbAction=plan -PdbPlanOperation=PURGE` after normalization; approve exact allowlist deletion scope | Current rows explicitly classified as disposable | Any business row must be retained or an unknown table/trigger/reference exists |
| 8 | `./gradlew dbConvergence -PdbLineage=legacy -PdbAction=purge` | Allowlisted business rows gone, users/profile/history digests preserved | Approval/target/source/content/catalog drift or DML error: rollback |
| 9 | `./gradlew dbConvergence -PdbLineage=legacy -PdbAction=migrate` | Entire chain, strict validate, second migrate=0 | Error/ignored/pending/unresolved migration, unexpected DDL |
| 10 | check + manifest again; compare catalog, required constraints/indexes and preserved rows | DB owner records DB GO | Any violation, NOT_RUN, unexpected counts or missing validated contract |
| 11 | Verify deployment env/storage/TLS; restrict ingress; start candidate; create data through APIs | Explicit operational acceptance | Smoke failure: keep access blocked |

Use `dated` for dated DBs; steps 5–6 normalization do not apply to them. Purge is optional and **only** applies when
the entire listed business-table contents are approved for disposal. Retained-business DBs use the preserving migrate path (M3).
There is no arbitrary subset deletion or table override. Unknown rows cannot be classified from the historical snapshot.
The runner locks all existing application tables in stable name order, rechecks the full plan (target, source digest,
table contents including preserved tables, history, columns, constraints, indexes), then writes. There are 5s lock and 60s
statement timeouts on the maintenance JDBC transaction. A failed multi-statement step rolls back.
Every plan includes exactly one `operation=NORMALIZE` or `operation=PURGE` plus its scope, covered by the
approved SHA-256. Execution must match that operation; missing, unknown, duplicate and cross-purpose operations
are rejected. Old plans without an operation must be regenerated and approved again. Changed contents/history
invalidate the snapshot; a no-op normalization may leave it unchanged, but never authorizes purge.
Generate and approve a fresh purpose-specific plan before every write step.
The plan is read-only; there is no delete-and-rollback dry-run masquerading as a plan.

The maintenance transaction's `SET LOCAL` timeouts do not propagate to Flyway's separate migration connections,
including nontransactional concurrent indexing. Before an actual clone rehearsal, inspect the effective
`lock_timeout` and `statement_timeout` on the approved migration role/connection and agree on cancellation
limits separately. On interruption inspect applied history and index validity as described below; do not assume
all migrations roll back together. Use an approved connection compatible with PostgreSQL session advisory locks.

Purge uses a fixed child-before-parent DELETE allowlist in code, retaining sequences/identities. It preserves `users`,
`user_profile_images` and `flyway_schema_history`; auth/storage/managed schemas are outside scope. It refuses unknown
application tables, user triggers, and incoming references from preserved/external tables. No TRUNCATE CASCADE, blanket
owner reassignment, account resurrection or sequence reset is performed. PostgreSQL FK checks can still stop a deletion;
rollback and investigate the changed graph rather than broadening scope automatically.

SQL deletes do not delete files, Redis state, signed URLs or cached tokens. Before purging, the human storage owner must
capture needed object references into an access-restricted local inventory (never the PR/artifacts), then separately approve
specific local/S3 object and Redis-key cleanup. Never delete a whole bucket or use FLUSHALL/shared FLUSHDB.
New API uploads must receive fresh storage keys; do not reuse historical local keys as S3 references.

For SQL Editor users, `check-convergence.sql` is ordinary read-only SQL and can be run in a read-only transaction in one
editor execution. It is the catalog section; use the runner for the full dynamic check and plan. The reviewed write path
is the runner, not manually copied SQL: arrange a JDK17 maintenance terminal if SQL Editor is the only current tool.
Do not paste `.psql` commands or manually add Flyway history. Different editor executions do not share transactions.
The PostgreSQL backup tool uses PostgreSQL/libpq connection options, **not** the JDBC URL; use an approved service/passfile
with restricted permissions instead of password arguments.

## Constraints and failure recovery

V2026110402 adds NO ACTION FKs products.owner→users and documents.product/uploader→products/users;
existing indexes cover these lookups. It enforces positive nonnull owner, nonnull ciphertext and DIRECT/INTERNAL nonnull
transfer type, validates existing owner/ciphertext CHECKs where present, and verifies the exact cursor index shape and validity.
It never deletes rows or rewrites owner IDs. Existing invalid references must stop migration.
Fresh schemas with equivalent NOT NULL are supported. Identically named unexpected new constraints cause failure, not silent no-op.
Product removal archives rows; unverified-account cleanup deletes accounts which should have no authenticated business data.
A legacy unverified account with retained data requires owner review rather than cascade deletion.
Chat sender/participant orphans are reported; no speculative user FK is added for historical/system-message retention.
Keep that contract and existing consumers in the operational review.

V2026110202 is Java and nontransactional (`CREATE INDEX CONCURRENTLY`); V2026102102 is Java with null checksum.
Do **not** wrap Flyway plus normalization/purge in a single outer BEGIN.
On Flyway failure, keep writers stopped, capture full history plus constraint/index validity and actual applied versions.
Earlier migrations can already be committed; a concurrent invalid/incomplete index can remain.
Never run automatic repair, clean, history deletion or rollback to an unverified JAR.
An error inside normalization/purge means that transaction is rolled back; an error after a committed step requires a
fresh plan and a reviewed resume or restoration of the verified backup. Database restoration is distinct from JAR rollback.

## Re-seeding and operational acceptance

The PostgreSQL API test uses isolated JWT/authentication doubles only; the actual product endpoint performs encryption
and fingerprinting, file APIs create product image/masked document objects, and profile images use the DB path separately.
The existing two-party trade suite runs under the new constraints and Hibernate validation, including transaction rollback
and access denial. Synthetic SQL fixture ciphertext is labeled test-only and is not an API-created encryption proof.
CI runs that HTTP suite both on the pending/deploy lineage and on a separately synthesized, normalized and purged legacy
lineage (`CONVERGENCE_API_LINEAGE=legacy`), preserving separate XML evidence. The legacy HTTP setup is a second isolated
schema, not a claim that it is the real shared DB or a restored backup.
The M5 fixture uses preserved source originals and the four observed history mappings, plus synthetic orphan rows;
it is **not** a faithful full clone (rank ordering, all business contents, external storage and every manual DDL are not reproduced).

After DB GO and private storage/env readiness, humans must use real signup/email/login for separate seller, buyer and
external recipient accounts; take returned IDs rather than assuming 25. Use an identifiable new test namespace and record
its created IDs privately so a repeat does not overwrite normal rows. Use approved admin provisioning only.
Perform product/serial registration, image upload, masked document upload/replacement/download, history/publication,
listing publication, chat HTTP/STOMP/read/reconnect/leave, request/cancel/reserve/change-review/two-party-complete/internal
transfer, then direct QR/login-return/recipient-consent/owner-approval. Verify old owner/third party/old file URL denial.
Profile-image upload/read/delete is a separate DB test. Real Gemini failures/success, email, private S3, TLS/QR and Redis
acceptance remain **PENDING** until actually exercised. No test inserts are a substitute for those checks.

Humans must also review Supabase Data API enabled schemas, anon/authenticated grants, RLS policies and function execution
privileges using the catalog output and Dashboard. This is an operational review, not a claim that a vulnerability was found.
Spring's JDBC maintenance role differs from web client roles. Decide Data API disablement only after checking consumers;
this package neither changes grants/policies nor enables blanket RLS.

## Deployment preparation (no AWS calls)

Review first-deploy handling separately from A. An existing `current.jar` checksum proves bytes, not successful prior operation
or schema/env/storage compatibility. Archived 2367101f remains an unverified artifact. Bootstrap must stop on failure and keep
public access blocked; a follow-up deployment may revert only to a human-verified compatible previous artifact.
Any independent switch-release correction belongs in B's separate PR.

The operator checks EC2/JDK/Valkey/Nginx/resources/service account/ports, separate release-artifact and content bucket/prefix,
EC2-role read of JAR/checksum/manifest, and private S3 put/get/delete through the service user's SDK path. Then confirm
DNS/TLS/Nginx syntax, websocket upgrade, external 8080 blocked and restricted HTTPS, logs without tokens/query secrets.
Confirm the exact checked env file equals systemd's EnvironmentFile; `/etc/pastport/disabled.env` must be absent or separately
approved because the name does not prevent loading. For full feature acceptance CHAT_API_ENABLED, LISTING_HISTORY_ENABLED,
TRADE_PURCHASE_REQUESTS_ENABLED, TRADE_P3_ENABLED and TRADE_P4_ENABLED must be true; AI_SUMMARY_ENABLED/AI_LISTING_ENABLED
require deliberately approved Gemini setup. A syntactically valid false flag does not activate a feature.
No automatic key rotation, provisioning, service start or infrastructure update is part of this package.
`GET /` and systemd active are liveness only; independently accept DB/Redis/S3/auth/business behavior.
