import React, {useEffect, useState} from "react"
import {ResourceDto, getResourceById} from "../../api/generated"
import * as RadixDialog from "@radix-ui/react-dialog"
import * as Tabs from "@radix-ui/react-tabs"
import "../common/Dialog.scss"
import DialogWindow from "../common/DialogWindow"
import InfoTable, {InfoRow} from "../common/InfoTable"
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
    <InfoTable className="resource-details">
      <InfoRow label="Resource ID">{displayResource.resourceId}</InfoRow>
      <InfoRow label="Hash">{displayResource.partialHash || "-"}</InfoRow>
      <InfoRow label="Bucket ID">{displayResource.bucketId}</InfoRow>
      <InfoRow label="Owner">{displayResource.userId}</InfoRow>
      <InfoRow label="Path">{displayResource.path}</InfoRow>
      <InfoRow label="File size">{fileSize} ({displayResource.sizeInBytes} bytes)</InfoRow>
      <InfoRow label="Title">{displayResource.title || "-"}</InfoRow>
      <InfoRow label="Description">{displayResource.description || "-"}</InfoRow>
      <InfoRow label="Tags">{displayResource.tags.length > 0 ? displayResource.tags.join(", ") : "-"}</InfoRow>
      <InfoRow label="Date added">{dateAdded}</InfoRow>
      {dateModified && <InfoRow label="Last modified">{dateModified}</InfoRow>}
      <InfoRow label="Content type">{displayResource.contentType}</InfoRow>
      <InfoRow label="Dimensions">{meta.width} × {meta.height}</InfoRow>
      {meta.fps > 0 && <InfoRow label="FPS">{meta.fps}</InfoRow>}
      <InfoRow label="Duration">{durationStr}</InfoRow>
      {meta.codec && <InfoRow label="Codec">{meta.codec}</InfoRow>}
    </InfoTable>
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
