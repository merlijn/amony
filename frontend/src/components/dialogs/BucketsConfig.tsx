import {useEffect, useState} from "react";
import axios from "axios";
import * as RadixDialog from "@radix-ui/react-dialog";
import {
  adminCreateBucket,
  adminDeleteBucket,
  adminFixNonStreamable,
  adminGeneratePreviews,
  adminReComputeHashes,
  adminRefreshBucket,
  adminReindexBucket,
  adminRescanMetaData,
  adminUpdateBucket,
  BucketConfigDto,
  getBuckets,
  LocalDirectoryStorageDto,
  S3StorageDto,
} from "../../api/generated";
import InfoTable, {InfoRow} from "../common/InfoTable";
import ChoiceOption from "../common/ChoiceOption";
import DialogWindow from "../common/DialogWindow";
import "../common/Dialog.scss";
import "./BucketsConfig.scss";

type StorageType = "LocalDirectory" | "S3"

const defaultIncludePatterns = ["**/*.{mp4,webm,mkv,mpeg,mpg,wmv,mov,avi,jpg,jpeg,png,webp}"]
const defaultExcludePatterns = ["**/.*"]

const defaultLocalDirectory = (): LocalDirectoryStorageDto => ({
  path: "",
  relativeUploadPath: "_upload",
  sync: {
    enabled: false,
    syncOnStartup: true,
    newFilesOwner: "admin",
    pollIntervalSeconds: 300,
    includePatterns: defaultIncludePatterns,
    excludePatterns: defaultExcludePatterns,
  }
})

const defaultS3 = (): S3StorageDto => ({
  bucket: "",
  region: "auto",
  endpoint: undefined,
  prefix: undefined,
  pathStyleAccess: false,
  presignedUrlTtlSeconds: 900,
})

const newBucket = (): BucketConfigDto => ({
  id: "",
  type: "LocalDirectory",
  requiredRole: undefined,
  generatePreviewsOnAdd: false,
  localDirectory: defaultLocalDirectory(),
})

const errorMessage = (error: unknown, fallback: string): string =>
  (axios.isAxiosError(error) && error.response?.data?.message) || fallback

const bucketLocation = (bucket: BucketConfigDto): string => {
  if (bucket.type === "S3" && bucket.s3) {
    const prefix = bucket.s3.prefix?.replace(/^\/+|\/+$/g, "")
    return prefix ? `s3://${bucket.s3.bucket}/${prefix}` : `s3://${bucket.s3.bucket}`
  }
  return bucket.localDirectory?.path ?? ""
}

const syncDescription = (bucket: BucketConfigDto): string => {
  if (bucket.type !== "LocalDirectory" || !bucket.localDirectory) return "—"
  const {sync} = bucket.localDirectory
  if (sync.enabled) return `Every ${sync.pollIntervalSeconds}s`
  if (sync.syncOnStartup) return "On startup"
  return "Manual"
}

type EditState = { mode: "create" | "edit", bucket: BucketConfigDto }

