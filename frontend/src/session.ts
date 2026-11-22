/**
 * Keeps a signed-in browser session alive across the 900-second access token.
 * POST /api/v1/auth/refresh rotates the refresh token; one refresh runs at a time.
 */

export const ACCESS_TOKEN_KEY = 'agentbench.accessToken'
export const REFRESH_TOKEN_KEY = 'agentbench.refreshToken'
export const ACCESS_EXPIRES_AT_KEY = 'agentbench.accessTokenExpiresAt'
export const REFRESH_PATH = '/api/v1/auth/refresh'

/** Refresh this long before the access token expires. */
export const ACCESS_TOKEN_REFRESH_LEAD_MS = 60_000
export const DEFAULT_ACCESS_TTL_SECONDS = 900

export const FIRST_VISIT_SIGN_IN_COPY =
  'Sign in to manage organizations, benchmarks, and evaluations.'
export const SESSION_ENDED_COPY = 'Your session ended. Sign in again to continue.'

export type IssuedTokens = {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  tokenType?: string
}

export type KeyValueStore = {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
  removeItem: (key: string) => void
}

export type SessionFetch = (path: string, init: RequestInit) => Promise<Response>
export type SessionSchedule = (callback: () => void, delayMs: number) => () => void

const SESSION_ENDED_NAME = 'SessionEndedError'

export function sessionEndedError(): Error {
  const error = new Error(SESSION_ENDED_COPY)
  error.name = SESSION_ENDED_NAME
  return error
}

export function isSessionEndedError(err: unknown): boolean {
  return err instanceof Error && err.name === SESSION_ENDED_NAME
}

export function signInCopy(sessionEnded: boolean): string {
  return sessionEnded ? SESSION_ENDED_COPY : FIRST_VISIT_SIGN_IN_COPY
}

/**
 * A URL fragment that is rejected never became a session. The sign-in screen
 * keeps the first-visit copy. A refresh rejected after a real sign-in says the session ended.
 */
export function sessionEndedForBootstrap(arrivedFromHash: boolean): boolean {
  return !arrivedFromHash
}

export function createMemoryStore(): KeyValueStore {
  const items = new Map<string, string>()
  return {
    getItem: (key) => items.get(key) ?? null,
    setItem: (key, value) => {
      items.set(key, value)
    },
    removeItem: (key) => {
      items.delete(key)
    },
  }
}

export async function readProblemDetail(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: string; title?: string }
    return body.detail || body.title || `HTTP ${response.status}`
  } catch {
    return `HTTP ${response.status}`
  }
}

function defaultSchedule(callback: () => void, delayMs: number): () => void {
  const id = setTimeout(callback, delayMs)
  return () => clearTimeout(id)
}

