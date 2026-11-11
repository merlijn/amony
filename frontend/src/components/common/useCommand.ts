import {useCallback, useEffect, useRef, useState} from "react"
import type {AxiosError} from "axios"
import {getCommandStatus} from "../../api/generated"

const DEFAULT_POLL_INTERVAL_MS = 1000
const DEFAULT_TIMEOUT_MS = 30 * 60 * 1000

type PendingCommand = string | Promise<string>

type SuccessCallback = () => void | Promise<void>
type FailureCallback = (message: string) => void

const wait = (millis: number) => new Promise(resolve => setTimeout(resolve, millis))

export const serverErrorMessage = (error: unknown): string | undefined => {
  const response = (error as AxiosError<{ message?: string }>)?.response
  return response?.data?.message
}

export type CommandRunner = {
  isRunning: boolean
  error: string | undefined
  run: (command: PendingCommand, onSucceeded: SuccessCallback, onFailed?: FailureCallback) => Promise<void>
  cancel: () => void
  reset: () => void
}

/**
 * Polls a command until it succeeds or fails. Accepts either a command id or a promise for one, so the initial
 * request and the polling share a single pending/error state.
 */
export const useCommand = (): CommandRunner => {
  const [isRunning, setIsRunning] = useState(false)
  const [error, setError] = useState<string | undefined>(undefined)
  const aborter = useRef<AbortController | undefined>(undefined)

  useEffect(() => () => aborter.current?.abort(), [])

  const reset = useCallback(() => {
    aborter.current?.abort()
    aborter.current = undefined
    setIsRunning(false)
    setError(undefined)
  }, [])

  const cancel = useCallback(() => {
    aborter.current?.abort()
    aborter.current = undefined
    setIsRunning(false)
  }, [])

  const run = useCallback(async (command: PendingCommand, onSucceeded: SuccessCallback, onFailed?: FailureCallback) => {
    aborter.current?.abort()
    const controller = new AbortController()
    aborter.current = controller
    setIsRunning(true)
    setError(undefined)

    const fail = (message: string) => {
      setError(message)
      onFailed?.(message)
    }

    try {
      const commandId = typeof command === "string" ? command : await command
      const deadline = Date.now() + DEFAULT_TIMEOUT_MS

      while (!controller.signal.aborted) {
        const status = await getCommandStatus(commandId, undefined, controller.signal)

        if (status.state === "succeeded") {
          try {
            await onSucceeded()
          } catch (cause) {
            fail(serverErrorMessage(cause) || "The operation succeeded, but refreshing the result failed.")
          }
          return
        }

        if (status.state === "failed") {
          fail(status.error || "The operation failed.")
          return
        }

        if (Date.now() >= deadline) {
          fail("Timed out waiting for the operation to complete.")
          return
        }

        await wait(DEFAULT_POLL_INTERVAL_MS)
      }
    } catch (cause) {
      if (!controller.signal.aborted) {
        fail(serverErrorMessage(cause) || "The operation failed.")
      }
    } finally {
      if (aborter.current === controller) {
        aborter.current = undefined
        setIsRunning(false)
      }
    }
  }, [])

  return {isRunning, error, run, cancel, reset}
}
