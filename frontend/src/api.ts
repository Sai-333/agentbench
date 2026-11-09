const TOKEN_KEY = 'agentbench.accessToken'
const REFRESH_KEY = 'agentbench.refreshToken'

export type TokenResponse = {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInSeconds: number
}

export type MeResponse = {
  id: string
  email: string
  displayName: string
  memberships: Array<{ orgId: string; orgName: string; orgSlug: string; role: string }>
}

export type Org = { id: string; name: string; slug: string; createdAt: string }
export type Project = {
  id: string
  orgId: string
  name: string
  description: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

export function getAccessToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function clearTokens(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
}

export function storeTokens(tokens: TokenResponse): void {
  localStorage.setItem(TOKEN_KEY, tokens.accessToken)
  localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
}

async function readError(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: string; title?: string }
    return body.detail || body.title || `HTTP ${response.status}`
  } catch {
    return `HTTP ${response.status}`
  }
}

export async function api<T>(path: string, options: RequestInit = {}, auth = true): Promise<T> {
  const headers = new Headers(options.headers)
  if (!headers.has('Content-Type') && options.body) {
    headers.set('Content-Type', 'application/json')
  }
  if (auth) {
    const token = getAccessToken()
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
  }
  const response = await fetch(path, { ...options, headers })
  if (!response.ok) {
    throw new Error(await readError(response))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
