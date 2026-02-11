export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gray-50">
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900">Ticket Queue</h1>
      </div>
      <div className="w-full max-w-md">{children}</div>
    </div>
  )
}
