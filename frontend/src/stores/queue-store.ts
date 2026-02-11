import { create } from 'zustand'

interface QueueState {
  queueToken: string | null
  scheduleId: string | null
  position: number | null

  setQueueToken: (token: string, scheduleId: string) => void
  setPosition: (position: number) => void
  clearQueue: () => void
}

export const useQueueStore = create<QueueState>((set) => ({
  queueToken: null,
  scheduleId: null,
  position: null,

  setQueueToken: (token, scheduleId) =>
    set({ queueToken: token, scheduleId }),
  setPosition: (position) => set({ position }),
  clearQueue: () => set({ queueToken: null, scheduleId: null, position: null }),
}))
