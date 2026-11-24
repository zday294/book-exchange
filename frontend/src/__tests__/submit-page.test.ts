import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY } from '../stores/participant'

const URL = 'https://www.amazon.com/dp/0547928221'
const exchange = {
  code: 'K7M2PXA', name: 'Office Christmas', listed: true, status: 'OPEN', mode: 'SUBMIT',
  bookCount: 0, claimedCount: 0, createdAt: '2026-11-24T14:02:03Z',
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

type Handler = (init: { method?: string; body?: string; headers: Record<string, string> }) => Response | Promise<Response>

/** Serves the exchange on GET; `onSubmit` answers the POST. */
function stubApi(onSubmit: Handler = () => json({}, 201), exchangeBody: unknown = exchange, exchangeStatus = 200) {
  const fetchMock = vi.fn().mockImplementation(async (url: string, init: Parameters<Handler>[0]) => {
    if (init.method === 'POST') return onSubmit(init)
    return json(exchangeBody, exchangeStatus)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const created = (over = {}) => ({
  id: 7, submitterName: 'Zach', bookTitle: 'The Hobbit', amazonUrl: URL, wrappingPaper: 'holly', claimed: false, ...over,
})

async function mountPage() {
  const router = createAppRouter(createMemoryHistory())
  router.push('/exchange/K7M2PXA/submit')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

async function fill(wrapper: VueWrapper, values: { name?: string; title?: string; url?: string; paper?: string }) {
  if (values.name !== undefined) await wrapper.get('#submitter').setValue(values.name)
  if (values.title !== undefined) await wrapper.get('#title').setValue(values.title)
  if (values.url !== undefined) await wrapper.get('#url').setValue(values.url)
  if (values.paper !== undefined) await wrapper.get(`[data-test=paper-${values.paper}]`).setValue(true)
}

async function submit(wrapper: VueWrapper) {
  await wrapper.get('[data-test=form]').trigger('submit')
  await flushPromises()
}

const sent = (fetchMock: ReturnType<typeof stubApi>) =>
  JSON.parse(fetchMock.mock.calls.find((c) => c[1].method === 'POST')![1].body)

describe('submit page', () => {
  beforeEach(() => localStorage.setItem(PARTICIPANT_STORAGE_KEY, 'Zach'))
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    document.body.innerHTML = ''
  })

  it('shows the form with the saved name filled in and every paper to choose from', async () => {
    stubApi()
    const { wrapper } = await mountPage()
    expect(wrapper.get('h1').text()).toBe('Add a book to Office Christmas')
    expect((wrapper.get('#submitter').element as HTMLInputElement).value).toBe('Zach')
    expect(wrapper.findAll('input[type=radio]')).toHaveLength(10)
    expect(wrapper.text()).toContain('Only the person who claims the book will see this link.')
  })

  it('lets the name be edited', async () => {
    stubApi()
    const { wrapper } = await mountPage()
    await fill(wrapper, { name: 'Pat' })
    expect((wrapper.get('#submitter').element as HTMLInputElement).value).toBe('Pat')
  })

  describe('checking the form before sending', () => {
    it('does not send an empty form and says what is missing, next to each field', async () => {
      const fetchMock = stubApi()
      const { wrapper } = await mountPage()
      await fill(wrapper, { name: '' })
      await submit(wrapper)

      expect(wrapper.get('[data-test=error-submitterName]').text()).toBeTruthy()
      expect(wrapper.get('[data-test=error-bookTitle]').text()).toBeTruthy()
      expect(wrapper.get('[data-test=error-amazonUrl]').text()).toBeTruthy()
      expect(wrapper.get('[data-test=error-wrappingPaper]').text()).toMatch(/choose a wrapping paper/i)
      expect(fetchMock.mock.calls.some((c) => c[1].method === 'POST')).toBe(false)
    })

    it('moves focus to the first problem', async () => {
      stubApi()
      const { wrapper } = await mountPage()
      await fill(wrapper, { title: '', url: URL, paper: 'holly' })
      await submit(wrapper)
      expect(document.activeElement).toBe(wrapper.get('#title').element)
    })

    it('rejects a link that is not an Amazon link', async () => {
      const fetchMock = stubApi()
      const { wrapper } = await mountPage()
      await fill(wrapper, { title: 'The Hobbit', url: 'https://example.com/book', paper: 'holly' })
      await submit(wrapper)
      expect(wrapper.get('[data-test=error-amazonUrl]').text()).toMatch(/amazon/i)
      expect(fetchMock.mock.calls.some((c) => c[1].method === 'POST')).toBe(false)
    })

    it('clears a message as soon as that field is edited', async () => {
      stubApi()
      const { wrapper } = await mountPage()
      await submit(wrapper)
      expect(wrapper.get('[data-test=error-bookTitle]').text()).toBeTruthy()
      await fill(wrapper, { title: 'The Hobbit' })
      expect(wrapper.get('[data-test=error-bookTitle]').text()).toBe('')
      await fill(wrapper, { paper: 'stars' })
      expect(wrapper.get('[data-test=error-wrappingPaper]').text()).toBe('')
    })
  })

  describe('wrapping paper', () => {
    it('highlights the chosen paper, and sends its key', async () => {
      const fetchMock = stubApi(() => json(created({ wrappingPaper: 'plaid' }), 201))
      const { wrapper } = await mountPage()
      await fill(wrapper, { title: 'The Hobbit', url: URL, paper: 'plaid' })
      expect(wrapper.get('[data-test=paper-plaid]').element.closest('label')!.classList.contains('selected')).toBe(true)
      expect(wrapper.get('[data-test=paper-holly]').element.closest('label')!.classList.contains('selected')).toBe(false)

      await submit(wrapper)
      expect(sent(fetchMock).wrappingPaper).toBe('plaid')
    })
  })

  describe('sending', () => {
    it('posts trimmed values to the exchange and shows the confirmation', async () => {
      const fetchMock = stubApi(() => json(created(), 201))
      const { wrapper } = await mountPage()
      await fill(wrapper, { name: '  Zach ', title: '  The Hobbit ', url: `  ${URL}  `, paper: 'holly' })
      await submit(wrapper)

      const post = fetchMock.mock.calls.find((c) => c[1].method === 'POST')!
      expect(post[0]).toBe('/api/exchanges/K7M2PXA/books')
      expect(JSON.parse(post[1].body)).toEqual({
        submitterName: 'Zach', bookTitle: 'The Hobbit', amazonUrl: URL, wrappingPaper: 'holly',
      })
      expect(wrapper.get('[data-test=success] h1').text()).toBe('Your book is under the tree!')
      expect(wrapper.get('[data-test=success] svg').attributes('aria-label')).toContain('Holly')
      expect(wrapper.find('[data-test=form]').exists()).toBe(false)
    })

    it('links to the exchange page afterwards', async () => {
      stubApi(() => json(created(), 201))
      const { wrapper, router } = await mountPage()
      await fill(wrapper, { title: 'The Hobbit', url: URL, paper: 'holly' })
      await submit(wrapper)
      await wrapper.get('[data-test=to-exchange]').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.fullPath).toBe('/exchange/K7M2PXA')
    })

    it('keeps the submitter as the current user in this browser', async () => {
      stubApi(() => json(created({ submitterName: 'Pat' }), 201))
      const { wrapper } = await mountPage()
      await fill(wrapper, { name: 'Pat', title: 'The Hobbit', url: URL, paper: 'holly' })
      await submit(wrapper)
      expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBe('Pat')
    })

    it('disables the button while sending', async () => {
      let finish!: (r: Response) => void
      stubApi(() => new Promise<Response>((r) => (finish = r)))
      const { wrapper } = await mountPage()
      await fill(wrapper, { title: 'The Hobbit', url: URL, paper: 'holly' })
      await wrapper.get('[data-test=form]').trigger('submit')
      expect((wrapper.get('[data-test=submit]').element as HTMLButtonElement).disabled).toBe(true)
      finish(json(created(), 201))
      await flushPromises()
      expect(wrapper.find('[data-test=success]').exists()).toBe(true)
    })
  })

  describe('problems reported by the server', () => {
    async function submitWith(handler: Handler) {
      stubApi(handler)
      const page = await mountPage()
      await fill(page.wrapper, { title: 'The Hobbit', url: URL, paper: 'holly' })
      await submit(page.wrapper)
      return page
    }

    it('shows 400 problems next to the fields and keeps what was typed', async () => {
      const { wrapper } = await submitWith(() =>
        json({ error: 'Validation failed', details: ['amazonUrl: must be an https link to Amazon', 'bookTitle: size must be between 0 and 300'] }, 400),
      )
      expect(wrapper.get('[data-test=error-amazonUrl]').text()).toContain('Amazon')
      expect(wrapper.get('[data-test=error-bookTitle]').text()).toContain('300')
      expect((wrapper.get('#title').element as HTMLInputElement).value).toBe('The Hobbit')
      expect((wrapper.get('#url').element as HTMLInputElement).value).toBe(URL)
      expect(wrapper.get('[data-test=paper-holly]').element.closest('label')!.classList.contains('selected')).toBe(true)
      expect(wrapper.find('[data-test=success]').exists()).toBe(false)
    })

    it('shows "already submitted" next to the name field', async () => {
      const { wrapper } = await submitWith(() =>
        json({ error: 'You have already submitted a book to this exchange', details: [] }, 409),
      )
      expect(wrapper.get('[data-test=error-submitterName]').text()).toBe(
        'You have already submitted a book to this exchange',
      )
      expect(document.activeElement).toBe(wrapper.get('#submitter').element)
      expect((wrapper.get('#title').element as HTMLInputElement).value).toBe('The Hobbit')
    })

    it('shows other conflicts (such as a closed exchange) as a general message', async () => {
      const { wrapper } = await submitWith(() =>
        json({ error: 'This exchange is closed and no longer accepts books', details: [] }, 409),
      )
      expect(wrapper.get('[data-test=form-error]').text()).toContain('closed')
      expect((wrapper.get('#title').element as HTMLInputElement).value).toBe('The Hobbit')
    })

    it('shows a general message when the server cannot be reached, and allows another try', async () => {
      let up = false
      const { wrapper } = await submitWith(() => {
        if (!up) throw new TypeError('down')
        return json(created(), 201)
      })
      expect(wrapper.get('[data-test=form-error]').text()).toContain('Could not reach the server')
      expect((wrapper.get('[data-test=submit]').element as HTMLButtonElement).disabled).toBe(false)

      up = true
      await submit(wrapper)
      expect(wrapper.find('[data-test=success]').exists()).toBe(true)
    })
  })

  describe('the exchange itself', () => {
    it('says so for an unknown exchange', async () => {
      stubApi(undefined, { error: 'Exchange not found', details: [] }, 404)
      const { wrapper } = await mountPage()
      expect(wrapper.get('[data-test=notfound]').text()).toContain('K7M2PXA')
      expect(wrapper.find('[data-test=form]').exists()).toBe(false)
    })

    it('does not offer the form for a closed exchange', async () => {
      stubApi(undefined, { ...exchange, status: 'CLOSED' })
      const { wrapper } = await mountPage()
      expect(wrapper.get('[data-test=closed]').text()).toContain('closed')
      expect(wrapper.find('[data-test=form]').exists()).toBe(false)
    })

    it('offers a retry when the exchange cannot be loaded', async () => {
      let up = false
      vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => {
        if (!up) throw new TypeError('down')
        return json(exchange)
      }))
      const { wrapper } = await mountPage()
      expect(wrapper.find('[data-test=error]').exists()).toBe(true)
      up = true
      await wrapper.get('[data-test=retry]').trigger('click')
      await flushPromises()
      expect(wrapper.find('[data-test=form]').exists()).toBe(true)
    })
  })
})
