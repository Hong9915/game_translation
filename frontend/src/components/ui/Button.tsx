import type { ButtonHTMLAttributes } from 'react'

type Variant = 'primary' | 'secondary' | 'purple' | 'danger'

const styles: Record<Variant, string> = {
  primary: 'bg-primary text-white hover:bg-[#1a4084] disabled:bg-[#c5cfe0]',
  secondary: 'bg-white text-ink border border-line hover:bg-canvas disabled:text-muted disabled:bg-white',
  purple: 'bg-purple text-white hover:bg-[#5b3489] disabled:bg-[#d6c8e8]',
  danger: 'bg-[#c0392b] text-white hover:bg-[#a93226] disabled:bg-[#e6b0aa]',
}

export function Button({ variant = 'secondary', className = '', ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type="button"
      className={`inline-flex items-center press rounded-lg px-3.5 py-2 text-sm font-medium leading-normal disabled:cursor-not-allowed ${styles[variant]} ${className}`}
      {...rest}
    />
  )
}
