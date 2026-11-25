import { isBookUrl } from './bookLink'
import { PAPER_KEYS } from '../wrapping/papers'

export interface SubmitFields {
  submitterName: string
  bookTitle: string
  bookUrl: string
  wrappingPaper: string
}

export type SubmitErrors = Partial<Record<keyof SubmitFields, string>>

/** Same limits as the backend's SubmitBookRequest. Text is trimmed first, as the backend does. */
export const LIMITS = { submitterName: 100, bookTitle: 300, bookUrl: 2048 } as const

export function validateSubmit(fields: SubmitFields): SubmitErrors {
  const errors: SubmitErrors = {}
  const name = fields.submitterName.trim()
  const title = fields.bookTitle.trim()
  const url = fields.bookUrl.trim()

  if (!name) errors.submitterName = 'Please enter your name.'
  else if (name.length > LIMITS.submitterName) errors.submitterName = `Names can be at most ${LIMITS.submitterName} characters.`

  if (!title) errors.bookTitle = "Please enter the book's name."
  else if (title.length > LIMITS.bookTitle) errors.bookTitle = `The name can be at most ${LIMITS.bookTitle} characters.`

  if (!url) errors.bookUrl = 'Please paste the link to the book.'
  else if (url.length > LIMITS.bookUrl) errors.bookUrl = 'That link is too long.'
  else if (!isBookUrl(url)) {
    errors.bookUrl = 'That doesn\'t look like an Amazon or Barnes & Noble link. It should start with https:// and be on amazon.com, amzn.to, a.co, barnesandnoble.com or bn.com.'
  }

  if (!fields.wrappingPaper) errors.wrappingPaper = 'Please choose a wrapping paper.'
  else if (!PAPER_KEYS.includes(fields.wrappingPaper)) errors.wrappingPaper = 'Please choose a wrapping paper.'

  return errors
}
