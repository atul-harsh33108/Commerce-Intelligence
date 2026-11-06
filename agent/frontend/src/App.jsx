import { useState, useRef, useEffect } from 'react'
import './App.css'

const API_URL = '/api/query'

export default function App() {
  const [question, setQuestion] = useState('')
  const [loading, setLoading] = useState(false)
  const [messages, setMessages] = useState([])
  const messagesEl = useRef(null)
  const examples = [
    'What are the total sales by region?',
    'Top 5 customers by order amount',
    'Monthly revenue trend for Electronics in the South',
  ]

  function scrollToBottom() {
    const el = messagesEl.current
    if (el) el.scrollTop = el.scrollHeight
  }

  useEffect(() => {
    scrollToBottom()
  }, [messages])

  async function sendQuestion() {
    if (!question.trim() || loading) return

    const q = question.trim()
    setQuestion('')
    setLoading(true)

    // Add user message
    setMessages(prev => [...prev, { role: 'user', type: 'text', content: q }])

    // Add empty steps bubble
    const stepId = Date.now()
    setMessages(prev => [...prev, { role: 'assistant', type: 'steps', id: stepId, steps: [] }])

    try {
      const response = await fetch(API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: q }),
      })

      if (!response.body) throw new Error('Server did not return stream')

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      while (true) {
        const { value, done } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const events = buffer.split('\n\n')
        buffer = events.pop()

        for (const evt of events) {
          const line = evt.trim()
          if (!line.startsWith('data:')) continue

          let data
          try {
            data = JSON.parse(line.replace(/^data:\s*/, ''))
          } catch {
            continue
          }

          if (data.type === 'progress') {
            setMessages(prev => prev.map(msg => {
              if (msg.id !== stepId) return msg
              const existing = msg.steps.find(s => s.text === data.step)
              if (!existing) {
                return { ...msg, steps: [...msg.steps, { text: data.step, status: data.status }] }
              }
              return {
                ...msg,
                steps: msg.steps.map(s => s.text === data.step ? { ...s, status: data.status } : s),
              }
            }))
          } else if (data.type === 'result' && Array.isArray(data.data)) {
            setMessages(prev => [...prev, {
              role: 'assistant',
              type: 'table',
              columns: Object.keys(data.data[0] || {}),
              rows: data.data,
            }])
          } else if (data.type === 'error') {
            setMessages(prev => [...prev, {
              role: 'assistant',
              type: 'error',
              content: data.message || 'An error occurred',
            }])
          } else if (data.type === 'sql') {
            setMessages(prev => [...prev, {
              role: 'assistant',
              type: 'sql',
              sql: data.sql,
              source: data.source || 'generation',
              attempt: data.attempt,
            }])
          }
        }
      }
    } catch (e) {
      setMessages(prev => [...prev, {
        role: 'assistant',
        type: 'error',
        content: e?.message || 'Request failed',
      }])
    } finally {
      setLoading(false)
    }
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendQuestion()
    }
  }

  return (
    <div className="chat-page">
      <div className="messages" ref={messagesEl}>
        {messages.length === 0 && (
          <div className="welcome">
            <h1>DataQuery Agent</h1>
            <p>Ask business questions and watch the LangGraph pipeline generate safe SQL.</p>
            <div className="example-row">
              {examples.map(example => (
                <button key={example} onClick={() => setQuestion(example)}>{example}</button>
              ))}
            </div>
          </div>
        )}
        {messages.map((msg, idx) => (
          <div key={idx} className={`message-row ${msg.role}`}>
            {msg.role === 'assistant' && <div className="avatar">🤖</div>}

            <div className="bubble">
              {msg.type === 'text' && <div>{msg.content}</div>}

              {msg.type === 'steps' && (
                <div className="steps">
                  {msg.steps.map((step, sIdx) => (
                    <div key={sIdx} className="step">
                      <span className={`dot ${step.status}`} />
                      <span>{step.text}</span>
                    </div>
                  ))}
                </div>
              )}

              {msg.type === 'table' && (
                <div className="table-wrap">
                  <table className="result-table">
                    <thead>
                      <tr>
                        {msg.columns.map(col => <th key={col}>{col}</th>)}
                      </tr>
                    </thead>
                    <tbody>
                      {msg.rows.map((row, rIdx) => (
                        <tr key={rIdx}>
                          {msg.columns.map(col => <td key={col}>{row[col]}</td>)}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {msg.type === 'sql' && (
                <div className="sql-block">
                  <div className="sql-meta">
                    <span>{msg.source === 'correction' ? `Corrected SQL #${msg.attempt}` : 'Generated SQL'}</span>
                    <button onClick={() => navigator.clipboard?.writeText(msg.sql)}>Copy</button>
                  </div>
                  <pre><code>{msg.sql}</code></pre>
                </div>
              )}

              {msg.type === 'error' && (
                <div className="error-text">{msg.content}</div>
              )}
            </div>

            {msg.role === 'user' && <div className="avatar">🧑</div>}
          </div>
        ))}
        <div className="messages-bottom-spacer" />
      </div>

      <div className="input-wrapper">
        <div className="input-box">
          <input
            value={question}
            onChange={e => setQuestion(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Ask a question about your data..."
            disabled={loading}
          />
          <button onClick={sendQuestion} disabled={loading}>
            {loading ? 'Running...' : 'Send'}
          </button>
        </div>
      </div>
    </div>
  )
}
