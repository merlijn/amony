import {useState, useTransition} from "react"
import {fixResourceStreamable, ResourceDto} from "../../api/generated"
import * as RadixDialog from "@radix-ui/react-dialog"
import "../common/Dialog.scss"
import DialogContainer from "../common/DialogWindow"
import {useEventBus} from "../common/EventBus"
import "./FixStreamabilityDialog.scss"

type FixStreamabilityDialogProps = {
  resource: ResourceDto | undefined
  visible: boolean
  onFixed: (resource: ResourceDto) => void
  onHide: () => void
}

const FixStreamabilityDialog = ({resource, visible, onFixed, onHide}: FixStreamabilityDialogProps) => {
  const [isFixing, startFixing] = useTransition()
  const [error, setError] = useState<string | undefined>(undefined)
  const emitter = useEventBus()

  const handleFix = () => {
    if (!resource) return

    setError(undefined)

    startFixing(async () => {
      try {
        await fixResourceStreamable(resource.bucketId, resource.resourceId)
        emitter.emit('resource-updated', resource)
        onFixed(resource)
      } catch {
        setError("Failed to fix this resource. It may use an unsupported container or codec.")
      }
    })
  }

  const handleHide = () => {
    if (!isFixing) {
      setError(undefined)
      onHide()
    }
  }

  const title = resource?.title || resource?.path?.split("/").pop() || "this resource"

  return (
    <RadixDialog.Root open={visible} onOpenChange={(open) => { if (!open) handleHide() }}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="dialog-overlay" />
        <RadixDialog.Content className="dialog-content">
          <DialogContainer title="Fix streaming">
            <div className="fix-streamability-dialog">
              <p>Remux <strong>{title}</strong> so it can be streamed progressively?</p>
              <p className="fix-streamability-note">The video is remuxed without re-encoding, so its quality is unchanged.</p>

              {error && <div className="fix-streamability-error">{error}</div>}

              <div className="fix-streamability-actions">
                <button
                  className="button-secondary"
                  type="button"
                  onClick={handleHide}
                  disabled={isFixing}
                >
                  Cancel
                </button>
                <button
                  className="button-primary"
                  type="button"
                  onClick={handleFix}
                  disabled={isFixing}
                >
                  {isFixing ? "Fixing..." : "Fix"}
                </button>
              </div>
            </div>
          </DialogContainer>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}

export default FixStreamabilityDialog
