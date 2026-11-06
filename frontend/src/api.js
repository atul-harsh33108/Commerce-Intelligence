/*
 * Every network call the SPA makes, in one file.
 *
 * The base URL is the GATEWAY and never a service. The services are still listening on 8081-8086 in
 * development and calling one directly would work, which is precisely why this is stated rather than
 * assumed: the gateway is where authentication, rate limiting and CORS live, so a page that bypasses
 * it is testing a system nobody deploys.
 */

export const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080'
const BASE = API_BASE

const TOKEN_KEY = 'ecommerce.token'

/*
 * The token is in localStorage, and that is a real trade rather than a default.
 *
 * localStorage is readable by any script that runs on this origin, so a successful XSS reads the
 * token. The usual answer - an httpOnly cookie - is not available here for a specific reason: the
 * gateway sets `allow-credentials: false` and authenticates from an `Authorization` header, and a
 * cookie the browser attaches automatically is exactly what makes CSRF possible and what would force
 * a CSRF token onto every write. Cookies would also require the origin list to become a security
 * boundary rather than a convenience.
 *
 * The honest production answer is neither: a backend-for-frontend that holds the token server-side
 * and hands the browser a session. That is a component this project does not have, so the trade is
 * written down instead of hidden. What follows from it is that the token is short-lived (see
 * `expiresIn` on the login response) and that nothing else is ever put in localStorage.
 */
export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

export class ApiError extends Error {
  constructor(status, body) {
    // The platform's error bodies are {message, error, path, ...}. Preferring `message` over a
    // generic string is what makes a 400 from bean validation readable in the UI instead of "400".
    super(body?.message || body?.error || `HTTP ${status}`)
    this.status = status
    this.body = body
  }
}

async function request(path, { method = 'GET', body, auth = true } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const token = tokenStore.get()
  if (auth && token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (res.status === 204) return null

  const text = await res.text()
  const parsed = text ? safeJson(text) : null

  if (!res.ok) {
    // A 401 on any call means the token is gone or expired. Clearing it here rather than in each
    // caller is what stops the app from sitting in a state where it believes it is logged in and
    // every request fails.
    if (res.status === 401) tokenStore.clear()
    throw new ApiError(res.status, parsed)
  }
  return parsed
}

function safeJson(text) {
  try {
    return JSON.parse(text)
  } catch {
    return { message: text.slice(0, 300) }
  }
}

export const api = {
  // ---- auth -------------------------------------------------------------------------------------
  login: (username, password) =>
    request('/api/auth/login', { method: 'POST', auth: false, body: { username, password } }),
  register: (username, email, password) =>
    request('/api/auth/register', { method: 'POST', auth: false, body: { username, email, password } }),
  me: () => request('/api/users/me'),

  // ---- catalogue and search ---------------------------------------------------------------------
  // `auth: false` on the public reads is not cosmetic. Sending a stale token to a public endpoint
  // would turn a browsing session into a 401 as soon as the token expired, on pages that never
  // needed a token at all.
  search: (params) => request(`/api/search?${new URLSearchParams(params)}`, { auth: false }),
  suggest: (q) => request(`/api/search/suggest?q=${encodeURIComponent(q)}&limit=6`, { auth: false }),
  product: (id) => request(`/api/products/${id}`, { auth: false }),
  productByKey: (key) => request(`/api/products/key/${key}`, { auth: false }),
  products: (page = 0, size = 12) => request(`/api/products?page=${page}&size=${size}`, { auth: false }),
  inventory: (productKey) => request(`/api/inventory/${productKey}`, { auth: false }),

  // ---- orders -----------------------------------------------------------------------------------
  placeOrder: (lines) => request('/api/orders', { method: 'POST', body: { lines } }),
  order: (orderKey) => request(`/api/orders/key/${orderKey}`),
  myOrders: () => request('/api/orders/me'),
}

/*
 * The analytics stream, and the reason it is hand-rolled.
 *
 * The browser has a purpose-built API for server-sent events - `EventSource` - and it cannot be used
 * here: EventSource takes a URL and nothing else. There is no way to set an `Authorization` header
 * on it. The workarounds are to put the JWT in the query string, which writes a credential into
 * every access log and browser history entry, or to authenticate with a cookie, which the gateway
 * deliberately does not do (see tokenStore above).
 *
 * So: `fetch` with a ReadableStream reader, and the SSE framing parsed by hand. It is about thirty
 * lines, and it keeps the credential in a header where it belongs.
 *
 * The parsing detail that matters is the buffer. A network chunk has no relationship to an SSE
 * frame - one chunk can carry three events, or half of one. Splitting each chunk on "\n\n" and
 * processing the pieces would work for most of a run and then silently drop an event under load,
 * which is the kind of bug that shows up as "the answer sometimes doesn't appear". Everything after
 * the last delimiter is therefore kept and prepended to the next chunk.
 */
export async function streamQuery(question, { onEvent, signal }) {
  const res = await fetch(`${BASE}/api/analytics/query`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${tokenStore.get()}`,
    },
    body: JSON.stringify({ query: question }),
    signal,
  })

  if (!res.ok) {
    const text = await res.text()
    throw new ApiError(res.status, safeJson(text))
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    const frames = buffer.split('\n\n')
    buffer = frames.pop()                        // incomplete tail, kept for the next chunk

    for (const frame of frames) {
      for (const line of frame.split('\n')) {
        if (!line.startsWith('data: ')) continue
        try {
          onEvent(JSON.parse(line.slice(6)))
        } catch {
          /* a frame that is not JSON is a keep-alive or a comment; ignoring it is correct */
        }
      }
    }
  }
}