const BucketsConfig = () => {
  const [buckets, setBuckets] = useState<BucketConfigDto[]>([])
  const [loadError, setLoadError] = useState<string | undefined>(undefined)
  const [editing, setEditing] = useState<EditState | undefined>(undefined)
  const [deleting, setDeleting] = useState<BucketConfigDto | undefined>(undefined)
  const [actions, setActions] = useState<BucketConfigDto | undefined>(undefined)

  const load = () => {
    getBuckets()
      .then((data) => { setBuckets(data.flatMap((bucket) => bucket.config ?? [])); setLoadError(undefined) })
      .catch((e) => setLoadError(errorMessage(e, "Failed to load buckets")))
  }

  useEffect(load, [])

  if (editing)
    return (
      <BucketForm
        mode={editing.mode}
        initial={editing.bucket}
        onCancel={() => { setEditing(undefined); load() }}
        onSaved={() => { setEditing(undefined); load() }}
      />
    )

  if (actions)
    return <BucketActions bucket={actions} onBack={() => setActions(undefined)} />

  return (
    <div className="buckets-config">
      <div className="buckets-toolbar">
        <button className="button-primary" type="button" onClick={() => setEditing({mode: "create", bucket: newBucket()})}>
          Add bucket
        </button>
      </div>

      {loadError && <div className="buckets-error">{loadError}</div>}

      <table className="buckets-table">
        <thead>
          <tr>
            <th>Id</th>
            <th>Type</th>
            <th>Location</th>
            <th>Access</th>
            <th>Sync</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {buckets.map((bucket) => {
            const location = bucketLocation(bucket)
            return (
              <tr key={bucket.id}>
                <td>{bucket.id}</td>
                <td>{bucket.type === "S3" ? "S3" : "Local"}</td>
                <td className="buckets-path" title={location}>{location}</td>
                <td>{bucket.requiredRole ? `Role: ${bucket.requiredRole}` : "Public"}</td>
                <td>{syncDescription(bucket)}</td>
                <td className="buckets-row-actions">
                  <button className="button-secondary" type="button" onClick={() => setActions(bucket)}>Actions</button>
                  <button className="button-secondary" type="button" onClick={() => setEditing({mode: "edit", bucket})}>Edit</button>
                  <button className="button-danger" type="button" onClick={() => setDeleting(bucket)}>Delete</button>
                </td>
              </tr>
            )
          })}
          {buckets.length === 0 && !loadError && (
            <tr><td colSpan={6} className="buckets-empty">No buckets configured</td></tr>
          )}
        </tbody>
      </table>

      <DeleteBucketDialog
        bucket={deleting}
        onHide={() => setDeleting(undefined)}
        onDeleted={() => { setDeleting(undefined); load() }}
      />
    </div>
  )
}

type AdminAction = 'refresh' | 'reindex' | 'rescan' | 'recompute' | 'generate-previews' | 'fix-non-streamable'

type BucketActionsProps = {
  bucket: BucketConfigDto
  onBack: () => void
}

const BucketActions = ({bucket, onBack}: BucketActionsProps) => {
  const [activeAction, setActiveAction] = useState<AdminAction | null>(null)
  const [error, setError] = useState<string | undefined>(undefined)

  const runAction = (action: AdminAction, apiCall: () => Promise<unknown>) => {
    setActiveAction(action)
    setError(undefined)
    apiCall()
      .catch((e) => setError(errorMessage(e, "Failed to run the action")))
      .finally(() => setActiveAction(null))
  }

  const anyLoading = activeAction !== null
  const bucketId = bucket.id
  const isLocal = bucket.type === "LocalDirectory"

  const actions: Array<{action: AdminAction, label: string, apiCall: () => Promise<unknown>}> = [
    ...(isLocal ? [{action: 'refresh' as const, label: 'Refresh resources', apiCall: () => adminRefreshBucket(bucketId)}] : []),
    {action: 'reindex', label: 'Reindex resources', apiCall: () => adminReindexBucket(bucketId)},
    {action: 'rescan', label: 'Rescan metadata', apiCall: () => adminRescanMetaData(bucketId)},
    ...(isLocal ? [{action: 'recompute' as const, label: 'Recompute hashes', apiCall: () => adminReComputeHashes(bucketId)}] : []),
    {action: 'generate-previews', label: 'Generate thumbnails and clips', apiCall: () => adminGeneratePreviews(bucketId)},
    {action: 'fix-non-streamable', label: 'Fix non streamable videos', apiCall: () => adminFixNonStreamable(bucketId)},
  ]

  return (
    <div className="bucket-form">
      <div className="bucket-form-title">Actions for bucket '{bucket.id}'</div>

      <InfoTable className="config-form">
        {actions.map(({action, label, apiCall}) => (
          <InfoRow key={action} label={label}>
            <button type="button" disabled={anyLoading} onClick={() => runAction(action, apiCall)}>
              {activeAction === action ? <span className="admin-spinner" /> : "Go"}
            </button>
          </InfoRow>
        ))}
      </InfoTable>

      {error && <div className="buckets-error">{error}</div>}

      <div className="bucket-form-actions">
        <button className="button-secondary" type="button" onClick={onBack} disabled={anyLoading}>Back</button>
      </div>
    </div>
  )
}

