import { create } from 'zustand'
import type { Seat } from '@/types/event'

/** 좌석 선택 시 SeatGrade에서 가져온 가격을 함께 저장 */
export interface SelectedSeat extends Seat {
  price: number
}

interface ReservationState {
  selectedSeats: SelectedSeat[]
  scheduleId: string | null
  totalPrice: number

  addSeat: (seat: SelectedSeat) => void
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
      if (state.selectedSeats.length >= 4) return state
      // 중복 좌석 방지
      if (state.selectedSeats.some((s) => s.id === seat.id)) return state

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

  setScheduleId: (id) => set({ scheduleId: id, selectedSeats: [], totalPrice: 0 }),
}))
