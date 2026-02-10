'use client'

export function Header() {
  return (
    <header
      data-slot="header"
      className="h-16 border-b border-border bg-background flex items-center px-6"
    >
      <div className="text-lg font-semibold text-foreground">Ticket Queue</div>
    </header>
  )
}
