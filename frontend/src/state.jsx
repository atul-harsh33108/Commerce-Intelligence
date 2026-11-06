import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api, tokenStore } from './api'

/*
 * Two contexts: who you are, and what is in your basket. Deliberately no state library.
 *
 * Redux/Zustand earn their place when state is shared by many components in ways that are hard to
 * trace. Here there are two pieces of state, both small, both read by three or four components. A
 * store would be ceremony around a `useState`.
 */

const AuthContext = createContext(null)
const CartContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(Boolean(tokenStore.get()))

  // On a reload the token is in localStorage but the user is not. Rather than decoding the JWT in
  // the browser to find out who it belongs to, ask the server: a token this client cannot verify is
  // a token it should not trust, and /api/users/me answers "is this still valid" at the same time.
  useEffect(() => {
    if (!tokenStore.get()) return
    api
      .me()
      .then(setUser)
      .catch(() => tokenStore.clear())
      .finally(() => setLoading(false))
  }, [])

  const login = useCallback(async (username, password) => {
    const res = await api.login(username, password)
    tokenStore.set(res.token)
    setUser(await api.me())
    return res
  }, [])

  const logout = useCallback(() => {
    tokenStore.clear()
    setUser(null)
  }, [])

  const value = useMemo(() => ({ user, loading, login, logout }), [user, loading, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export const useAuth = () => useContext(AuthContext)

const CART_KEY = 'ecommerce.cart'

export function CartProvider({ children }) {
  const [lines, setLines] = useState(() => {
    try {
      return JSON.parse(localStorage.getItem(CART_KEY)) ?? []
    } catch {
      return []
    }
  })

  useEffect(() => {
    localStorage.setItem(CART_KEY, JSON.stringify(lines))
  }, [lines])

  const add = useCallback((product, quantity = 1) => {
    setLines((prev) => {
      const existing = prev.find((l) => l.productKey === product.productKey)
      if (existing) {
        return prev.map((l) =>
          l.productKey === product.productKey
            // Capped at 100 because order-service caps it at 100 (@Max on PlaceOrderRequest.Line).
            // Enforcing it here as well is not duplication for its own sake: the server rejecting a
            // whole order at checkout because of a quantity chosen five clicks earlier is a bad way
            // to find out. The server's rule is still the one that decides.
            ? { ...l, quantity: Math.min(100, l.quantity + quantity) }
            : l,
        )
      }
      return [...prev, {
        productKey: product.productKey,
        name: product.name,
        category: product.category,
        price: product.price,
        quantity,
      }]
    })
  }, [])

  const setQuantity = useCallback((productKey, quantity) => {
    setLines((prev) =>
      quantity <= 0
        ? prev.filter((l) => l.productKey !== productKey)
        : prev.map((l) => (l.productKey === productKey ? { ...l, quantity } : l)),
    )
  }, [])

  const clear = useCallback(() => setLines([]), [])

  const total = useMemo(
    () => lines.reduce((sum, l) => sum + Number(l.price) * l.quantity, 0),
    [lines],
  )
  const count = useMemo(() => lines.reduce((n, l) => n + l.quantity, 0), [lines])

  const value = useMemo(
    () => ({ lines, add, setQuantity, clear, total, count }),
    [lines, add, setQuantity, clear, total, count],
  )
  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}

export const useCart = () => useContext(CartContext)

// Brazilian reais, because that is the currency in the Olist data and rendering R$ as $ would be a
// quiet lie about what the numbers mean.
export const brl = (n) =>
  new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(Number(n ?? 0))
