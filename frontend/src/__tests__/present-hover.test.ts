import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { Book } from '../api/books'
import BookPresent from '../components/BookPresent.vue'
import source from '../components/BookPresent.vue?raw'
import type { Claimability } from '../lib/claim'

const book = (over: Partial<Book> = {}): Book => ({
  id: 1, submitterName: 'Sam', wrappingPaper: 'holly', claimed: false, viewerIsClaimer: false, ...over,
})
const mountPresent = (b: Book, claimability: Claimability) => mount(BookPresent, { props: { book: b, claimability } })

describe('which presents get the hover lift (#48)', () => {
  it('a claimable present carries the can-claim class on the slot and the button', () => {
    const wrapper = mountPresent(book(), { state: 'claimable' })
    expect(wrapper.classes()).toContain('can-claim')
    expect(wrapper.get('[data-test=present]').classes()).toContain('can-claim')
  })

  it.each([
    ['already holding a book', "You've already claimed a book"],
    ['your own present', 'You brought this one'],
    ['claiming not open', "Claiming hasn't started yet"],
    ['a closed exchange', 'This exchange is closed'],
  ])('a present that cannot be claimed (%s) gets no lift', (_label, reason) => {
    const wrapper = mountPresent(book(), { state: 'disabled', reason })
    expect(wrapper.classes()).not.toContain('can-claim')
    expect(wrapper.get('[data-test=present]').classes()).not.toContain('can-claim')
  })

  it('a present without any claimability information gets no lift', () => {
    const wrapper = mount(BookPresent, { props: { book: book() } })
    expect(wrapper.get('[data-test=present]').classes()).not.toContain('can-claim')
  })

  it('a claimed present is an opened card, not a button, and never lifts', () => {
    const wrapper = mountPresent(
      book({ claimed: true, claimedByName: 'Lee', bookTitle: 'Emma' }),
      { state: 'disabled', reason: '' },
    )
    expect(wrapper.find('[data-test=present]').exists()).toBe(false)
    expect(wrapper.classes()).not.toContain('can-claim')
  })

  it("keeps the pile's own tilt on the slot, separate from the lifted button", () => {
    const wrapper = mountPresent(book({ id: 5 }), { state: 'claimable' })
    expect(wrapper.attributes('style')).toContain('rotate(')
    // The lift is a stylesheet rule on the inner button, so it can never overwrite the slot's inline transform.
    expect(wrapper.get('[data-test=present]').attributes('style')).toBeUndefined()
  })
})

// jsdom can't hover or evaluate media queries, so the stylesheet rules are pinned by reading the component source.
describe('the hover lift stylesheet (#48)', () => {
  const css = source.slice(source.indexOf('<style'))
  const block = (start: string) => {
    const from = css.indexOf(start)
    expect(from, `missing ${start}`).toBeGreaterThan(-1)
    let depth = 0
    for (let i = css.indexOf('{', from); i < css.length; i++) {
      if (css[i] === '{') depth++
      if (css[i] === '}' && --depth === 0) return css.slice(from, i + 1)
    }
    throw new Error(`unterminated ${start}`)
  }

  it('lifts about 6px and grows about 5% with a short ease, using transform only', () => {
    expect(css).toMatch(/translateY\(-6px\) scale\(1\.05\)/)
    expect(css).toMatch(/transition: transform 180ms ease-out/)
  })

  it('applies the hover effect only on devices that really hover', () => {
    expect(block('@media (hover: hover) {')).toContain('.present.can-claim:hover')
    // ...and never outside that media query (a touch screen must not get a stuck hover)
    const outside = css.replace(block('@media (hover: hover) {'), '').replace(block('@media (hover: hover) and (prefers-reduced-motion: reduce)'), '')
    expect(outside).not.toMatch(/\.present\.can-claim:hover\s*{[^}]*transform: translateY/)
  })

  it('also lifts on keyboard focus', () => {
    expect(css).toMatch(/\.present\.can-claim:focus-visible\s*{\s*transform: translateY\(-6px\) scale\(1\.05\)/)
  })

  it('raises the slot above its neighbours while enlarged, without moving the layout', () => {
    expect(css).toMatch(/\.slot\.can-claim:hover\s*{\s*z-index: 2/)
    expect(css).toMatch(/\.slot\.can-claim\s*{\s*position: relative/)
  })

  it('under reduced motion nothing moves and a static highlight is shown instead', () => {
    const reduced = block('@media (prefers-reduced-motion: reduce) {')
    expect(reduced).toContain('transition: none')
    expect(reduced).toContain('transform: none')
    expect(reduced).toMatch(/outline: 3px solid/)
    expect(reduced).toMatch(/box-shadow/)
    expect(block('@media (hover: hover) and (prefers-reduced-motion: reduce)')).toContain('transform: none')
  })
})
