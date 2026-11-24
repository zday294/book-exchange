import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import type { Book } from '../api/books'
import { saveHostToken } from '../lib/hostToken'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY } from '../stores/participant'

const base = {
  code: 'K7M2PXA', name: 'Office Christmas', description: 'Bring a book', organizerName: 'Zach', listed: true,
  status: 'OPEN', mode: 'SUBMIT', bookCount: 2, claimedCount: 0, createdAt: '2026-11-24T14:02:03Z', isHost: false,
}
const unclaimed: Book = { id: 1, submitterName: 'Sam', wrappingPaper: 'holly', claimed: false, viewerIsClaimer: false }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

type Init = { method?: string; body?: string; headers: Record<string, string> }

function stubApi(state: { exchange: Record<string, unknown>; books?: Book[]; put?: (body: { mode: string }, init: Init) => Response | Promise<Response> }) {
  const fetchMock = vi.fn().mockImplementation(async (url: string, init: Init) => {
    if (url.endsWith('/books')) return json(state.books ?? [unclaimed])
    if (url.endsWith('/mode') && init.method === 'PUT') {
      const body = JSON.parse(init.body!)
      if (state.put) return state.put(body, init)
      state.exchange = { ...state.exchange, mode: body.mode }
      return json(state.exchange)
    }
    return json(state.exchange)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const puts = (m: ReturnType<typeof stubApi>) => m.mock.calls.filter((c) => c[1].method === 'PUT')

async function mountAt(path = '/exchange/K7M2PXA') {
  localStorage.setItem(PARTICIPANT_STORAGE_KEY, 'Alex')
  const router = createAppRouter(createMemoryHistory())
  router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('submit mode and claim mode', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    document.body.innerHTML = ''
  })

  describe('what everyone sees on the exchange page', () => {
    it('says it is submit mode, offers "Add a book", and holds the presents back', async () => {
      stubApi({ exchange: base })
      const wrapper = await mountAt()
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('📚 Gathering books 📚')
      expect(wrapper.find('.add a').exists()).toBe(true)
      const present = wrapper.get('[data-test=book-1] [data-test=present]')
      expect(present.attributes('aria-disabled')).toBe('true')
      expect(present.attributes('title')).toBe("Claiming hasn't started yet")
    })

    it('keeps the emoji out of what screen readers read', async () => {
      stubApi({ exchange: base })
      const wrapper = await mountAt()
      const hidden = wrapper.findAll('[data-test=mode-banner] [aria-hidden=true]').map((n) => n.text())
      expect(hidden).toEqual(['📚', '📚'])
      wrapper.unmount()

      stubApi({ exchange: { ...base, mode: 'CLAIM' } })
      const claim = await mountAt()
      expect(claim.findAll('[data-test=mode-banner] [aria-hidden=true]').map((n) => n.text())).toEqual(['🎅', '🎁'])
    })

    it('says it is claim mode, lets people claim, and no longer offers "Add a book"', async () => {
      stubApi({ exchange: { ...base, mode: 'CLAIM' } })
      const wrapper = await mountAt()
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('🎅 Exchange Time! 🎁')
      expect(wrapper.find('.add').exists()).toBe(false)
      expect(wrapper.get('[data-test=book-1] [data-test=present]').attributes('aria-disabled')).toBe('false')
    })

    it('offers "Add a book" in the empty state only in submit mode', async () => {
      stubApi({ exchange: base, books: [] })
      let wrapper = await mountAt()
      expect(wrapper.get('[data-test=empty]').text()).toContain('No presents yet — add one!')
      expect(wrapper.find('[data-test=empty] a').exists()).toBe(true)
      wrapper.unmount()

      stubApi({ exchange: { ...base, mode: 'CLAIM' }, books: [] })
      wrapper = await mountAt()
      expect(wrapper.get('[data-test=empty]').text()).toContain('No presents were brought')
      expect(wrapper.find('[data-test=empty] a').exists()).toBe(false)
    })

    it('says the exchange is closed, whatever its mode, and offers neither', async () => {
      stubApi({ exchange: { ...base, status: 'CLOSED', mode: 'CLAIM' } })
      const wrapper = await mountAt()
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('This exchange is closed.')
      expect(wrapper.find('.add').exists()).toBe(false)
      expect(wrapper.get('[data-test=book-1] [data-test=present]').attributes('title')).toBe('This exchange is closed')
    })
  })

  describe('the submit page', () => {
    it('shows the form in submit mode', async () => {
      stubApi({ exchange: base })
      const wrapper = await mountAt('/exchange/K7M2PXA/submit')
      expect(wrapper.find('[data-test=form]').exists()).toBe(true)
    })

    it('says submissions are closed in claim mode, with no form', async () => {
      stubApi({ exchange: { ...base, mode: 'CLAIM' } })
      const wrapper = await mountAt('/exchange/K7M2PXA/submit')
      expect(wrapper.get('[data-test=submissions-closed]').text()).toContain('Submissions are closed')
      expect(wrapper.find('[data-test=form]').exists()).toBe(false)
      expect(wrapper.get('[data-test=submissions-closed] a').attributes('href')).toBe('/exchange/K7M2PXA')
    })

    it('keeps the "closed" message for a closed exchange', async () => {
      stubApi({ exchange: { ...base, status: 'CLOSED', mode: 'CLAIM' } })
      const wrapper = await mountAt('/exchange/K7M2PXA/submit')
      expect(wrapper.find('[data-test=closed]').exists()).toBe(true)
    })

    it('shows the server message if the mode changed while the form was open', async () => {
      const m = stubApi({ exchange: base })
      m.mockImplementation(async (url: string, init: Init) => {
        if (init.method === 'POST') {
          return json({ error: 'This exchange has moved on to claiming, so no more books can be submitted', details: [] }, 409)
        }
        return json(base)
      })
      const wrapper = await mountAt('/exchange/K7M2PXA/submit')
      await wrapper.get('#title').setValue('The Hobbit')
      await wrapper.get('#url').setValue('https://www.amazon.com/dp/0547928221')
      await wrapper.get('[data-test=paper-holly]').setValue(true)
      await wrapper.get('[data-test=form]').trigger('submit')
      await flushPromises()
      expect(wrapper.get('[data-test=form-error]').text()).toContain('moved on to claiming')
      expect((wrapper.get('#title').element as HTMLInputElement).value).toBe('The Hobbit')
    })
  })

  describe('the host controls', () => {
    beforeEach(() => saveHostToken('K7M2PXA', 'mine'))
    const asHost = (over: Record<string, unknown> = {}) => ({ ...base, isHost: true, ...over })

    it('are not shown to anyone but the host', async () => {
      stubApi({ exchange: base })
      const wrapper = await mountAt()
      expect(wrapper.find('[data-test=host-mode]').exists()).toBe(false)
    })

    it('describe the current mode and the pile', async () => {
      stubApi({ exchange: asHost({ bookCount: 5, claimedCount: 2 }) })
      const wrapper = await mountAt()
      expect(wrapper.get('[data-test=mode-description]').text()).toContain('Submit mode')
      expect(wrapper.get('[data-test=mode-counts]').text()).toBe('5 books in the pile · 2 claimed')
      expect(wrapper.get('[data-test=mode-switch]').text()).toBe('Start claiming…')
    })

    it('ask before starting to claim, and say what it means', async () => {
      stubApi({ exchange: asHost() })
      const wrapper = await mountAt()
      await wrapper.get('[data-test=mode-switch]').trigger('click')
      const dialog = wrapper.get('[role=dialog]')
      expect(dialog.text()).toContain('Start claiming?')
      expect(dialog.get('[data-test=mode-confirm-text]').text()).toContain('Nobody can submit a book after this')
      expect(dialog.find('[data-test=mode-no-books]').exists()).toBe(false)
    })

    it('warn when nothing has been submitted yet, but still allow it', async () => {
      stubApi({ exchange: asHost({ bookCount: 0 }) })
      const wrapper = await mountAt()
      await wrapper.get('[data-test=mode-switch]').trigger('click')
      expect(wrapper.get('[data-test=mode-no-books]').text()).toContain('nothing to claim')
      expect((wrapper.get('[data-test=mode-confirm]').element as HTMLButtonElement).disabled).toBe(false)
    })

    it('change nothing when cancelled (button or Escape)', async () => {
      const m = stubApi({ exchange: asHost() })
      const wrapper = await mountAt()
      await wrapper.get('[data-test=mode-switch]').trigger('click')
      await wrapper.get('[data-test=mode-cancel]').trigger('click')
      expect(wrapper.find('[role=dialog]').exists()).toBe(false)

      await wrapper.get('[data-test=mode-switch]').trigger('click')
      await wrapper.get('[role=dialog]').trigger('keydown', { key: 'Escape' })
      expect(wrapper.find('[role=dialog]').exists()).toBe(false)
      expect(puts(m)).toHaveLength(0)
    })

    it('switch to claim mode with the host token, and the whole page follows', async () => {
      const m = stubApi({ exchange: asHost() })
      const wrapper = await mountAt()
      await wrapper.get('[data-test=mode-switch]').trigger('click')
      await wrapper.get('[data-test=mode-confirm]').trigger('click')
      await flushPromises()

      const [url, init] = puts(m)[0]
      expect(url).toBe('/api/exchanges/K7M2PXA/mode')
      expect(JSON.parse(init.body)).toEqual({ mode: 'CLAIM' })
      expect(init.headers['X-Host-Token']).toBe('mine')

      expect(wrapper.find('[role=dialog]').exists()).toBe(false)
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('🎅 Exchange Time! 🎁')
      expect(wrapper.get('[data-test=mode-description]').text()).toContain('Claim mode')
      expect(wrapper.get('[data-test=mode-switch]').text()).toBe('Go back to submitting…')
      expect(wrapper.find('.add').exists()).toBe(false)
      expect(wrapper.get('[data-test=book-1] [data-test=present]').attributes('aria-disabled')).toBe('false')
    })

    it('can switch back to submitting, with a calmer confirmation', async () => {
      const m = stubApi({ exchange: asHost({ mode: 'CLAIM' }) })
      const wrapper = await mountAt()
      await wrapper.get('[data-test=mode-switch]').trigger('click')
      expect(wrapper.get('[role=dialog]').text()).toContain('Go back to submitting?')
      expect(wrapper.get('[data-test=mode-confirm-text]').text()).toContain('already claimed stay claimed')
      await wrapper.get('[data-test=mode-confirm]').trigger('click')
      await flushPromises()

      expect(JSON.parse(puts(m)[0][1].body)).toEqual({ mode: 'SUBMIT' })
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('📚 Gathering books 📚')
    })

    it('are disabled with an explanation for a closed exchange', async () => {
      stubApi({ exchange: asHost({ status: 'CLOSED' }) })
      const wrapper = await mountAt()
      expect((wrapper.get('[data-test=mode-switch]').element as HTMLButtonElement).disabled).toBe(true)
      expect(wrapper.get('[data-test=mode-closed]').text()).toContain('closed')
    })

    it('say so when the server refuses because this is not the host', async () => {
      stubApi({ exchange: asHost(), put: () => json({ error: 'Only the host of this exchange can change it', details: [] }, 403) })
      const wrapper = await mountAt()
      await wrapper.get('[data-test=mode-switch]').trigger('click')
      await wrapper.get('[data-test=mode-confirm]').trigger('click')
      await flushPromises()
      expect(wrapper.get('[data-test=mode-error]').text()).toMatch(/not the host/i)
      expect(wrapper.find('[role=dialog]').exists()).toBe(false)
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('📚 Gathering books 📚') // nothing changed
    })

    it('report a network problem and allow another try', async () => {
      let up = false
      stubApi({
        exchange: asHost(),
        put: () => {
          if (!up) throw new TypeError('down')
          return json(asHost({ mode: 'CLAIM' }))
        },
      })
      const wrapper = await mountAt()
      await wrapper.get('[data-test=mode-switch]').trigger('click')
      await wrapper.get('[data-test=mode-confirm]').trigger('click')
      await flushPromises()
      expect(wrapper.get('[data-test=mode-error]').text()).toContain('Could not reach the server')

      up = true
      await wrapper.get('[data-test=mode-switch]').trigger('click')
      await wrapper.get('[data-test=mode-confirm]').trigger('click')
      await flushPromises()
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('🎅 Exchange Time! 🎁')
    })

    it('send only one request when confirm is pressed twice quickly', async () => {
      let release!: (r: Response) => void
      const m = stubApi({ exchange: asHost(), put: () => new Promise<Response>((r) => (release = r)) })
      const wrapper = await mountAt()
      await wrapper.get('[data-test=mode-switch]').trigger('click')
      await wrapper.get('[data-test=mode-confirm]').trigger('click')
      await wrapper.get('[data-test=mode-confirm]').trigger('click')
      expect(puts(m)).toHaveLength(1)
      expect(wrapper.get('[data-test=mode-confirm]').text()).toBe('Switching…')
      release(json(asHost({ mode: 'CLAIM' })))
      await flushPromises()
    })
  })
})
