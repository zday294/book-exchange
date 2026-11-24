import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import { createAppRouter } from '../router'
import { PARTICIPANT_STORAGE_KEY } from '../stores/participant'

async function mountAt(path: string) {
  const router = createAppRouter(createMemoryHistory())
  router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return wrapper
}

describe('routes', () => {
  beforeEach(() => localStorage.setItem(PARTICIPANT_STORAGE_KEY, 'Alex')) // pages that need a name get one
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it.each([
    ['/exchanges/new', 'Create an exchange'],
  ])('%s renders its placeholder', async (path, heading) => {
    const wrapper = await mountAt(path)
    expect(wrapper.find('h1').text()).toBe(heading)
  })

  it('home shows that the backend is reachable', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ name: 'Demo' }), { status: 200 })),
    )
    const wrapper = await mountAt('/')
    expect(wrapper.get('[data-test=api-status]').text()).toContain('Backend connected')
  })

  it('home says so when the backend is down', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('down')))
    const wrapper = await mountAt('/')
    expect(wrapper.get('[data-test=api-status]').text()).toContain('not reachable')
  })

  it('the header links to the create page', async () => {
    const wrapper = await mountAt('/exchanges/new')
    expect(wrapper.findAll('a').map((a) => a.attributes('href'))).toContain('/exchanges/new')
  })
})
