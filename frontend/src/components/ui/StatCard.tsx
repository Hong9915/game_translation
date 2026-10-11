import type { ReactNode } from 'react'

type Accent = 'ink' | 'muted' | 'green' | 'purple' | 'orange'
const color: Record<Accent, string> = { ink: 'text-ink', muted: 'text-muted', green: 'text-green', purple: 'text-purple', orange: 'text-orange' }

export function StatCard({ value, label, accent = 'ink', children }: { value: string | number; label: string; accent?: Accent; children?: ReactNode }) {
  return (
    <div className="flex flex-col rounded-lg border border-line bg-white px-3.5 py-3">
      <span className={`text-[22px] font-semibold leading-tight ${color[accent]}`}>{typeof value === 'number' ? value.toLocaleString() : value}</span>
      <span className="text-xs text-muted">{label}</span>
      {children}
    </div>
  )
}
