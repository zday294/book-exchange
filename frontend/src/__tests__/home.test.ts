import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import type { ExchangeList, ExchangeSummary } from '../api/exchanges'
import { readLastExchange } from '../lib/lastExchange'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY } from '../stores/participant'

const summary = (code: string, name: string, over: Partial<ExchangeSummary> = {}): ExchangeSummary => ({
  code, name, description: `About ${name}`, status: 'OPEN', bookCount: 3, claimedCount: 1,
  createdAt: '2026-11-24T14:02:03Z', ...over,
})
const page = (items: ExchangeSummary[], over: Partial<ExchangeList> = {}): ExchangeList => ({
  items, page: 0, size: 12, totalItems: items.length, totalPages: items.length ? 1 : 0, ...over,
})

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

interface Backend {
  list?: (params: URLSearchParams) => ExchangeList | Response | Promise<ExchangeList | Response>
  /** codes that exist (anything else is a 404) */
  known?: string[]
}

function stubBackend(backend: Backend) {
  const fetchMock = vi.fn().mockImplementation(async (url: string) => {
    if (url.startsWith('/api/exchanges?')) {
      const result = await (backend.list ?? (() => page([])))(new URLSearchParams(url.split('?')[1]))
      return result instanceof Response ? result : json(result)
    }
    const code = decodeURIComponent(url.replace('/api/exchanges/', ''))
    if ((backend.known ?? []).includes(code)) return json(summary(code, 'Found ' + code))
    return json({ error: 'Exchange not found', details: [] }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const listCalls = (m: ReturnType<typeof stubBackend>) =>
  m.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith('/api/exchanges?'))
const lastParams = (m: ReturnType<typeof stubBackend>) => new URLSearchParams(listCalls(m).at(-1)!.split('?')[1])

async function mountHome() {
  const router = createAppRouter(createMemoryHistory())
  router.push('/')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

describe('home page', () => {
  beforeEach(() => localStorage.setItem(PARTICIPANT_STORAGE_KEY, 'Alex'))
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    localStorage.clear()
    document.body.innerHTML = ''
  })

  describe('the list of public exchanges', () => {
    it('shows each exchange as a card with name, description, counts and status', async () => {
      stubBackend({
        list: () => page([
          summary('AAA1111', 'Office Party', { bookCount: 1, claimedCount: 0 }),
          summary('BBB2222', 'Family', { status: 'CLOSED', bookCount: 12, claimedCount: 12 }),
        ]),
      })
      const { wrapper } = await mountHome()

      const open = wrapper.get('[data-test=exchange-AAA1111]')
      expect(open.get('[data-test=card-name]').text()).toBe('Office Party')
      expect(open.get('[data-test=card-description]').text()).toBe('About Office Party')
      expect(open.get('[data-test=card-counts]').text()).toBe('1 book · 0 claimed')
      expect(open.get('[data-test=card-status]').text()).toBe('Open')
      expect(open.attributes('href')).toBe('/exchange/AAA1111')

      const closed = wrapper.get('[data-test=exchange-BBB2222]')
      expect(closed.get('[data-test=card-status]').text()).toBe('Closed')
      expect(closed.classes()).toContain('closed')
      expect(closed.get('[data-test=card-counts]').text()).toBe('12 books · 12 claimed')
    })

    it('keeps the server order (open first, then closed)', async () => {
      stubBackend({ list: () => page([summary('O1', 'Open one'), summary('C1', 'Closed one', { status: 'CLOSED' })]) })
      const { wrapper } = await mountHome()
      const names = wrapper.findAll('[data-test=card-name]').map((n) => n.text())
      expect(names).toEqual(['Open one', 'Closed one'])
    })

    it('opens an exchange when its card is clicked', async () => {
      stubBackend({ list: () => page([summary('AAA1111', 'Office Party')]) })
      const { wrapper, router } = await mountHome()
      await wrapper.get('[data-test=exchange-AAA1111]').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.fullPath).toBe('/exchange/AAA1111')
    })

    it('asks for the first page, twelve at a time, with no filters', async () => {
      const m = stubBackend({})
      await mountHome()
      expect(lastParams(m).get('page')).toBe('0')
      expect(lastParams(m).get('size')).toBe('12')
      expect(lastParams(m).has('query')).toBe(false)
      expect(lastParams(m).has('status')).toBe(false)
    })

    it('says so when there are no public exchanges yet', async () => {
      stubBackend({ list: () => page([]) })
      const { wrapper } = await mountHome()
      expect(wrapper.get('[data-test=empty]').text()).toContain('No public exchanges yet')
    })

    it('shows an error with a retry that works', async () => {
      let up = false
      stubBackend({ list: () => (up ? page([summary('AAA1111', 'Office Party')]) : json({ error: 'x', details: [] }, 500)) })
      const { wrapper } = await mountHome()
      expect(wrapper.find('[data-test=error]').exists()).toBe(true)
      up = true
      await wrapper.get('[data-test=retry]').trigger('click')
      await flushPromises()
      expect(wrapper.find('[data-test=exchange-AAA1111]').exists()).toBe(true)
    })
  })

  describe('searching', () => {
    it('searches after a short pause in typing, not on every keystroke', async () => {
      vi.useFakeTimers()
      const m = stubBackend({})
      const { wrapper } = await mountHome()
      const before = listCalls(m).length

      const input = wrapper.get('[data-test=search]')
      await input.setValue('of')
      await input.setValue('office')
      expect(listCalls(m).length).toBe(before) // still waiting
      await vi.advanceTimersByTimeAsync(350)
      await flushPromises()

      expect(listCalls(m).length).toBe(before + 1)
      expect(lastParams(m).get('query')).toBe('office')
      expect(lastParams(m).get('page')).toBe('0')
    })

    it('shows the matches, and a "no match" message with a way to clear the search', async () => {
      vi.useFakeTimers()
      stubBackend({ list: (p) => (p.get('query') ? page([]) : page([summary('AAA1111', 'Office Party')])) })
      const { wrapper } = await mountHome()
      await wrapper.get('[data-test=search]').setValue('zebra')
      await vi.advanceTimersByTimeAsync(350)
      await flushPromises()

      expect(wrapper.get('[data-test=no-match]').text()).toContain('“zebra”')
      expect(wrapper.find('[data-test=empty]').exists()).toBe(false)

      await wrapper.get('[data-test=clear]').trigger('click')
      await flushPromises()
      expect((wrapper.get('[data-test=search]').element as HTMLInputElement).value).toBe('')
      expect(wrapper.find('[data-test=exchange-AAA1111]').exists()).toBe(true)
    })

    it('goes back to the first page when the search changes', async () => {
      vi.useFakeTimers()
      const m = stubBackend({ list: () => page([summary('A1', 'One')], { totalPages: 3, totalItems: 30 }) })
      const { wrapper } = await mountHome()
      await wrapper.get('[data-test=next]').trigger('click')
      await flushPromises()
      expect(lastParams(m).get('page')).toBe('1')

      await wrapper.get('[data-test=search]').setValue('x')
      await vi.advanceTimersByTimeAsync(350)
      await flushPromises()
      expect(lastParams(m).get('page')).toBe('0')
    })

    it('ignores an older answer that arrives after a newer one', async () => {
      vi.useFakeTimers()
      let release!: (r: ExchangeList) => void
      let calls = 0
      stubBackend({
        list: () => {
          calls++
          if (calls === 2) return new Promise<ExchangeList>((r) => (release = r)) // the "office" search is slow
          return calls === 1 ? page([summary('ALL1', 'Everything')]) : page([summary('FAM1', 'Family')])
        },
      })
      const { wrapper } = await mountHome()

      await wrapper.get('[data-test=search]').setValue('office')
      await vi.advanceTimersByTimeAsync(350)
      await wrapper.get('[data-test=search]').setValue('family')
      await vi.advanceTimersByTimeAsync(350)
      await flushPromises()
      release(page([summary('OFF1', 'Office')])) // late, and for the older search
      await flushPromises()

      expect(wrapper.find('[data-test=exchange-FAM1]').exists()).toBe(true)
      expect(wrapper.find('[data-test=exchange-OFF1]').exists()).toBe(false)
    })
  })

  describe('hiding closed exchanges', () => {
    it('shows closed ones by default and filters them out on request', async () => {
      const m = stubBackend({})
      const { wrapper } = await mountHome()
      expect(lastParams(m).has('status')).toBe(false)

      await wrapper.get('[data-test=hide-closed]').setValue(true)
      await flushPromises()
      expect(lastParams(m).get('status')).toBe('OPEN')
      expect(lastParams(m).get('page')).toBe('0')

      await wrapper.get('[data-test=hide-closed]').setValue(false)
      await flushPromises()
      expect(lastParams(m).has('status')).toBe(false)
    })
  })

  describe('pages', () => {
    const many = () => page([summary('A1', 'One')], { totalPages: 3, totalItems: 30 })

    it('shows no pager when everything fits on one page', async () => {
      stubBackend({ list: () => page([summary('A1', 'One')]) })
      const { wrapper } = await mountHome()
      expect(wrapper.find('[data-test=pager]').exists()).toBe(false)
    })

    it('moves between pages and disables the buttons at either end', async () => {
      const m = stubBackend({ list: (p) => ({ ...many(), page: Number(p.get('page')) }) })
      const { wrapper } = await mountHome()
      expect(wrapper.get('[data-test=page-label]').text()).toBe('Page 1 of 3')
      expect((wrapper.get('[data-test=prev]').element as HTMLButtonElement).disabled).toBe(true)

      await wrapper.get('[data-test=next]').trigger('click')
      await flushPromises()
      expect(lastParams(m).get('page')).toBe('1')
      expect(wrapper.get('[data-test=page-label]').text()).toBe('Page 2 of 3')

      await wrapper.get('[data-test=next]').trigger('click')
      await flushPromises()
      expect((wrapper.get('[data-test=next]').element as HTMLButtonElement).disabled).toBe(true)

      await wrapper.get('[data-test=prev]').trigger('click')
      await flushPromises()
      expect(lastParams(m).get('page')).toBe('1')
    })
  })

  describe('"Have a code?"', () => {
    it('opens the exchange for a known code', async () => {
      stubBackend({ known: ['K7M2PXA'] })
      const { wrapper, router } = await mountHome()
      await wrapper.get('[data-test=code-input]').setValue('K7M2PXA')
      await wrapper.get('[data-test=code-form]').trigger('submit')
      await flushPromises()
      expect(router.currentRoute.value.fullPath).toBe('/exchange/K7M2PXA')
    })

    it('ignores case and stray spaces', async () => {
      const m = stubBackend({ known: ['K7M2PXA'] })
      const { wrapper, router } = await mountHome()
      await wrapper.get('[data-test=code-input]').setValue('  k7m 2pxa ')
      await wrapper.get('[data-test=code-form]').trigger('submit')
      await flushPromises()
      expect(m.mock.calls.some((c) => c[0] === '/api/exchanges/K7M2PXA')).toBe(true)
      expect(router.currentRoute.value.fullPath).toBe('/exchange/K7M2PXA')
    })

    it('shows an error for an unknown code and stays on the home page', async () => {
      stubBackend({ known: [] })
      const { wrapper, router } = await mountHome()
      await wrapper.get('[data-test=code-input]').setValue('nope123')
      await wrapper.get('[data-test=code-form]').trigger('submit')
      await flushPromises()
      expect(wrapper.get('[data-test=code-error]').text()).toContain('NOPE123')
      expect(router.currentRoute.value.fullPath).toBe('/')
    })

    it('asks for a code when the box is empty, without calling the server', async () => {
      const m = stubBackend({})
      const { wrapper } = await mountHome()
      const calls = m.mock.calls.length
      await wrapper.get('[data-test=code-input]').setValue('   ')
      await wrapper.get('[data-test=code-form]').trigger('submit')
      await flushPromises()
      expect(wrapper.get('[data-test=code-error]').text()).toMatch(/enter the code/i)
      expect(m.mock.calls.length).toBe(calls)
    })

    it('reaches private exchanges, which are not in the list', async () => {
      stubBackend({ list: () => page([]), known: ['PRIVATE1'] })
      const { wrapper, router } = await mountHome()
      await wrapper.get('[data-test=code-input]').setValue('private1')
      await wrapper.get('[data-test=code-form]').trigger('submit')
      await flushPromises()
      expect(router.currentRoute.value.fullPath).toBe('/exchange/PRIVATE1')
    })
  })

  describe('create button', () => {
    it('links to the create page', async () => {
      stubBackend({})
      const { wrapper } = await mountHome()
      expect(wrapper.get('[data-test=create-link]').attributes('href')).toBe('/exchanges/new')
    })
  })

  describe('"Continue to ..." shortcut', () => {
    it('is not shown until an exchange has been visited', async () => {
      stubBackend({})
      const { wrapper } = await mountHome()
      expect(wrapper.find('[data-test=continue]').exists()).toBe(false)
    })

    it('offers the last visited exchange', async () => {
      localStorage.setItem('bookExchange.lastExchange', JSON.stringify({ code: 'AAA1111', name: 'Office Party' }))
      stubBackend({})
      const { wrapper } = await mountHome()
      const link = wrapper.get('[data-test=continue]')
      expect(link.text()).toContain('Continue to Office Party')
      expect(link.attributes('href')).toBe('/exchange/AAA1111')
    })

    it('ignores a damaged saved value', async () => {
      localStorage.setItem('bookExchange.lastExchange', '{not json')
      stubBackend({})
      const { wrapper } = await mountHome()
      expect(wrapper.find('[data-test=continue]').exists()).toBe(false)
      expect(readLastExchange()).toBeNull()
    })

    it('is remembered when an exchange page is opened, and forgotten if that exchange is gone', async () => {
      const exchange = { code: 'AAA1111', name: 'Office Party', listed: true, status: 'OPEN', mode: 'SUBMIT', bookCount: 0, claimedCount: 0, createdAt: 'x' }
      let gone = false
      vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
        if (gone) return json({ error: 'Exchange not found', details: [] }, 404)
        return json(url.endsWith('/books') ? [] : exchange)
      }))
      const router = createAppRouter(createMemoryHistory())
      router.push('/exchange/AAA1111')
      await router.isReady()
      const wrapper = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
      await flushPromises()
      expect(readLastExchange()).toEqual({ code: 'AAA1111', name: 'Office Party' })
      wrapper.unmount()

      gone = true
      router.push('/exchange/AAA1111?again=1')
      const second = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
      await flushPromises()
      expect(second.find('[data-test=notfound]').exists()).toBe(true)
      expect(readLastExchange()).toBeNull()
    })
  })
})
