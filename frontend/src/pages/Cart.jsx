import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { brl, useAuth, useCart } from '../state'

export default function Cart() {
  const { lines, setQuantity, clear, total } = useCart()
  const { user } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState(null)
  const [placing, setPlacing] = useState(false)

  async function checkout() {
    setPlacing(true)
    setError(null)
    try {
      const order = await api.placeOrder(
        lines.map((l) => ({ productKey: l.productKey, quantity: l.quantity })),
      )
      /*
       * The cart is cleared here, on a 201, and not a moment later.
       *
       * The order comes back PENDING, not CONFIRMED - the saga has not run yet. It is tempting to
       * hold the cart until the order confirms, so that a failed saga leaves the shopper where they
       * were. That is wrong: the order EXISTS and is durable the instant this returns, so keeping
       * the cart invites a second identical order from a shopper who thinks the first did not
       * happen. Cancellation is the recovery path for a failed order, not an un-cleared cart.
       */
      clear()
      navigate(`/orders/${order.orderKey}`)
    } catch (e) {
      setError(e.message)
    } finally {
      setPlacing(false)
    }
  }

  if (lines.length === 0) {
    return (
      <div>
        <h2>Cart</h2>
        <p className="muted">Empty. <Link to="/">Find something</Link>.</p>
      </div>
    )
  }

  return (
    <div>
      <h2>Cart</h2>
      <table className="table">
        <thead>
          <tr><th>Product</th><th>Category</th><th className="r">Price</th><th className="r">Qty</th><th className="r">Line</th></tr>
        </thead>
        <tbody>
          {lines.map((l) => (
            <tr key={l.productKey}>
              <td>
                <Link to={`/product/${l.productKey}`}>{l.name || l.productKey}</Link>
                <div className="mono tiny">{l.productKey}</div>
              </td>
              <td>{l.category?.replace(/_/g, ' ')}</td>
              <td className="r">{brl(l.price)}</td>
              <td className="r">
                <input
                  className="qty"
                  type="number"
                  min="0"
                  max="100"
                  value={l.quantity}
                  onChange={(e) => setQuantity(l.productKey, Number(e.target.value))}
                  aria-label={`Quantity for ${l.productKey}`}
                />
              </td>
              <td className="r">{brl(Number(l.price) * l.quantity)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr><td colSpan="4" className="r"><strong>Total</strong></td><td className="r"><strong>{brl(total)}</strong></td></tr>
        </tfoot>
      </table>

      <p className="muted tiny">
        Freight is added per line by order-service from the seller's own rate — the total above is
        the goods only.
      </p>

      {error && <p className="error">{error}</p>}

      {user ? (
        <button className="btn big" onClick={checkout} disabled={placing}>
          {placing ? 'Placing…' : `Place order · ${brl(total)}`}
        </button>
      ) : (
        <p className="muted">
          <Link to="/login">Sign in</Link> to check out. The cart survives — it is in localStorage,
          not on the server.
        </p>
      )}
    </div>
  )
}
