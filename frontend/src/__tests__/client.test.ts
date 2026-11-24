import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api, ApiError } from '../api/client'
import { useParticipantStore } from '../stores/participant'

function respond(status: number, body?: unknown) {
  // A fresh Response per call: a body can only be read once.
  return vi.fn().mockImplementation(
    async () =>
      new Response(body === undefined ? null : JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
  )
}

describe('api client', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })
  afterEach(() => vi.unstubAllGlobals())

  it('adds X-Participant-Name when a name is saved, and not otherwise', async () => {
    const fetchMock = respond(200, {})
    vi.stubGlobal('fetch', fetchMock)

    await api('/api/x')
    expect(fetchMock.mock.calls[0][1].headers['X-Participant-Name']).toBeUndefined()

    useParticipantStore().setName('Alex')
    await api('/api/x')
    expect(fetchMock.mock.calls[1][1].headers['X-Participant-Name']).toBe('Alex')
  })

  it('sends JSON bodies with a content type', async () => {
    const fetchMock = respond(201, { code: 'ABC' })
    vi.stubGlobal('fetch', fetchMock)

    const result = await api<{ code: string }>('/api/exchanges', { method: 'POST', body: { name: 'N' } })

    expect(result.code).toBe('ABC')
    const [, init] = fetchMock.mock.calls[0]
    expect(init.method).toBe('POST')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(init.body).toBe('{"name":"N"}')
  })

  it('turns error responses into ApiError with message, details and field errors', async () => {
    vi.stubGlobal('fetch', respond(400, { error: 'Validation failed', details: ['name: must not be blank'] }))

    const error = (await api('/api/x').catch((e) => e)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.message).toBe('Validation failed')
    expect(error.fieldErrors).toEqual({ name: 'must not be blank' })
  })

  it('reports an unreachable server as status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')))
    const error = (await api('/api/x').catch((e) => e)) as ApiError
    expect(error.status).toBe(0)
  })
})