const linesToList = (text: string): string[] => text.split("\n").map((line) => line.trim()).filter((line) => line.length > 0)

type BucketFormProps = {
  mode: "create" | "edit"
  initial: BucketConfigDto
  onCancel: () => void
  onSaved: () => void
}

const BucketForm = ({mode, initial, onCancel, onSaved}: BucketFormProps) => {
  const initialLocal = initial.localDirectory ?? defaultLocalDirectory()
  const initialS3 = initial.s3 ?? defaultS3()

  const [id, setId] = useState(initial.id)
  const [storageType, setStorageType] = useState<StorageType>(initial.type === "S3" ? "S3" : "LocalDirectory")
  const [requiredRole, setRequiredRole] = useState(initial.requiredRole ?? "")
  const [generatePreviewsOnAdd, setGeneratePreviewsOnAdd] = useState(initial.generatePreviewsOnAdd)

  const [local, setLocal] = useState<LocalDirectoryStorageDto>(initialLocal)
  const [includePatterns, setIncludePatterns] = useState((initialLocal.sync.includePatterns ?? []).join("\n"))
  const [excludePatterns, setExcludePatterns] = useState((initialLocal.sync.excludePatterns ?? []).join("\n"))
  const [pollInterval, setPollInterval] = useState(String(initialLocal.sync.pollIntervalSeconds))

  const [s3, setS3] = useState<S3StorageDto>(initialS3)
  const [presignedUrlTtl, setPresignedUrlTtl] = useState(String(initialS3.presignedUrlTtlSeconds))

  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | undefined>(undefined)

  const updateLocal = (values: Partial<LocalDirectoryStorageDto>) => setLocal({...local, ...values})
  const updateSync = (values: Partial<LocalDirectoryStorageDto["sync"]>) => setLocal({...local, sync: {...local.sync, ...values}})
  const updateS3 = (values: Partial<S3StorageDto>) => setS3({...s3, ...values})

  const patternsChanged =
    mode === "edit" &&
    storageType === "LocalDirectory" &&
    (includePatterns !== (initialLocal.sync.includePatterns ?? []).join("\n") ||
      excludePatterns !== (initialLocal.sync.excludePatterns ?? []).join("\n"))

  const save = () => {
    const dto: BucketConfigDto = {
      id,
      type: storageType,
      requiredRole: requiredRole.trim() || undefined,
      generatePreviewsOnAdd,
      updatedAt: initial.updatedAt,
      ...(storageType === "LocalDirectory"
        ? {
            localDirectory: {
              ...local,
              sync: {
                ...local.sync,
                pollIntervalSeconds: Number(pollInterval),
                includePatterns: linesToList(includePatterns),
                excludePatterns: linesToList(excludePatterns),
              }
            }
          }
        : {
            s3: {
              ...s3,
              endpoint: s3.endpoint?.trim() || undefined,
              prefix: s3.prefix?.trim() || undefined,
              presignedUrlTtlSeconds: Number(presignedUrlTtl),
              accessKeyId: s3.accessKeyId?.trim() || undefined,
              secretAccessKey: s3.secretAccessKey?.trim() || undefined,
              hasSecretAccessKey: undefined,
            }
          })
    }

    setSaving(true)
    setError(undefined)

    const request = mode === "create" ? adminCreateBucket(dto) : adminUpdateBucket(initial.id, dto)

    request
      .then(() => onSaved())
      .catch((e) => setError(errorMessage(e, "Failed to save the bucket")))
      .finally(() => setSaving(false))
  }

  return (
    <div className="bucket-form">
      <div className="bucket-form-title">{mode === "create" ? "Add bucket" : `Edit bucket '${initial.id}'`}</div>

      <div className="bucket-form-section">General</div>
      <InfoTable className="config-form">
        <InfoRow label="Id">
          <input
            type="text"
            value={id}
            disabled={mode === "edit"}
            placeholder="e.g. movies"
            onChange={(e) => setId(e.target.value)}
          />
        </InfoRow>
        <InfoRow label="Storage type">
          <select
            value={storageType}
            disabled={mode === "edit"}
            onChange={(e) => setStorageType(e.target.value as StorageType)}
          >
            <option value="LocalDirectory">Local directory</option>
            <option value="S3">S3 / Object storage</option>
          </select>
        </InfoRow>
        <InfoRow label="Required role">
          <input
            type="text"
            value={requiredRole}
            placeholder="None (public)"
            onChange={(e) => setRequiredRole(e.target.value)}
          />
        </InfoRow>
        <InfoRow label="Generate previews on add">
          <ChoiceOption
            type="checkbox"
            checked={generatePreviewsOnAdd}
            onChange={() => setGeneratePreviewsOnAdd(!generatePreviewsOnAdd)}
          />
        </InfoRow>
      </InfoTable>

      {storageType === "LocalDirectory" ? (
        <>
          <div className="bucket-form-section">Local directory</div>
          <InfoTable className="config-form">
            <InfoRow label="Path">
              <input type="text" value={local.path} placeholder="/media/movies" onChange={(e) => updateLocal({path: e.target.value})} />
            </InfoRow>
            <InfoRow label="Upload folder">
              <input type="text" value={local.relativeUploadPath} onChange={(e) => updateLocal({relativeUploadPath: e.target.value})} />
            </InfoRow>
          </InfoTable>

          <div className="bucket-form-section">Scanning</div>
          <InfoTable className="config-form">
            <InfoRow label="Scan on startup">
              <ChoiceOption type="checkbox" checked={local.sync.syncOnStartup} onChange={() => updateSync({syncOnStartup: !local.sync.syncOnStartup})} />
            </InfoRow>
            <InfoRow label="Watch for changes">
              <ChoiceOption type="checkbox" checked={local.sync.enabled} onChange={() => updateSync({enabled: !local.sync.enabled})} />
            </InfoRow>
            <InfoRow label="Poll interval (seconds)">
              <input type="number" min={1} value={pollInterval} disabled={!local.sync.enabled} onChange={(e) => setPollInterval(e.target.value)} />
            </InfoRow>
            <InfoRow label="Owner of new files">
              <input type="text" value={local.sync.newFilesOwner} onChange={(e) => updateSync({newFilesOwner: e.target.value})} />
            </InfoRow>
          </InfoTable>

          <div className="bucket-form-section">Files</div>
          <p className="bucket-form-help">
            One glob pattern per line, relative to the bucket path and case-insensitive. A file is indexed when it matches an include pattern
            and no exclude pattern. Folders matching an exclude pattern are skipped.
          </p>
          <InfoTable className="config-form">
            <InfoRow label="Include">
              <textarea rows={3} value={includePatterns} onChange={(e) => setIncludePatterns(e.target.value)} />
            </InfoRow>
            <InfoRow label="Exclude">
              <textarea rows={3} value={excludePatterns} onChange={(e) => setExcludePatterns(e.target.value)} />
            </InfoRow>
          </InfoTable>

          {patternsChanged && (
            <div className="bucket-form-warning">
              Files that no longer match the patterns are removed from the index, including their tags and metadata.
            </div>
          )}
        </>
      ) : (
        <>
          <div className="bucket-form-section">S3 storage</div>
          <p className="bucket-form-help">
            Leave credentials blank to use the server environment (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY or IAM role).
          </p>
          <InfoTable className="config-form">
            <InfoRow label="Bucket">
              <input
                type="text"
                value={s3.bucket}
                placeholder="e.g. amony-media"
                onChange={(e) => updateS3({bucket: e.target.value})}
              />
            </InfoRow>
            <InfoRow label="Region">
              <input
                type="text"
                value={s3.region}
                placeholder="e.g. eu-west-1 or auto"
                onChange={(e) => updateS3({region: e.target.value})}
              />
            </InfoRow>
            <InfoRow label="Endpoint">
              <input
                type="text"
                value={s3.endpoint ?? ""}
                placeholder="Optional (e.g. https://<account>.r2.cloudflarestorage.com)"
                onChange={(e) => updateS3({endpoint: e.target.value})}
              />
            </InfoRow>
            <InfoRow label="Prefix">
              <input
                type="text"
                value={s3.prefix ?? ""}
                placeholder="Optional key prefix"
                onChange={(e) => updateS3({prefix: e.target.value})}
              />
            </InfoRow>
            <InfoRow label="Access key ID">
              <input
                type="text"
                value={s3.accessKeyId ?? ""}
                placeholder="Optional (uses server credentials if empty)"
                onChange={(e) => updateS3({accessKeyId: e.target.value})}
              />
            </InfoRow>
            <InfoRow label="Secret access key">
              <input
                type="password"
                autoComplete="new-password"
                value={s3.secretAccessKey ?? ""}
                placeholder={mode === "edit" && s3.hasSecretAccessKey ? "Leave blank to keep existing" : "Optional"}
                onChange={(e) => updateS3({secretAccessKey: e.target.value})}
              />
            </InfoRow>
            <InfoRow label="Path-style access">
              <ChoiceOption
                type="checkbox"
                checked={s3.pathStyleAccess}
                onChange={() => updateS3({pathStyleAccess: !s3.pathStyleAccess})}
              />
            </InfoRow>
            <InfoRow label="Presigned URL TTL (seconds)">
              <input
                type="number"
                min={1}
                max={604800}
                value={presignedUrlTtl}
                onChange={(e) => setPresignedUrlTtl(e.target.value)}
              />
            </InfoRow>
          </InfoTable>
        </>
      )}

      {error && <div className="buckets-error">{error}</div>}

      <div className="bucket-form-actions">
        <button className="button-secondary" type="button" onClick={onCancel} disabled={saving}>Cancel</button>
        <button className="button-primary" type="button" onClick={save} disabled={saving}>
          {saving ? "Saving..." : "Save"}
        </button>
      </div>
    </div>
  )
}

