import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import type { Book } from '../api/books'
import { ApiError } from '../api/client'
import ModalDialog from '../components/ModalDialog.vue'
import { claimability, claimFailureMessage } from '../lib/claim'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY } from '../stores/participant'

const URL = 'https://www.amazon.com/dp/0547928221'
const exchange = {
  code: 'K7M2PXA', name: 'Office Christmas', listed: true, status: 'OPEN', mode: 'CLAIM',
  bookCount: 3, claimedCount: 1, createdAt: '2026-11-24T14:02:03Z',
}

const book = (id: number, submitter: string, over: Partial<Book> = {}): Book => ({
  id, submitterName: submitter, wrappingPaper: 'holly', claimed: false, viewerIsClaimer: false, ...over,
})
const zachs = book(1, 'Zach') // Alex's own? no: brought by Zach
const sams = book(2, 'Sam')
const pats = book(3, 'Pat')
const takenByLee = book(4, 'Kim', {
  claimed: true, claimedByName: 'Lee', claimedAt: '2026-12-01T18:00:00Z', bookTitle: 'Emma',
})
const wonByAlex = (id: number, submitter: string): Book => ({
  ...book(id, submitter), claimed: true, viewerIsClaimer: true, claimedByName: 'Alex',
  claimedAt: '2026-12-01T18:05:00Z', bookTitle: 'The Hobbit',
  coverImageUrl: 'https://example.com/hobbit.jpg', bookUrl: URL,
})

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

interface Api {
  pile: () => Book[]
  onClaim?: (id: number, body: { participantName: string }) => Response | Promise<Response>
  exchange?: object
}

