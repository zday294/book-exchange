import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import WrappedPresent from '../wrapping/WrappedPresent.vue'
import WrappingPaperPicker from '../wrapping/WrappingPaperPicker.vue'
import { DEFAULT_PAPER, PAPER_KEYS, WRAPPING_PAPERS, resolvePaper } from '../wrapping/papers'

describe('paper definitions', () => {
  it('defines ten distinct papers (two rows of five) with keys, labels and colors', () => {
    expect(WRAPPING_PAPERS).toHaveLength(10)
    expect(new Set(PAPER_KEYS).size).toBe(WRAPPING_PAPERS.length)
    expect(new Set(WRAPPING_PAPERS.map((p) => p.pattern)).size).toBe(WRAPPING_PAPERS.length)
    for (const p of WRAPPING_PAPERS) {
      expect(p.label).not.toBe('')
      for (const color of [p.base, p.accent, p.accent2, p.ribbon, p.ribbonShade]) {
        expect(color).toMatch(/^#[0-9a-f]{6}$/i)
      }
    }
  })

  it('matches the keys the backend accepts (same keys, same order)', () => {
    const java = readFileSync(
      resolve(__dirname, '../../../backend/src/main/java/com/bookexchange/book/WrappingPapers.java'),
      'utf8',
    )
    const list = java.match(/KEY_LIST\s*=\s*List\.of\(([^)]*)\)/s)?.[1] ?? ''
    const backendKeys = [...list.matchAll(/"([^"]+)"/g)].map((m) => m[1])
    expect(backendKeys.length).toBeGreaterThan(0)
    expect(PAPER_KEYS).toEqual(backendKeys)
  })

  it('falls back to the default paper for unknown or missing keys', () => {
    expect(resolvePaper('holly').key).toBe('holly')
    expect(resolvePaper('nope')).toBe(DEFAULT_PAPER)
    expect(resolvePaper(undefined)).toBe(DEFAULT_PAPER)
    expect(resolvePaper(null)).toBe(DEFAULT_PAPER)
  })
})

