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
