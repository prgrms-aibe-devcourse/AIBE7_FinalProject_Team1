# Private S3 storage

`STORAGE_TYPE=s3` selects one adapter, including `prod,supabase`. Local/test default
to local storage. Missing bucket/region or an unsafe prefix fails configuration.
AWS SDK v2 is pinned to 2.57.8; upgrades require review. Production credentials
come from the EC2 instance role through `DefaultCredentialsProvider`. Do not set
static AWS credential environment variables or enable SDK wire/debug logging.

User-content and release-artifact buckets must be provisioned separately. The
application role needs only `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` on
`arn:aws:s3:::<approved-content-bucket>/<approved-prefix>/*`. HEAD uses GetObject
permission; no ListBucket is needed. Enable all Block Public Access settings,
bucket-owner-enforced ownership, default SSE-S3 (or approved KMS permissions),
and a bucket policy denying non-HTTPS access. No ACL is sent by this adapter.
Infrastructure approval and actual provisioning are outside this change.

Keys contain prefix/directory/UUID.extension only. Original names are discarded.
Uploads spool to a private OS temporary file to avoid unknown-length SDK memory
buffering; files are deleted in finally. Downloads use HEAD metadata and lazy,
repeatable GET streams; callers must close streams. SDK error messages/causes
are not propagated because they can contain private object keys or URLs.

## URL/caller audit (2026-11-03)

- ProductService: product images use the existing authenticated product/image-ID
  HTTP endpoint. The browser does not receive the S3 key.
- DocumentService: uploads and downloads retain application document-ID signing,
  current-owner/version/masking checks and no-store responses. Extraction reads
  streams internally. No document presigning is introduced.
- ListingDraftDemoImages: internal local/demo storage consumer, no temporary URL.
- `createTemporaryUrl`: no runtime caller exists outside LocalFileStorage.
  The S3 implementation deliberately fails closed with UnsupportedOperationException;
  consumers must use the existing image/document endpoints. It cannot generate
  object URLs for either documents or images. A future caller requires a reviewed
  application proxy design rather than silently introducing public S3 links.

Existing local object keys are not S3 keys. Switching storage does not migrate
existing content: inventory, controlled copy and DB key reconciliation require
a separate approved plan before STORAGE GO. Never overwrite shared keys locally.

Sources: [SDK release](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.8),
[stream upload behavior](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/best-practices-s3-uploads.html).
