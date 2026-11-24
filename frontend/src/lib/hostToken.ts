/**
 * The host token proves this browser's owner created an exchange (there is no sign-in). It is shown
 * once, when the exchange is created, and kept here per exchange code. Having a token saved does NOT
 * make someone the host: the server decides (`isHost`), and a wrong or old token simply doesn't count.
 */
const KEY = 'bookExchange.hostTokens'

function readAll(): Record<string, string> {
  try {
    const value = JSON.parse(localStorage.getItem(KEY) ?? '{}')
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
  } catch {
    return {} // storage blocked or damaged
  }
}

export function getHostToken(exchangeCode: string): string | undefined {
  const token = readAll()[exchangeCode]
  return typeof token === 'string' && token ? token : undefined
}

export function saveHostToken(exchangeCode: string, token: string) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ ...readAll(), [exchangeCode]: token }))
  } catch {
    /* without storage the person just won't have host controls after leaving this page */
  }
}

export function forgetHostToken(exchangeCode: string) {
  try {
    const all = readAll()
    delete all[exchangeCode]
    localStorage.setItem(KEY, JSON.stringify(all))
  } catch {
    /* not essential */
  }
}

/** The header to send on a call about this exchange: the token when there is one, otherwise nothing. */
export function hostHeaders(exchangeCode: string): Record<string, string> {
  const token = getHostToken(exchangeCode)
  return token ? { 'X-Host-Token': token } : {}
}
