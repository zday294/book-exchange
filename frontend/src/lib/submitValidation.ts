import { isAmazonUrl } from './amazon'
import { PAPER_KEYS } from '../wrapping/papers'

export interface SubmitFields {
  submitterName: string
  bookTitle: string
  amazonUrl: string
  wrappingPaper: string
}

export type SubmitErrors = Partial<Record<keyof SubmitFields, string>>

/** Same limits as the backend's SubmitBookRequest. Text is trimmed first, as the backend does. */
export const LIMITS = { submitterName: 100, bookTitle: 300, amazonUrl: 2048 } as const

export function validateSubmit(fields: SubmitFields): SubmitErrors {
  const errors: SubmitErrors = {}
  const name = fields.submitterName.trim()
  const title = fields.bookTitle.trim()
  const url = fields.amazonUrl.trim()

  if (!name) errors.submitterName = 'Please enter your name.'
  else if (name.length > LIMITS.submitterName) errors.submitterName = `Names can be at most ${LIMITS.submitterName} characters.`

  if (!title) errors.bookTitle = "Please enter the book's name."
  else if (title.length > LIMITS.bookTitle) errors.bookTitle = `The name can be at most ${LIMITS.bookTitle} characters.`

  if (!url) errors.amazonUrl = 'Please paste the Amazon link.'
  else if (url.length > LIMITS.amazonUrl) errors.amazonUrl = 'That link is too long.'
  else if (!isAmazonUrl(url)) {
    errors.amazonUrl = 'That doesn\'t look like an Amazon link. It should start with https:// and be on amazon.com, amzn.to or a.co.'
  }

  if (!fields.wrappingPaper) errors.wrappingPaper = 'Please choose a wrapping paper.'
  else if (!PAPER_KEYS.includes(fields.wrappingPaper)) errors.wrappingPaper = 'Please choose a wrapping paper.'

  return errors
}
