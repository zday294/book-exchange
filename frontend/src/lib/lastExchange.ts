/** Remembers the exchange the person last looked at, so the home page can offer to go back to it. */
const KEY = 'bookExchange.lastExchange'

export interface LastExchange {
  code: string
  name: string
}

export function readLastExchange(): LastExchange | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const value = JSON.parse(raw)
    return typeof value?.code === 'string' && typeof value?.name === 'string' ? value : null
  } catch {
    return null // storage blocked or corrupted: just don't offer the shortcut
  }
}

export function rememberExchange(exchange: LastExchange) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ code: exchange.code, name: exchange.name }))
  } catch {
    /* not essential */
  }
}

export function forgetExchange(code?: string) {
  try {
    const current = readLastExchange()
    if (!code || current?.code === code) localStorage.removeItem(KEY)
  } catch {
    /* not essential */
  }
}
