import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/*
 * Port 3000, and NO dev proxy. Both halves are deliberate.
 *
 * 3000 because it is one of the three origins the gateway's CORS configuration names
 * (config-repo/api-gateway.yml). Vite's own default is 5173, which is also on the list; 3000 is
 * pinned here so the origin the browser sends is a constant rather than "whatever port was free".
 *
 * The missing proxy is the more important decision. Vite can proxy /api to localhost:8080, which
 * makes every request same-origin and CORS stops applying at all. That is a pleasant way to develop
 * and a bad way to test, because it removes the exact thing the roadmap's Definition of Done calls
 * out: "an API test is not a frontend test - curl sends no Origin and runs no component". With no
 * proxy, every page load performs a real cross-origin request, the browser sends a real preflight
 * for anything carrying an Authorization header, and a missing allowed-origin fails here in
 * development rather than in a deployment.
 */
export default defineConfig({
  plugins: [react()],
  server: { port: 3000, strictPort: true },
})