describe('WrappedPresent', () => {
  it.each(PAPER_KEYS)('renders the %s paper with a pattern fill', (key) => {
    const w = mount(WrappedPresent, { props: { paper: key, tag: 'Alex' } })
    expect(w.find('svg').exists()).toBe(true)
    expect(w.findAll('pattern')).toHaveLength(1)
    expect(w.find('pattern').attributes('id')).toBeTruthy()
    expect(w.html()).toContain('url(#' + w.find('pattern').attributes('id') + ')')
  })

  it('shows the name tag and describes the present for screen readers', () => {
    const w = mount(WrappedPresent, { props: { paper: 'stars', tag: 'Alex' } })
    expect(w.find('text').text()).toBe('Alex')
    expect(w.get('svg').attributes('aria-label')).toBe('Stars wrapped present for Alex')
  })

  describe('book shape', () => {
    const dims = (props: Record<string, unknown>) => {
      const w = mount(WrappedPresent, { props: { paper: 'holly', tag: 'Zach', ...props } })
      const rect = w.get('[data-test=book-shape]')
      return { w, width: Number(rect.attributes('width')), height: Number(rect.attributes('height')) }
    }

    it.each([0.5, 0.67, 0.75, 1, 1.3, 1.6])('draws a rectangle in the proportions %s', (aspect) => {
      const { width, height } = dims({ aspect })
      expect(width / height).toBeCloseTo(aspect, 2)
      expect(Math.max(width, height)).toBeLessThanOrEqual(164)
    })

    it('clamps extreme proportions to 0.5 - 1.6', () => {
      const tall = dims({ aspect: 0.1 })
      const wide = dims({ aspect: 5 })
      expect(tall.width / tall.height).toBeCloseTo(0.5, 2)
      expect(wide.width / wide.height).toBeCloseTo(1.6, 2)
    })

    it.each([undefined, null, 0, -1, NaN, Infinity])('uses a default book shape when the ratio is %s', (aspect) => {
      const { width, height } = dims({ aspect })
      expect(width / height).toBeCloseTo(0.67, 2)
    })

    it('puts the crossed ribbon and the bow in the middle of the book', () => {
      const { w } = dims({ aspect: 0.67 })
      const rects = w.findAll('rect')
      const book = w.get('[data-test=book-shape]')
      const cx = Number(book.attributes('x')) + Number(book.attributes('width')) / 2
      const cy = Number(book.attributes('y')) + Number(book.attributes('height')) / 2
      const vertical = rects.find((r) => Number(r.attributes('height')) === Number(book.attributes('height')) && r !== book && Number(r.attributes('width')) > 6 && Number(r.attributes('width')) < 30)!
      expect(Number(vertical.attributes('x')) + Number(vertical.attributes('width')) / 2).toBeCloseTo(cx, 1)
      expect(w.html()).toContain(`translate(${cx} ${cy + 4})`) // the bow
    })

    it.each([0.5, 0.67, 1, 1.6])('keeps the name tag on the book at %s, even with two lines', (aspect) => {
      const { w } = dims({ aspect, tag: 'Bartholomew Jones' })
      const book = w.get('[data-test=book-shape]')
      const left = Number(book.attributes('x'))
      const right = left + Number(book.attributes('width'))
      const bottom = Number(book.attributes('y')) + Number(book.attributes('height'))
      const m = /translate\(([\d.]+) ([\d.]+)\) rotate\(8\) scale\(([\d.]+)\)/.exec(w.get('[data-test=tag]').attributes('transform') ?? '')!
      const [x, y, scale] = [Number(m[1]), Number(m[2]), Number(m[3])]
      expect(x - 31 * scale).toBeGreaterThanOrEqual(left - 1)
      expect(x + 31 * scale).toBeLessThanOrEqual(right + 6) // the tag may tilt a little past the edge
      expect(y + 14 * scale).toBeLessThanOrEqual(bottom + 3)
    })

    it('describes the present the same way whatever its shape', () => {
      const { w } = dims({ aspect: 1.3, paper: 'holly', tag: 'Alex' })
      expect(w.get('svg').attributes('aria-label')).toBe('Holly wrapped present for Alex')
    })

    it.each(PAPER_KEYS)('renders the %s paper in a wide and a tall book', (key) => {
      for (const aspect of [0.5, 1.6]) {
        const w = mount(WrappedPresent, { props: { paper: key, tag: 'Alex', aspect } })
        expect(w.html()).toContain('url(#' + w.find('pattern').attributes('id') + ')')
      }
    })
  })

  it('shows no tag when none is given', () => {
    const w = mount(WrappedPresent, { props: { paper: 'stars' } })
    expect(w.find('text').exists()).toBe(false)
    expect(w.get('svg').attributes('aria-label')).toBe('Stars wrapped present')
  })

  it('shortens long names on the tag but keeps the full name in the description', () => {
    const long = 'Bartholomew Featherstonehaugh'
    const w = mount(WrappedPresent, { props: { paper: 'holly', tag: long } })
    const lines = w.findAll('text tspan').map((t) => t.text())
    expect(lines.length).toBeLessThanOrEqual(2)
    for (const line of lines) expect(line.length).toBeLessThanOrEqual(12)
    expect(lines.join(' ')).toContain('…')
    expect(w.get('svg').attributes('aria-label')).toContain(long)
  })

  it('puts a longer name on two lines, breaking between words', () => {
    const w = mount(WrappedPresent, { props: { paper: 'holly', tag: 'Christmas tree' } })
    expect(w.findAll('text tspan').map((t) => t.text())).toEqual(['Christmas', 'tree'])
    const one = mount(WrappedPresent, { props: { paper: 'holly', tag: 'Alexandria' } })
    expect(one.findAll('text tspan').map((t) => t.text())).toEqual(['Alexandria'])
  })

  it('does not break on an unknown key and uses the default paper', () => {
    const w = mount(WrappedPresent, { props: { paper: 'mystery', tag: 'Zach' } })
    expect(w.findAll('pattern')).toHaveLength(1)
    expect(w.get('svg').attributes('aria-label')).toContain(DEFAULT_PAPER.label)
  })

  it('gives every present its own pattern id so several can share a page', () => {
    // Both inside ONE app, like on the exchange page (separate mount() calls are separate apps).
    const Page = defineComponent({
      render: () => h('div', [h(WrappedPresent, { paper: 'plaid' }), h(WrappedPresent, { paper: 'holly' })]),
    })
    const ids = mount(Page).findAll('pattern').map((p) => p.attributes('id'))
    expect(ids).toHaveLength(2)
    expect(ids[0]).not.toBe(ids[1])
  })

  it('scales to its container (viewBox, no fixed size)', () => {
    const svg = mount(WrappedPresent, { props: { paper: 'plaid' } }).get('svg')
    expect(svg.attributes('viewBox')).toBe('0 0 200 200')
    expect(svg.attributes('width')).toBeUndefined()
  })
})

describe('WrappingPaperPicker', () => {
  it('lists every paper as a radio option', () => {
    const w = mount(WrappingPaperPicker)
    const radios = w.findAll('input[type=radio]')
    expect(radios.map((r) => (r.element as HTMLInputElement).value)).toEqual([...PAPER_KEYS])
    for (const p of WRAPPING_PAPERS) expect(w.text()).toContain(p.label)
  })

  it('puts each paper\'s name on its present\'s tag', () => {
    const w = mount(WrappingPaperPicker)
    const tags = w.findAll('svg text').map((t) => t.text())
    // Longer names wrap onto two lines, so compare without spaces.
    expect(tags.map((t) => t.replace(/\s/g, ''))).toEqual(WRAPPING_PAPERS.map((p) => p.label.replace(/\s/g, '')))
  })

  it('marks the current value as selected', () => {
    const w = mount(WrappingPaperPicker, { props: { modelValue: 'plaid' } })
    const checked = w.findAll('input[type=radio]').filter((r) => (r.element as HTMLInputElement).checked)
    expect(checked).toHaveLength(1)
    expect((checked[0].element as HTMLInputElement).value).toBe('plaid')
  })

  it('emits the chosen key', async () => {
    const w = mount(WrappingPaperPicker, { props: { modelValue: 'holly' } })
    await w.get('[data-test=paper-snowflake]').setValue(true)
    expect(w.emitted('update:modelValue')).toEqual([['snowflake']])
  })

  it('selects nothing when there is no value yet', () => {
    const w = mount(WrappingPaperPicker)
    expect(w.findAll('input[type=radio]').some((r) => (r.element as HTMLInputElement).checked)).toBe(false)
  })
})
