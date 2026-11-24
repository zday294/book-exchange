import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import { getExchange } from '../api/exchanges'
import { forgetHostToken, getHostToken, hostHeaders, saveHostToken } from '../lib/hostToken'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY } from '../stores/participant'

const exchange = {
  code: 'K7M2PXA', name: 'Office Christmas', description: 'Bring a book under $20', organizerName: 'Zach',
  listed: true, status: 'OPEN', mode: 'SUBMIT', bookCount: 0, claimedCount: 0, createdAt: '2026-11-24T14:02:03Z', isHost: true,
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

type Init = { method?: string; body?: string; headers: Record<string, string> }
interface Api {
  /** what GET /api/exchanges/K7M2PXA answers (a function so a test can change it) */
  exchange?: () => object
  patch?: (body: Record<string, unknown>, init: Init) => Response | Promise<Response>
}

function stubApi(api: Api = {}) {
  const fetchMock = vi.fn().mockImplementation(async (url: string, init: Init) => {
    if (url.endsWith('/books')) return json([])
    if (init.method === 'PATCH') {
      return api.patch ? api.patch(JSON.parse(init.body!), init) : json({ ...exchange, ...JSON.parse(init.body!) })
    }
    return json(api.exchange ? api.exchange() : exchange)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const patches = (m: ReturnType<typeof stubApi>) => m.mock.calls.filter((c) => c[1].method === 'PATCH')

async function mountPage() {
  localStorage.setItem(PARTICIPANT_STORAGE_KEY, 'Zach')
  const router = createAppRouter(createMemoryHistory())
  router.push('/exchange/K7M2PXA')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

async function edit(wrapper: VueWrapper, values: { name?: string; description?: string; organizer?: string; listed?: boolean }) {
  if (values.name !== undefined) await wrapper.get('#host-name').setValue(values.name)
  if (values.description !== undefined) await wrapper.get('#host-description').setValue(values.description)
  if (values.organizer !== undefined) await wrapper.get('#host-organizer').setValue(values.organizer)
  if (values.listed !== undefined) await wrapper.get('[data-test=listed]').setValue(values.listed)
}

const save = async (wrapper: VueWrapper) => {
  await wrapper.get('[data-test=settings-form]').trigger('submit')
  await flushPromises()
}

describe('host token storage', () => {
  beforeEach(() => localStorage.clear())

  it('keeps a token per exchange', () => {
    saveHostToken('AAA1111', 'tokenA')
    saveHostToken('BBB2222', 'tokenB')
    expect(getHostToken('AAA1111')).toBe('tokenA')
    expect(getHostToken('BBB2222')).toBe('tokenB')
    expect(getHostToken('CCC3333')).toBeUndefined()
  })

  it('offers the header only when a token is saved, and can forget it', () => {
    expect(hostHeaders('AAA1111')).toEqual({})
    saveHostToken('AAA1111', 'tokenA')
    expect(hostHeaders('AAA1111')).toEqual({ 'X-Host-Token': 'tokenA' })
    forgetHostToken('AAA1111')
    expect(hostHeaders('AAA1111')).toEqual({})
  })

  it('copes with damaged storage', () => {
    localStorage.setItem('bookExchange.hostTokens', '{not json')
    expect(getHostToken('AAA1111')).toBeUndefined()
    saveHostToken('AAA1111', 'tokenA') // overwrites the damage
    expect(getHostToken('AAA1111')).toBe('tokenA')
    localStorage.setItem('bookExchange.hostTokens', '[1,2]')
    expect(getHostToken('AAA1111')).toBeUndefined()
  })
})

describe('sending the token', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia()) // the API client reads the saved name from the store
  })
  afterEach(() => vi.unstubAllGlobals())

  it('getExchange sends the saved token for that exchange only', async () => {
    const m = stubApi()
    saveHostToken('K7M2PXA', 'mine')
    saveHostToken('OTHER11', 'not-this-one')
    await getExchange('K7M2PXA')
    expect(m.mock.calls[0][1].headers['X-Host-Token']).toBe('mine')
  })

  it('sends no token header when none is saved', async () => {
    const m = stubApi()
    await getExchange('K7M2PXA')
    expect(m.mock.calls[0][1].headers['X-Host-Token']).toBeUndefined()
  })
})

describe('host section on the exchange page', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    localStorage.clear()
    document.body.innerHTML = ''
  })

  describe('who sees it', () => {
    it('shows it at the bottom of the page to the host, titled for host controls', async () => {
      stubApi()
      saveHostToken('K7M2PXA', 'mine')
      const wrapper = await mountPage()
      const section = wrapper.get('[data-test=host-section]')
      expect(section.get('h2').text()).toBe('Host controls')
      expect(section.find('[data-test=host-settings]').exists()).toBe(true)
      // below the pile
      const html = wrapper.html()
      expect(html.indexOf('data-test="host-section"')).toBeGreaterThan(html.indexOf('data-test="pile"') === -1 ? 0 : html.indexOf('data-test="pile"'))
    })

    it('shows nothing to anyone else, and asks the server only', async () => {
      stubApi({ exchange: () => ({ ...exchange, isHost: false }) })
      const wrapper = await mountPage()
      expect(wrapper.find('[data-test=host-section]').exists()).toBe(false)
    })

    it('does not trust a saved token the server rejects', async () => {
      const m = stubApi({ exchange: () => ({ ...exchange, isHost: false }) })
      saveHostToken('K7M2PXA', 'old-or-wrong')
      const wrapper = await mountPage()
      expect(m.mock.calls.some((c) => c[1].headers['X-Host-Token'] === 'old-or-wrong')).toBe(true)
      expect(wrapper.find('[data-test=host-section]').exists()).toBe(false)
      expect(patches(m)).toHaveLength(0)
    })

    it('stays visible across the periodic refresh', async () => {
      vi.useFakeTimers()
      stubApi()
      saveHostToken('K7M2PXA', 'mine')
      const wrapper = await mountPage()
      await vi.advanceTimersByTimeAsync(5100)
      await flushPromises()
      expect(wrapper.find('[data-test=host-section]').exists()).toBe(true)
    })
  })

  describe('editing the settings', () => {
    beforeEach(() => saveHostToken('K7M2PXA', 'mine'))

    it('is prefilled with the current settings', async () => {
      stubApi()
      const wrapper = await mountPage()
      expect((wrapper.get('#host-name').element as HTMLInputElement).value).toBe('Office Christmas')
      expect((wrapper.get('#host-description').element as HTMLTextAreaElement).value).toBe('Bring a book under $20')
      expect((wrapper.get('#host-organizer').element as HTMLInputElement).value).toBe('Zach')
      expect((wrapper.get('[data-test=listed]').element as HTMLInputElement).checked).toBe(true)
    })

    it('can only be saved or undone once something has changed', async () => {
      stubApi()
      const wrapper = await mountPage()
      const saveBtn = () => wrapper.get('[data-test=settings-save]').element as HTMLButtonElement
      expect(saveBtn().disabled).toBe(true)
      await edit(wrapper, { name: 'Office Christmas 2027' })
      expect(saveBtn().disabled).toBe(false)
      await edit(wrapper, { name: 'Office Christmas' }) // back to how it was
      expect(saveBtn().disabled).toBe(true)
    })

    it('explains what public and private mean', async () => {
      stubApi()
      const wrapper = await mountPage()
      expect(wrapper.get('[data-test=listed-hint]').text()).toMatch(/anyone can find/i)
      await edit(wrapper, { listed: false })
      expect(wrapper.get('[data-test=listed-hint]').text()).toMatch(/only people with the link or code/i)
    })

    it('sends the changes with the host token and confirms', async () => {
      const m = stubApi()
      const wrapper = await mountPage()
      await edit(wrapper, { name: 'New name', description: '', organizer: 'Pat', listed: false })
      await save(wrapper)

      const [url, init] = patches(m)[0]
      expect(url).toBe('/api/exchanges/K7M2PXA')
      expect(init.headers['X-Host-Token']).toBe('mine')
      expect(JSON.parse(init.body)).toEqual({ name: 'New name', description: '', organizerName: 'Pat', listed: false })
      expect(wrapper.get('[data-test=settings-saved]').text()).toBe('Saved')
      expect((wrapper.get('[data-test=settings-save]').element as HTMLButtonElement).disabled).toBe(true)
    })

    it('updates the exchange title and description on the page straight away', async () => {
      stubApi()
      const wrapper = await mountPage()
      await edit(wrapper, { name: 'Brand new name', description: 'Fresh description' })
      await save(wrapper)
      expect(wrapper.get('[data-test=exchange-name]').text()).toBe('Brand new name')
      expect(wrapper.text()).toContain('Fresh description')
    })

    it('shows the cleaned-up values the server returns', async () => {
      stubApi({ patch: (body) => json({ ...exchange, ...body, name: 'Trimmed', organizerName: 'Pat' }) })
      const wrapper = await mountPage()
      await edit(wrapper, { name: '  Trimmed  ', organizer: ' Pat ' })
      await save(wrapper)
      expect((wrapper.get('#host-name').element as HTMLInputElement).value).toBe('Trimmed')
      expect((wrapper.get('#host-organizer').element as HTMLInputElement).value).toBe('Pat')
    })

    it('shows the server\'s problems next to their fields and keeps what was typed', async () => {
      stubApi({
        patch: () => json({ error: 'Validation failed', details: ['name: must not be blank', 'organizerName: must not be blank'] }, 400),
      })
      const wrapper = await mountPage()
      await edit(wrapper, { name: '  ', organizer: '', description: 'Keep this' })
      await save(wrapper)

      expect(wrapper.get('[data-test=error-name]').text()).toBe('must not be blank')
      expect(wrapper.get('[data-test=error-organizerName]').text()).toBe('must not be blank')
      expect((wrapper.get('#host-description').element as HTMLTextAreaElement).value).toBe('Keep this')
      expect(wrapper.find('[data-test=settings-saved]').exists()).toBe(false)
      expect(wrapper.get('[data-test=exchange-name]').text()).toBe('Office Christmas') // page unchanged
    })

    it('says so when the server refuses because this is not the host', async () => {
      stubApi({ patch: () => json({ error: 'Only the host of this exchange can change it', details: [] }, 403) })
      const wrapper = await mountPage()
      await edit(wrapper, { name: 'Nope' })
      await save(wrapper)
      expect(wrapper.get('[data-test=settings-error]').text()).toMatch(/not the host/i)
    })

    it('reports a network problem and allows another try', async () => {
      let up = false
      stubApi({
        patch: (body) => {
          if (!up) throw new TypeError('down')
          return json({ ...exchange, ...body })
        },
      })
      const wrapper = await mountPage()
      await edit(wrapper, { name: 'Try twice' })
      await save(wrapper)
      expect(wrapper.get('[data-test=settings-error]').text()).toContain('Could not reach the server')
      expect((wrapper.get('[data-test=settings-save]').element as HTMLButtonElement).disabled).toBe(false)

      up = true
      await save(wrapper)
      expect(wrapper.find('[data-test=settings-saved]').exists()).toBe(true)
    })

    it('disables the buttons while saving', async () => {
      let finish!: (r: Response) => void
      stubApi({ patch: () => new Promise<Response>((r) => (finish = r)) })
      const wrapper = await mountPage()
      await edit(wrapper, { name: 'Slow' })
      await wrapper.get('[data-test=settings-form]').trigger('submit')
      expect((wrapper.get('[data-test=settings-save]').element as HTMLButtonElement).disabled).toBe(true)
      expect(wrapper.get('[data-test=settings-save]').text()).toBe('Saving…')
      finish(json({ ...exchange, name: 'Slow' }))
      await flushPromises()
      expect(wrapper.find('[data-test=settings-saved]').exists()).toBe(true)
    })

    it('undoes unsaved changes and clears the messages', async () => {
      stubApi({ patch: () => json({ error: 'Validation failed', details: ['name: must not be blank'] }, 400) })
      const wrapper = await mountPage()
      await edit(wrapper, { name: ' ', listed: false })
      await save(wrapper)
      expect(wrapper.find('[data-test=error-name]').exists()).toBe(true)

      await wrapper.get('[data-test=settings-undo]').trigger('click')
      expect((wrapper.get('#host-name').element as HTMLInputElement).value).toBe('Office Christmas')
      expect((wrapper.get('[data-test=listed]').element as HTMLInputElement).checked).toBe(true)
      expect(wrapper.find('[data-test=error-name]').exists()).toBe(false)
    })

    it('does not overwrite what the host is typing when the page refreshes', async () => {
      vi.useFakeTimers()
      stubApi()
      const wrapper = await mountPage()
      await edit(wrapper, { name: 'Half-typed nam' })

      await vi.advanceTimersByTimeAsync(5100) // the periodic refresh brings the old name again
      await flushPromises()
      expect((wrapper.get('#host-name').element as HTMLInputElement).value).toBe('Half-typed nam')
    })

    it('takes a changed value from the refresh when nothing is being edited', async () => {
      vi.useFakeTimers()
      let current: object = exchange
      stubApi({ exchange: () => current })
      const wrapper = await mountPage()

      current = { ...exchange, name: 'Changed elsewhere' }
      await vi.advanceTimersByTimeAsync(5100)
      await flushPromises()
      expect((wrapper.get('#host-name').element as HTMLInputElement).value).toBe('Changed elsewhere')
    })
  })
})

describe('creating an exchange gives the host their token', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  async function createWith(responseBody: object) {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => json(responseBody, 201)))
    localStorage.setItem(PARTICIPANT_STORAGE_KEY, 'Zach')
    const router = createAppRouter(createMemoryHistory())
    router.push('/exchanges/new')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
    await flushPromises()
    await wrapper.get('#name').setValue('Office Christmas')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()
    return wrapper
  }

  it('saves the token it receives, for that exchange', async () => {
    await createWith({ ...exchange, hostToken: 'shown-once' })
    expect(getHostToken('K7M2PXA')).toBe('shown-once')
  })

  it('saves nothing when no token comes back', async () => {
    await createWith({ ...exchange })
    expect(getHostToken('K7M2PXA')).toBeUndefined()
  })
})
