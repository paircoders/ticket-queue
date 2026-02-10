'use client'

export default function PaymentCompletePage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-spacing-xl">
      <div className="max-w-2xl text-center">
        <h1 className="mb-spacing-lg text-4xl font-bold text-gray-900">결제 완료</h1>
        <p className="text-gray-600">결제가 성공적으로 완료되었습니다.</p>
        <p className="mt-spacing-md text-gray-500">
          예매 내역은 마이페이지에서 확인하실 수 있습니다.
        </p>
      </div>
    </main>
  )
}