type DeleteBucketDialogProps = {
  bucket: BucketConfigDto | undefined
  onHide: () => void
  onDeleted: () => void
}

const DeleteBucketDialog = ({bucket, onHide, onDeleted}: DeleteBucketDialogProps) => {
  const [force, setForce] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | undefined>(undefined)

  useEffect(() => { setForce(false); setError(undefined) }, [bucket])

  const handleDelete = () => {
    if (!bucket) return

    setDeleting(true)
    setError(undefined)

    adminDeleteBucket(bucket.id, {force})
      .then(() => onDeleted())
      .catch((e) => setError(errorMessage(e, "Failed to delete the bucket")))
      .finally(() => setDeleting(false))
  }

  return (
    <RadixDialog.Root open={bucket !== undefined} onOpenChange={(open) => { if (!open && !deleting) onHide() }}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="dialog-overlay" />
        <RadixDialog.Content className="dialog-content">
          <DialogWindow title="Delete bucket">
            <div className="delete-bucket-dialog">
              <p>Are you sure you want to delete bucket <strong>{bucket?.id}</strong>?</p>
              <ChoiceOption
                type="checkbox"
                checked={force}
                onChange={() => setForce(!force)}
                label="Also remove its resources from the index (tags and metadata are lost)"
              />
              <p className="delete-bucket-note">Media files in storage are never deleted.</p>

              {error && <div className="buckets-error">{error}</div>}

              <div className="bucket-form-actions">
                <button className="button-secondary" type="button" onClick={onHide} disabled={deleting}>Cancel</button>
                <button className="button-danger" type="button" onClick={handleDelete} disabled={deleting}>
                  {deleting ? "Deleting..." : "Delete"}
                </button>
              </div>
            </div>
          </DialogWindow>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}

export default BucketsConfig
