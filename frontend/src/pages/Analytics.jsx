import { useRef, useState } from 'react'
import { streamQuery } from '../api'
import { useAuth } from '../state'

const EXAMPLES = [
  'What is the total revenue by product category?',
  'Which five states generated the most revenue?',
  'How many orders were placed each month in 2017?',
  'What is the average review score by product category?',
]

/*
 * The admin end of the NL->SQL agent, and the only place in this project where the streaming is
 * actually observable.
 *
 * Until this page existed, `docs/reflection.md` said the SSE path had never been seen in a browser:
 * `eval/run_eval.py` reads the same stream, but a Python client reading frames as they arrive
 * cannot tell you whether the gateway is buffering, because it does not care when they arrive. The
 * gateway's `RemoveResponseHeader=Content-Length` on the analytics route exists exactly to stop that
 * buffering, and this component is the thing that can prove it: the progress steps appear one at a
 * time, or they all appear at the end, and those look different on a screen.
 */
export default function Analytics() {
  const { user } = useAuth()
  const [question, setQuestion] = useState(EXAMPLES[0])
  const [steps, setSteps] = useState([])
  const [sql, setSql] = useState(null)
  const [rows, setRows] = useState(null)
  const [error, setError] = useState(null)
  const [running, setRunning] = useState(false)
  const [elapsed, setElapsed] = useState(null)
  const abort = useRef(null)

  async function ask(q) {
    setSteps([])
    setSql(null)
    setRows(null)
    setError(null)
    setElapsed(null)
    setRunning(true)

    const started = performance.now()
    abort.current = new AbortController()

    try {
      await streamQuery(q, {
        signal: abort.current.signal,
        onEvent: (event) => {
          // Timestamped as they arrive, because the arrival times are the evidence that this is a
          // stream and not one response that was assembled elsewhere and delivered at the end.
          const at = Math.round(performance.now() - started)
          switch (event.type) {
            case 'progress':
              setSteps((s) => [...s, { step: event.step, status: event.status, at }])
              break
            case 'sql':
              setSql(event.sql)
              break
            case 'result':
              setRows(event.data)
              break
            case 'error':
              setError(event.message || JSON.stringify(event))
              break
            default:
              break
          }
        },
      })
      setElapsed(Math.round(performance.now() - started))
    } catch (e) {
      if (e.name !== 'AbortError') setError(e.message)
    } finally {
      setRunning(false)
    }
  }

  if (!user) return <p className="muted">Sign in as an admin to use the analytics agent.</p>
  if (user.role !== 'ADMIN') {
    return (
      <p className="muted">
        This page needs ADMIN. You are signed in as <strong>{user.role}</strong>, and the gateway will
        refuse the request before it reaches the agent — which is the intended behaviour, since the
        agent can read the whole warehouse.
      </p>
    )
  }

  // The agent returns rows as a list of objects; the column set comes from whatever SQL it wrote, so
  // it is derived from the first row rather than known in advance.
  const columns = Array.isArray(rows) && rows.length > 0 ? Object.keys(rows[0]) : []

  // Corrections > 0 means the agent's self-correction loop fired: it wrote SQL, the warehouse
  // rejected it, and it rewrote it. Worth surfacing because it is invisible in the final answer.
  const corrections = steps.filter((s) => s.step?.startsWith('Correct SQL')).length

  return (
    <div>
      <h2>Ask the warehouse</h2>
      <p className="muted">
        Plain English → SQL → the MySQL star schema, over 112,652 order-item facts. The agent holds a
        read-only credential and cannot write.
      </p>

      <div className="examples">
        {EXAMPLES.map((e) => (
          <button key={e} className="chip" onClick={() => { setQuestion(e); ask(e) }} disabled={running}>
            {e}
          </button>
        ))}
      </div>

      <form
        className="askbar"
        onSubmit={(e) => { e.preventDefault(); if (question.trim()) ask(question.trim()) }}
      >
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="Ask a question about the warehouse"
          aria-label="Question"
        />
        <button className="btn" disabled={running}>{running ? 'Thinking…' : 'Ask'}</button>
        {running && (
          <button type="button" className="btn ghost" onClick={() => abort.current?.abort()}>
            Stop
          </button>
        )}
      </form>

      {steps.length > 0 && (
        <ol className="steps">
          {steps.map((s, i) => (
            <li key={i} className={`step-${s.status}`}>
              <span className="mono tiny">{String(s.at).padStart(5)} ms</span> {s.step}
              <span className="muted"> · {s.status}</span>
            </li>
          ))}
        </ol>
      )}

      {corrections > 0 && (
        <p className="muted">
          The self-correction loop fired {corrections} time{corrections > 1 ? 's' : ''} — the first
          SQL did not run and the agent rewrote it.
        </p>
      )}

      {error && <p className="error">{error}</p>}

      {sql && (
        <>
          <h3>SQL it wrote</h3>
          <pre className="sql">{sql}</pre>
        </>
      )}

      {Array.isArray(rows) && (
        <>
          <h3>{rows.length} row{rows.length === 1 ? '' : 's'}{elapsed != null && <span className="muted"> · {(elapsed / 1000).toFixed(1)} s</span>}</h3>
          {rows.length === 0 ? (
            <p className="muted">The query ran and matched nothing.</p>
          ) : (
            <div className="scroll">
              <table className="table">
                <thead><tr>{columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
                <tbody>
                  {rows.slice(0, 100).map((r, i) => (
                    <tr key={i}>
                      {columns.map((c) => (
                        <td key={c} className={typeof r[c] === 'number' ? 'r' : ''}>
                          {r[c] === null ? <span className="muted">null</span> : String(r[c])}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
              {rows.length > 100 && <p className="muted tiny">showing the first 100 of {rows.length}</p>}
            </div>
          )}
        </>
      )}
    </div>
  )
}
