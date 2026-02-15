// EventBusContext.tsx
import { createContext, useContext, useEffect, useMemo } from 'react'
import mitt, { Emitter } from 'mitt'
import {ResourceDto} from "../../api/generated";

// Define your events
export type Events = {
  'resource-updated': ResourceDto
  'resource-deleted': string
}

const EventBusContext = createContext<Emitter<Events> | null>(null)

export const EventBusProvider = ({ children }: { children: React.ReactNode }) => {
  const emitter = useMemo(() => mitt<Events>(), [])

  return (
    <EventBusContext.Provider value={emitter}>
      {children}
    </EventBusContext.Provider>
  )
}

// Hook to get the emitter directly (for emitting)
export const useEventBus = (): Emitter<Events> => {
  const emitter = useContext(EventBusContext)
  if (!emitter) {
    throw new Error('useEventBus must be used within EventBusProvider')
  }
  return emitter
}

// Hook to subscribe to events (auto-cleanup)
export const useEventListener = <K extends keyof Events>(
  event: K,
  handler: (payload: Events[K]) => void
) => {
  const emitter = useEventBus()

  useEffect(() => {
    emitter.on(event, handler)
    return () => {
      emitter.off(event, handler)
    }
  }, [emitter, event, handler])
}