export function createSessionClient(options: {
  store: KeyValueStore
  fetchImpl?: SessionFetch
  now?: () => number
  schedule?: SessionSchedule
}) {
  const store = options.store
  const fetchImpl: SessionFetch = options.fetchImpl ?? ((path, init) => fetch(path, init))
  const now = options.now ?? (() => Date.now())
  const schedule = options.schedule ?? defaultSchedule

  const storageListeners = new Set<() => void>()
  const endedListeners = new Set<() => void>()
  let refreshInFlight: Promise<IssuedTokens> | null = null
  let epoch = 0

  function notifyStore(): void {
    for (const listener of [...storageListeners]) listener()
  }

  function notifyEnded(): void {
    for (const listener of [...endedListeners]) listener()
  }

  function subscribeStore(listener: () => void): () => void {
    storageListeners.add(listener)
    return () => storageListeners.delete(listener)
  }

  function onSessionEnded(listener: () => void): () => void {
    endedListeners.add(listener)
    return () => endedListeners.delete(listener)
  }

  function getAccessToken(): string | null {
    return store.getItem(ACCESS_TOKEN_KEY)
  }

  function getRefreshToken(): string | null {
    return store.getItem(REFRESH_TOKEN_KEY)
  }

  function getAccessExpiresAt(): number | null {
    const raw = store.getItem(ACCESS_EXPIRES_AT_KEY)
    if (!raw) return null
    const value = Number(raw)
    return Number.isFinite(value) ? value : null
  }

  function storeTokens(tokens: IssuedTokens): IssuedTokens {
    const parsed = Number(tokens.expiresInSeconds)
    const ttl = Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_ACCESS_TTL_SECONDS
    store.setItem(ACCESS_TOKEN_KEY, tokens.accessToken)
    store.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
    store.setItem(ACCESS_EXPIRES_AT_KEY, String(now() + ttl * 1000))
    notifyStore()
    return {
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      expiresInSeconds: ttl,
      tokenType: tokens.tokenType,
    }
  }

  function clearTokens(): void {
    epoch += 1
    store.removeItem(ACCESS_TOKEN_KEY)
    store.removeItem(REFRESH_TOKEN_KEY)
    store.removeItem(ACCESS_EXPIRES_AT_KEY)
    notifyStore()
  }

  function needsRefresh(): boolean {
    if (!getRefreshToken()) return false
    if (!getAccessToken()) return true
    const expiresAt = getAccessExpiresAt()
    if (expiresAt === null) return false
    return now() >= expiresAt - ACCESS_TOKEN_REFRESH_LEAD_MS
  }

  function msUntilRefresh(): number | null {
    if (!getRefreshToken() || !getAccessToken()) return null
    const expiresAt = getAccessExpiresAt()
    if (expiresAt === null) return null
    return Math.max(0, expiresAt - ACCESS_TOKEN_REFRESH_LEAD_MS - now())
  }

  async function refreshSession(): Promise<IssuedTokens> {
    if (refreshInFlight) return refreshInFlight
    const refreshToken = getRefreshToken()
    if (!refreshToken) {
      notifyEnded()
      throw sessionEndedError()
    }
    // Capture the token before the first await. A later refresh reads the
    // rotated value storeTokens saved after this request finishes.
    const ticket = epoch
    const pending = requestRefresh(refreshToken, ticket)
    refreshInFlight = pending
    try {
      return await pending
    } finally {
      if (refreshInFlight === pending) refreshInFlight = null
    }
  }

  async function requestRefresh(refreshToken: string, ticket: number): Promise<IssuedTokens> {
    let response: Response
    try {
      response = await fetchImpl(REFRESH_PATH, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
    } catch (err) {
      if (ticket !== epoch) throw new Error('Signed out')
      throw err instanceof Error ? err : new Error('Could not reach the authentication service')
    }
    if (ticket !== epoch) throw new Error('Signed out')
    if (response.status >= 400 && response.status < 500) {
      clearTokens()
      notifyEnded()
      throw sessionEndedError()
    }
    if (!response.ok) {
      throw new Error(await readProblemDetail(response))
    }
    let tokens: IssuedTokens
    try {
      tokens = (await response.json()) as IssuedTokens
    } catch {
      throw new Error('Could not read the refreshed session')
    }
    if (ticket !== epoch) throw new Error('Signed out')
    if (!tokens.accessToken || !tokens.refreshToken) {
      clearTokens()
      notifyEnded()
      throw sessionEndedError()
    }
    return storeTokens(tokens)
  }

  async function send(path: string, options: RequestInit, auth: boolean): Promise<Response> {
    const headers = new Headers(options.headers)
    if (!headers.has('Content-Type') && options.body) {
      headers.set('Content-Type', 'application/json')
    }
    if (auth) {
      const token = getAccessToken()
      if (token) headers.set('Authorization', `Bearer ${token}`)
    }
    return fetchImpl(path, { ...options, headers })
  }

  async function authorizedFetch(path: string, options: RequestInit = {}, auth = true): Promise<Response> {
    let refreshRejected = false
    if (auth && needsRefresh()) {
      try {
        await refreshSession()
      } catch (err) {
        if (!isSessionEndedError(err)) throw err
        refreshRejected = true
      }
    }

    const response = await send(path, options, auth)
    if (response.status === 401 && auth && !refreshRejected && getRefreshToken()) {
      await refreshSession()
      return send(path, options, auth)
    }
    if (response.status === 401 && auth && refreshRejected) {
      throw sessionEndedError()
    }
    return response
  }

  async function api<T>(path: string, options: RequestInit = {}, auth = true): Promise<T> {
    const response = await authorizedFetch(path, options, auth)
    if (!response.ok) {
      throw new Error(await readProblemDetail(response))
    }
    if (response.status === 204) {
      return undefined as T
    }
    return (await response.json()) as T
  }

  function startKeeper(): () => void {
    let cancelTimer = () => {}
    let stopped = false
    let arming = false
    const arm = () => {
      if (arming) return
      arming = true
      try {
        cancelTimer()
        cancelTimer = () => {}
        if (stopped) return
        const delay = msUntilRefresh()
        if (delay === null) return
        cancelTimer = schedule(() => {
          void refreshSession().catch(() => {})
        }, delay)
      } finally {
        arming = false
      }
    }
    const unsubscribe = subscribeStore(arm)
    arm()
    return () => {
      stopped = true
      unsubscribe()
      cancelTimer()
    }
  }

  return {
    getAccessToken,
    getRefreshToken,
    getAccessExpiresAt,
    storeTokens,
    clearTokens,
    refreshSession,
    authorizedFetch,
    api,
    startKeeper,
    onSessionEnded,
    needsRefresh,
    msUntilRefresh,
  }
}
