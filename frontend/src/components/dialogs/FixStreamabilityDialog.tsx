import {fixResourceStreamable, getResourceById, ResourceDto} from "../../api/generated"
import * as RadixDialog from "@radix-ui/react-dialog"
import "../common/Dialog.scss"
import DialogContainer from "../common/DialogWindow"
import {useEventBus} from "../common/EventBus"
import {useCommand} from "../common/useCommand"
import "./FixStreamabilityDialog.scss"

type FixStreamabilityDialogProps = {
  resource: ResourceDto | undefined
  visible: boolean
  onFixed: (resource: ResourceDto) => void
  onHide: () => void
}

const FixStreamabilityDialog = ({resource, visible, onFixed, onHide}: FixStreamabilityDialogProps) => {
  const {isRunning, error, run} = useCommand()
  const emitter = useEventBus()

  const handleFix = () => {
    if (!resource) return

    void run(
      fixResourceStreamable(resource.bucketId, resource.resourceId).then(command => command.commandId),
      async () => {
        const updated = await getResourceById(resource.bucketId, resource.resourceId)
        emitter.emit("resource-updated", updated)
        onFixed(updated)
      }
    )
  }

  const handleHide = () => {
    if (!isRunning) {
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
                  disabled={isRunning}
                >
                  Cancel
                </button>
                <button
                  className="button-primary"
                  type="button"
                  onClick={handleFix}
                  disabled={isRunning}
                >
                  {isRunning ? "Fixing..." : "Fix"}
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
