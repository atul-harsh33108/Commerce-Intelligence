import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { brl, useCart } from '../state'

/*
 * One product, assembled from two services.
 *
 * The description comes from catalog-service and the availability from inventory-service, and they
 * are fetched separately on purpose - it is the same boundary the backend enforces. Availability is
 * also allowed to fail without taking the page with it: if inventory-service is down, a shopper
 * should still see the product, and the "add to cart" button should still work, because stock is
 * decided at checkout by a reservation and not by what this page displayed.
 */
export default function Product() {
  const { productKey } = useParams()
  const navigate = useNavigate()
  const { add } = useCart()

  const [product, setProduct] = useState(null)
  const [stock, setStock] = useState(null)
  const [stockError, setStockError] = useState(null)
  const [error, setError] = useState(null)
  const [quantity, setQuantity] = useState(1)

  useEffect(() => {
    api.productByKey(productKey).then(setProduct).catch((e) => setError(e.message))
    api.inventory(productKey).then(setStock).catch((e) => setStockError(e.message))
  }, [productKey])

  if (error) return <p className="error">{error}</p>
  if (!product) return <p className="muted">Loading…</p>

  return (
    <div className="detail">
      <button className="linkish" onClick={() => navigate(-1)}>← back</button>

      <h2>{product.name || product.category.replace(/_/g, ' ')}</h2>
      <div className="card-cat">{product.category?.replace(/_/g, ' ')}</div>

      <dl className="spec">
        <dt>Price</dt><dd className="price">{brl(product.price)}</dd>
        <dt>Product key</dt><dd className="mono">{product.productKey}</dd>
        {product.weightGrams != null && <><dt>Weight</dt><dd>{product.weightGrams} g</dd></>}
        {product.photosQty != null && <><dt>Photos</dt><dd>{product.photosQty}</dd></>}
        <dt>Availability</dt>
        <dd>
          {stock
            ? <>{stock.available} available <span className="muted">({stock.reserved} reserved of {stock.onHand} on hand)</span></>
            : stockError
              ? <span className="muted">inventory-service did not answer — stock is decided at checkout anyway</span>
              : <span className="muted">checking…</span>}
        </dd>
      </dl>

      <div className="buyrow">
        <input
          type="number"
          min="1"
          max="100"
          value={quantity}
          onChange={(e) => setQuantity(Math.max(1, Math.min(100, Number(e.target.value) || 1)))}
          aria-label="Quantity"
        />
        <button className="btn" onClick={() => { add(product, quantity); navigate('/cart') }}>
          Add to cart
        </button>
      </div>
    </div>
  )
}
