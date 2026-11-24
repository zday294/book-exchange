import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import type { Book } from '../api/books'
import type { PartyState } from '../api/party'
import { saveHostToken } from '../lib/hostToken'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY } from '../stores/participant'

/** Stands in for the browser's EventSource so the test can play the server. */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  static CLOSED = 2
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
  }
  open() {
    this.readyState = 1
    this.fire('open', {})
  }
  send(name: string, data: unknown) {
    this.fire(name, { data: JSON.stringify(data) })
  }
  private fire(name: string, event: unknown) {
    for (const fn of this.listeners.get(name) ?? []) fn(event)
  }
}

const baseExchange = {
  code: 'K7M2PXA', name: 'Office Christmas', listed: true, status: 'OPEN', mode: 'PARTY', bookCount: 3, claimedCount: 0,
  createdAt: '2026-11-24T14:02:03Z', isHost: false,
}
const wrapped = (id: number, who: string): Book => ({ id, submitterName: who, wrappingPaper: 'holly', claimed: false, viewerIsClaimer: false })
const BOOKS = [wrapped(1, 'Zed'), wrapped(2, 'Yan'), wrapped(3, 'Xia')]

const entry = (name: string, status: 'DONE' | 'CURRENT' | 'WAITING', online = true) => ({ name, status, online })
const partyOf = (queue: PartyState['queue'], secondsLeft = 90): PartyState => {
  const current = queue.find((q) => q.status === 'CURRENT')
  return { active: true, current: current?.name, turnSecondsLeft: current ? secondsLeft : undefined, queue, eligible: [] }
}
const QUEUE = [entry('Dan', 'DONE'), entry('Amy', 'CURRENT'), entry('Bob', 'WAITING'), entry('Cat', 'WAITING', false)]

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

type Init = { method?: string; body?: string; headers: Record<string, string> }
interface Server {
  exchange: Record<string, unknown>
  party: PartyState
  /** answers to POST .../party/<action> */
  action?: (action: string, init: Init) => Response | Promise<Response>
}

