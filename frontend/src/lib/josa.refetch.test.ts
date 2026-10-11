import { describe, expect, it } from 'vitest'
import { josaRefetchInterval } from './josa'

describe('josaRefetchInterval', () => {
  it('polls the summary while rows are waiting for the josa check, and stops after', () => {
    expect(josaRefetchInterval({ josaPending: 120 })).toBe(15_000)
    expect(josaRefetchInterval({ josaPending: 0 })).toBe(false)
    expect(josaRefetchInterval(undefined)).toBe(false)
  })
})
