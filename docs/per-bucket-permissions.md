# Per-bucket permissions — proposal

Status: **proposal, not implemented**. Written for later reference while buckets moved into the database (see
`BucketRegistry` / `BucketAdminRoutes`).

## 1. Current situation

Two separate mechanisms decide what a user can do with a bucket:

| Mechanism                         | Where                              | Decides                                                  |
| --------------------------------- | ---------------------------------- | -------------------------------------------------------- |
| Role permissions                  | `auth.conf` → `access-control`     | _What_ a role may do (view, upload, manage, …), globally |
| Optional `required_role` / bucket | `buckets.required_role` (database) | _Whether_ a bucket is visible at all (admins always)     |

Limitations:

1. **Visibility only.** Once a bucket is visible, the global permissions apply to it. It is not possible to say
   "everyone may view bucket X, but only `family` may upload to or tag it".
2. **A single role.** "Visible to `family` _or_ `friends`" requires a combined role in the identity provider.
3. **Confusing values are accepted.** Signed-in users get the `authenticated` role, anonymous visitors only get
   `anonymous`. A bucket with `required_role = anonymous` is therefore hidden from signed-in (non-admin) users.
4. **Permission checks happen before the bucket is known.** `serverLogic(endpoint, requiredPermission = …)` authorizes
   in the security logic, before the endpoint resolves the bucket, so a bucket cannot influence the check.

Gaps found in the current implementation that this proposal also addresses:

- `ResourceContentRoutes` (content, thumbnails, preview clips) only checks bucket visibility. The `preview-resource` and
  `download` permissions are not enforced anywhere.
- The frontend decides what to show with `session.isAdmin()` only, not with permissions. A non-admin with
  `manage-resources` can edit through the API but sees no edit controls.

## 2. Proposal

### 2.1 Split permissions into bucket and global permissions

| Bucket permissions (per bucket) | Global permissions   |
| ------------------------------- | -------------------- |
| `view-resource`                 | `manage-collections` |
| `preview-resource`              | `admin`              |
| `download`                      |                      |
| `search-resources`              |                      |
| `manage-resources`              |                      |
| `upload-resource`               |                      |

### 2.2 Optional access configuration on the bucket

```hocon
access = {
  anonymous     = [ view-resource, preview-resource, search-resources ]
  authenticated = [ view-resource, preview-resource, search-resources ]
  family        = [ view-resource, preview-resource, search-resources, upload-resource, manage-resources ]
}
```

- **Not set:** the bucket uses the global `access-control` from `auth.conf`. This is the current behaviour, so the
  default bucket needs no extra configuration and `auth.conf` keeps its meaning.
- **Set:** the bucket's rules _replace_ the global role permissions for that bucket.
  - _Replace_ is preferred over _intersect_. With intersection a bucket can only narrow the global permissions: to allow
    `family` to upload to one bucket, `family` would need upload rights globally and every other bucket would have to
    take them away again.

### 2.3 Evaluation

- Effective permissions in a bucket are the **union** over all the user's roles, as is the case for `access-control`
  today. Signed-in users have `authenticated` plus their identity-provider roles; anonymous visitors only have
  `anonymous`.
- Admins always have all permissions.
- A bucket is **visible** when the user has `view-resource` on it.
  - Not visible: `404` (the bucket's existence is not revealed), and the bucket is excluded from search.
  - Visible but missing the required permission: `403`.

### 2.4 Replacing `required_role`

`required_role` becomes a special case and can be migrated:

| Current                  | Equivalent `access`                                                                   |
| ------------------------ | ------------------------------------------------------------------------------------- |
| `required_role` not set  | `access` not set (use global `access-control`)                                        |
| `required_role = <role>` | `{ <role>: <global permissions of authenticated users> }`, i.e. only `<role>` sees it |

The migration has to read `access-control` from `auth.conf` to fill in the permissions. It can therefore not be a pure
SQL migration and should run at application startup (or be written out explicitly with the current defaults).

### 2.5 Storage and API

- Database: an `access JSONB` column on `buckets` (nullable, `NULL` = use global defaults). It is generic to all bucket
  types, so it is a column next to `bucket_id`, `bucket_type` and `required_role` rather than part of `settings`.
- Admin API (`BucketConfigDto`): an optional `access: Map[String, List[String]]` field. Validation:
  - only bucket permissions are allowed (not `admin` or `manage-collections`);
  - unknown permission names are rejected;
  - role names are free text (they come from the identity provider). The UI can suggest the roles named in
    `access-control`.
- Public API: `GET /api/buckets` returns the current user's effective permissions per bucket, so the frontend can show
  or hide controls per bucket instead of using `isAdmin()`.

## 3. Implementation outline

1. **Security helper.** Add `ApiSecurity.bucketPermissions(token, bucket): Set[Permission]` plus a helper that resolves
   a bucket and checks a permission in one go (returning `NotFoundError` or `Forbidden`).
2. **Bucket-scoped endpoints.** Authorize these without a global permission and call the helper after resolving the
   bucket:
   - `ResourceRoutes`: get, delete, update metadata, thumbnail timestamp, bulk tags, upload, list buckets.
   - `ResourceContentRoutes`: content (`view-resource`/`download`), thumbnails and clips (`preview-resource`).
   - `SearchRoutes`: exclude buckets without `search-resources`.
   - `CollectionRoutes`: adding/removing a resource and listing a collection's resources require `view-resource` on the
     resource's bucket. `manage-collections` itself stays global.
3. **Global endpoints stay as they are:** admin, bucket admin, import/export, create/list collections.
4. **Config and storage:** `access` on `ResourceBucketConfig`, the database column, DTOs and validation.
5. **Migration** of `required_role` (see 2.4) and removal of the column.
6. **Frontend:** use the per-bucket permissions from `/api/buckets` instead of `isAdmin()`, and add an access editor
   (role × permission matrix) to the bucket management page.
7. **Tests:** permission evaluation (union, admin, fallback to global, replace semantics), and route tests for the `404`
   versus `403` behaviour.

## 4. Open questions

- Should `download` be separate from `view-resource` for original content, or should view imply download (streaming a
  video is effectively a download)?
- Should a bucket be able to grant permissions to `anonymous` when `require-login = true`? Probably ignored, as login is
  checked first.
- Is a per-user (not per-role) grant ever needed? This proposal deliberately only supports roles.
