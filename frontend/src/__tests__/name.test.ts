import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import { api } from '../api/client'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY, validateName } from '../stores/participant'

/** A tiny backend: one exchange with an empty pile, so the exchange page can load. */
function stubBackend() {
  const fetchMock = vi.fn().mockImplementation(async (url: string) => {
    const body = url.endsWith('/books')
      ? []
      : { code: 'K7M2PXA', name: 'Office Christmas', listed: true, status: 'OPEN', bookCount: 0, claimedCount: 0, createdAt: '2026-11-24T14:02:03Z' }
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

async function mountAt(path: string) {
  const pinia = createPinia()
  const router = createAppRouter(createMemoryHistory())
  router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [pinia, router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, pinia, router }
}

async function enterName(wrapper: VueWrapper, value: string) {
  await wrapper.get('[data-test=name-input]').setValue(value)
  await wrapper.get('[data-test=name-dialog] form').trigger('submit')
  await flushPromises()
}

describe('participant name', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  describe('validation', () => {
    it('accepts 1 to 100 characters after trimming', () => {
      expect(validateName('A')).toBe('')
      expect(validateName('  Alex  ')).toBe('')
      expect(validateName('x'.repeat(100))).toBe('')
      expect(validateName(' ' + 'x'.repeat(100) + ' ')).toBe('')
    })

    it('rejects empty, whitespace-only and over-long names', () => {
      expect(validateName('')).toMatch(/enter your name/i)
      expect(validateName('   \t ')).toMatch(/enter your name/i)
      expect(validateName('x'.repeat(101))).toMatch(/100/)
    })
  })

  describe('first visit gate', () => {
    it.each(['/exchange/K7M2PXA', '/exchange/K7M2PXA/submit'])(
      '%s asks for a name and shows nothing of the page behind it',
      async (path) => {
        const { wrapper } = await mountAt(path)
        expect(wrapper.find('[data-test=name-dialog]').exists()).toBe(true)
        expect(wrapper.find('h1').exists()).toBe(false)
        expect(wrapper.find('[data-test=name-cancel]').exists()).toBe(false) // can't be dismissed
      },
    )

    it('does not ask on pages that do not need a name', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('down')))
      for (const path of ['/', '/exchanges/new']) {
        const { wrapper } = await mountAt(path)
        expect(wrapper.find('[data-test=name-dialog]').exists()).toBe(false)
        wrapper.unmount()
      }
    })

    it('focuses the name field', async () => {
      const { wrapper } = await mountAt('/exchange/K7M2PXA')
      expect(document.activeElement).toBe(wrapper.get('[data-test=name-input]').element)
    })

    it('Escape does not dismiss the gate', async () => {
      const { wrapper } = await mountAt('/exchange/K7M2PXA')
      await wrapper.get('[data-test=name-input]').trigger('keydown', { key: 'Escape' })
      expect(wrapper.find('[data-test=name-dialog]').exists()).toBe(true)
    })

    it('rejects an empty or whitespace-only name and keeps the page hidden', async () => {
      const { wrapper } = await mountAt('/exchange/K7M2PXA')
      await enterName(wrapper, '   ')
      expect(wrapper.get('[data-test=name-error]').text()).toMatch(/enter your name/i)
      expect(wrapper.find('h1').exists()).toBe(false)
      expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBeNull()
    })

    it('rejects a name over 100 characters', async () => {
      const { wrapper } = await mountAt('/exchange/K7M2PXA')
      await enterName(wrapper, 'x'.repeat(101))
      expect(wrapper.get('[data-test=name-error]').text()).toMatch(/100/)
      expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBeNull()
    })

    it('saves a trimmed name as typed, then shows the page and the header name', async () => {
      stubBackend()
      const { wrapper } = await mountAt('/exchange/K7M2PXA')
      await enterName(wrapper, '  aLEx  ')

      expect(wrapper.find('[data-test=name-dialog]').exists()).toBe(false)
      expect(wrapper.get('h1').text()).toBe('Office Christmas')
      expect(wrapper.get('[data-test=participant-name]').text()).toBe('aLEx') // case kept as typed
      expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBe('aLEx')
    })
  })

  describe('remembering the name', () => {
    it('does not ask again after a refresh', async () => {
      stubBackend()
      const first = await mountAt('/exchange/K7M2PXA')
      await enterName(first.wrapper, 'Alex')
      first.wrapper.unmount()

      const { wrapper } = await mountAt('/exchange/K7M2PXA') // a fresh app, like a page reload
      expect(wrapper.find('[data-test=name-dialog]').exists()).toBe(false)
      expect(wrapper.get('h1').text()).toBe('Office Christmas')
      expect(wrapper.get('[data-test=participant-name]').text()).toBe('Alex')
    })
  })

  describe('changing the name', () => {
    beforeEach(() => localStorage.setItem(PARTICIPANT_STORAGE_KEY, 'Alex'))

    it('"Not you? Change name" opens a dialog prefilled with the current name', async () => {
      const { wrapper } = await mountAt('/exchange/K7M2PXA')
      expect(wrapper.get('[data-test=change-name]').text()).toBe('Not you? Change name')
      await wrapper.get('[data-test=change-name]').trigger('click')

      expect((wrapper.get('[data-test=name-input]').element as HTMLInputElement).value).toBe('Alex')
      expect(wrapper.find('[data-test=name-cancel]').exists()).toBe(true)
      expect(wrapper.get('main').attributes('inert')).toBeDefined() // page behind is not reachable
    })

    it('updates the header, storage and later API calls', async () => {
      const fetchMock = stubBackend()
      const { wrapper } = await mountAt('/exchange/K7M2PXA')

      await wrapper.get('[data-test=change-name]').trigger('click')
      await enterName(wrapper, 'Sam')

      expect(wrapper.get('[data-test=participant-name]').text()).toBe('Sam')
      expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBe('Sam')
      await api('/api/anything')
      expect(fetchMock.mock.calls.at(-1)![1].headers['X-Participant-Name']).toBe('Sam')
    })

    it('can be cancelled with the button or Escape, keeping the old name', async () => {
      const { wrapper } = await mountAt('/exchange/K7M2PXA')

      await wrapper.get('[data-test=change-name]').trigger('click')
      await wrapper.get('[data-test=name-input]').setValue('Sam')
      await wrapper.get('[data-test=name-cancel]').trigger('click')
      expect(wrapper.find('[data-test=name-dialog]').exists()).toBe(false)
      expect(wrapper.get('[data-test=participant-name]').text()).toBe('Alex')

      await wrapper.get('[data-test=change-name]').trigger('click')
      await wrapper.get('[data-test=name-input]').trigger('keydown', { key: 'Escape' })
      expect(wrapper.find('[data-test=name-dialog]').exists()).toBe(false)
      expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBe('Alex')
    })

    it('applies the same rules when changing', async () => {
      const { wrapper } = await mountAt('/exchange/K7M2PXA')
      await wrapper.get('[data-test=change-name]').trigger('click')
      await enterName(wrapper, '  ')
      expect(wrapper.get('[data-test=name-error]').text()).toMatch(/enter your name/i)
      expect(wrapper.find('[data-test=name-dialog]').exists()).toBe(true)
    })

    it('works on pages that do not require a name (home) as well', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('down')))
      const { wrapper } = await mountAt('/')
      await wrapper.get('[data-test=change-name]').trigger('click')
      await enterName(wrapper, 'Sam')
      expect(wrapper.get('[data-test=participant-name]').text()).toBe('Sam')
    })
  })

  it('offers "Enter your name" in the header when there is none (e.g. on the home page)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('down')))
    const { wrapper } = await mountAt('/')
    expect(wrapper.get('[data-test=change-name]').text()).toBe('Enter your name')
    expect(wrapper.find('[data-test=participant-name]').exists()).toBe(false)
  })
})
