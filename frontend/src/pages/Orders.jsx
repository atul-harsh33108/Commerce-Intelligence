import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { brl } from '../state'

/*
 * The stage in the saga, in the order it happens. Used to render the tracker below.
 *
 * CANCELED is not in this list because it is not a later stage - it is the path the saga takes when
 * a step fails, and drawing it as "further along than CONFIRMED" would be a lie about the model.
 */
const STAGES = ['PENDING', 'AWAITING_PAYMENT', 'CONFIRMED']

/*
 * One order, watched while it settles.
 *
 * This page is the only place in the storefront where the platform's asynchrony is visible to a
 * shopper, and showing it rather than hiding it is the point. `POST /api/orders` returns 201 with
 * status PENDING in about 39 ms; the order becomes CONFIRMED roughly 1.5 s later, after
 * inventory-service reserves the stock and payment-service authorises the charge, each reacting to
 * an event. A UI that waited for CONFIRMED before rendering would throw away the fast commit that
 * the outbox was built to provide, and one that rendered PENDING and never updated would look broken.
 *
 * So it polls. Polling is not the sophisticated answer - the sophisticated answer is a WebSocket or
 * an SSE endpoint on order-service pushing the transition - but it is the honest one for a saga that
 * settles in about a second and a half: two seconds of 400 ms polls, then stop. A push channel would
 * mean order-service holding a connection per shopper for the ~1.5 s it takes, which is real
 * infrastructure to save six requests.
 */
export default function OrderDetail() {
  const { orderKey } = useParams()
  const [order, setOrder] = useState(null)
  const [error, setError] = useState(null)
  const [polls, setPolls] = useState(0)
  const settledAt = useRef(null)
  const startedAt = useRef(performance.now())

  useEffect(() => {
    let stop = false
    let timer

    async function tick() {
      try {
        const o = await api.order(orderKey)
        if (stop) return
        setOrder(o)
        setPolls((n) => n + 1)

        // Terminal states: nothing else will happen without a human, so stop asking. A poll loop
        // that keeps running on a settled order is a background request every 400 ms for as long as
        // the tab is open.
        if (o.status === 'CONFIRMED' || o.status === 'CANCELED' || o.status === 'DELIVERED') {
          if (settledAt.current === null) {
            settledAt.current = Math.round(performance.now() - startedAt.current)
          }
          return
        }
        timer = setTimeout(tick, 400)
      } catch (e) {
        if (!stop) setError(e.message)
      }
    }

    tick()
    return () => { stop = true; clearTimeout(timer) }
  }, [orderKey])

  if (error) return <p className="error">{error}</p>
  if (!order) return <p className="muted">Loading…</p>

  const canceled = order.status === 'CANCELED'
  const reached = STAGES.indexOf(order.status)

  return (
    <div>
      <h2>Order <span className="mono">{order.orderKey}</span></h2>

      <div className={`tracker ${canceled ? 'tracker-failed' : ''}`}>
        {STAGES.map((stage, i) => (
          <div
            key={stage}
            className={`stage ${i <= reached && !canceled ? 'stage-done' : ''} ${order.status === stage ? 'stage-now' : ''}`}
          >
            <span className="dot" />
            {stage.replace(/_/g, ' ').toLowerCase()}
          </div>
        ))}
        {canceled && <div className="stage stage-failed"><span className="dot" />canceled</div>}
      </div>

      {order.status === 'PENDING' && (
        <p className="muted">
          Committed and announced. Inventory and payment are reacting to the event now — this page is
          polling, it will update itself.
        </p>
      )}
      {settledAt.current !== null && (
        <p className="muted">
          Settled in <strong>{settledAt.current} ms</strong> from opening this page, over {polls} polls.
        </p>
      )}
      {canceled && (
        <p className="error">
          Compensated: {order.failureReason || 'a saga step failed'}. Any stock that was reserved has
          been released — <code>on_hand</code> never moved.
        </p>
      )}

      <table className="table">
        <thead>
          <tr><th>#</th><th>Product</th><th className="r">Qty</th><th className="r">Unit</th><th className="r">Freight</th><th className="r">Line</th></tr>
        </thead>
        <tbody>
          {order.items?.map((i) => (
            <tr key={i.lineNo}>
              <td>{i.lineNo}</td>
              <td><Link to={`/product/${i.productKey}`} className="mono tiny">{i.productKey}</Link></td>
              <td className="r">{i.quantity}</td>
              <td className="r">{brl(i.unitPrice)}</td>
              <td className="r">{brl(i.freightAmount)}</td>
              <td className="r">{brl(i.lineTotal)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr><td colSpan="5" className="r"><strong>Total</strong></td><td className="r"><strong>{brl(order.totalAmount)}</strong></td></tr>
        </tfoot>
      </table>

      <p><Link to="/orders">← all orders</Link></p>
    </div>
  )
}

export function OrderList() {
  const [orders, setOrders] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.myOrders().then(setOrders).catch((e) => setError(e.message))
  }, [])

  if (error) return <p className="error">{error}</p>
  if (!orders) return <p className="muted">Loading…</p>
  if (orders.length === 0) return <p className="muted">No orders yet.</p>

  return (
    <div>
      <h2>Your orders</h2>
      <table className="table">
        <thead><tr><th>Order</th><th>Status</th><th className="r">Total</th><th>Placed</th></tr></thead>
        <tbody>
          {orders.map((o) => (
            <tr key={o.orderKey}>
              <td><Link to={`/orders/${o.orderKey}`} className="mono tiny">{o.orderKey}</Link></td>
              <td><span className={`badge badge-${o.status.toLowerCase()}`}>{o.status}</span></td>
              <td className="r">{brl(o.totalAmount)}</td>
              <td className="muted tiny">{o.purchasedAt ? new Date(o.purchasedAt).toLocaleString() : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
