export default async function EventDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params

  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-spacing-xl">
      <div className="max-w-2xl text-center">
        <h1 className="mb-spacing-lg text-4xl font-bold text-gray-900">공연 상세</h1>
        <p className="text-gray-600">
          공연 ID: <span className="font-mono">{id}</span>
        </p>
      </div>
    </main>
  )
}