function stubServer(state: Server) {
  const fetchMock = vi.fn().mockImplementation(async (url: string, init: Init) => {
    const action = url.match(/\/party\/(start|skip|end)$/)
    if (action && init.method === 'POST') {
      return state.action ? state.action(action[1], init) : json(state.party)
    }
    if (url.endsWith('/party')) return json(state.party)
    if (url.endsWith('/books')) return json(BOOKS)
    return json(state.exchange)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const stream = () => FakeEventSource.instances.at(-1)!
const posts = (m: ReturnType<typeof stubServer>) => m.mock.calls.filter((c) => c[1]?.method === 'POST')

async function mountPage(name = 'Bob') {
  localStorage.setItem(PARTICIPANT_STORAGE_KEY, name)
  const router = createAppRouter(createMemoryHistory())
  router.push('/exchange/K7M2PXA')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('party mode', () => {
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

  describe('the queue panel', () => {
    it('shows the queue beside the pile, with the party banner, when a party is running', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage()
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('🎉 Party time! 🎉')
      expect(wrapper.get('.layout').classes()).toContain('with-queue')
      expect(wrapper.find('aside [data-test=party-queue]').exists()).toBe(true)
      expect(wrapper.find('[data-test=pile]').exists()).toBe(true)
    })

    it('is not shown when there is no party', async () => {
      stubServer({ exchange: { ...baseExchange, mode: 'CLAIM' }, party: partyOf(QUEUE) })
      const wrapper = await mountPage()
      expect(wrapper.find('[data-test=party-queue]').exists()).toBe(false)
      expect(wrapper.get('.layout').classes()).not.toContain('with-queue')
    })

    it('says whose turn it is, with a countdown, and lists everyone in order', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE, 102) })
      const wrapper = await mountPage()
      expect(wrapper.get('[data-test=party-now]').text()).toContain("Amy's turn")
      expect(wrapper.get('[data-test=party-clock]').text()).toBe('1:42')
      const names = wrapper.findAll('[data-test=party-list] li .name').map((n) => n.text())
      expect(names).toEqual(['Dan', 'Amy', 'Bob (you)', 'Cat'])
    })

    it('marks who is done, whose turn it is now, and who is offline', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage()
      expect(wrapper.get('[data-test=entry-Dan]').classes()).toContain('done')
      expect(wrapper.get('[data-test=entry-Dan] [data-test=entry-state]').text()).toBe('had a turn')
      expect(wrapper.get('[data-test=entry-Amy]').classes()).toContain('current')
      expect(wrapper.get('[data-test=entry-Amy] [data-test=entry-state]').text()).toBe('turn now')
      expect(wrapper.get('[data-test=entry-Cat] [data-test=entry-offline]').text()).toBe('offline')
      expect(wrapper.find('[data-test=entry-Bob] [data-test=entry-offline]').exists()).toBe(false)
    })

    it.each([
      ['Bob', "You're next!"],
      ['Cat', "You're 3rd."],
      ['Dan', "You've had your turn."],
      ['Zoe', "You're not in the queue yet. You'll join the end of the line."],
    ])('tells %s where they are in line', async (who, text) => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage(who)
      expect(wrapper.get('[data-test=party-place]').text()).toBe(text)
    })

    it('matches your name ignoring case (and does not say it twice when it is your turn)', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage('aMy')
      expect(wrapper.get('[data-test=party-now]').text()).toContain("It's your turn!")
      expect(wrapper.find('[data-test=party-place]').exists()).toBe(false)
    })

    it('says so when nobody has the turn (waiting for late arrivals)', async () => {
      stubServer({ exchange: baseExchange, party: partyOf([entry('Dan', 'DONE')]) })
      const wrapper = await mountPage()
      expect(wrapper.get('[data-test=party-idle]').text()).toContain('Waiting for the next person')
    })
  })

  describe('the countdown', () => {
    it('counts down on this device and turns urgent in the last 15 seconds', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE, 20) })
      const wrapper = await mountPage()
      stream().open() // live: no polling, which would bring the server's number again
      expect(wrapper.get('[data-test=party-clock]').text()).toBe('0:20')
      expect(wrapper.get('[data-test=party-clock]').classes()).not.toContain('urgent')

      await settle(6000)
      expect(wrapper.get('[data-test=party-clock]').text()).toBe('0:14')
      expect(wrapper.get('[data-test=party-clock]').classes()).toContain('urgent')

      await settle(20000)
      expect(wrapper.get('[data-test=party-clock]').text()).toBe('0:00') // never negative
    })

    it('starts again from the server\'s number each time a new snapshot arrives', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE, 20) })
      const wrapper = await mountPage()
      stream().open()
      await settle(10000)
      stream().send('party-changed', partyOf([entry('Amy', 'DONE'), entry('Bob', 'CURRENT')], 120))
      await flushPromises()
      expect(wrapper.get('[data-test=party-clock]').text()).toBe('2:00')
    })
  })

  describe('claiming', () => {
    it("tells everyone else's presents who they are waiting for", async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage('Bob') // it's Amy's turn
      const present = wrapper.get('[data-test=book-1] [data-test=present]')
      expect(present.attributes('aria-disabled')).toBe('true')
      expect(present.attributes('title')).toBe('Waiting for Amy')
      expect(wrapper.find('[data-test=your-turn]').exists()).toBe(false)
    })

    it('opens the presents for the person whose turn it is, with a clear prompt', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE, 75) })
      const wrapper = await mountPage('Amy')
      expect(wrapper.get('[data-test=book-1] [data-test=present]').attributes('aria-disabled')).toBe('false')
      expect(wrapper.get('[data-test=your-turn]').text()).toContain("It's your turn!")
      expect(wrapper.get('[data-test=your-turn-clock]').text()).toBe('1:15')
    })

    it('gives the your-turn prompt a warning style in the last 15 seconds', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE, 10) })
      const wrapper = await mountPage('Amy')
      expect(wrapper.get('[data-test=your-turn]').classes()).toContain('urgent')
    })

    it('says "waiting for the next turn" when nobody has the turn', async () => {
      stubServer({ exchange: baseExchange, party: partyOf([entry('Dan', 'DONE')]) })
      const wrapper = await mountPage('Bob')
      expect(wrapper.get('[data-test=book-1] [data-test=present]').attributes('title')).toBe('Waiting for the next turn')
    })

    it('shows the server\'s message if a claim is refused because the turn moved on', async () => {
      const server: Server = { exchange: baseExchange, party: partyOf(QUEUE) }
      const m = stubServer(server)
      const wrapper = await mountPage('Amy')
      m.mockImplementation(async (url: string, init: Init) => {
        if (url.endsWith('/claim')) return json({ error: "That turn has just ended. Wait for the next one.", details: [] }, 409)
        if (url.endsWith('/party')) return json(partyOf([entry('Amy', 'WAITING'), entry('Bob', 'CURRENT')]))
        if (url.endsWith('/books')) return json(BOOKS)
        return json(server.exchange)
      })
      await wrapper.get('[data-test=book-1] [data-test=present]').trigger('click')
      await wrapper.get('[data-test=claim-confirm]').trigger('click')
      await settle()
      expect(wrapper.get('[data-test=notice]').text()).toContain('That turn has just ended')
    })
  })

  describe('live changes', () => {
    it('follows the queue as it changes, and the turn passing to me', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage('Bob')
      stream().open()
      expect(wrapper.find('[data-test=your-turn]').exists()).toBe(false)

      stream().send('party-changed', partyOf([entry('Dan', 'DONE'), entry('Amy', 'DONE'), entry('Bob', 'CURRENT'), entry('Cat', 'WAITING')], 118))
      await flushPromises()

      expect(wrapper.get('[data-test=party-now]').text()).toContain("It's your turn!")
      expect(wrapper.find('[data-test=your-turn]').exists()).toBe(true)
      expect(wrapper.get('[data-test=book-1] [data-test=present]').attributes('aria-disabled')).toBe('false')
      expect(wrapper.get('[data-test=entry-Amy]').classes()).toContain('done')
    })

    it('tells me when my turn passed and I am back at the end of the queue', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage('Amy') // Amy has the turn
      stream().open()

      stream().send('party-changed', partyOf([entry('Dan', 'DONE'), entry('Bob', 'CURRENT'), entry('Cat', 'WAITING'), entry('Amy', 'WAITING')]))
      await flushPromises()

      expect(wrapper.get('[data-test=party-notice]').text()).toContain('Your turn passed')
      expect(wrapper.find('[data-test=your-turn]').exists()).toBe(false)
      expect(wrapper.get('[data-test=party-place]').text()).toBe("You're 3rd.")
    })

    it('does not say my turn passed when I claimed (I am done, not waiting)', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage('Amy')
      stream().open()
      stream().send('party-changed', partyOf([entry('Dan', 'DONE'), entry('Amy', 'DONE'), entry('Bob', 'CURRENT')]))
      await flushPromises()
      expect(wrapper.find('[data-test=party-notice]').exists()).toBe(false)
    })

    it('says why the party ended, and goes back to the normal view', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage('Bob')
      stream().open()

      stream().send('mode-changed', { mode: 'CLAIM' })
      stream().send('party-changed', { active: false, endedReason: 'The host ended the party.', queue: [], eligible: [] })
      await flushPromises()

      expect(wrapper.get('[data-test=party-notice]').text()).toContain('The host ended the party.')
      expect(wrapper.find('[data-test=party-queue]').exists()).toBe(false)
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('🎅 Exchange Time! 🎁')
    })

    it('shows the queue when a party starts while the page is open', async () => {
      const server: Server = { exchange: { ...baseExchange, mode: 'CLAIM' }, party: partyOf(QUEUE) }
      stubServer(server)
      const wrapper = await mountPage('Bob')
      stream().open()
      expect(wrapper.find('[data-test=party-queue]').exists()).toBe(false)

      server.exchange = baseExchange
      stream().send('mode-changed', { mode: 'PARTY' })
      stream().send('party-changed', partyOf(QUEUE))
      await flushPromises()

      expect(wrapper.find('[data-test=party-queue]').exists()).toBe(true)
      expect(wrapper.get('[data-test=mode-banner]').text()).toBe('🎉 Party time! 🎉')
    })

    it('ignores a party message that is not a queue', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage('Bob')
      stream().open()
      stream().send('party-changed', { nope: true })
      await flushPromises()
      expect(wrapper.find('[data-test=party-queue]').exists()).toBe(true)
    })

    it('reads the queue again when the connection comes back', async () => {
      const server: Server = { exchange: baseExchange, party: partyOf(QUEUE) }
      const m = stubServer(server)
      const wrapper = await mountPage('Bob')
      stream().open()
      const before = m.mock.calls.filter((c) => (c[0] as string).endsWith('/party')).length
      server.party = partyOf([entry('Dan', 'DONE'), entry('Amy', 'DONE'), entry('Bob', 'CURRENT')])
      stream().readyState = 0
      ;(stream() as unknown as { listeners: Map<string, ((e: unknown) => void)[]> }).listeners.get('error')?.forEach((fn) => fn({}))
      stream().open()
      await settle(0)
      expect(m.mock.calls.filter((c) => (c[0] as string).endsWith('/party')).length).toBeGreaterThan(before)
      expect(wrapper.get('[data-test=party-now]').text()).toContain("It's your turn!")
    })
  })

  describe('who is counted as online', () => {
    it('opens the live connection with the person\'s name', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      await mountPage('Mary Ann')
      expect(stream().url).toBe('/api/exchanges/K7M2PXA/events?name=Mary%20Ann')
    })

    it('opens a new connection when the name changes, and closes the old one', async () => {
      stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
      const wrapper = await mountPage('Bob')
      const first = stream()
      await wrapper.get('[data-test=change-name]').trigger('click')
      await wrapper.get('[data-test=name-input]').setValue('Sam')
      await wrapper.get('[data-test=name-dialog] form').trigger('submit')
      await flushPromises()
      expect(first.closed).toBe(true)
      expect(stream()).not.toBe(first)
      expect(stream().url).toBe('/api/exchanges/K7M2PXA/events?name=Sam')
    })
  })
})

