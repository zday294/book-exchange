import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createApp } from './app.js'

// Configuration comes only from environment variables.
const port = Number(process.env.PORT ?? 3000)
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:8080'
const distDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist')

const server = createApp({ backendUrl, distDir }).listen(port, () => {
  console.log(`Front-end server listening on :${port}, proxying /api to ${backendUrl}`)
})

// Finish in-flight requests, then exit (Cloud Run / Docker send SIGTERM on deploy).
function shutdown() {
  server.close(() => process.exit(0))
  server.closeIdleConnections()
  setTimeout(() => process.exit(0), 10_000).unref() // long-lived streams must not block a deploy
}
process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
