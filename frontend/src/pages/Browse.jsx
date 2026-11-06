import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { brl, useCart } from '../state'

/*
 * The search page. It is the storefront's front door and it is also the only place a visitor can
 * see what Step 7 actually bought, so two things are shown that a normal shop would hide: the
 * relevance score on each hit, and the raw facet counts.
 */
export default function Browse() {
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState('')
  const [maxPrice, setMaxPrice] = useState('')
  const [page, setPage] = useState(0)

  const [data, setData] = useState(null)
  const [suggestions, setSuggestions] = useState([])
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [elapsed, setElapsed] = useState(null)

  const { add } = useCart()

  // Debounced, and the debounce is the point rather than a nicety: without it every keystroke is a
  // request, and typing "health beauty" fires thirteen searches of which twelve are already stale by
  // the time they return. 250 ms is below the threshold where typing feels laggy and above the
  // interval between keystrokes.
  const debounce = useRef(null)
  useEffect(() => {
    clearTimeout(debounce.current)
    debounce.current = setTimeout(() => {
      setBusy(true)
      const started = performance.now()
      const params = { page, size: 12 }
      if (query.trim()) params.q = query.trim()
      if (category) params.category = category
      if (maxPrice) params.maxPrice = maxPrice

      api
        .search(params)
        .then((res) => {
          setData(res)
          setError(null)
          setElapsed(Math.round(performance.now() - started))
        })
        .catch((e) => setError(e.message))
        .finally(() => setBusy(false))
    }, 250)
    return () => clearTimeout(debounce.current)
  }, [query, category, maxPrice, page])

  // Reset to the first page whenever the query changes, because page 4 of the old results is not
  // page 4 of the new ones and staying there shows an empty grid on a search that has plenty of hits.
  useEffect(() => setPage(0), [query, category, maxPrice])

  useEffect(() => {
    const q = query.trim()
    if (q.length < 3) return setSuggestions([])
    let cancelled = false
    api.suggest(q).then((s) => !cancelled && setSuggestions(s)).catch(() => {})
    return () => { cancelled = true }
  }, [query])

  return (
    <div>
      <div className="searchbar">
        <input
          className="search-input"
          value={query}
          placeholder="Try a typo: beaty, computr acessorie, or beleza saude"
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search products"
        />
        <input
          className="price-input"
          value={maxPrice}
          placeholder="max R$"
          inputMode="numeric"
          onChange={(e) => setMaxPrice(e.target.value.replace(/[^\d.]/g, ''))}
          aria-label="Maximum price"
        />
      </div>

      {suggestions.length > 0 && (
        <div className="suggestions">
          {suggestions.map((s) => (
            <button key={s} className="chip" onClick={() => { setQuery(s.replace(/_/g, ' ')); setSuggestions([]) }}>
              {s}
            </button>
          ))}
        </div>
      )}

      {category && (
        <div className="active-filter">
          filtering on <strong>{category}</strong>
          <button className="linkish" onClick={() => setCategory('')}>clear</button>
        </div>
      )}

      {error && <p className="error">{error}</p>}

      {data && (
        <>
          <p className="muted meta-line">
            <strong>{data.totalHits.toLocaleString()}</strong> matches
            {elapsed != null && <> · {elapsed} ms round trip</>}
            {busy && <> · searching…</>}
          </p>

          {/*
            The facets come back in the same response as the hits - one Elasticsearch round trip,
            an aggregation alongside the query, not a second call. Showing the counts makes the
            fuzzy matching legible: `computr acessorie` returns three different accessory
            categories, and without these numbers that looks like the search being wrong rather
            than the search being generous.
          */}
          {data.categories?.length > 0 && (
            <div className="facets">
              {data.categories.slice(0, 12).map((f) => (
                <button
                  key={f.value}
                  className={`chip ${category === f.value ? 'chip-on' : ''}`}
                  onClick={() => setCategory(category === f.value ? '' : f.value)}
                >
                  {f.value.replace(/_/g, ' ')} <span className="count">{f.count.toLocaleString()}</span>
                </button>
              ))}
            </div>
          )}

          <div className="grid">
            {data.results.map((p) => (
              <div className="card" key={p.productKey}>
                <Link to={`/product/${p.productKey}`} className="card-title">
                  {p.name || p.category.replace(/_/g, ' ')}
                </Link>
                <div className="card-cat">{p.category.replace(/_/g, ' ')}</div>
                {p.categoryPt && <div className="card-pt">{p.categoryPt.replace(/_/g, ' ')}</div>}
                <div className="card-foot">
                  <span className="price">{brl(p.price)}</span>
                  {/* Only with a query. match_all scores every document 1.0, so rendering it
                      while browsing shows a column of identical 1.00s that means nothing. */}
                  {query.trim() && (
                    <span className="score" title="Elasticsearch relevance score">
                      {p.score.toFixed(2)}
                    </span>
                  )}
                </div>
                <button className="btn" onClick={() => add(p)}>Add to cart</button>
              </div>
            ))}
          </div>

          {data.results.length === 0 && !busy && (
            <p className="muted">Nothing matched. The index holds 32,951 products — try `beauty`.</p>
          )}

          {data.totalPages > 1 && (
            <div className="pager">
              <button className="btn ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>
                ← previous
              </button>
              <span className="muted">page {page + 1} of {data.totalPages.toLocaleString()}</span>
              <button
                className="btn ghost"
                disabled={page + 1 >= data.totalPages}
                onClick={() => setPage(page + 1)}
              >
                next →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