describe('the host controls for the party', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    saveHostToken('K7M2PXA', 'mine')
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    document.body.innerHTML = ''
  })

  const asHost = (over: Record<string, unknown> = {}) => ({ ...baseExchange, isHost: true, ...over })
  const inactive = (eligible: string[]): PartyState => ({ active: false, queue: [], eligible })

  it('are not shown to anyone but the host', async () => {
    stubServer({ exchange: baseExchange, party: partyOf(QUEUE) })
    const wrapper = await mountPage('Bob')
    expect(wrapper.find('[data-test=host-party]').exists()).toBe(false)
  })

  it('offer to start a party and show who is online before confirming', async () => {
    const m = stubServer({ exchange: asHost({ mode: 'CLAIM', claimedCount: 1 }), party: inactive(['Amy', 'Bob']) })
    const wrapper = await mountPage('Bob')
    await wrapper.get('[data-test=party-start]').trigger('click')
    await flushPromises()

    const dialog = wrapper.get('[role=dialog]')
    expect(dialog.get('[data-test=party-online]').text()).toContain('Amy')
    expect(dialog.get('[data-test=party-online]').text()).toContain('Bob')
    expect(posts(m)).toHaveLength(0) // nothing yet
  })

  it('start the party with the host token, then the page refreshes', async () => {
    const server: Server = { exchange: asHost({ mode: 'CLAIM' }), party: inactive(['Amy']) }
    const m = stubServer(server)
    const wrapper = await mountPage('Amy')
    await wrapper.get('[data-test=party-start]').trigger('click')
    await flushPromises()

    server.exchange = asHost({ mode: 'PARTY' })
    server.party = partyOf([entry('Amy', 'CURRENT')])
    await wrapper.get('[data-test=party-confirm]').trigger('click')
    await flushPromises()

    const [url, init] = posts(m)[0]
    expect(url).toBe('/api/exchanges/K7M2PXA/party/start')
    expect(init.headers['X-Host-Token']).toBe('mine')
    expect(wrapper.find('[role=dialog]').exists()).toBe(false)
    expect(wrapper.find('[data-test=party-queue]').exists()).toBe(true)
    expect(wrapper.get('[data-test=party-running]').text()).toContain("It is Amy's turn")
  })

  it('cannot start when nobody who could claim is online', async () => {
    stubServer({ exchange: asHost({ mode: 'CLAIM' }), party: inactive([]) })
    const wrapper = await mountPage('Bob')
    await wrapper.get('[data-test=party-start]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=party-online]').text()).toContain('Nobody is online')
    expect((wrapper.get('[data-test=party-confirm]').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('cannot start when every present is already claimed', async () => {
    stubServer({ exchange: asHost({ mode: 'CLAIM', bookCount: 3, claimedCount: 3 }), party: inactive(['Amy']) })
    const wrapper = await mountPage('Bob')
    expect((wrapper.get('[data-test=party-start]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.get('[data-test=party-no-presents]').text()).toContain('has been claimed already')
  })

  it('cancelling the start sends nothing', async () => {
    const m = stubServer({ exchange: asHost({ mode: 'CLAIM' }), party: inactive(['Amy']) })
    const wrapper = await mountPage('Bob')
    await wrapper.get('[data-test=party-start]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=party-cancel]').trigger('click')
    expect(wrapper.find('[role=dialog]').exists()).toBe(false)
    expect(posts(m)).toHaveLength(0)
  })

  it('show skip and end while the party is on, and ask before each', async () => {
    const m = stubServer({ exchange: asHost(), party: partyOf(QUEUE) })
    const wrapper = await mountPage('Bob')
    expect(wrapper.get('[data-test=party-running]').text()).toContain("It is Amy's turn")
    expect(wrapper.get('[data-test=party-skip]').text()).toBe('Skip Amy')

    await wrapper.get('[data-test=party-skip]').trigger('click')
    expect(wrapper.get('[role=dialog]').text()).toContain('Skip Amy?')
    await wrapper.get('[data-test=party-confirm]').trigger('click')
    await flushPromises()
    expect(posts(m)[0][0]).toBe('/api/exchanges/K7M2PXA/party/skip')

    await wrapper.get('[data-test=party-end]').trigger('click')
    expect(wrapper.get('[role=dialog]').text()).toContain('End the party?')
    await wrapper.get('[data-test=party-confirm]').trigger('click')
    await flushPromises()
    expect(posts(m)[1][0]).toBe('/api/exchanges/K7M2PXA/party/end')
    expect(posts(m)[1][1].headers['X-Host-Token']).toBe('mine')
  })

  it('stop the submit/claim switch while a party is running', async () => {
    stubServer({ exchange: asHost(), party: partyOf(QUEUE) })
    const wrapper = await mountPage('Bob')
    expect((wrapper.get('[data-test=mode-switch]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.get('[data-test=mode-party]').text()).toContain('End the party')
  })

  it('show the server\'s reason when starting is refused', async () => {
    stubServer({
      exchange: asHost({ mode: 'CLAIM' }), party: inactive(['Amy']),
      action: () => json({ error: 'There are no presents left to claim', details: [] }, 409),
    })
    const wrapper = await mountPage('Bob')
    await wrapper.get('[data-test=party-start]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=party-confirm]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=party-error]').text()).toBe('There are no presents left to claim')
    expect(wrapper.find('[role=dialog]').exists()).toBe(false)
  })

  it('say so when the server says this device is not the host', async () => {
    stubServer({
      exchange: asHost(), party: partyOf(QUEUE),
      action: () => json({ error: 'Only the host of this exchange can change it', details: [] }, 403),
    })
    const wrapper = await mountPage('Bob')
    await wrapper.get('[data-test=party-end]').trigger('click')
    await wrapper.get('[data-test=party-confirm]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=party-error]').text()).toMatch(/not the host/i)
  })

  it('send only one request when confirm is pressed twice quickly', async () => {
    let release!: (r: Response) => void
    const m = stubServer({ exchange: asHost(), party: partyOf(QUEUE), action: () => new Promise<Response>((r) => (release = r)) })
    const wrapper = await mountPage('Bob')
    await wrapper.get('[data-test=party-end]').trigger('click')
    await wrapper.get('[data-test=party-confirm]').trigger('click')
    await wrapper.get('[data-test=party-confirm]').trigger('click')
    expect(posts(m)).toHaveLength(1)
    release(json(inactive([])))
    await flushPromises()
  })
})
