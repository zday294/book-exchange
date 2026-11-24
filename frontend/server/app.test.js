// @vitest-environment node
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { createApp } from './app.js'

let backend
let backendUrl
let front
let base
let distDir
const upstream = { streamClosed: false, lastRequest: null }

function listen(server) {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)))
}

beforeAll(async () => {
  distDir = fs.mkdtempSync(path.join(os.tmpdir(), 'dist-'))
  fs.mkdirSync(path.join(distDir, 'assets'))
  fs.writeFileSync(path.join(distDir, 'index.html'), '<!doctype html><html><body>APP' + ' '.repeat(3000) + '</body></html>')
  fs.writeFileSync(path.join(distDir, 'assets', 'app.abc123.js'), 'console.log("x")'.repeat(200))
  fs.writeFileSync(path.join(distDir, 'favicon.txt'), 'icon')

  backend = http.createServer((req, res) => {
    if (req.url === '/api/stream') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
      res.write('data: first\n\n')
      const timer = setTimeout(() => res.write('data: second\n\n'), 700)
      res.on('close', () => {
        clearTimeout(timer)
        upstream.streamClosed = true
      })
      return
    }
    if (req.url === '/api/conflict') {
      res.writeHead(409, { 'Content-Type': 'application/json' })
      return res.end('{"error":"nope","details":[]}')
    }
    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', () => {
      upstream.lastRequest = { method: req.method, url: req.url, headers: req.headers, body }
      res.writeHead(201, { 'Content-Type': 'application/json', 'X-Upstream': 'yes' })
      res.end(JSON.stringify({ ok: true }))
    })
  })
  backendUrl = `http://127.0.0.1:${await listen(backend)}`

  front = http.createServer(createApp({ backendUrl, distDir }))
  base = `http://127.0.0.1:${await listen(front)}`
})

afterAll(() => {
  front.closeAllConnections()
  backend.closeAllConnections()
  front.close()
  backend.close()
  fs.rmSync(distDir, { recursive: true, force: true })
})

describe('static files and routing', () => {
  it('serves the app at /', async () => {
    const res = await fetch(base + '/')
    expect(res.status).toBe(200)
    expect(await res.text()).toContain('APP')
  })

  it('returns index.html for client-side routes, so deep links survive a refresh', async () => {
    for (const url of ['/exchange/K7M2PXA', '/exchanges/new', '/exchange/K7M2PXA/submit']) {
      const res = await fetch(base + url)
      expect(res.status).toBe(200)
      expect(res.headers.get('content-type')).toContain('text/html')
      expect(await res.text()).toContain('APP')
    }
  })

  it('does not return the app for missing files or other methods', async () => {
    expect((await fetch(base + '/assets/missing.js')).status).toBe(404)
    expect((await fetch(base + '/exchange/K7M2PXA', { method: 'POST' })).status).toBe(404)
  })

  it('caches hashed assets for a long time and never caches index.html', async () => {
    const asset = await fetch(base + '/assets/app.abc123.js')
    expect(asset.status).toBe(200)
    expect(asset.headers.get('cache-control')).toBe('public, max-age=31536000, immutable')
    expect((await fetch(base + '/')).headers.get('cache-control')).toBe('no-cache')
    expect((await fetch(base + '/exchange/X')).headers.get('cache-control')).toBe('no-cache')
    expect((await fetch(base + '/favicon.txt')).headers.get('cache-control')).toBe('no-cache')
  })

  it('compresses static responses when the client accepts it', async () => {
    const res = await fetch(base + '/', { headers: { 'Accept-Encoding': 'gzip' } })
    expect(res.headers.get('content-encoding')).toBe('gzip')
  })

  it('sets security headers and hides the framework', async () => {
    const res = await fetch(base + '/')
    expect(res.headers.get('x-powered-by')).toBeNull()
    expect(res.headers.get('x-content-type-options')).toBe('nosniff')
    expect(res.headers.get('content-security-policy')).toContain("default-src 'self'")
  })

  it('has a health endpoint that does not depend on the backend', async () => {
    const res = await fetch(base + '/healthz')
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({ status: 'ok' })
  })
})

describe('API proxy', () => {
  it('forwards method, path, query, body and headers (including X-Participant-Name)', async () => {
    const res = await fetch(base + '/api/exchanges/ABC/books?x=1', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Participant-Name': 'Alex', Origin: 'https://app.example.com' },
      body: '{"a":1}',
    })
    expect(res.status).toBe(201)
    expect(res.headers.get('x-upstream')).toBe('yes')
    expect(await res.json()).toEqual({ ok: true })
    expect(upstream.lastRequest).toMatchObject({
      method: 'POST',
      url: '/api/exchanges/ABC/books?x=1',
      body: '{"a":1}',
    })
    expect(upstream.lastRequest.headers['x-participant-name']).toBe('Alex')
    expect(upstream.lastRequest.headers['content-type']).toBe('application/json')
  })

  it("does not forward the browser's Origin header (it would trip the backend's CORS check)", async () => {
    await fetch(base + '/api/anything', { method: 'POST', headers: { Origin: 'https://app.example.com' }, body: '{}' })
    expect(upstream.lastRequest.headers.origin).toBeUndefined()
  })

  it('returns error statuses and bodies unchanged', async () => {
    const res = await fetch(base + '/api/conflict')
    expect(res.status).toBe(409)
    expect(await res.json()).toEqual({ error: 'nope', details: [] })
  })

  it('answers 502 without leaking details when the backend is down', async () => {
    const dead = http.createServer(createApp({ backendUrl: 'http://127.0.0.1:1', distDir }))
    const port = await listen(dead)
    const res = await fetch(`http://127.0.0.1:${port}/api/anything`)
    const text = await res.text()
    dead.close()
    expect(res.status).toBe(502)
    expect(JSON.parse(text).error).toMatch(/unavailable/i)
    expect(text).not.toMatch(/127\.0\.0\.1|ECONNREFUSED/)
  })

  it('rejects oversized request bodies with 413', async () => {
    const res = await fetch(base + '/api/exchanges', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: 'x'.repeat(1024 * 1024 + 1),
    })
    expect(res.status).toBe(413)
  })

  it('streams Server-Sent Events immediately, uncompressed, and closes upstream on disconnect', async () => {
    upstream.streamClosed = false
    const controller = new AbortController()
    const started = Date.now()
    const res = await fetch(base + '/api/stream', {
      headers: { 'Accept-Encoding': 'gzip' },
      signal: controller.signal,
    })
    expect(res.headers.get('content-type')).toContain('text/event-stream')
    expect(res.headers.get('content-encoding')).toBeNull()

    const reader = res.body.getReader()
    const first = new TextDecoder().decode((await reader.read()).value)
    expect(first).toContain('data: first')
    expect(Date.now() - started).toBeLessThan(500) // arrived before the second event was even sent

    controller.abort()
    await new Promise((resolve) => setTimeout(resolve, 300))
    expect(upstream.streamClosed).toBe(true)
  })
})
