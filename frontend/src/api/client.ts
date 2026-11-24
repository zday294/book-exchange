import { useParticipantStore } from '../stores/participant'

/** The shared error body of the backend: { error, details }. */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public details: string[] = [],
  ) {
    super(message)
  }

  /** Validation problems come back as "field: message"; group them by field for forms. */
  get fieldErrors(): Record<string, string> {
    const out: Record<string, string> = {}
    for (const d of this.details) {
      const i = d.indexOf(':')
      if (i > 0) out[d.slice(0, i).trim()] = d.slice(i + 1).trim()
    }
    return out
  }
}

export const baseUrl = (): string => (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

/** Thin fetch wrapper: JSON in/out, adds X-Participant-Name when a name is saved, throws ApiError. */
export async function api<T>(
  path: string,
  options: { method?: string; body?: unknown; headers?: Record<string, string> } = {},
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  const name = useParticipantStore().name
  if (name) headers['X-Participant-Name'] = name
  Object.assign(headers, options.headers)

  let response: Response
  try {
    response = await fetch(baseUrl() + path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch {
    throw new ApiError(0, 'Could not reach the server. Please try again.')
  }

  if (!response.ok) {
    let message = response.statusText || 'Request failed'
    let details: string[] = []
    try {
      const body = await response.json()
      if (typeof body?.error === 'string') message = body.error
      if (Array.isArray(body?.details)) details = body.details
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(response.status, message, details)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}
