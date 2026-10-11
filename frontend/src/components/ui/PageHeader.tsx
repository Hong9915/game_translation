import type { ReactNode } from 'react'

export function PageHeader({ title, description, right }: { title: string; description: string; right?: ReactNode }) {
  return (
    <div className="flex items-end justify-between gap-4">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-[22px] font-semibold leading-tight">{title}</h1>
        <p className="max-w-[640px] text-sm text-body">{description}</p>
      </div>
      {right && <div className="flex shrink-0 items-start gap-2">{right}</div>}
    </div>
  )
}
