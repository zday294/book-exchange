/**
 * The server never sends the title or link of an unclaimed book (not even to the person who brought it,
 * because there is no sign-in to prove who is asking). So this browser remembers what its owner submitted,
 * per exchange and name, to let them look it up again. On another device or after clearing the browser's
 * data the page still says a book was submitted (the pile shows who brought each one) but has no link.
 */
export interface MySubmission {
  bookTitle: string
  amazonUrl: string
}

const KEY = 'bookExchange.mySubmissions'

const keyFor = (exchangeCode: string, name: string) => `${exchangeCode}|${name.trim().toLowerCase()}`

function readAll(): Record<string, MySubmission> {
  try {
    const value = JSON.parse(localStorage.getItem(KEY) ?? '{}')
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
  } catch {
    return {} // storage blocked or damaged
  }
}

export function getMySubmission(exchangeCode: string, name: string): MySubmission | undefined {
  const s = readAll()[keyFor(exchangeCode, name)]
  return s && typeof s.bookTitle === 'string' && typeof s.amazonUrl === 'string' ? s : undefined
}

export function saveMySubmission(exchangeCode: string, name: string, submission: MySubmission) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ ...readAll(), [keyFor(exchangeCode, name)]: submission }))
  } catch {
    /* without storage the person just won't get the link back */
  }
}
