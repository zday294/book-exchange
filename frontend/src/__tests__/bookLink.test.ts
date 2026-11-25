import { describe, expect, it } from 'vitest'
import { isBookUrl, retailerName, viewLinkLabel } from '../lib/bookLink'
import { validateSubmit } from '../lib/submitValidation'

// The same examples the backend's BookUrlValidatorTest uses, so the two stay in step.
describe('isBookUrl', () => {
  it.each([
    'https://www.amazon.com/dp/0547928221',
    'https://www.amazon.com/Hobbit/dp/0547928221/ref=sr_1_1?keywords=hobbit',
    'https://smile.amazon.com/Hobbit/dp/0547928221/ref=sr_1_1',
    'https://AMAZON.COM/dp/0547928221',
    'https://amzn.to/3abcdef',
    'https://a.co/d/abc123',
    'https://www.amazon.com:443/dp/0547928221',
    '  https://www.amazon.com/dp/0547928221  ',
    'https://www.barnesandnoble.com/w/the-hobbit-j-r-r-tolkien/1100054405?ean=9780547928227',
    'https://barnesandnoble.com/w/x/1',
    'https://www.bn.com/w/x/1100054405',
    'https://BARNESANDNOBLE.COM/w/x/1',
  ])('accepts %s', (url) => expect(isBookUrl(url)).toBe(true))

  it.each([
    'http://www.amazon.com/dp/0547928221',
    'ftp://amazon.com/dp/1',
    'https://example.com/dp/0547928221',
    'https://evilamazon.com/dp/1',
    'https://amazon.com.evil.com/dp/1',
    'https://amazon.com@evil.com/dp/1',
    'https://evil.com/?u=https://amazon.com/dp/1',
    'https://amazon.com:8443/dp/1',
    'http://www.barnesandnoble.com/w/x/1',
    'https://evilbarnesandnoble.com/w/x/1',
    'https://barnesandnoble.com.evil.com/w/x/1',
    'https://notbn.com/w/x/1',
    'https://bn.com@evil.com/w/x/1',
    'https://user:pw@www.barnesandnoble.com/w/x/1',
    'https://www.barnesandnoble.com:8443/w/x/1',
    'https://bookshop.org/a/1/9780547928227',
    'https://localhost/dp/1',
    'https://10.0.0.1/dp/1',
    '//amazon.com/dp/1',
    'amazon.com/dp/1',
    'not a url',
    'https://amazon.com/has space',
    '',
    '   ',
  ])('rejects %s', (url) => expect(isBookUrl(url)).toBe(false))
})

describe('retailerName and viewLinkLabel', () => {
  it.each([
    ['https://www.amazon.com/dp/0547928221', 'Amazon'],
    ['https://a.co/d/abc123', 'Amazon'],
    ['https://amzn.to/3abcdef', 'Amazon'],
    ['https://www.barnesandnoble.com/w/x/1100054405', 'Barnes & Noble'],
    ['https://www.bn.com/w/x/1', 'Barnes & Noble'],
  ])('%s is %s', (url, store) => {
    expect(retailerName(url)).toBe(store)
    expect(viewLinkLabel(url)).toBe(`View on ${store}`)
  })

  it('does not guess for anything else', () => {
    expect(retailerName('https://evilbarnesandnoble.com/x')).toBeUndefined()
    expect(retailerName('not a url')).toBeUndefined()
    expect(viewLinkLabel('https://example.com/x')).toBe('View the book')
  })
})

describe('validateSubmit', () => {
  const ok = {
    submitterName: 'Zach',
    bookTitle: 'The Hobbit',
    bookUrl: 'https://www.amazon.com/dp/0547928221',
    wrappingPaper: 'holly',
  }

  it('accepts a complete form', () => expect(validateSubmit(ok)).toEqual({}))

  it('requires every field, treating spaces as empty', () => {
    const errors = validateSubmit({ submitterName: ' ', bookTitle: '  ', bookUrl: '', wrappingPaper: '' })
    expect(Object.keys(errors).sort()).toEqual(['bookTitle', 'bookUrl', 'submitterName', 'wrappingPaper'])
  })

  it('applies the backend length limits after trimming', () => {
    expect(validateSubmit({ ...ok, submitterName: 'x'.repeat(100) })).toEqual({})
    expect(validateSubmit({ ...ok, submitterName: 'x'.repeat(101) }).submitterName).toBeTruthy()
    expect(validateSubmit({ ...ok, bookTitle: 'x'.repeat(300) })).toEqual({})
    expect(validateSubmit({ ...ok, bookTitle: 'x'.repeat(301) }).bookTitle).toBeTruthy()
    expect(validateSubmit({ ...ok, bookUrl: 'https://amazon.com/' + 'x'.repeat(2048) }).bookUrl).toBeTruthy()
  })

  it('explains a link that is not an Amazon or Barnes & Noble link', () => {
    const message = validateSubmit({ ...ok, bookUrl: 'https://example.com/book' }).bookUrl
    expect(message).toMatch(/amazon/i)
    expect(message).toMatch(/barnes & noble/i)
  })

  it('accepts a Barnes & Noble link', () => {
    expect(validateSubmit({ ...ok, bookUrl: 'https://www.barnesandnoble.com/w/the-hobbit/1100054405?ean=9780547928227' })).toEqual({})
  })

  it('rejects an unknown paper key', () => {
    expect(validateSubmit({ ...ok, wrappingPaper: 'not-a-paper' }).wrappingPaper).toBeTruthy()
  })
})
