import { baseUrl } from '../api/client'

/** What the server tells everyone watching an exchange. Public information only: never a book link. */
export interface BookAdded {
  id: number
  submitterName: string
  wrappingPaper: string
}
export interface BookClaimed {
  id: number
  claimedByName: string
  claimedAt: string
  bookTitle: string
  coverImageUrl?: string
}
export interface ModeChanged {
  mode: 'SUBMIT' | 'CLAIM'
}

export interface LiveHandlers {
  onBookAdded(event: BookAdded): void
  onBookClaimed(event: BookClaimed): void
  onModeChanged(event: ModeChanged): void
  /** The stream is up (the first time). */
  onConnected(): void
  /** The stream was lost; the browser keeps trying to reconnect by itself. */
  onDisconnected(): void
  /** The stream is back after a loss: events sent in between were missed, so re-read everything. */
  onReconnected(): void
}

export interface LiveConnection {
  close(): void
}

const FIRST_RETRY_MS = 5000
const MAX_RETRY_MS = 60000

/**
 * Watches one exchange with Server-Sent Events. Returns null when the browser has no EventSource, in which
 * case the page just keeps polling.
 *
 * A dropped connection is retried by the browser itself. But when the server answers with an error (it is
 * restarting behind a proxy, it is full, ...) the browser gives up for good, so this opens a fresh one after a
 * growing pause. Either way the page is told when the stream is lost and when it is back, and meanwhile keeps
 * polling.
 */
export function openLiveUpdates(exchangeCode: string, handlers: LiveHandlers): LiveConnection | null {
  if (typeof EventSource === 'undefined') return null

  const url = `${baseUrl()}/api/exchanges/${encodeURIComponent(exchangeCode)}/events`
  let source: EventSource | null = null
  let retryTimer: ReturnType<typeof setTimeout> | undefined
  let stopped = false
  let connected = false
  let missedSomething = false // true once the stream has been lost or has failed to open
  let failures = 0

  /** Ignores anything that isn't the JSON we expect, so one odd message can never break the page. */
  const listen = <T>(es: EventSource, name: string, handle: (data: T) => void) =>
    es.addEventListener(name, (event) => {
      try {
        const data = JSON.parse((event as MessageEvent<string>).data)
        if (data && typeof data === 'object') handle(data as T)
      } catch {
        /* not JSON: ignore */
      }
    })

  function connect() {
    if (stopped) return
    const es = new EventSource(url)
    source = es

    es.addEventListener('open', () => {
      failures = 0
      connected = true
      if (missedSomething) {
        missedSomething = false
        handlers.onReconnected() // events sent while it was down were missed: re-read everything
      } else {
        handlers.onConnected()
      }
    })

    es.addEventListener('error', () => {
      if (connected) {
        connected = false
        missedSomething = true
        handlers.onDisconnected()
      }
      if (es.readyState === EventSource.CLOSED && !stopped) {
        // The browser has given up on this one. Try again later with a fresh connection.
        es.close()
        missedSomething = true
        failures++
        retryTimer = setTimeout(connect, Math.min(FIRST_RETRY_MS * 2 ** (failures - 1), MAX_RETRY_MS))
      }
    })

    listen<BookAdded>(es, 'book-added', handlers.onBookAdded)
    listen<BookClaimed>(es, 'book-claimed', handlers.onBookClaimed)
    listen<ModeChanged>(es, 'mode-changed', handlers.onModeChanged)
  }

  connect()

  return {
    close() {
      stopped = true
      clearTimeout(retryTimer)
      source?.close()
    },
  }
}
