# Pastport release readiness

These are **unapplied templates**, not deployment approval. No script provisions
AWS resources or changes a database explicitly. Application startup **does run
Flyway**, including deploy migrations; do not start before DB GO. Never disable
Flyway, clean/repair history, or change historical migrations to bypass approval.

## Gates / ownership

Record approver, approved full commit, environment and timestamp outside secrets:

| Gate | Required evidence |
| --- | --- |
| CODE GO | Reviewed PRs, all CI checks green on the exact release commit |
| DB GO | DB owner approves schema/history inventory, migration ordering/checksums, backup and restore rehearsal; compatibility with rollback JAR |
| STORAGE GO | Infra-approved private content bucket, role/prefix/IAM/encryption, read/write/delete acceptance and existing-content migration plan |
| HTTPS GO | Approved hostname/certificate, Nginx syntax, security group; backend port not public |
| ENV GO | Root-owned 0600 env passes checker; independently generated secrets, correct origin, approved feature settings |
| ARTIFACT GO | Immutable release-bucket objects, exact full SHA manifest/checksum and provenance approved |
| SMOKE GO | Internal-only deployment: login/cookies, product list/count/image, signed document denial/owner/version, upload/masking, P5 transfer/return/cancel |

Any NO means **no external exposure**. The first six GO approvals permit internal
startup only; SMOKE GO must follow before allowing public ingress. The scripts
never open security groups or Nginx/public ingress. Operators enforce that gate.
Mock test success is not operational GO. Feature flags remain false by default;
changing them is a separate team approval. DB rollback is never automatic: only
use an old JAR confirmed compatible with the already-applied schema.

## Environment and service (do not execute before approval)

Linux prerequisites: Bash 4.3+, GNU coreutils, util-linux/flock, curl, AWS CLI v2,
systemd, JDK 17, local Valkey/Redis and Nginx. Create the `pastport` service account,
`/var/lib/pastport`, root-owned non-writable `/opt/pastport/releases` and root-owned
`/etc/pastport/pastport.env` mode 0600. Install scripts root-owned, not writable
by the service user. systemd loads the root-only env before dropping privileges.
The service writes only its StateDirectory and private temp; port is loopback
8080. Logs default to `/var/lib/pastport/logs` plus journal.

Env format is literal `KEY=value`, one key per line; no shell sourcing, interpolation,
quotes or whitespace. Use independently generated high-entropy base64url secrets
(32+ UTF-8 bytes). Run `bash deploy/scripts/check-env.sh <env-file>` as root.
It outputs key names and SET/INVALID only, never partial values. Include all keys
listed in its `required` array and `REDIS_PASSWORD` (explicit empty is permitted
only for approved loopback Redis). AI flags must be explicit; no key is needed
when disabled. Set `DOTENV_PATH=/etc/pastport/disabled.env` to avoid working-dir
dotenv fallback. `prod,supabase` order is required so Supabase datasource wins.
Do not add static AWS credentials, JVM/JSON property overrides, debug/wire logging,
alternate config locations or Flyway overrides to the approved environment.

Storage settings and the minimum content-role policy are documented in
[`../docs/development/s3-storage.md`](../docs/development/s3-storage.md). The artifact
bucket is **separate**; an operator role needs only read of approved artifact
objects for staging. No release bucket is defaulted or hardcoded into application
settings. Do not grant content users release-artifact access. Actual S3 roundtrip
and content inventory/key migration remain a team/infra acceptance gate.

Install the reviewed service template only after all pre-start approvals.
Install the Nginx template in the `http` include context; replace hostname and
certificate placeholders, run `nginx -t`, verify no duplicate/default upstream
access logs record query strings. The access format uses `$uri`, not `$request`
or `$request_uri`. Error logs are critical-only; never enable request/debug logs
around token URLs. All proxied responses use no proxy cache; no separate static
cache is enabled, so sensitive API responses cannot inherit one. TLS, websocket,
12 MB uploads, timeouts and no-referrer are included. Keep ingress blocked until
SMOKE GO; templates alone are not HTTPS GO.

## Reproducible build

On a clean reviewed Windows worktree with JDK 17 and Node 22+:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/build-release.ps1 -JavaHome <jdk17>
```

The script checks cleanliness/SHA before and after quality gates, runs Spotless,
discards inherited DB/AWS-adjacent test opt-ins and application credentials from
its build process, disables dotenv/AI and supplies synthetic H2 settings, then runs
all Node tests and clean build (Java tests included). Outputs are ignored under
`build/release`: `<sha8>.jar`, `<sha8>.jar.sha256`, `<sha8>.manifest.txt` with full
commit, UTC build time, Java version and digest. Reproducible here means an
auditable procedure and checksum identity, not bit-for-bit reproducible Gradle
archives. Conditional PostgreSQL tests must additionally pass the existing CI
jobs for the exact commit; a local default build is not their substitute.
Upload the three files under an approved **release-only** S3 prefix using the
operator process. Protect artifact writes/versioning separately; a checksum from
the same bucket is integrity evidence, not an independent cryptographic signature.

## Stage / activate / rollback (operator-only examples)

```bash
bash deploy/scripts/stage-release.sh s3://APPROVED_RELEASE_BUCKET/releases abcdef12
bash deploy/scripts/switch-release.sh abcdef12 /etc/pastport/pastport.env /etc/pastport/approved-gates
bash deploy/scripts/rollback-release.sh 12345678 /etc/pastport/pastport.env /etc/pastport/approved-gates
```

Use actual lowercase SHA8 identifiers. The root-owned 0600 gate file must contain
exactly one `CODE=GO`, `DB=GO`, `STORAGE=GO`, `HTTPS=GO`, `ENV=GO`, `ARTIFACT=GO` line;
attach the full-SHA approvals to the release record (a text GO file is not an
authorization system). Reapprove the gate record for an explicit rollback target.
Staging downloads with AWS CLI, checks SHA256 and full commit manifest, installs
root:root 0644, refuses overwrite and does not touch current.jar. Activation
rechecks immutable files, records previous-release.txt, switches the symlink
atomically, restarts and polls loopback HTTP plus service state for ~150 seconds.
There is no actuator dependency: `/` is a minimal liveness probe, **not** a
business/DB smoke test. A failed restart/probe restores the previous verified JAR;
first-deploy failure stops the service and removes only the current link.
Rollback-health failure exits nonzero: keep public traffic blocked, inspect
`systemctl status pastport` / `journalctl -u pastport` in the approved secure
console, and involve DB/infra owners. Avoid exporting token-containing logs.
Use the same scripts for explicit rollback, including artifact/environment checks.
All operations take a nonblocking exclusive release lock. No script runs SQL,
repairs history, modifies Nginx, grants IAM, opens ingress or rolls back migrations.

## Offline tests

```bash
sudo bash deploy/tests/release-test.sh
```

Tests use a private temporary directory and PATH stubs for AWS CLI, systemctl,
curl and sleep. Dummy JARs exercise checksums, immutable staging, atomic activation,
previous link, explicit and automatic rollback, DB-NO refusal, env duplicates,
missing values/modes/owners/flags and secret non-disclosure. They never contact
AWS, systemd or an application; local validation additionally runs with Docker
network disabled. CI runs the same fixtures, alongside existing build/P5 jobs.
