import type { ReactNode } from 'react'

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5 text-xs text-muted">
      <span>{label}</span>
      {children}
    </label>
  )
}

export const inputClass = 'w-full rounded-lg border border-line bg-white px-3 py-2 text-sm text-ink outline-none transition-shadow focus:border-primary focus:ring-4 focus:ring-primary/10'
