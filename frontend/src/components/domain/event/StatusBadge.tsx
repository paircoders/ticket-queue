import { Badge } from '@/components/ui/badge'

type BadgeVariant = 'default' | 'secondary' | 'outline' | 'success' | 'warning' | 'danger'

const STATUS_MAP: Record<string, { label: string; variant: BadgeVariant }> = {
  OPEN: { label: '예매중', variant: 'success' },
  PREPARING: { label: '준비중', variant: 'warning' },
  ENDED: { label: '종료', variant: 'secondary' },
  CANCELLED: { label: '취소', variant: 'danger' },
}

export function StatusBadge({ status }: { status: string }) {
  const { label, variant } = STATUS_MAP[status] ?? { label: status, variant: 'outline' as BadgeVariant }
  return <Badge variant={variant}>{label}</Badge>
}
