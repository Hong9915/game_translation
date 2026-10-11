import { vi } from 'vitest'

export interface MockRoute { method?: string; path: string | RegExp; status?: number; body?: unknown; blob?: Blob }
export interface RecordedCall { method: string; url: string; body: unknown; headers?: Record<string, string> }

export function mockFetch(routes: MockRoute[]) {
  const calls: RecordedCall[] = []
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = null
    if (typeof init?.body === 'string') { try { body = JSON.parse(init.body) } catch { body = init.body } }
    else if (init?.body instanceof FormData) body = Object.fromEntries(init.body.entries())
    const headers = { ...(init?.headers as Record<string, string> | undefined) }
    calls.push({ method, url, body, headers })
    const route = routes.find((r) => (r.method ?? 'GET').toUpperCase() === method && (typeof r.path === 'string' ? url.includes(r.path) : r.path.test(url)))
    if (!route) return new Response(JSON.stringify({ message: `no mock for ${method} ${url}` }), { status: 404 })
    if (route.blob) return new Response(route.blob, { status: 200 })
    if (route.status === 204) return new Response(null, { status: 204 })
    return new Response(JSON.stringify(route.body ?? {}), { status: route.status ?? 200, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fn)
  return { fn, calls }
}
