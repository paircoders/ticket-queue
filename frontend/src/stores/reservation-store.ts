import { create } from 'zustand'
import type { Seat } from '@/types/event'

interface ReservationState {
  selectedSeats: Seat[]
  scheduleId: string | null
  totalPrice: number

  addSeat: (seat: Seat) => void
  removeSeat: (seatId: string) => void
  clearSeats: () => void
  setScheduleId: (id: string) => void
}

export const useReservationStore = create<ReservationState>((set) => ({
  selectedSeats: [],
  scheduleId: null,
  totalPrice: 0,

  addSeat: (seat) =>
    set((state) => {
      // 최대 4장 제한 체크
      if (state.selectedSeats.length >= 4) {
        return state
      }

      const newSeats = [...state.selectedSeats, seat]
      const totalPrice = newSeats.reduce((sum, s) => sum + s.price, 0)
      return { selectedSeats: newSeats, totalPrice }
    }),

  removeSeat: (seatId) =>
    set((state) => {
      const newSeats = state.selectedSeats.filter((s) => s.id !== seatId)
      const totalPrice = newSeats.reduce((sum, s) => sum + s.price, 0)
      return { selectedSeats: newSeats, totalPrice }
    }),

  clearSeats: () => set({ selectedSeats: [], totalPrice: 0 }),

  setScheduleId: (id) => set({ scheduleId: id }),
}))
