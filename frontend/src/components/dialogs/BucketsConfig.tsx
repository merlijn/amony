import {useEffect, useState} from "react";
import axios from "axios";
import * as RadixDialog from "@radix-ui/react-dialog";
import {adminCreateBucket, adminDeleteBucket, adminListBuckets, adminUpdateBucket, BucketConfigDto} from "../../api/generated";
import InfoTable, {InfoRow} from "../common/InfoTable";
import ChoiceOption from "../common/ChoiceOption";
import DialogWindow from "../common/DialogWindow";
import "../common/Dialog.scss";
import "./BucketsConfig.scss";

const defaultIncludePatterns = ["**/*.{mp4,webm,mkv,mpeg,mpg,wmv,mov,avi,jpg,jpeg,png,webp}"]
const defaultExcludePatterns = ["**/.*"]

const newBucket: BucketConfigDto = {
  id: "",
  type: "LocalDirectory",
  requiredRole: undefined,
  path: "",
  relativeUploadPath: "_upload",
  generatePreviewsOnAdd: false,
  hashingAlgorithm: "partial-hash",
  sync: {
    enabled: false,
    syncOnStartup: true,
    newFilesOwner: "admin",
    pollIntervalSeconds: 300,
    includePatterns: defaultIncludePatterns,
    excludePatterns: defaultExcludePatterns,
  }
}

const errorMessage = (error: unknown, fallback: string): string =>
  (axios.isAxiosError(error) && error.response?.data?.message) || fallback

const syncDescription = (bucket: BucketConfigDto): string => {
  if (bucket.sync.enabled) return `Every ${bucket.sync.pollIntervalSeconds}s`
  if (bucket.sync.syncOnStartup) return "On startup"
  return "Manual"
}

type EditState = { mode: "create" | "edit", bucket: BucketConfigDto }

const BucketsConfig = () => {
  const [buckets, setBuckets] = useState<BucketConfigDto[]>([])
  const [loadError, setLoadError] = useState<string | undefined>(undefined)
  const [editing, setEditing] = useState<EditState | undefined>(undefined)
  const [deleting, setDeleting] = useState<BucketConfigDto | undefined>(undefined)

  const load = () => {
    adminListBuckets()
      .then((data) => { setBuckets(data); setLoadError(undefined) })
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

  return (
    <div className="buckets-config">
      <div className="buckets-toolbar">
        <button className="button-primary" type="button" onClick={() => setEditing({mode: "create", bucket: newBucket})}>
          Add bucket
        </button>
      </div>

      {loadError && <div className="buckets-error">{loadError}</div>}

      <table className="buckets-table">
        <thead>
          <tr>
            <th>Id</th>
            <th>Path</th>
            <th>Access</th>
            <th>Sync</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {buckets.map((bucket) => (
            <tr key={bucket.id}>
              <td>{bucket.id}</td>
              <td className="buckets-path" title={bucket.path}>{bucket.path}</td>
              <td>{bucket.requiredRole ? `Role: ${bucket.requiredRole}` : "Public"}</td>
              <td>{syncDescription(bucket)}</td>
              <td className="buckets-row-actions">
                <button className="button-secondary" type="button" onClick={() => setEditing({mode: "edit", bucket})}>Edit</button>
                <button className="button-danger" type="button" onClick={() => setDeleting(bucket)}>Delete</button>
              </td>
            </tr>
          ))}
          {buckets.length === 0 && !loadError && (
            <tr><td colSpan={5} className="buckets-empty">No buckets configured</td></tr>
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

const linesToList = (text: string): string[] => text.split("\n").map((line) => line.trim()).filter((line) => line.length > 0)

type BucketFormProps = {
  mode: "create" | "edit"
  initial: BucketConfigDto
  onCancel: () => void
  onSaved: () => void
}

const BucketForm = ({mode, initial, onCancel, onSaved}: BucketFormProps) => {
  const [bucket, setBucket] = useState<BucketConfigDto>(initial)
  const [includePatterns, setIncludePatterns] = useState((initial.sync.includePatterns ?? []).join("\n"))
  const [excludePatterns, setExcludePatterns] = useState((initial.sync.excludePatterns ?? []).join("\n"))
  const [pollInterval, setPollInterval] = useState(String(initial.sync.pollIntervalSeconds))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | undefined>(undefined)

  const update = (values: Partial<BucketConfigDto>) => setBucket({...bucket, ...values})
  const updateSync = (values: Partial<BucketConfigDto["sync"]>) => setBucket({...bucket, sync: {...bucket.sync, ...values}})

  const patternsChanged =
    mode === "edit" &&
    (includePatterns !== (initial.sync.includePatterns ?? []).join("\n") ||
      excludePatterns !== (initial.sync.excludePatterns ?? []).join("\n"))

  const save = () => {
    const dto: BucketConfigDto = {
      ...bucket,
      requiredRole: bucket.requiredRole?.trim() || undefined,
      sync: {
        ...bucket.sync,
        pollIntervalSeconds: Number(pollInterval),
        includePatterns: linesToList(includePatterns),
        excludePatterns: linesToList(excludePatterns),
      }
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
            value={bucket.id}
            disabled={mode === "edit"}
            placeholder="e.g. movies"
            onChange={(e) => update({id: e.target.value})}
          />
        </InfoRow>
        <InfoRow label="Path">
          <input type="text" value={bucket.path} placeholder="/media/movies" onChange={(e) => update({path: e.target.value})} />
        </InfoRow>
        <InfoRow label="Upload folder">
          <input type="text" value={bucket.relativeUploadPath} onChange={(e) => update({relativeUploadPath: e.target.value})} />
        </InfoRow>
        <InfoRow label="Required role">
          <input
            type="text"
            value={bucket.requiredRole ?? ""}
            placeholder="None (public)"
            onChange={(e) => update({requiredRole: e.target.value})}
          />
        </InfoRow>
        <InfoRow label="Generate previews on add">
          <ChoiceOption
            type="checkbox"
            checked={bucket.generatePreviewsOnAdd}
            onChange={() => update({generatePreviewsOnAdd: !bucket.generatePreviewsOnAdd})}
          />
        </InfoRow>
      </InfoTable>

      <div className="bucket-form-section">Scanning</div>
      <InfoTable className="config-form">
        <InfoRow label="Scan on startup">
          <ChoiceOption type="checkbox" checked={bucket.sync.syncOnStartup} onChange={() => updateSync({syncOnStartup: !bucket.sync.syncOnStartup})} />
        </InfoRow>
        <InfoRow label="Watch for changes">
          <ChoiceOption type="checkbox" checked={bucket.sync.enabled} onChange={() => updateSync({enabled: !bucket.sync.enabled})} />
        </InfoRow>
        <InfoRow label="Poll interval (seconds)">
          <input type="number" min={1} value={pollInterval} disabled={!bucket.sync.enabled} onChange={(e) => setPollInterval(e.target.value)} />
        </InfoRow>
        <InfoRow label="Owner of new files">
          <input type="text" value={bucket.sync.newFilesOwner} onChange={(e) => updateSync({newFilesOwner: e.target.value})} />
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
              <p className="delete-bucket-note">Media files on disk are never deleted.</p>

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
