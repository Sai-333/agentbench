import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  ACCESS_EXPIRES_AT_KEY,
  ACCESS_TOKEN_KEY,
  ACCESS_TOKEN_REFRESH_LEAD_MS,
  FIRST_VISIT_SIGN_IN_COPY,
  REFRESH_PATH,
  REFRESH_TOKEN_KEY,
  createMemoryStore,
  createSessionClient,
  isSessionEndedError,
  signInCopy,
  type IssuedTokens,
  type SessionFetch,
} from './session.ts'

const ISSUED_AT = 1_700_000_000_000

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function tokens(accessToken: string, refreshToken: string, expiresInSeconds = 900): IssuedTokens {
  return { accessToken, refreshToken, expiresInSeconds, tokenType: 'Bearer' }
}

function unauthorized(): Response {
  return json(
    {
      title: 'AUTH_UNAUTHORIZED',
      status: 401,
      detail: 'Authentication required',
      code: 'AUTH_UNAUTHORIZED',
    },
    401,
  )
}

async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

test('storeTokens keeps the access token, refresh token, and expiry', () => {
  const store = createMemoryStore()
  const client = createSessionClient({ store, now: () => ISSUED_AT })
  client.storeTokens(tokens('access-1', 'refresh-1', 900))
  assert.equal(store.getItem(ACCESS_TOKEN_KEY), 'access-1')
  assert.equal(store.getItem(REFRESH_TOKEN_KEY), 'refresh-1')
  assert.equal(store.getItem(ACCESS_EXPIRES_AT_KEY), String(ISSUED_AT + 900_000))
  assert.equal(client.getAccessExpiresAt(), ISSUED_AT + 900_000)
})

test('the session refreshes before the access token expires and the next call uses the new token', async () => {
  const store = createMemoryStore()
  let nowMs = ISSUED_AT
  const calls: Array<{ path: string; authorization: string | null; body: string | null }> = []
  const scheduled: Array<{ delay: number; run: () => void }> = []
  const fetchImpl: SessionFetch = async (path, init) => {
    const headers = new Headers(init.headers)
    calls.push({
      path,
      authorization: headers.get('Authorization'),
      body: typeof init.body === 'string' ? init.body : null,
    })
    if (path === REFRESH_PATH) {
      assert.equal(init.method, 'POST')
      assert.equal(headers.get('Content-Type'), 'application/json')
      assert.equal(headers.get('Authorization'), null)
      assert.deepEqual(JSON.parse(String(init.body)), { refreshToken: 'refresh-1' })
      return json(tokens('access-2', 'refresh-2', 900))
    }
    return json({ ok: true })
  }
  const client = createSessionClient({
    store,
    now: () => nowMs,
    fetchImpl,
    schedule: (run, delay) => {
      scheduled.push({ delay, run })
      return () => {}
    },
  })
  client.storeTokens(tokens('access-1', 'refresh-1', 900))
  const stop = client.startKeeper()
  assert.equal(scheduled.length, 1)
  assert.equal(scheduled[0].delay, 900_000 - ACCESS_TOKEN_REFRESH_LEAD_MS)
  assert.ok(scheduled[0].delay < 900_000)
  assert.equal(calls.length, 0)

  nowMs += scheduled[0].delay
  scheduled[0].run()
  await flush()

  assert.equal(calls.length, 1)
  assert.equal(calls[0].path, REFRESH_PATH)
  assert.equal(client.getAccessToken(), 'access-2')
  assert.equal(client.getRefreshToken(), 'refresh-2')

  const body = await client.api<{ ok: boolean }>('/api/v1/orgs')
  assert.equal(body.ok, true)
  assert.equal(calls.at(-1)?.path, '/api/v1/orgs')
  assert.equal(calls.at(-1)?.authorization, 'Bearer access-2')
  stop()
})

test('one refresh is in flight and the next refresh uses the rotated token', async () => {
  const store = createMemoryStore()
  const sent: string[] = []
  let refreshCount = 0
  let releaseFirst: (response: Response) => void = () => {}
  const fetchImpl: SessionFetch = async (path, init) => {
    assert.equal(path, REFRESH_PATH)
    refreshCount += 1
    sent.push(JSON.parse(String(init.body)).refreshToken as string)
    if (refreshCount === 1) {
      return new Promise((resolve) => {
        releaseFirst = resolve
      })
    }
    return json(tokens('access-3', 'refresh-3', 900))
  }
  const client = createSessionClient({ store, now: () => ISSUED_AT, fetchImpl })
  client.storeTokens(tokens('access-1', 'refresh-1', 900))

  const first = client.refreshSession()
  const second = client.refreshSession()
  assert.equal(refreshCount, 1)

  releaseFirst(json(tokens('access-2', 'refresh-2', 900)))
  const [left, right] = await Promise.all([first, second])
  assert.equal(left.refreshToken, 'refresh-2')
  assert.equal(right.refreshToken, 'refresh-2')
  assert.equal(client.getRefreshToken(), 'refresh-2')

  const third = await client.refreshSession()
  assert.equal(third.refreshToken, 'refresh-3')
  assert.deepEqual(sent, ['refresh-1', 'refresh-2'])
  assert.equal(refreshCount, 2)
})

