import React, {useState} from "react"
import {deleteResource, ResourceDto} from "../../api/generated"
import Modal from "../common/Modal"
import Dialog from "../common/Dialog"
import "./DeleteResourceDialog.scss"

type DeleteResourceDialogProps = {
  resource: ResourceDto | undefined
  visible: boolean
  onDeleted: (resource: ResourceDto) => void
  onHide: () => void
}

const DeleteResourceDialog = ({resource, visible, onDeleted, onHide}: DeleteResourceDialogProps) => {
  const [isDeleting, setIsDeleting] = useState(false)
  const [error, setError] = useState<string | undefined>(undefined)

  const handleDelete = () => {
    if (!resource) return

    setIsDeleting(true)
    setError(undefined)

    deleteResource(resource.bucketId, resource.resourceId)
      .then(() => {
        setIsDeleting(false)
        onDeleted(resource)
      })
      .catch(() => {
        setError("Failed to delete resource. Please try again.")
        setIsDeleting(false)
      })
  }

  const handleHide = () => {
    if (!isDeleting) {
      setError(undefined)
      onHide()
    }
  }

  const title = resource?.title || resource?.path?.split("/").pop() || "this resource"

  return (
    <Modal visible={visible} onHide={handleHide}>
      <Dialog title="Delete resource">
        <div className="delete-resource-dialog">
          <p>Are you sure you want to delete <strong>{title}</strong>?</p>
          <p className="delete-resource-warning">This action cannot be undone.</p>

          {error && <div className="delete-resource-error">{error}</div>}

          <div className="delete-resource-actions">
            <button
              className="delete-resource-button"
              type="button"
              onClick={handleHide}
              disabled={isDeleting}
            >
              Cancel
            </button>
            <button
              className="delete-resource-button danger"
              type="button"
              onClick={handleDelete}
              disabled={isDeleting}
            >
              {isDeleting ? "Deleting..." : "Delete"}
            </button>
          </div>
        </div>
      </Dialog>
    </Modal>
  )
}

export default DeleteResourceDialog
