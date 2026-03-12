import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface QueueState {
  queueToken: string | null
  scheduleId: string | null
  position: number | null
  enteredAt: number | null

  setQueueToken: (token: string, scheduleId: string) => void
  setPosition: (position: number) => void
  setEnteredAt: (timestamp: number) => void
  clearQueue: () => void
}

export const useQueueStore = create<QueueState>()(
  persist(
    (set) => ({
      queueToken: null,
      scheduleId: null,
      position: null,
      enteredAt: null,

      setQueueToken: (token, scheduleId) =>
        set((state) => ({
          queueToken: token,
          scheduleId,
          enteredAt: state.scheduleId !== scheduleId ? null : state.enteredAt,
        })),
      setPosition: (position) => set({ position }),
      setEnteredAt: (timestamp) => set({ enteredAt: timestamp }),
      clearQueue: () =>
        set({ queueToken: null, scheduleId: null, position: null, enteredAt: null }),
    }),
    {
      name: 'queue-storage',
      partialize: (state) => ({
        scheduleId: state.scheduleId,
        enteredAt: state.enteredAt,
      }),
    }
  )
)
