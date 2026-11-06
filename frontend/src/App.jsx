import { Link, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import Browse from './pages/Browse'
import Product from './pages/Product'
import Cart from './pages/Cart'
import OrderDetail, { OrderList } from './pages/Orders'
import Login from './pages/Login'
import Analytics from './pages/Analytics'
import { useAuth, useCart } from './state'
import { API_BASE } from './api'

/*
 * A route that requires a session.
 *
 * It is a convenience, not a control. The server decides: every one of these pages calls an endpoint
 * that verifies the JWT at the gateway and again in the service. Deleting this component would make
 * the app uglier - unauthenticated users would see a page flash and then a 401 - and no less secure.
 * Worth stating plainly because "protected route" is a name that invites the opposite belief.
 */
function RequiresAuth({ children }) {
  const { user, loading } = useAuth()
  const location = useLocation()
  if (loading) return <p className="muted">…</p>
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return children
}

export default function App() {
  const { user, logout } = useAuth()
  const { count } = useCart()

  return (
    <div className="app">
      <header>
        <Link to="/" className="brand">Olist<span>Store</span></Link>
        <nav>
          <NavLink to="/">Browse</NavLink>
          <NavLink to="/cart">Cart{count > 0 && <span className="pill">{count}</span>}</NavLink>
          {user && <NavLink to="/orders">Orders</NavLink>}
          {user?.role === 'ADMIN' && <NavLink to="/analytics">Analytics</NavLink>}
        </nav>
        <div className="who">
          {user ? (
            <>
              <span className="muted">{user.username} · {user.role}</span>
              <button className="linkish" onClick={logout}>sign out</button>
            </>
          ) : (
            <Link to="/login">Sign in</Link>
          )}
        </div>
      </header>

      <main>
        <Routes>
          <Route path="/" element={<Browse />} />
          <Route path="/product/:productKey" element={<Product />} />
          <Route path="/cart" element={<Cart />} />
          <Route path="/login" element={<Login />} />
          <Route path="/orders" element={<RequiresAuth><OrderList /></RequiresAuth>} />
          <Route path="/orders/:orderKey" element={<RequiresAuth><OrderDetail /></RequiresAuth>} />
          <Route path="/analytics" element={<RequiresAuth><Analytics /></RequiresAuth>} />
          <Route path="*" element={<p className="muted">No such page.</p>} />
        </Routes>
      </main>

      <footer className="muted tiny">
        {/* The actual base, not a hardcoded ":8080". The literal was true on a laptop and wrong the
            moment this was deployed behind a different port - a footer claiming something the page
            was not doing, which is a small version of the thing this project is trying not to do. */}
        Every page here talks to the gateway at <code>{API_BASE}</code> — never to a service
        directly, and with no dev proxy, so the CORS configuration is exercised on every request.
      </footer>
    </div>
  )
}
