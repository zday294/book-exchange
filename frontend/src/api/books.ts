import { api } from './client'

/**
 * A book as the current viewer is allowed to see it. The server decides what is visible and
 * leaves hidden fields out entirely: an unclaimed book has no title, cover or link, and only the
 * claimer ever receives `amazonUrl`.
 */
export interface Book {
  id: number
  submitterName: string
  wrappingPaper: string
  claimed: boolean
  viewerIsClaimer: boolean
  claimedByName?: string
  claimedAt?: string
  bookTitle?: string
  coverImageUrl?: string
  amazonUrl?: string
}

export const getBooks = (exchangeCode: string) =>
  api<Book[]>(`/api/exchanges/${encodeURIComponent(exchangeCode)}/books`)