test('parallel calls that fail on the old access token share one refresh', async () => {
  const store = createMemoryStore()
  let refreshCount = 0
  let release: (response: Response) => void = () => {}
  const authorizations: string[] = []
  const fetchImpl: SessionFetch = async (path, init) => {
    const authorization = new Headers(init.headers).get('Authorization') ?? ''
    if (path === REFRESH_PATH) {
      refreshCount += 1
      return new Promise((resolve) => {
        release = resolve
      })
    }
    authorizations.push(authorization)
    if (authorization === 'Bearer access-2') return json({ ok: true })
    return unauthorized()
  }
  const client = createSessionClient({ store, now: () => ISSUED_AT, fetchImpl })
  client.storeTokens(tokens('access-1', 'refresh-1', 900))

  const me = client.api('/api/v1/me')
  const orgs = client.api('/api/v1/orgs')
  await flush()
  assert.equal(refreshCount, 1)

  release(json(tokens('access-2', 'refresh-2', 900)))
  await Promise.all([me, orgs])
  assert.equal(refreshCount, 1)
  assert.equal(client.getRefreshToken(), 'refresh-2')
  assert.ok(authorizations.filter((value) => value === 'Bearer access-2').length >= 2)
})

test('a rejected, expired, or revoked refresh token ends the session', async () => {
  const store = createMemoryStore()
  let ended = 0
  const client = createSessionClient({
    store,
    now: () => ISSUED_AT,
    fetchImpl: async () =>
      json({ title: 'AUTH_UNAUTHORIZED', detail: 'Invalid refresh token' }, 401),
  })
  client.onSessionEnded(() => {
    ended += 1
  })
  client.storeTokens(tokens('access-1', 'refresh-1', 900))

  await assert.rejects(client.refreshSession(), (err: unknown) => {
    assert.equal(isSessionEndedError(err), true)
    assert.equal(err instanceof Error ? err.message : '', 'Your session ended. Sign in again to continue.')
    return true
  })
  assert.equal(ended, 1)
  assert.equal(client.getAccessToken(), null)
  assert.equal(client.getRefreshToken(), null)
  assert.equal(store.getItem(ACCESS_EXPIRES_AT_KEY), null)
  assert.equal(signInCopy(true), 'Your session ended. Sign in again to continue.')
  assert.equal(signInCopy(false), FIRST_VISIT_SIGN_IN_COPY)
  assert.notEqual(signInCopy(true), signInCopy(false))
})

test('a server error on refresh keeps the refresh token', async () => {
  const store = createMemoryStore()
  let ended = 0
  const client = createSessionClient({
    store,
    now: () => ISSUED_AT,
    fetchImpl: async () => json({ detail: 'unavailable' }, 503),
  })
  client.onSessionEnded(() => {
    ended += 1
  })
  client.storeTokens(tokens('access-1', 'refresh-1', 900))
  await assert.rejects(client.refreshSession(), /unavailable/)
  assert.equal(ended, 0)
  assert.equal(client.getRefreshToken(), 'refresh-1')
  assert.equal(client.getAccessToken(), 'access-1')
})

test('a workspace load that fails on an expired access token retries after refresh', async () => {
  const store = createMemoryStore()
  const meAuth: string[] = []
  const fetchImpl: SessionFetch = async (path, init) => {
    const authorization = new Headers(init.headers).get('Authorization')
    if (path === REFRESH_PATH) {
      assert.deepEqual(JSON.parse(String(init.body)), { refreshToken: 'refresh-1' })
      return json(tokens('access-2', 'refresh-2', 900))
    }
    if (path === '/api/v1/me') {
      meAuth.push(authorization ?? '')
      if (authorization !== 'Bearer access-2') return unauthorized()
      return json({
        id: 'user-1',
        email: 'ada@example.com',
        displayName: 'Ada',
        memberships: [],
      })
    }
    return json({ detail: 'unexpected' }, 500)
  }
  const client = createSessionClient({ store, now: () => ISSUED_AT, fetchImpl })
  client.storeTokens(tokens('access-1', 'refresh-1', 900))

  const me = await client.api<{ email: string }>('/api/v1/me')
  assert.equal(me.email, 'ada@example.com')
  assert.deepEqual(meAuth, ['Bearer access-1', 'Bearer access-2'])
  assert.equal(client.getAccessToken(), 'access-2')
  assert.equal(client.getRefreshToken(), 'refresh-2')
})

test('an already expired access token is renewed before the workspace request', async () => {
  const store = createMemoryStore()
  let nowMs = ISSUED_AT
  const meAuth: string[] = []
  const fetchImpl: SessionFetch = async (path, init) => {
    if (path === REFRESH_PATH) return json(tokens('access-2', 'refresh-2', 900))
    meAuth.push(new Headers(init.headers).get('Authorization') ?? '')
    return json({ email: 'ada@example.com' })
  }
  const client = createSessionClient({ store, now: () => nowMs, fetchImpl })
  client.storeTokens(tokens('access-1', 'refresh-1', 900))
  nowMs += 900_000

  const me = await client.api<{ email: string }>('/api/v1/me')
  assert.equal(me.email, 'ada@example.com')
  assert.deepEqual(meAuth, ['Bearer access-2'])
  assert.equal(client.getRefreshToken(), 'refresh-2')
})

test('clearing the session during an in-flight refresh does not restore tokens', async () => {
  const store = createMemoryStore()
  let release: (response: Response) => void = () => {}
  const client = createSessionClient({
    store,
    now: () => ISSUED_AT,
    fetchImpl: async () =>
      new Promise((resolve) => {
        release = resolve
      }),
  })
  client.storeTokens(tokens('access-1', 'refresh-1', 900))
  const pending = client.refreshSession()
  client.clearTokens()
  release(json(tokens('access-2', 'refresh-2', 900)))
  await assert.rejects(pending, /Signed out/)
  assert.equal(client.getAccessToken(), null)
  assert.equal(client.getRefreshToken(), null)
})
