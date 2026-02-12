import Link from 'next/link'

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen flex items-center justify-center bg-muted px-4 py-8">
      <div className="w-full max-w-2xl min-w-[320px] space-y-8">
        <div className="text-center">
          <Link href="/">
            <h1 className="text-3xl font-bold text-foreground cursor-pointer hover:opacity-80 transition-opacity">
              Ticket Queue
            </h1>
          </Link>
        </div>
        <div className="w-full">
          {children}
        </div>
      </div>
    </div>
  )
}
