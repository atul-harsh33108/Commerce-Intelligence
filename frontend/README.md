# Storefront

A React SPA over the platform: browse and search the Olist catalogue, buy something, watch the saga
settle, and — if you are an admin — ask the warehouse a question in English.

```bash
npm install
npm run dev          # http://localhost:3000
```

It needs the gateway on `:8080` and the services behind it. `VITE_API_BASE` overrides the base URL;
nothing else is configurable, because nothing else should be.

## What is worth reading here

**There is no dev proxy** (`vite.config.js`). Vite will happily proxy `/api` to `localhost:8080`,
which makes every request same-origin and switches CORS off in development. That is comfortable and
it defeats the point: the roadmap's Definition of Done for this step is *"an API test is not a
frontend test — curl sends no `Origin` and runs no component."* With no proxy, every page load is a
real cross-origin request and every authenticated call is preceded by a real preflight.

**SSE is hand-rolled** (`src/api.js`). The browser's `EventSource` cannot set an `Authorization`
header — it takes a URL and nothing else. The alternatives are a token in the query string (which
writes a credential into every access log) or cookie auth (which the gateway deliberately does not
do). So the analytics stream is `fetch` plus a `ReadableStream` reader with the SSE framing parsed by
hand, buffering the incomplete tail of each chunk, because a network chunk has no relationship to a
frame boundary.

**The order page shows the asynchrony instead of hiding it** (`src/pages/Orders.jsx`). `POST
/api/orders` returns `PENDING` in ~39 ms and the saga confirms it about 1.3 s later. The tracker
polls every 400 ms until a terminal state and then stops.

**The token is in localStorage, and the trade is written down** in `src/api.js` rather than assumed.

## What this found

Building it took about an hour. Running it in a browser found two bugs that months of curl had not:

- **Every Redis cache hit in catalog-service was throwing.** The serializer wrote no type
  information, so reads came back as `LinkedHashMap` and the cast to `ProductResponse` failed. It
  needed *two* requests for the same product to see it, and React StrictMode's double-invoked effect
  made two requests.
- **Search reported 10,000 matches on a 32,951-document index**, because Elasticsearch stops counting
  at 10,000 by default. Every query I had measured returned fewer than that. Browsing with no query
  at all is what exposed it.

Both are written up in `docs/reflection.md`.
