import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY, useParticipantStore } from '../stores/participant'

const created = {
  code: 'K7M2PXA',
  name: 'Office Christmas',
  listed: true,
  status: 'OPEN',
  mode: 'SUBMIT',
  bookCount: 0,
  claimedCount: 0,
  createdAt: '2026-11-24T14:02:03Z',
}

function respond(status: number, body: unknown) {
  return vi.fn().mockImplementation(
    async () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  )
}

async function mountPage(savedName = '') {
  const pinia = createPinia()
  setActivePinia(pinia)
  if (savedName) useParticipantStore().setName(savedName)
  const router = createAppRouter(createMemoryHistory())
  router.push('/exchanges/new')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [pinia, router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

describe('create exchange page', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('prefills the organizer from the saved participant name and defaults to public', async () => {
    const { wrapper } = await mountPage('Zach')
    expect((wrapper.get('#organizer').element as HTMLInputElement).value).toBe('Zach')
    expect((wrapper.get('[data-test=listed]').element as HTMLInputElement).checked).toBe(true)
  })

  it('leaves the organizer empty when no name is saved', async () => {
    const { wrapper } = await mountPage()
    expect((wrapper.get('#organizer').element as HTMLInputElement).value).toBe('')
  })

  it('posts the form and shows the shareable link and code', async () => {
    const fetchMock = respond(201, created)
    vi.stubGlobal('fetch', fetchMock)
    const { wrapper } = await mountPage('Zach')

    await wrapper.get('#name').setValue('Office Christmas')
    await wrapper.get('#description').setValue('Under $20')
    await wrapper.get('[data-test=listed]').setValue(false)
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/exchanges')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({
      name: 'Office Christmas',
      description: 'Under $20',
      organizerName: 'Zach',
      listed: false,
    })
    expect(wrapper.get('[data-test=code]').text()).toBe('K7M2PXA')
    expect((wrapper.get('[data-test=share-url]').element as HTMLInputElement).value).toBe(
      `${window.location.origin}/exchange/K7M2PXA`,
    )
    expect(wrapper.find('[data-test=form]').exists()).toBe(false)
  })

  it('saves the host as the current user in this browser, like joining would', async () => {
    vi.stubGlobal('fetch', respond(201, { ...created, organizerName: 'Zach' }))
    const { wrapper, router } = await mountPage() // no saved name yet
    await wrapper.get('#name').setValue('Office Christmas')
    await wrapper.get('#organizer').setValue('Zach')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()

    expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBe('Zach')
    expect(useParticipantStore().name).toBe('Zach')
    expect(wrapper.get('[data-test=participant-name]').text()).toBe('Zach') // header updates

    // ...so the exchange page doesn't ask for a name again.
    await wrapper.get('[data-test=open]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/exchange/K7M2PXA')
    expect(wrapper.find('[data-test=name-dialog]').exists()).toBe(false)
  })

  it('replaces a different saved name with the host name', async () => {
    vi.stubGlobal('fetch', respond(201, { ...created, organizerName: 'Zachary' }))
    const { wrapper } = await mountPage('Zach')
    await wrapper.get('#organizer').setValue('Zachary')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()

    expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBe('Zachary')
    expect(wrapper.get('[data-test=participant-name]').text()).toBe('Zachary')
  })

  it('saves the trimmed name the server returned', async () => {
    vi.stubGlobal('fetch', respond(201, { ...created, organizerName: 'Zach' }))
    const { wrapper } = await mountPage()
    await wrapper.get('#organizer').setValue('  Zach  ')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()
    expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBe('Zach')
  })

  it('does not change the saved name when creating fails', async () => {
    vi.stubGlobal('fetch', respond(400, { error: 'Validation failed', details: ['name: must not be blank'] }))
    const { wrapper } = await mountPage('Zach')
    await wrapper.get('#organizer').setValue('Someone Else')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()

    expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBe('Zach')
    expect(wrapper.get('[data-test=participant-name]').text()).toBe('Zach')
  })

  it('copies the link to the clipboard', async () => {
    vi.stubGlobal('fetch', respond(201, created))
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const { wrapper } = await mountPage('Zach')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()

    await wrapper.get('[data-test=copy]').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/exchange/K7M2PXA`)
    expect(wrapper.get('[data-test=copy-status]').text()).toBe('Copied!')
  })

  it('tells the user when copying is blocked, and still shows the link', async () => {
    vi.stubGlobal('fetch', respond(201, created))
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    const { wrapper } = await mountPage('Zach')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()

    await wrapper.get('[data-test=copy]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test=copy-status]').text()).toContain("Couldn't copy")
    expect(wrapper.find('[data-test=share-url]').exists()).toBe(true)
  })

  it('links into the new exchange', async () => {
    vi.stubGlobal('fetch', respond(201, created))
    const { wrapper, router } = await mountPage('Zach')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()

    await wrapper.get('[data-test=open]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/exchange/K7M2PXA')
  })

  it('shows validation errors next to each field and keeps what was typed', async () => {
    vi.stubGlobal(
      'fetch',
      respond(400, {
        error: 'Validation failed',
        details: ['name: must not be blank', 'organizerName: must not be blank'],
      }),
    )
    const { wrapper } = await mountPage()
    await wrapper.get('#description').setValue('Keep me')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-test=error-name]').text()).toBe('must not be blank')
    expect(wrapper.get('[data-test=error-organizerName]').text()).toBe('must not be blank')
    expect((wrapper.get('#description').element as HTMLTextAreaElement).value).toBe('Keep me')
    expect(wrapper.find('[data-test=created]').exists()).toBe(false)
  })

  it('shows a general message when the server is unreachable, and can retry', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('down')))
    const { wrapper } = await mountPage('Zach')
    await wrapper.get('#name').setValue('Office Christmas')
    await wrapper.get('[data-test=form]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-test=form-error]').text()).toContain('Could not reach the server')
    expect((wrapper.get('[data-test=submit]').element as HTMLButtonElement).disabled).toBe(false)
    expect((wrapper.get('#name').element as HTMLInputElement).value).toBe('Office Christmas')
  })

  it('disables the button while the request is in flight', async () => {
    let finish!: (r: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>((r) => (finish = r))))
    const { wrapper } = await mountPage('Zach')
    await wrapper.get('[data-test=form]').trigger('submit')

    expect((wrapper.get('[data-test=submit]').element as HTMLButtonElement).disabled).toBe(true)
    finish(new Response(JSON.stringify(created), { status: 201 }))
    await flushPromises()
    expect(wrapper.find('[data-test=created]').exists()).toBe(true)
  })
})
