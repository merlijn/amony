import {useEffect, useRef, useState, useTransition} from "react"
import {fixResourceStreamable, getCommandStatus, getResourceById, ResourceDto} from "../../api/generated"
import * as RadixDialog from "@radix-ui/react-dialog"
import type {AxiosError} from "axios"
import "../common/Dialog.scss"
import DialogContainer from "../common/DialogWindow"
import {useEventBus} from "../common/EventBus"
import "./FixStreamabilityDialog.scss"

const POLL_INTERVAL_MS = 1000
const MAX_WAIT_MS = 30 * 60 * 1000

const wait = (millis: number) => new Promise(resolve => setTimeout(resolve, millis))

const serverErrorMessage = (error: unknown): string | undefined => {
  const response = (error as AxiosError<{ message?: string }>)?.response
  return response?.data?.message
}

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
  const aborter = useRef<AbortController | undefined>(undefined)

  useEffect(() => () => aborter.current?.abort(), [])

  const handleFix = () => {
    if (!resource) return

    setError(undefined)

    const controller = new AbortController()
    aborter.current = controller

    startFixing(async () => {
      try {
        const command = await fixResourceStreamable(resource.bucketId, resource.resourceId, undefined, controller.signal)
        const deadline = Date.now() + MAX_WAIT_MS

        while (!controller.signal.aborted) {
          const status = await getCommandStatus(command.commandId, undefined, controller.signal)

          if (status.state === "succeeded") {
            const updated = await getResourceById(resource.bucketId, resource.resourceId, undefined, controller.signal)
            emitter.emit("resource-updated", updated)
            onFixed(updated)
            return
          }

          if (status.state === "failed") {
            setError(status.error || "Failed to fix this resource. It may use an unsupported container or codec.")
            return
          }

          if (Date.now() >= deadline) {
            setError("Timed out waiting for this resource to be fixed.")
            return
          }

          await wait(POLL_INTERVAL_MS)
        }
      } catch (cause) {
        if (!controller.signal.aborted) {
          setError(serverErrorMessage(cause) || "Failed to fix this resource. It may use an unsupported container or codec.")
        }
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
