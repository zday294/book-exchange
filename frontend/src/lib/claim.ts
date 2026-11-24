import type { Book } from '../api/books'
import { ApiError } from '../api/client'

export type Claimability = { state: 'claimable' } | { state: 'disabled'; reason: string }

/**
 * What the UI offers for one book. This is only a convenience: the backend decides (and returns
 * 409 if the screen was out of date), so the rules are repeated here just to avoid pointless clicks.
 */
export function claimability(
  book: Book,
  viewerName: string,
  viewerHasClaimed: boolean,
  exchangeOpen: boolean,
): Claimability {
  if (book.claimed) return { state: 'disabled', reason: '' }
  if (!exchangeOpen) return { state: 'disabled', reason: 'This exchange is closed' }
  if (viewerHasClaimed) return { state: 'disabled', reason: "You've already claimed a book" }
  if (book.submitterName.trim().toLowerCase() === viewerName.trim().toLowerCase()) {
    return { state: 'disabled', reason: 'You brought this one' }
  }
  return { state: 'claimable' }
}

/** A friendly sentence for why a claim failed. The server's own message is the fallback. */
export function claimFailureMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Something went wrong. Please try again.'
  if (error.status === 0) return error.message
  const text = error.message
  if (/book has already been claimed/i.test(text)) return 'Too slow! Someone else just claimed that present.'
  if (/already claimed a book/i.test(text)) return "You've already claimed a book in this exchange."
  if (/submitted yourself/i.test(text)) return "You can't claim a present you brought yourself."
  if (error.status === 404) return "That present isn't there any more."
  return text
}
