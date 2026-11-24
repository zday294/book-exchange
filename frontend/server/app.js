import path from 'node:path'
import express from 'express'
import compression from 'compression'
import helmet from 'helmet'
import { createProxyMiddleware } from 'http-proxy-middleware'

const MAX_BODY_BYTES = 1024 * 1024 // the API only takes small JSON bodies

/**
 * Builds the front-end server: serves the built Vue app, falls back to index.html for
 * client-side routes, and forwards /api/** to the backend.
 *
 * @param {{ backendUrl: string, distDir: string }} options
 */
export function createApp({ backendUrl, distDir }) {
  const app = express()
  app.disable('x-powered-by')

  app.use(
    helmet({
      contentSecurityPolicy: {
        directives: {
          ...helmet.contentSecurityPolicy.getDefaultDirectives(),
          // Book covers come from other hosts (e.g. Amazon's image servers).
          'img-src': ["'self'", 'data:', 'https:'],
        },
      },
    }),
  )

  app.get('/healthz', (_req, res) => {
    res.set('Cache-Control', 'no-store').json({ status: 'ok' })
  })

  // Reject oversized bodies before they reach the backend.
  app.use('/api', (req, res, next) => {
    const length = Number(req.headers['content-length'] ?? 0)
    if (length > MAX_BODY_BYTES) return res.status(413).json({ error: 'Request too large', details: [] })
    next()
  })

  // The proxy goes before compression: it streams bytes through untouched, so a Server-Sent
  // Events stream is delivered immediately and a closed browser tab closes the upstream request.
  app.use(
    createProxyMiddleware({
      target: backendUrl,
      pathFilter: '/api',
      changeOrigin: true,
      proxyTimeout: 0, // no idle timeout: live-update streams stay open
      timeout: 0,
      on: {
        proxyReq(proxyReq) {
          // The browser talks to this server (same origin), not to the backend. Forwarding its
          // Origin header would make the backend's CORS check reject the request as cross-site.
          proxyReq.removeHeader('origin')
        },
        error(_err, _req, res) {
          // Never leak upstream details (hostnames, stack traces) to the browser.
          if (res && typeof res.writeHead === 'function' && !res.headersSent) {
            res.writeHead(502, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ error: 'The server is unavailable. Please try again.', details: [] }))
          } else if (res?.end) {
            res.end()
          }
        },
      },
    }),
  )

  app.use(compression())

  // Hashed build assets never change: cache for a year. Everything else (index.html) must be revalidated.
  app.use(
    express.static(distDir, {
      index: false,
      setHeaders(res, filePath) {
        const hashed = filePath.includes(`${path.sep}assets${path.sep}`)
        res.setHeader('Cache-Control', hashed ? 'public, max-age=31536000, immutable' : 'no-cache')
      },
    }),
  )

  // Client-side routes (/exchange/ABC123) return the app; missing files and unknown methods do not.
  app.use((req, res, next) => {
    if ((req.method !== 'GET' && req.method !== 'HEAD') || path.extname(req.path) !== '') return next()
    res.set('Cache-Control', 'no-cache')
    res.sendFile(path.join(path.resolve(distDir), 'index.html'), (err) => err && next())
  })

  app.use((_req, res) => res.status(404).type('text/plain').send('Not found'))
  return app
}
