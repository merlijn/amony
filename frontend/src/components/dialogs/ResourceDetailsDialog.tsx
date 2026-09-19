import React, {useEffect, useState} from "react"
import {ResourceDto, getResourceById} from "../../api/generated"
import * as RadixDialog from "@radix-ui/react-dialog"
import * as Tabs from "@radix-ui/react-tabs"
import "../common/Dialog.scss"
import DialogWindow from "../common/DialogWindow"
import {dateMillisToString, durationInMillisToString, formatByteSize} from "../../api/Util"
import "./ResourceDetailsDialog.scss"

type ResourceDetailsDialogProps = {
  resource: ResourceDto | undefined
  visible: boolean
  onHide: () => void
}

const ResourceDetailsDialog = ({resource, visible, onHide}: ResourceDetailsDialogProps) => {
  const [fullResource, setFullResource] = useState<ResourceDto | undefined>(undefined)
  const [isLoading, setIsLoading] = useState(false)

  useEffect(() => {
    if (visible && resource) {
      setIsLoading(true)
      getResourceById(resource.bucketId, resource.resourceId)
        .then((res) => setFullResource(res))
        .catch(() => setFullResource(undefined))
        .finally(() => setIsLoading(false))
    }
  }, [visible, resource])

  const displayResource = fullResource || resource
  if (!displayResource) return null

  const meta = displayResource.contentMeta
  const dateAdded = dateMillisToString(displayResource.timeAdded)
  const dateModified = displayResource.timeLastModified ? dateMillisToString(displayResource.timeLastModified) : undefined
  const durationStr = durationInMillisToString(meta.duration)
  const fileSize = formatByteSize(displayResource.sizeInBytes)

  const detailsContent = (
    <div className="resource-details">
      <div className="detail-section">
        <p className="detail-label">Resource ID</p>
        <div className="detail-value">{displayResource.resourceId}</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">Hash</p>
        <div className="detail-value">{displayResource.partialHash || "-"}</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">Bucket ID</p>
        <div className="detail-value">{displayResource.bucketId}</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">Owner</p>
        <div className="detail-value">{displayResource.userId}</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">Path</p>
        <div className="detail-value">{displayResource.path}</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">File size</p>
        <div className="detail-value">{fileSize} ({displayResource.sizeInBytes} bytes)</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">Title</p>
        <div className="detail-value">{displayResource.title || "-"}</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">Description</p>
        <div className="detail-value">{displayResource.description || "-"}</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">Tags</p>
        <div className="detail-value">{displayResource.tags.length > 0 ? displayResource.tags.join(", ") : "-"}</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">Date added</p>
        <div className="detail-value">{dateAdded}</div>
      </div>
      {dateModified && (
        <div className="detail-section">
          <p className="detail-label">Last modified</p>
          <div className="detail-value">{dateModified}</div>
        </div>
      )}
      <div className="detail-section">
        <p className="detail-label">Content type</p>
        <div className="detail-value">{displayResource.contentType}</div>
      </div>
      <div className="detail-section">
        <p className="detail-label">Dimensions</p>
        <div className="detail-value">{meta.width} × {meta.height}</div>
      </div>
      {meta.fps > 0 && (
        <div className="detail-section">
          <p className="detail-label">FPS</p>
          <div className="detail-value">{meta.fps}</div>
        </div>
      )}
      <div className="detail-section">
        <p className="detail-label">Duration</p>
        <div className="detail-value">{durationStr}</div>
      </div>
      {meta.codec && (
        <div className="detail-section">
          <p className="detail-label">Codec</p>
          <div className="detail-value">{meta.codec}</div>
        </div>
      )}

    </div>
  )

  const fullMeta = fullResource?.fullMeta as Record<string, unknown> | undefined

  return (
    <RadixDialog.Root open={visible} onOpenChange={(open) => { if (!open) onHide() }}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="dialog-overlay" />
        <RadixDialog.Content className="dialog-content-window resource-details-dialog">
          <DialogWindow>
            {isLoading && !fullResource && <div className="resource-details-loading">Loading...</div>}
            {!isLoading && (
              <Tabs.Root className="tabs-root" defaultValue="details">
                <Tabs.List className="tabs-list">
                  <Tabs.Trigger className="tab-trigger" value="details">Details</Tabs.Trigger>
                  {fullMeta && <Tabs.Trigger className="tab-trigger" value="metadata">Metadata</Tabs.Trigger>}
                </Tabs.List>
                <Tabs.Content className="tab-content" value="details">
                  {detailsContent}
                </Tabs.Content>
                {fullMeta && (
                  <Tabs.Content className="tab-content metadata-tab-content" value="metadata">
                    <pre className="resource-details-json">{JSON.stringify(fullMeta, null, 2)}</pre>
                  </Tabs.Content>
                )}
              </Tabs.Root>
            )}
          </DialogWindow>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}

export default ResourceDetailsDialog
