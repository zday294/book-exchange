import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import type { Book } from '../api/books'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY } from '../stores/participant'

/** Stands in for the browser's EventSource (jsdom has none) so the test can play the server's part. */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  static CLOSED = 2
  static CONNECTING = 0
  static OPEN = 1
  readyState = 0
  closed = false
  private listeners = new Map<string, ((e: unknown) => void)[]>()
  constructor(public url: string) {
    FakeEventSource.instances.push(this)
  }
  addEventListener(name: string, fn: (e: unknown) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), fn])
  }
  close() {
    this.closed = true
    this.readyState = 2
  }
  // --- the test plays the server and the network ---
  open() {
    this.readyState = 1
    this.fire('open', {})
  }
  drop() { // the connection broke; the browser will retry by itself
    this.readyState = 0
    this.fire('error', {})
  }
  refuse() { // the server said no for good
    this.readyState = 2
    this.fire('error', {})
  }
  send(name: string, data: unknown) {
    this.fire(name, { data: typeof data === 'string' ? data : JSON.stringify(data) })
  }
  private fire(name: string, event: unknown) {
    for (const fn of this.listeners.get(name) ?? []) fn(event)
  }
}

const exchange = {
  code: 'K7M2PXA', name: 'Office Christmas', listed: true, status: 'OPEN', mode: 'SUBMIT', bookCount: 1, claimedCount: 0,
  createdAt: '2026-11-24T14:02:03Z', isHost: false,
}
const wrapped = (id: number, who: string): Book => ({ id, submitterName: who, wrappingPaper: 'holly', claimed: false, viewerIsClaimer: false })

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** The "server": what GET exchange and GET books answer right now. */
function stubServer(state: { exchange: Record<string, unknown>; books: Book[] }) {
  const fetchMock = vi.fn().mockImplementation(async (url: string) => json(url.endsWith('/books') ? state.books : state.exchange))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const reads = (m: ReturnType<typeof stubServer>) => m.mock.calls.filter((c) => (c[0] as string).endsWith('/books')).length
const stream = () => FakeEventSource.instances.at(-1)!

async function mountPage(name = 'Alex') {
  localStorage.setItem(PARTICIPANT_STORAGE_KEY, name)
  const router = createAppRouter(createMemoryHistory())
  router.push('/exchange/K7M2PXA')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

describe('live updates on the exchange page', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    localStorage.clear()
    document.body.innerHTML = ''
  })

  const settle = async (ms = 200) => {
    await vi.advanceTimersByTimeAsync(ms)
    await flushPromises()
  }

  describe('the connection', () => {
    it('watches this exchange', async () => {
      stubServer({ exchange, books: [] })
      await mountPage()
      expect(FakeEventSource.instances).toHaveLength(1)
      expect(stream().url).toBe('/api/exchanges/K7M2PXA/events?name=Alex')
    })

    it('is closed when the person leaves the page', async () => {
      stubServer({ exchange, books: [] })
      const { router } = await mountPage()
      const first = stream()
      await router.push('/exchanges/new')
      await flushPromises()
      expect(first.closed).toBe(true)
    })

    it('does nothing special when the browser cannot do live updates', async () => {
      vi.stubGlobal('EventSource', undefined)
      const m = stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      const { wrapper } = await mountPage()
      expect(wrapper.find('[data-test=book-1]').exists()).toBe(true)
      const before = reads(m)
      await settle(5100) // still polling
      expect(reads(m)).toBe(before + 1)
    })
  })

  describe('events', () => {
    it('shows a new present at once, and counts it', async () => {
      const server = { exchange, books: [wrapped(1, 'Zach')] }
      stubServer(server)
      const { wrapper } = await mountPage()
      stream().open()

      stream().send('book-added', { id: 2, submitterName: 'Sam', wrappingPaper: 'stars' })
      await flushPromises() // no waiting for a refresh

      const slot = wrapper.get('[data-test=book-2]')
      expect(slot.get('svg').attributes('aria-label')).toBe('Stars wrapped present for From: Sam')
      expect(slot.find('[data-test=opened]').exists()).toBe(false)
      expect(wrapper.get('[data-test=counts]').text()).toBe('2 presents · 0 taken')
    })

    it('reshapes a present when its cover proportions become known (nothing else about the book)', async () => {
      stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      const { wrapper } = await mountPage()
      stream().open()
      const widthOf = () => Number(wrapper.get('[data-test=book-1] [data-test=book-shape]').attributes('width'))
      const before = widthOf()

      stream().send('book-shaped', { id: 1, coverAspectRatio: 1.3 })
      await flushPromises()

      expect(widthOf()).toBeGreaterThan(before)
      expect(wrapper.get('[data-test=book-1]').find('[data-test=opened]').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('1.3')
    })

    it('keeps the new shape when an older read lands after the book-shaped event', async () => {
      const server = { exchange, books: [wrapped(1, 'Zach')] }
      const fetchMock = stubServer(server)
      const { wrapper } = await mountPage()
      stream().open()
      const widthOf = () => Number(wrapper.get('[data-test=book-1] [data-test=book-shape]').attributes('width'))
      const before = widthOf()

      // A read starts before the ratio is saved, and is slow to answer.
      let answerStale: (r: Response) => void = () => {}
      fetchMock.mockImplementationOnce(async () => json(exchange))
      fetchMock.mockImplementationOnce(() => new Promise<Response>((resolve) => (answerStale = resolve)))
      stream().send('book-added', { id: 1, submitterName: 'Zach', wrappingPaper: 'holly' }) // already there: no read
      stream().drop()
      stream().open() // reconnecting re-reads everything
      await flushPromises()

      server.books = [{ ...wrapped(1, 'Zach'), coverAspectRatio: 1.3 }]
      stream().send('book-shaped', { id: 1, coverAspectRatio: 1.3 })
      await flushPromises()
      const reshaped = widthOf()
      expect(reshaped).toBeGreaterThan(before)

      answerStale(json([wrapped(1, 'Zach')])) // the old answer, without the ratio
      await settle()

      expect(widthOf()).toBe(reshaped)
    })

    it('ignores a book-shaped event without a number', async () => {
      stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      const { wrapper } = await mountPage()
      stream().open()
      const before = wrapper.get('[data-test=book-1] [data-test=book-shape]').attributes('width')
      stream().send('book-shaped', { id: 1, coverAspectRatio: 'wide' })
      await flushPromises()
      expect(wrapper.get('[data-test=book-1] [data-test=book-shape]').attributes('width')).toBe(before)
    })

    it('does not show a present twice when it is already there', async () => {
      stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      const { wrapper } = await mountPage()
      stream().open()
      stream().send('book-added', { id: 1, submitterName: 'Zach', wrappingPaper: 'holly' })
      await flushPromises()
      expect(wrapper.findAll('[data-test=pile] > li')).toHaveLength(1)
    })

    it('opens a claimed present in place for everyone, without the link', async () => {
      stubServer({ exchange, books: [wrapped(1, 'Zach'), wrapped(2, 'Sam')] })
      const { wrapper } = await mountPage('Alex')
      stream().open()

      stream().send('book-claimed', {
        id: 2, claimedByName: 'Lee', claimedAt: '2026-12-01T18:00:00Z', bookTitle: 'The Hobbit',
        coverImageUrl: 'https://example.com/hobbit.jpg',
      })
      await flushPromises()

      const slot = wrapper.get('[data-test=book-2]')
      expect(slot.get('[data-test=title]').text()).toBe('The Hobbit')
      expect(slot.get('[data-test=taken]').text()).toContain('Claimed by Lee')
      expect(slot.get('[data-test=cover]').attributes('src')).toBe('https://example.com/hobbit.jpg')
      expect(slot.find('[data-test=amazon]').exists()).toBe(false)
      expect(wrapper.get('[data-test=counts]').text()).toBe('2 presents · 1 taken')
      expect(wrapper.find('[data-test=book-1] [data-test=opened]').exists()).toBe(false) // the others stay wrapped
    })

    it('shows a title placeholder when the event has no cover yet', async () => {
      stubServer({ exchange, books: [wrapped(2, 'Sam')] })
      const { wrapper } = await mountPage('Alex')
      stream().open()
      stream().send('book-claimed', { id: 2, claimedByName: 'Lee', claimedAt: 'x', bookTitle: 'The Hobbit' })
      await flushPromises()
      expect(wrapper.get('[data-test=book-2] [data-test=cover-placeholder]').text()).toBe('The Hobbit')
    })

    it('then re-reads the truth, so my own book link appears when I claimed on another device', async () => {
      const server = { exchange: { ...exchange, mode: 'CLAIM' }, books: [wrapped(2, 'Sam')] }
      stubServer(server)
      const { wrapper } = await mountPage('Alex')
      stream().open()

      stream().send('book-claimed', { id: 2, claimedByName: 'alex', claimedAt: 'x', bookTitle: 'The Hobbit' })
      await flushPromises()
      expect(wrapper.get('[data-test=book-2] [data-test=taken]').text()).toContain('You claimed this') // at once
      expect(wrapper.find('[data-test=amazon]').exists()).toBe(false) // the event never carries the link

      server.books = [{
        ...wrapped(2, 'Sam'), claimed: true, viewerIsClaimer: true, claimedByName: 'Alex', bookTitle: 'The Hobbit',
        amazonUrl: 'https://www.amazon.com/dp/0547928221',
      }]
      await settle()
      expect(wrapper.get('[data-test=amazon]').attributes('href')).toBe('https://www.amazon.com/dp/0547928221')
    })

    it('does not undo my own claim (and its link) when its event arrives', async () => {
      const mine: Book = {
        ...wrapped(2, 'Sam'), claimed: true, viewerIsClaimer: true, claimedByName: 'Alex', bookTitle: 'The Hobbit',
        amazonUrl: 'https://www.amazon.com/dp/0547928221',
      }
      stubServer({ exchange: { ...exchange, mode: 'CLAIM' }, books: [mine] })
      const { wrapper } = await mountPage('Alex')
      stream().open()
      stream().send('book-claimed', { id: 2, claimedByName: 'Alex', claimedAt: 'x', bookTitle: 'The Hobbit' })
      await flushPromises()
      expect(wrapper.find('[data-test=book-2] [data-test=amazon]').exists()).toBe(true)
      expect(wrapper.get('[data-test=counts]').text()).toBe('1 present · 1 taken') // counted once, not twice
    })

    it('follows a change of mode', async () => {
      stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      const { wrapper } = await mountPage()
      stream().open()
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('📚 Gathering books 📚')
      expect(wrapper.find('.add').exists()).toBe(true)

      stream().send('mode-changed', { mode: 'CLAIM' })
      await flushPromises()

      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('🎅 Exchange Time! 🎁')
      expect(wrapper.find('.add').exists()).toBe(false)
      expect(wrapper.get('[data-test=book-1] [data-test=present]').attributes('aria-disabled')).toBe('false')
    })

    it('ignores messages it does not understand', async () => {
      stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      const { wrapper } = await mountPage()
      stream().open()
      stream().send('book-added', 'this is not json')
      stream().send('book-added', '"just a string"')
      stream().send('book-added', 'null')
      stream().send('something-new', { id: 9 })
      await flushPromises()
      expect(wrapper.findAll('[data-test=pile] > li')).toHaveLength(1)
      expect(wrapper.find('[data-test=error]').exists()).toBe(false)
    })

    it('reads the pile once after a burst of events, not once per event', async () => {
      const m = stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      await mountPage()
      stream().open()
      const before = reads(m)

      for (let id = 2; id <= 6; id++) stream().send('book-added', { id, submitterName: 'P' + id, wrappingPaper: 'holly' })
      await settle()
      expect(reads(m)).toBe(before + 1)
    })

    it('replaces what an event showed with the server truth afterwards', async () => {
      const server = { exchange, books: [wrapped(1, 'Zach')] }
      stubServer(server)
      const { wrapper } = await mountPage()
      stream().open()

      stream().send('book-added', { id: 2, submitterName: 'Sam', wrappingPaper: 'stars' })
      server.books = [wrapped(1, 'Zach'), wrapped(2, 'Sam'), wrapped(3, 'Pat')] // one more arrived meanwhile
      await settle()
      expect(wrapper.findAll('[data-test=pile] > li')).toHaveLength(3)
    })
  })

  describe('polling as the fallback', () => {
    it('stops polling while the live connection is up', async () => {
      const m = stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      await mountPage()
      stream().open()
      const before = reads(m)
      await settle(20000)
      expect(reads(m)).toBe(before)
    })

    it('polls until the connection is open, and again after it drops', async () => {
      const m = stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      await mountPage()
      let before = reads(m)
      await settle(5100) // not connected yet: polling
      expect(reads(m)).toBe(before + 1)

      stream().open()
      before = reads(m)
      await settle(5100)
      expect(reads(m)).toBe(before)

      stream().drop() // connection lost: back to polling
      await settle(5100)
      expect(reads(m)).toBe(before + 1)
    })

    it('re-reads everything when the connection comes back, to catch what was missed', async () => {
      const server = { exchange, books: [wrapped(1, 'Zach')] }
      const m = stubServer(server)
      const { wrapper } = await mountPage()
      stream().open()
      stream().drop()

      server.books = [wrapped(1, 'Zach'), wrapped(2, 'Missed')]
      const before = reads(m)
      stream().open() // the browser reconnected
      await settle(0)

      expect(reads(m)).toBe(before + 1)
      expect(wrapper.find('[data-test=book-2]').exists()).toBe(true)
    })

    it('keeps polling when the server refuses the stream, and tries again later', async () => {
      const m = stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      await mountPage()
      stream().refuse()
      expect(stream().closed).toBe(true)
      expect(FakeEventSource.instances).toHaveLength(1)
      let before = reads(m)
      await settle(5100) // polling meanwhile, and a fresh attempt after the pause
      expect(reads(m)).toBeGreaterThan(before)
      expect(FakeEventSource.instances).toHaveLength(2)

      before = reads(m)
      stream().open() // the server is back: live again, with a re-read to catch up
      await settle(0)
      expect(reads(m)).toBe(before + 1)
      before = reads(m)
      await settle(20000)
      expect(reads(m)).toBe(before) // polling is off again
    })

    it('waits longer and longer between attempts, and stops when the page is left', async () => {
      stubServer({ exchange, books: [wrapped(1, 'Zach')] })
      const { router } = await mountPage()
      stream().refuse()
      await settle(5100)
      expect(FakeEventSource.instances).toHaveLength(2)
      stream().refuse()
      await settle(5100) // the pause has doubled: not yet
      expect(FakeEventSource.instances).toHaveLength(2)
      await settle(5000)
      expect(FakeEventSource.instances).toHaveLength(3)

      stream().refuse()
      await router.push('/exchanges/new')
      await flushPromises()
      await settle(120000)
      expect(FakeEventSource.instances).toHaveLength(3) // no more attempts once the page is gone
    })
  })
})
