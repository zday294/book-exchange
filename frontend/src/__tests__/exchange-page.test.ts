import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import type { Book } from '../api/books'
import { presentLayout } from '../lib/layout'
import { createAppRouter } from '../router'
import { saveMySubmission } from '../lib/mySubmission'
import { PARTICIPANT_STORAGE_KEY, useParticipantStore } from '../stores/participant'

const exchange = {
  code: 'K7M2PXA',
  name: 'Office Christmas',
  description: 'Bring a book under $20',
  listed: true,
  status: 'OPEN',
  mode: 'SUBMIT',
  bookCount: 3,
  claimedCount: 2,
  createdAt: '2026-11-24T14:02:03Z',
}
const URL = 'https://www.amazon.com/dp/0547928221'

const unclaimed: Book = {
  id: 1, submitterName: 'Zach', wrappingPaper: 'holly', claimed: false, viewerIsClaimer: false,
}
const claimedByAlex: Book = {
  id: 2, submitterName: 'Sam', wrappingPaper: 'stars', claimed: true, viewerIsClaimer: false,
  claimedByName: 'Alex', claimedAt: '2026-12-01T18:02:11Z', bookTitle: 'The Hobbit',
  coverImageUrl: 'https://example.com/hobbit.jpg',
}
const claimedByMe: Book = {
  id: 3, submitterName: 'Pat', wrappingPaper: 'plaid', claimed: true, viewerIsClaimer: true,
  claimedByName: 'Zach', claimedAt: '2026-12-01T18:05:00Z', bookTitle: 'Dune',
  coverImageUrl: 'https://example.com/dune.jpg', bookUrl: URL,
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** Routes the page's two requests; `books` may be a function so a test can change the pile. */
function stubApi(books: Book[] | (() => Book[]), options: { exchangeStatus?: number; mode?: string } = {}) {
  const fetchMock = vi.fn().mockImplementation(async (url: string) => {
    if (url.endsWith('/books')) return json(typeof books === 'function' ? books() : books)
    if (options.exchangeStatus && options.exchangeStatus !== 200) {
      return json({ error: 'Exchange not found', details: [] }, options.exchangeStatus)
    }
    return json(options.mode ? { ...exchange, mode: options.mode } : exchange)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

async function mountPage(path = '/exchange/K7M2PXA') {
  const pinia = createPinia()
  const router = createAppRouter(createMemoryHistory())
  router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [pinia, router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, pinia, router }
}

describe('exchange page', () => {
  beforeEach(() => localStorage.setItem(PARTICIPANT_STORAGE_KEY, 'Zach'))
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    localStorage.clear()
    document.body.innerHTML = ''
  })

  it('shows the exchange, the tree and one present per book', async () => {
    stubApi([unclaimed, claimedByAlex, claimedByMe])
    const { wrapper } = await mountPage()

    expect(wrapper.get('[data-test=exchange-name]').text()).toBe('Office Christmas')
    expect(wrapper.text()).toContain('Bring a book under $20')
    expect(wrapper.get('[data-test=counts]').text()).toBe('3 presents · 2 taken')
    expect(wrapper.find('svg[aria-label="A decorated Christmas tree"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test=pile] > li')).toHaveLength(3)
  })

  it('shows an unclaimed present wrapped in its paper with a "From" tag and nothing else', async () => {
    stubApi([unclaimed])
    const { wrapper } = await mountPage()
    const slot = wrapper.get('[data-test=book-1]')

    expect(slot.get('svg').attributes('aria-label')).toBe('Holly wrapped present for From: Zach')
    expect(slot.text()).toContain('From: Zach')
    expect(slot.find('[data-test=opened]').exists()).toBe(false)
    expect(slot.find('img').exists()).toBe(false)
    expect(slot.find('a').exists()).toBe(false)
    expect(slot.text()).not.toMatch(/claimed|taken/i)
  })

  it('shows a claimed present opened with cover, title and who has it, to any viewer', async () => {
    stubApi([claimedByAlex])
    const { wrapper } = await mountPage()
    const slot = wrapper.get('[data-test=book-2]')

    expect(slot.get('[data-test=title]').text()).toBe('The Hobbit')
    expect(slot.get('[data-test=cover]').attributes('src')).toBe('https://example.com/hobbit.jpg')
    expect(slot.get('[data-test=cover]').attributes('alt')).toBe('Cover of The Hobbit')
    expect(slot.get('[data-test=taken]').text()).toContain('Claimed by Alex')
    expect(slot.text()).toContain('From Sam')
    expect(slot.find('svg').exists()).toBe(false) // no longer wrapped
  })

  it('shows no book link to anyone but the claimer', async () => {
    stubApi([unclaimed, claimedByAlex])
    const { wrapper } = await mountPage()
    expect(wrapper.find('[data-test=book-link]').exists()).toBe(false)
    expect(wrapper.find('a[href*="amazon"]').exists()).toBe(false)
  })

  it('shows the claimer their own book with "You claimed this" and a safe link labelled with the store', async () => {
    stubApi([unclaimed, claimedByMe])
    const { wrapper } = await mountPage()
    const slot = wrapper.get('[data-test=book-3]')

    expect(slot.get('[data-test=taken]').text()).toContain('You claimed this')
    const link = slot.get('[data-test=book-link]')
    expect(link.attributes('href')).toBe(URL)
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
  })

  it('says "View on Amazon" or "View on Barnes & Noble" depending on the claimer\'s link', async () => {
    const bn = 'https://www.barnesandnoble.com/w/dune-frank-herbert/1100054405?ean=9780441172719'
    stubApi([unclaimed, { ...claimedByMe, bookUrl: bn }])
    const { wrapper } = await mountPage()
    const link = wrapper.get('[data-test=book-3] [data-test=book-link]')
    expect(link.text()).toBe('View on Barnes & Noble')
    expect(link.attributes('href')).toBe(bn)
  })

  it('says "View on Amazon" for an Amazon link', async () => {
    stubApi([unclaimed, claimedByMe])
    const { wrapper } = await mountPage()
    expect(wrapper.get('[data-test=book-3] [data-test=book-link]').text()).toBe('View on Amazon')
  })

  it('shows a placeholder with the title when there is no cover yet or it fails to load', async () => {
    stubApi([{ ...claimedByAlex, coverImageUrl: undefined }])
    const { wrapper } = await mountPage()
    expect(wrapper.find('[data-test=cover]').exists()).toBe(false)
    expect(wrapper.get('[data-test=cover-placeholder]').text()).toBe('The Hobbit')
    wrapper.unmount()

    stubApi([claimedByAlex])
    const second = await mountPage()
    await second.wrapper.get('[data-test=cover]').trigger('error')
    expect(second.wrapper.find('[data-test=cover]').exists()).toBe(false)
    expect(second.wrapper.find('[data-test=cover-placeholder]').exists()).toBe(true)
  })

  describe('your status', () => {
    it('in claim mode, says you have not claimed yet', async () => {
      stubApi([unclaimed, claimedByAlex], { mode: 'CLAIM' })
      const { wrapper } = await mountPage()
      expect(wrapper.get('[data-test=my-status]').text()).toBe("Zach, you haven't claimed a book yet.")
    })

    it('in claim mode, shows what you claimed, with the link', async () => {
      stubApi([claimedByMe], { mode: 'CLAIM' })
      const { wrapper } = await mountPage()
      expect(wrapper.get('[data-test=my-status]').text()).toContain('you claimed Dune')
      expect(wrapper.get('[data-test=my-book-link]').attributes('href')).toBe(URL)
    })

    it('while gathering books, says you have not submitted a book', async () => {
      stubApi([claimedByAlex]) // nobody named Zach brought anything
      const { wrapper } = await mountPage()
      expect(wrapper.get('[data-test=my-status]').text()).toBe('Zach, you have not submitted a book.')
      expect(wrapper.text()).not.toContain('claimed a book yet')
    })

    it('while gathering books, says you submitted one and links to it', async () => {
      saveMySubmission('K7M2PXA', 'zach', { bookTitle: 'Dune', bookUrl: URL }) // name match ignores case
      stubApi([unclaimed]) // brought by Zach
      const { wrapper } = await mountPage()
      const status = wrapper.get('[data-test=my-status]')
      expect(status.text()).toContain('you have submitted a book: Dune')
      expect(status.get('[data-test=my-submitted-link]').attributes('href')).toBe(URL)
      expect(status.get('[data-test=my-submitted-link]').attributes('target')).toBe('_blank')
    })

    it("says you submitted one even when this browser doesn't remember it (no link then)", async () => {
      stubApi([unclaimed])
      const { wrapper } = await mountPage()
      expect(wrapper.get('[data-test=my-status]').text()).toBe('Zach, you have submitted a book.')
      expect(wrapper.find('[data-test=my-submitted-link]').exists()).toBe(false)
    })

    it("does not show another person's remembered book", async () => {
      saveMySubmission('K7M2PXA', 'Sam', { bookTitle: 'Secret', bookUrl: URL })
      stubApi([unclaimed]) // Zach's book; this browser's saved book is Sam's
      const { wrapper } = await mountPage()
      expect(wrapper.text()).not.toContain('Secret')
    })
  })

  describe('"Add a book"', () => {
    const fromMe: Book = { ...unclaimed, id: 4, submitterName: ' zach' }
    const fromSam: Book = { ...unclaimed, id: 5, submitterName: 'Sam' }
    const addLinks = (wrapper: Awaited<ReturnType<typeof mountPage>>['wrapper']) => wrapper.findAll('[data-test=add-book]')

    it('is offered while I have not submitted a book', async () => {
      stubApi([fromSam])
      const { wrapper } = await mountPage()
      expect(addLinks(wrapper)).toHaveLength(1)
      expect(addLinks(wrapper)[0]!.attributes('href')).toBe('/exchange/K7M2PXA/submit')
    })

    it('is offered in the empty state', async () => {
      stubApi([])
      const { wrapper } = await mountPage()
      expect(wrapper.get('[data-test=empty] [data-test=add-book]').text()).toBe('Add a book')
    })

    it('is not offered once I have a book in the pile (names compared ignoring case and spaces)', async () => {
      stubApi([fromSam, fromMe])
      const { wrapper } = await mountPage()
      expect(addLinks(wrapper)).toHaveLength(0)
      expect(wrapper.get('[data-test=my-status]').text()).toContain('you have submitted a book')
    })

    it('goes away without a reload when my book appears in the pile', async () => {
      vi.useFakeTimers()
      let pile = [fromSam]
      stubApi(() => pile)
      const { wrapper } = await mountPage()
      expect(addLinks(wrapper)).toHaveLength(1)

      pile = [fromSam, fromMe]
      await vi.advanceTimersByTimeAsync(5100)
      await flushPromises()
      expect(addLinks(wrapper)).toHaveLength(0)
    })

    it('follows a change of name, in both directions', async () => {
      stubApi([fromSam, fromMe])
      const { wrapper, pinia } = await mountPage()
      expect(addLinks(wrapper)).toHaveLength(0)

      useParticipantStore(pinia).setName('Pat')
      await flushPromises()
      expect(addLinks(wrapper)).toHaveLength(1)

      useParticipantStore(pinia).setName('ZACH')
      await flushPromises()
      expect(addLinks(wrapper)).toHaveLength(0)
    })

    it('is not offered outside submit mode, as before', async () => {
      stubApi([fromSam], { mode: 'CLAIM' })
      const { wrapper } = await mountPage()
      expect(addLinks(wrapper)).toHaveLength(0)
    })
  })

  describe('wrapped presents', () => {
    it("are drawn in each book's cover proportions, and a default shape when unknown", async () => {
      stubApi([{ ...unclaimed, coverAspectRatio: 1.3 }, { ...unclaimed, id: 9, coverAspectRatio: undefined }])
      const { wrapper } = await mountPage()
      const ratio = (id: number) => {
        const r = wrapper.get(`[data-test=book-${id}] [data-test=book-shape]`)
        return Number(r.attributes('width')) / Number(r.attributes('height'))
      }
      expect(ratio(1)).toBeCloseTo(1.3, 2)
      expect(ratio(9)).toBeCloseTo(0.67, 2)
    })
  })

  describe('opened books', () => {
    it('stand upright, while wrapped presents keep their angle', async () => {
      stubApi([unclaimed, claimedByAlex])
      const { wrapper } = await mountPage()
      const wrapped = wrapper.get('[data-test=book-1]').attributes('style')
      const opened = wrapper.get('[data-test=book-2]').attributes('style')
      expect(wrapped).toContain('rotate(')
      expect(opened).not.toContain('rotate')
    })
  })

  describe('loading problems', () => {
    it('says so for an unknown exchange', async () => {
      stubApi([], { exchangeStatus: 404 })
      const { wrapper } = await mountPage()
      expect(wrapper.get('[data-test=notfound]').text()).toContain('K7M2PXA')
    })

    it('shows an error with a retry that works', async () => {
      let up = false
      const fetchMock = vi.fn().mockImplementation(async (url: string) => {
        if (!up) throw new TypeError('down')
        return url.endsWith('/books') ? json([unclaimed]) : json(exchange)
      })
      vi.stubGlobal('fetch', fetchMock)
      const { wrapper } = await mountPage()
      expect(wrapper.find('[data-test=error]').exists()).toBe(true)

      up = true
      await wrapper.get('[data-test=retry]').trigger('click')
      await flushPromises()
      expect(wrapper.get('[data-test=exchange-name]').text()).toBe('Office Christmas')
    })
  })

  describe('staying up to date', () => {
    it('re-reads the pile every few seconds so new claims appear', async () => {
      vi.useFakeTimers()
      let pile: Book[] = [unclaimed]
      stubApi(() => pile)
      const { wrapper } = await mountPage()
      expect(wrapper.find('[data-test=opened]').exists()).toBe(false)

      pile = [{ ...unclaimed, claimed: true, claimedByName: 'Alex', bookTitle: 'The Hobbit' }, claimedByAlex]
      await vi.advanceTimersByTimeAsync(5100)
      await flushPromises()

      expect(wrapper.findAll('[data-test=opened]')).toHaveLength(2)
      expect(wrapper.get('[data-test=counts]').text()).toContain('2 presents')
    })

    it('keeps showing the pile when a background refresh fails', async () => {
      vi.useFakeTimers()
      let fail = false
      const fetchMock = vi.fn().mockImplementation(async (url: string) => {
        if (fail) throw new TypeError('down')
        return url.endsWith('/books') ? json([unclaimed]) : json(exchange)
      })
      vi.stubGlobal('fetch', fetchMock)
      const { wrapper } = await mountPage()

      fail = true
      await vi.advanceTimersByTimeAsync(5100)
      await flushPromises()
      expect(wrapper.find('[data-test=pile]').exists()).toBe(true)
      expect(wrapper.find('[data-test=error]').exists()).toBe(false)
    })

    it('stops refreshing when you leave the page', async () => {
      vi.useFakeTimers()
      const fetchMock = stubApi([unclaimed])
      const { wrapper, router } = await mountPage()
      const calls = fetchMock.mock.calls.length

      await router.push('/exchanges/new')
      await flushPromises()
      await vi.advanceTimersByTimeAsync(20000)
      expect(wrapper.find('[data-test=pile]').exists()).toBe(false)
      expect(fetchMock.mock.calls.length).toBe(calls)
    })

    it('reloads with the new name when you change who you are (so links follow the person)', async () => {
      const fetchMock = vi.fn().mockImplementation(async (url: string, init: { headers: Record<string, string> }) => {
        if (!url.endsWith('/books')) return json(exchange)
        return json(init.headers['X-Participant-Name'] === 'Zach' ? [claimedByMe] : [{ ...claimedByMe, viewerIsClaimer: false, bookUrl: undefined }])
      })
      vi.stubGlobal('fetch', fetchMock)
      const { wrapper, pinia } = await mountPage()
      expect(wrapper.find('[data-test=book-link]').exists()).toBe(true)

      useParticipantStore(pinia).setName('Sam')
      await flushPromises()

      expect(wrapper.find('[data-test=book-link]').exists()).toBe(false)
      expect(wrapper.get('[data-test=my-status]').text()).toContain('you have not submitted a book')
    })

    it('ignores a slow answer that was for a previous name', async () => {
      let release!: (r: Response) => void
      let calls = 0
      const fetchMock = vi.fn().mockImplementation(async (url: string) => {
        if (!url.endsWith('/books')) return json(exchange)
        calls++
        // the first books request (as Zach) is slow; the second (as Sam) is quick
        if (calls === 1) return new Promise<Response>((r) => (release = r))
        return json([unclaimed])
      })
      vi.stubGlobal('fetch', fetchMock)
      const { wrapper, pinia } = await mountPage()

      useParticipantStore(pinia).setName('Sam')
      await flushPromises()
      release(json([claimedByMe])) // Zach's answer, arriving late, must not overwrite Sam's view
      await flushPromises()

      expect(wrapper.find('[data-test=book-link]').exists()).toBe(false)
      expect(wrapper.findAll('[data-test=pile] > li')).toHaveLength(1)
    })
  })
})

describe('pile layout', () => {
  it('is stable: the same id always gets the same tilt and offset', () => {
    for (const id of [1, 2, 3, 17, 4242]) expect(presentLayout(id)).toEqual(presentLayout(id))
  })

  it('stays small and varies between presents', () => {
    const layouts = Array.from({ length: 60 }, (_, i) => presentLayout(i + 1))
    for (const l of layouts) {
      expect(Math.abs(l.rotateDeg)).toBeLessThanOrEqual(3)
      expect(l.offsetY).toBeGreaterThanOrEqual(0)
      expect(l.offsetY).toBeLessThan(15)
    }
    expect(new Set(layouts.map((l) => l.rotateDeg)).size).toBeGreaterThan(3)
  })
})
