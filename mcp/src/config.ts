export interface Config {
  publicUrl: string
  backendUrl: string
  tokenSecret: string
  port: number
}

const stripSlash = (s: string) => s.replace(/\/+$/, '')

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const publicUrl = env.PUBLIC_URL?.trim()
  if (!publicUrl) throw new Error('PUBLIC_URL 이 필요합니다.')
  const tokenSecret = env.MCP_TOKEN_SECRET ?? ''
  if (tokenSecret.length < 32) throw new Error('MCP_TOKEN_SECRET 은 32자 이상이어야 합니다.')
  const port = env.PORT ? Number(env.PORT) : 3000
  if (!Number.isInteger(port) || port <= 0) throw new Error('PORT 가 올바르지 않습니다.')
  return {
    publicUrl: stripSlash(publicUrl),
    backendUrl: stripSlash(env.BACKEND_URL?.trim() || 'http://backend:8080'),
    tokenSecret,
    port,
  }
}
