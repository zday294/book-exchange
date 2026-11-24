import { describe, expect, it } from 'vitest'
import { isAmazonUrl } from '../lib/amazon'
import { validateSubmit } from '../lib/submitValidation'

// The same examples the backend's AmazonUrlValidatorTest uses, so the two stay in step.
describe('isAmazonUrl', () => {
  it.each([
    'https://www.amazon.com/dp/0547928221',
    'https://www.amazon.com/Hobbit/dp/0547928221/ref=sr_1_1?keywords=hobbit',
    'https://smile.amazon.com/Hobbit/dp/0547928221/ref=sr_1_1',
    'https://AMAZON.COM/dp/0547928221',
    'https://amzn.to/3abcdef',
    'https://a.co/d/abc123',
    'https://www.amazon.com:443/dp/0547928221',
    '  https://www.amazon.com/dp/0547928221  ',
  ])('accepts %s', (url) => expect(isAmazonUrl(url)).toBe(true))

  it.each([
    'http://www.amazon.com/dp/0547928221',
    'ftp://amazon.com/dp/1',
    'https://example.com/dp/0547928221',
    'https://evilamazon.com/dp/1',
    'https://amazon.com.evil.com/dp/1',
    'https://amazon.com@evil.com/dp/1',
    'https://evil.com/?u=https://amazon.com/dp/1',
    'https://amazon.com:8443/dp/1',
    'https://localhost/dp/1',
    'https://10.0.0.1/dp/1',
    '//amazon.com/dp/1',
    'amazon.com/dp/1',
    'not a url',
    'https://amazon.com/has space',
    '',
    '   ',
  ])('rejects %s', (url) => expect(isAmazonUrl(url)).toBe(false))
})

describe('validateSubmit', () => {
  const ok = {
    submitterName: 'Zach',
    bookTitle: 'The Hobbit',
    amazonUrl: 'https://www.amazon.com/dp/0547928221',
    wrappingPaper: 'holly',
  }

  it('accepts a complete form', () => expect(validateSubmit(ok)).toEqual({}))

  it('requires every field, treating spaces as empty', () => {
    const errors = validateSubmit({ submitterName: ' ', bookTitle: '  ', amazonUrl: '', wrappingPaper: '' })
    expect(Object.keys(errors).sort()).toEqual(['amazonUrl', 'bookTitle', 'submitterName', 'wrappingPaper'])
  })

  it('applies the backend length limits after trimming', () => {
    expect(validateSubmit({ ...ok, submitterName: 'x'.repeat(100) })).toEqual({})
    expect(validateSubmit({ ...ok, submitterName: 'x'.repeat(101) }).submitterName).toBeTruthy()
    expect(validateSubmit({ ...ok, bookTitle: 'x'.repeat(300) })).toEqual({})
    expect(validateSubmit({ ...ok, bookTitle: 'x'.repeat(301) }).bookTitle).toBeTruthy()
    expect(validateSubmit({ ...ok, amazonUrl: 'https://amazon.com/' + 'x'.repeat(2048) }).amazonUrl).toBeTruthy()
  })

  it('explains a link that is not an Amazon link', () => {
    expect(validateSubmit({ ...ok, amazonUrl: 'https://example.com/book' }).amazonUrl).toMatch(/amazon/i)
  })

  it('rejects an unknown paper key', () => {
    expect(validateSubmit({ ...ok, wrappingPaper: 'not-a-paper' }).wrappingPaper).toBeTruthy()
  })
})
