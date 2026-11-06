import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../state'

export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [mode, setMode] = useState('login')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mode === 'register') await api.register(username, email, password)
      await login(username, password)
      navigate(location.state?.from ?? '/')
    } catch (err) {
      /*
       * 429 deserves its own message. /api/auth is the only route the gateway rate-limits (5/s
       * sustained, 10 burst, a Lua token bucket in Redis), and the default rendering of a 429 is an
       * empty body - so without this the user sees "HTTP 429" and concludes the site is broken
       * rather than that they are being throttled.
       */
      setError(err.status === 429
        ? 'Too many attempts — the gateway is rate-limiting this route. Wait a second and retry.'
        : err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="auth" onSubmit={submit}>
      <h2>{mode === 'login' ? 'Sign in' : 'Create an account'}</h2>

      <label>
        Username
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
      </label>

      {mode === 'register' && (
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
      )}

      <label>
        Password
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
          required
        />
      </label>

      {error && <p className="error">{error}</p>}

      <button className="btn big" disabled={busy}>
        {busy ? 'Working…' : mode === 'login' ? 'Sign in' : 'Register and sign in'}
      </button>

      <button
        type="button"
        className="linkish"
        onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null) }}
      >
        {mode === 'login' ? 'No account? Register' : 'Have an account? Sign in'}
      </button>

      <p className="muted tiny">
        Registration creates a CUSTOMER. The analytics page needs ADMIN — see
        <code> scripts/create_admin.py</code>.
      </p>
    </form>
  )
}
