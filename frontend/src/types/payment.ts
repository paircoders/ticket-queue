export interface CreatePaymentRequest {
  reservationId: string
  amount: number
  paymentMethod: string
}

export interface CreatePaymentResponse {
  paymentId: string
  amount: number
  storeId: string
  channelKey: string
  paymentKey: string
}

export interface ConfirmPaymentRequest {
  reservationId: string
  paymentId: string
  paymentKey: string
  transactionId: string
  amount: number
}

export interface ConfirmPaymentResponse {
  paymentId: string
  status: string
  paidAt: string
}

export interface PaymentSummary {
  paymentId: string
  reservationId: string
  amount: number
  status: string
  method: string
  paidAt: string
}

export interface PaymentDetail {
  paymentId: string
  reservationId: string
  amount: number
  status: string
  method: string
  cardName: string
  cardNumber: string
}
