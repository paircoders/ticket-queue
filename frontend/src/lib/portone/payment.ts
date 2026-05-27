import PortOne from '@portone/browser-sdk/v2'

export interface RequestPaymentParams {
  storeId: string
  channelKey: string
  paymentId: string
  orderName: string
  totalAmount: number
  customerEmail?: string
  customerName?: string
  customerPhone?: string
}

export interface RequestPaymentResult {
  paymentId: string
  transactionId: string
}

export class PaymentCancelledError extends Error {
  constructor() {
    super('결제가 취소되었습니다.')
    this.name = 'PaymentCancelledError'
  }
}

export class PaymentFailedError extends Error {
  readonly code?: string

  constructor(message: string, code?: string) {
    super(message)
    this.name = 'PaymentFailedError'
    this.code = code
  }
}

export async function requestPortOnePayment(
  params: RequestPaymentParams
): Promise<RequestPaymentResult> {
  const response = await PortOne.requestPayment({
    storeId: params.storeId,
    channelKey: params.channelKey,
    paymentId: params.paymentId,
    orderName: params.orderName,
    totalAmount: params.totalAmount,
    currency: 'CURRENCY_KRW',
    payMethod: 'CARD',
    customer: {
      email: params.customerEmail,
      fullName: params.customerName,
      phoneNumber: params.customerPhone,
    },
  })

  if (response?.code !== undefined) {
    if (response.code === 'USER_CANCEL') {
      throw new PaymentCancelledError()
    }
    throw new PaymentFailedError(
      response.message ?? '결제에 실패했습니다.',
      response.code
    )
  }

  if (!response?.paymentId || !response.txId) {
    throw new PaymentFailedError('결제 응답이 올바르지 않습니다.')
  }

  return {
    paymentId: response.paymentId,
    transactionId: response.txId,
  }
}
