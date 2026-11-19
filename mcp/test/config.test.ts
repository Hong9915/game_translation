import { describe, it, expect } from 'vitest'
import { loadConfig } from '../src/config.js'

const ok = { PUBLIC_URL: 'https://mcp.example.com', MCP_TOKEN_SECRET: 'x'.repeat(32) }

describe('loadConfig', () => {
  it('requiresSecretOf32Chars', () => {
    expect(() => loadConfig({ ...ok, MCP_TOKEN_SECRET: 'x'.repeat(31) })).toThrow()
    expect(() => loadConfig({ PUBLIC_URL: ok.PUBLIC_URL })).toThrow()
  })
  it('requiresPublicUrl', () => {
    expect(() => loadConfig({ MCP_TOKEN_SECRET: ok.MCP_TOKEN_SECRET })).toThrow()
  })
  it('defaults', () => {
    const c = loadConfig(ok)
    expect(c.port).toBe(3000)
    expect(c.backendUrl).toBe('http://backend:8080')
    expect(c.publicUrl).toBe('https://mcp.example.com')
    expect(c.tokenSecret).toBe(ok.MCP_TOKEN_SECRET)
  })
  it('stripsTrailingSlashAndReadsOverrides', () => {
    const c = loadConfig({ ...ok, PUBLIC_URL: 'https://mcp.example.com/', PORT: '4000', BACKEND_URL: 'http://x:1/' })
    expect(c.publicUrl).toBe('https://mcp.example.com')
    expect(c.port).toBe(4000)
    expect(c.backendUrl).toBe('http://x:1')
  })
})
