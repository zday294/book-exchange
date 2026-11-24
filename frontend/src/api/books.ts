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

export interface SubmitBookRequest {
  submitterName: string
  bookTitle: string
  amazonUrl: string
  wrappingPaper: string
}

/** What the submitter gets back about their own book (not the public pile view). */
export interface SubmittedBook {
  id: number
  submitterName: string
  bookTitle: string
  amazonUrl: string
  wrappingPaper: string
  claimed: boolean
}

export const submitBook = (exchangeCode: string, request: SubmitBookRequest) =>
  api<SubmittedBook>(`/api/exchanges/${encodeURIComponent(exchangeCode)}/books`, {
    method: 'POST',
    body: request,
  })

/** Claims a book. The answer is the claimer's own view of it: title, cover and the book link. */
export const claimBook = (exchangeCode: string, bookId: number, participantName: string) =>
  api<Book>(`/api/exchanges/${encodeURIComponent(exchangeCode)}/books/${bookId}/claim`, {
    method: 'POST',
    body: { participantName },
  })