function stubApi(api: Api) {
  const fetchMock = vi.fn().mockImplementation(async (url: string, init: { method?: string; body?: string }) => {
    const claim = url.match(/\/books\/(\d+)\/claim$/)
    if (claim && init.method === 'POST') {
      return api.onClaim!(Number(claim[1]), JSON.parse(init.body!))
    }
    if (url.endsWith('/books')) return json(api.pile())
    return json(api.exchange ?? exchange)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const claims = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter((c) => c[1].method === 'POST')

async function mountPage(name = 'Alex') {
  localStorage.setItem(PARTICIPANT_STORAGE_KEY, name)
  const router = createAppRouter(createMemoryHistory())
  router.push('/exchange/K7M2PXA')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

const presentButton = (wrapper: ReturnType<typeof mount>, id: number) =>
  wrapper.get(`[data-test=book-${id}] [data-test=present]`)

describe('claiming from the pile', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    document.body.innerHTML = ''
  })

  describe('which presents can be claimed', () => {
    it('lets you claim presents from other people', async () => {
      stubApi({ pile: () => [zachs, sams] })
      const wrapper = await mountPage('Alex')
      const btn = presentButton(wrapper, 2)
      expect(btn.attributes('aria-disabled')).toBe('false')
      expect(btn.attributes('aria-label')).toBe('Claim the present from Sam')
      expect(btn.attributes('title')).toBeUndefined()
    })

    it('blocks your own present, ignoring case and spaces, with a reason', async () => {
      stubApi({ pile: () => [zachs, sams] })
      const wrapper = await mountPage('  zACH ')
      const own = presentButton(wrapper, 1)
      expect(own.attributes('aria-disabled')).toBe('true')
      expect(own.attributes('title')).toBe('You brought this one')
      expect(own.attributes('aria-label')).toContain('You brought this one')
      expect(presentButton(wrapper, 2).attributes('aria-disabled')).toBe('false')
    })

    it('blocks every present once you hold one (from the server data, so also after a reload)', async () => {
      stubApi({ pile: () => [wonByAlex(1, 'Zach'), sams, pats] })
      const wrapper = await mountPage('Alex')
      for (const id of [2, 3]) {
        const btn = presentButton(wrapper, id)
        expect(btn.attributes('aria-disabled')).toBe('true')
        expect(btn.attributes('title')).toBe("You've already claimed a book")
      }
      expect(wrapper.get('[data-test=my-status]').text()).toContain('you claimed The Hobbit')
    })

    it('blocks everything in a closed exchange', async () => {
      stubApi({ pile: () => [sams], exchange: { ...exchange, status: 'CLOSED' } })
      const wrapper = await mountPage('Alex')
      expect(presentButton(wrapper, 2).attributes('title')).toBe('This exchange is closed')
    })

    it('does not make already-claimed presents clickable', async () => {
      stubApi({ pile: () => [takenByLee] })
      const wrapper = await mountPage('Alex')
      expect(wrapper.find('[data-test=book-4] [data-test=present]').exists()).toBe(false)
    })

    it('does nothing when an unavailable present is clicked', async () => {
      const fetchMock = stubApi({ pile: () => [zachs] })
      const wrapper = await mountPage('Zach')
      await presentButton(wrapper, 1).trigger('click')
      expect(wrapper.find('[role=dialog]').exists()).toBe(false)
      expect(claims(fetchMock)).toHaveLength(0)
    })
  })

  describe('asking first', () => {
    it('opens a confirmation naming who brought it, and sends nothing yet', async () => {
      const fetchMock = stubApi({ pile: () => [sams] })
      const wrapper = await mountPage('Alex')
      await presentButton(wrapper, 2).trigger('click')

      const dialog = wrapper.get('[role=dialog]')
      expect(dialog.text()).toContain('Claim this present from Sam?')
      expect(dialog.attributes('aria-modal')).toBe('true')
      expect(claims(fetchMock)).toHaveLength(0)
    })

    it('starts on the safe choice, so Enter does not claim by accident', async () => {
      stubApi({ pile: () => [sams] })
      const wrapper = await mountPage('Alex')
      await presentButton(wrapper, 2).trigger('click')
      await flushPromises()
      expect(document.activeElement).toBe(wrapper.get('[data-test=claim-cancel]').element)
    })

    it('cancelling (button or Escape) sends nothing and leaves the present as it was', async () => {
      const fetchMock = stubApi({ pile: () => [sams] })
      const wrapper = await mountPage('Alex')

      await presentButton(wrapper, 2).trigger('click')
      await wrapper.get('[data-test=claim-cancel]').trigger('click')
      expect(wrapper.find('[role=dialog]').exists()).toBe(false)

      await presentButton(wrapper, 2).trigger('click')
      await wrapper.get('[role=dialog]').trigger('keydown', { key: 'Escape' })
      expect(wrapper.find('[role=dialog]').exists()).toBe(false)

      expect(claims(fetchMock)).toHaveLength(0)
      expect(presentButton(wrapper, 2).attributes('aria-disabled')).toBe('false')
    })
  })

  describe('a successful claim', () => {
    function winning() {
      let won = false
      const fetchMock = stubApi({
        pile: () => (won ? [zachs, wonByAlex(2, 'Sam'), pats] : [zachs, sams, pats]),
        onClaim: (id) => {
          won = true
          return json(wonByAlex(id, 'Sam'))
        },
      })
      return fetchMock
    }

    it('sends the saved name and shows what was won, with the link', async () => {
      const fetchMock = winning()
      const wrapper = await mountPage('Alex')
      await presentButton(wrapper, 2).trigger('click')
      await wrapper.get('[data-test=claim-confirm]').trigger('click')
      await flushPromises()

      const [url, init] = claims(fetchMock)[0]
      expect(url).toBe('/api/exchanges/K7M2PXA/books/2/claim')
      expect(JSON.parse(init.body)).toEqual({ participantName: 'Alex' })
      expect(init.headers['X-Participant-Name']).toBe('Alex')

      expect(wrapper.get('[data-test=reveal-title]').text()).toBe('You claimed The Hobbit!')
      expect(wrapper.get('[data-test=reveal-cover]').attributes('src')).toBe('https://example.com/hobbit.jpg')
      const link = wrapper.get('[data-test=reveal-link]')
      expect(link.attributes('href')).toBe(URL)
      expect(link.attributes('rel')).toContain('noopener')
      expect(wrapper.text()).toContain('Brought by Sam')
    })

    it('shows a title placeholder in the reveal when there is no cover yet', async () => {
      stubApi({
        pile: () => [sams],
        onClaim: (id) => json({ ...wonByAlex(id, 'Sam'), coverImageUrl: undefined }),
      })
      const wrapper = await mountPage('Alex')
      await presentButton(wrapper, 2).trigger('click')
      await wrapper.get('[data-test=claim-confirm]').trigger('click')
      await flushPromises()
      expect(wrapper.find('[data-test=reveal-cover]').exists()).toBe(false)
      expect(wrapper.get('[data-test=reveal-placeholder]').text()).toBe('The Hobbit')
    })

    it('then shows the present as claimed by you, with the link, in the pile and the status line', async () => {
      winning()
      const wrapper = await mountPage('Alex')
      await presentButton(wrapper, 2).trigger('click')
      await wrapper.get('[data-test=claim-confirm]').trigger('click')
      await flushPromises()
      await wrapper.get('[data-test=reveal-close]').trigger('click')

      expect(wrapper.find('[role=dialog]').exists()).toBe(false)
      expect(wrapper.get('[data-test=book-2] [data-test=taken]').text()).toContain('You claimed this')
      expect(wrapper.get('[data-test=book-2] [data-test=book-link]').attributes('href')).toBe(URL)
      expect(wrapper.get('[data-test=my-status]').text()).toContain('you claimed The Hobbit')
    })

    it('disables every other present afterwards', async () => {
      winning()
      const wrapper = await mountPage('Alex')
      await presentButton(wrapper, 2).trigger('click')
      await wrapper.get('[data-test=claim-confirm]').trigger('click')
      await flushPromises()

      for (const id of [1, 3]) {
        expect(presentButton(wrapper, id).attributes('aria-disabled')).toBe('true')
      }
    })

    it('sends only one request even if confirm is pressed twice quickly', async () => {
      let release!: (r: Response) => void
      const fetchMock = stubApi({
        pile: () => [sams],
        onClaim: () => new Promise<Response>((r) => (release = r)),
      })
      const wrapper = await mountPage('Alex')
      await presentButton(wrapper, 2).trigger('click')
      await wrapper.get('[data-test=claim-confirm]').trigger('click')
      await wrapper.get('[data-test=claim-confirm]').trigger('click')

      expect(claims(fetchMock)).toHaveLength(1)
      expect(wrapper.get('[data-test=claim-confirm]').text()).toBe('Claiming…')
      release(json(wonByAlex(2, 'Sam')))
      await flushPromises()
    })
  })

  describe('when the server says no', () => {
    async function claimWith(response: () => Response | Promise<Response>, pileAfter: () => Book[]) {
      let after = false
      stubApi({
        pile: () => (after ? pileAfter() : [sams, pats]),
        onClaim: () => {
          after = true
          return response()
        },
      })
      const wrapper = await mountPage('Alex')
      await presentButton(wrapper, 2).trigger('click')
      await wrapper.get('[data-test=claim-confirm]').trigger('click')
      await flushPromises()
      return wrapper
    }

    it('says so kindly when someone else got there first, and refreshes the pile', async () => {
      const wrapper = await claimWith(
        () => json({ error: 'This book has already been claimed', details: [] }, 409),
        () => [book(2, 'Sam', { claimed: true, claimedByName: 'Lee', bookTitle: 'Dune' }), pats],
      )
      expect(wrapper.get('[data-test=notice]').text()).toContain('Someone else just claimed that present')
      expect(wrapper.find('[role=dialog]').exists()).toBe(false)
      expect(wrapper.get('[data-test=book-2] [data-test=taken]').text()).toContain('Claimed by Lee')
      expect(wrapper.find('[data-test=reveal-title]').exists()).toBe(false)
    })

    it('explains the one-present rule when the screen was out of date', async () => {
      const wrapper = await claimWith(
        () => json({ error: 'You have already claimed a book in this exchange', details: [] }, 409),
        () => [sams, wonByAlex(3, 'Pat')],
      )
      expect(wrapper.get('[data-test=notice]').text()).toContain("You've already claimed a book")
      expect(presentButton(wrapper, 2).attributes('aria-disabled')).toBe('true') // the pile caught up
    })

    it('shows a closed exchange message from the server', async () => {
      const wrapper = await claimWith(
        () => json({ error: 'This exchange is closed and no longer accepts claims', details: [] }, 409),
        () => [sams, pats],
      )
      expect(wrapper.get('[data-test=notice]').text()).toContain('closed')
    })

    it('handles a network failure and lets you try again', async () => {
      const wrapper = await claimWith(
        () => {
          throw new TypeError('down')
        },
        () => [sams, pats],
      )
      expect(wrapper.get('[data-test=notice]').text()).toContain('Could not reach the server')
      await wrapper.get('[data-test=notice-close]').trigger('click')
      expect(wrapper.find('[data-test=notice]').exists()).toBe(false)
      expect(presentButton(wrapper, 2).attributes('aria-disabled')).toBe('false')
    })
  })
})

describe('claimability rules', () => {
  const open = true
  const claimingOpen = true
  it('allows a stranger to claim an unclaimed present', () => {
    expect(claimability(sams, 'Alex', false, open, true)).toEqual({ state: 'claimable' })
  })
  it('explains each refusal', () => {
    expect(claimability(zachs, 'zach', false, open, true)).toEqual({ state: 'disabled', reason: 'You brought this one' })
    expect(claimability(sams, 'Alex', true, open, true)).toEqual({ state: 'disabled', reason: "You've already claimed a book" })
    expect(claimability(sams, 'Alex', false, false, true)).toEqual({ state: 'disabled', reason: 'This exchange is closed' })
  })
  it('waits for the host to open claiming (submit mode)', () => {
    expect(claimability(sams, 'Alex', false, open, false)).toEqual({ state: 'disabled', reason: "Claiming hasn't started yet" })
    expect(claimability(zachs, 'zach', false, open, false)).toEqual({ state: 'disabled', reason: "Claiming hasn't started yet" })
  })
  it('a closed exchange is reported as closed, not as "not started"', () => {
    expect(claimability(sams, 'Alex', false, false, false)).toEqual({ state: 'disabled', reason: 'This exchange is closed' })
  })
  it('has nothing to offer for a claimed present', () => {
    expect(claimability(takenByLee, 'Alex', false, open, true)).toEqual({ state: 'disabled', reason: '' })
  })
})

describe('claimFailureMessage', () => {
  it('maps the backend messages to friendly ones and falls back to the server text', () => {
    expect(claimFailureMessage(new ApiError(409, 'This book has already been claimed'))).toMatch(/Too slow/)
    expect(claimFailureMessage(new ApiError(409, 'You have already claimed a book in this exchange'))).toMatch(/already claimed/)
    expect(claimFailureMessage(new ApiError(409, "You can't claim a book you submitted yourself"))).toMatch(/brought yourself/)
    expect(claimFailureMessage(new ApiError(404, 'Book not found'))).toMatch(/isn't there/)
    expect(claimFailureMessage(new ApiError(409, 'This exchange is closed'))).toBe('This exchange is closed')
    expect(claimFailureMessage(new ApiError(0, 'Could not reach the server. Please try again.'))).toMatch(/Could not reach/)
    expect(claimFailureMessage(new Error('boom'))).toMatch(/Something went wrong/)
  })
})

describe('ModalDialog', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  function host() {
    const open = ref(true)
    const closed = vi.fn()
    const Host = defineComponent({
      setup: () => () =>
        h('div', [
          h('button', { id: 'opener' }, 'open'),
          open.value
            ? h(
                ModalDialog,
                { labelledby: 't', onClose: () => { closed(); open.value = false } },
                () => [h('h2', { id: 't' }, 'Title'), h('button', { id: 'first' }, 'one'), h('button', { id: 'last' }, 'two')],
              )
            : null,
        ]),
    })
    return { Host, closed, open }
  }

  it('moves focus in, keeps Tab inside, and returns focus to where it came from', async () => {
    const { Host, open } = host()
    const wrapper = mount(Host, { attachTo: document.body })
    const opener = wrapper.get('#opener').element as HTMLElement
    // (mounted after the opener had focus)
    opener.focus()
    open.value = false
    await flushPromises()
    open.value = true
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('#first').element)

    wrapper.get('#last').element.dispatchEvent(new Event('focus'))
    ;(wrapper.get('#last').element as HTMLElement).focus()
    await wrapper.get('[role=dialog]').trigger('keydown', { key: 'Tab' }) // last -> first
    expect(document.activeElement).toBe(wrapper.get('#first').element)
    await wrapper.get('[role=dialog]').trigger('keydown', { key: 'Tab', shiftKey: true }) // first -> last
    expect(document.activeElement).toBe(wrapper.get('#last').element)

    await wrapper.get('[role=dialog]').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(document.activeElement).toBe(opener)
  })

  it('closes on Escape and on a click outside, but not on a click inside', async () => {
    const { Host, closed } = host()
    const wrapper = mount(Host, { attachTo: document.body })
    await flushPromises()

    await wrapper.get('[role=dialog]').trigger('click')
    expect(closed).not.toHaveBeenCalled()

    await wrapper.get('[data-test=backdrop]').trigger('click')
    expect(closed).toHaveBeenCalledTimes(1)
  })
})
