<script setup lang="ts">
import { computed, useId } from 'vue'
import { resolvePaper } from './papers'

const props = withDefaults(
  defineProps<{
    /** Wrapping paper key. Unknown keys fall back to the default paper. */
    paper?: string | null
    /** Name shown on the tag (usually the submitter). Omit for no tag. */
    tag?: string
  }>(),
  { paper: null, tag: undefined },
)

const p = computed(() => resolvePaper(props.paper))
const patternId = `paper-${useId()}`
const fill = computed(() => `url(#${patternId})`)

const MAX_TAG_CHARS = 10
const tagText = computed(() => {
  const t = (props.tag ?? '').trim()
  return t.length > MAX_TAG_CHARS ? t.slice(0, MAX_TAG_CHARS - 1) + '…' : t
})

/** Points of a 5-point star centred on (cx, cy). */
function star(cx: number, cy: number, outer: number, inner: number): string {
  const pts: string[] = []
  for (let i = 0; i < 10; i++) {
    const r = i % 2 === 0 ? outer : inner
    const a = (Math.PI / 5) * i - Math.PI / 2
    pts.push(`${(cx + r * Math.cos(a)).toFixed(2)},${(cy + r * Math.sin(a)).toFixed(2)}`)
  }
  return pts.join(' ')
}
const bigStar = star(12, 12, 8, 3.4)
const smallStar = star(32, 33, 5, 2.1)

const label = computed(() =>
  props.tag ? `${p.value.label} wrapped present for ${props.tag}` : `${p.value.label} wrapped present`,
)
</script>

<template>
  <svg viewBox="0 0 200 200" role="img" :aria-label="label" class="present" xmlns="http://www.w3.org/2000/svg">
    <title>{{ label }}</title>
    <defs>
      <!-- Each paper is a small repeating tile. -->
      <pattern v-if="p.pattern === 'stripes'" :id="patternId" width="26" height="26" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="26" height="26" :fill="p.base" />
        <rect width="13" height="26" :fill="p.accent" />
      </pattern>

      <pattern v-else-if="p.pattern === 'snowflakes'" :id="patternId" width="40" height="40" patternUnits="userSpaceOnUse">
        <rect width="40" height="40" :fill="p.base" />
        <g :stroke="p.accent" stroke-width="1.6" stroke-linecap="round" transform="translate(12 12)">
          <path d="M0-8V8M-7-4L7 4M-7 4L7-4" />
          <path d="M-2-6L0-4L2-6M-2 6L0 4L2 6" stroke-width="1" />
        </g>
        <g :stroke="p.accent2" stroke-width="1.2" stroke-linecap="round" transform="translate(32 32) scale(.6)">
          <path d="M0-8V8M-7-4L7 4M-7 4L7-4" />
        </g>
        <circle cx="32" cy="9" r="1.4" :fill="p.accent2" />
        <circle cx="7" cy="33" r="1.4" :fill="p.accent2" />
      </pattern>

      <pattern v-else-if="p.pattern === 'holly'" :id="patternId" width="44" height="44" patternUnits="userSpaceOnUse">
        <rect width="44" height="44" :fill="p.base" />
        <!-- a sprig: two pointed, scalloped leaves and a cluster of berries -->
        <g transform="translate(22 24)">
          <path d="M0 0C3-5 6-5 9-8C10-4 14-3 17 0C14 3 10 4 9 8C6 5 3 5 0 0Z" :fill="p.accent" transform="rotate(-150)" />
          <path d="M0 0C3-5 6-5 9-8C10-4 14-3 17 0C14 3 10 4 9 8C6 5 3 5 0 0Z" :fill="p.accent" transform="rotate(-30)" />
          <circle cx="-2.5" cy="1" r="3" :fill="p.accent2" />
          <circle cx="2.5" cy="1" r="3" :fill="p.accent2" />
          <circle cx="0" cy="4.5" r="3" :fill="p.accent2" />
        </g>
        <circle cx="4" cy="5" r="1.6" :fill="p.accent2" />
        <circle cx="40" cy="5" r="1.6" :fill="p.accent2" />
      </pattern>

      <pattern v-else-if="p.pattern === 'stars'" :id="patternId" width="40" height="40" patternUnits="userSpaceOnUse">
        <rect width="40" height="40" :fill="p.base" />
        <polygon :points="bigStar" :fill="p.accent" />
        <polygon :points="smallStar" :fill="p.accent2" />
      </pattern>

      <pattern v-else-if="p.pattern === 'plaid'" :id="patternId" width="48" height="48" patternUnits="userSpaceOnUse">
        <rect width="48" height="48" :fill="p.base" />
        <rect x="6" width="16" height="48" :fill="p.accent" opacity=".55" />
        <rect y="6" width="48" height="16" :fill="p.accent" opacity=".55" />
        <rect x="32" width="2" height="48" :fill="p.accent2" opacity=".85" />
        <rect y="32" width="48" height="2" :fill="p.accent2" opacity=".85" />
      </pattern>

      <pattern v-else :id="patternId" width="30" height="30" patternUnits="userSpaceOnUse">
        <rect width="30" height="30" :fill="p.base" />
        <circle cx="8" cy="8" r="5" :fill="p.accent" />
        <circle cx="23" cy="23" r="5" :fill="p.accent" />
        <circle cx="23" cy="6" r="1.8" :fill="p.accent2" />
        <circle cx="6" cy="24" r="1.8" :fill="p.accent2" />
      </pattern>
    </defs>

    <!-- soft shadow under the box -->
    <ellipse cx="100" cy="184" rx="78" ry="7" fill="#000" opacity=".12" />

    <!-- the wrapped box: body and a slightly wider lid -->
    <rect x="26" y="86" width="148" height="92" rx="4" :fill="fill" />
    <rect x="26" y="86" width="148" height="92" rx="4" fill="none" stroke="#000" stroke-opacity=".18" />
    <rect x="20" y="66" width="160" height="28" rx="4" :fill="fill" />
    <rect x="20" y="66" width="160" height="28" rx="4" fill="none" stroke="#000" stroke-opacity=".22" />
    <rect x="26" y="94" width="148" height="6" fill="#000" opacity=".12" />

    <!-- ribbon -->
    <rect x="90" y="66" width="20" height="112" :fill="p.ribbon" />
    <rect x="90" y="66" width="20" height="112" fill="none" :stroke="p.ribbonShade" stroke-opacity=".5" />
    <rect x="20" y="74" width="160" height="12" :fill="p.ribbon" />
    <rect x="20" y="74" width="160" height="12" fill="none" :stroke="p.ribbonShade" stroke-opacity=".5" />

    <!-- bow -->
    <path d="M100 66C78 30 44 42 62 62C70 70 90 68 100 66Z" :fill="p.ribbon" :stroke="p.ribbonShade" stroke-width="2" stroke-linejoin="round" />
    <path d="M100 66C122 30 156 42 138 62C130 70 110 68 100 66Z" :fill="p.ribbon" :stroke="p.ribbonShade" stroke-width="2" stroke-linejoin="round" />
    <path d="M100 66C86 50 70 50 66 58M100 66C114 50 130 50 134 58" fill="none" :stroke="p.ribbonShade" stroke-width="1.6" stroke-linecap="round" opacity=".6" />
    <rect x="91" y="58" width="18" height="16" rx="5" :fill="p.ribbon" :stroke="p.ribbonShade" stroke-width="2" />

    <!-- name tag on a string -->
    <g v-if="tagText">
      <path d="M118 74C132 96 142 106 146 122" fill="none" stroke="#6b5a3c" stroke-width="1.4" />
      <g transform="translate(146 122) rotate(8)">
        <path d="M-31-10H25L31-4V14H-31Z" fill="#fffaf0" stroke="#6b5a3c" stroke-width="1.4" stroke-linejoin="round" />
        <circle cx="25" cy="-4" r="1.6" fill="#6b5a3c" />
        <text x="-3" y="8" text-anchor="middle" font-size="10.5" font-family="Georgia, serif" fill="#2b2b2b">{{ tagText }}</text>
      </g>
    </g>
  </svg>
</template>

<style scoped>
.present {
  display: block;
  width: 100%;
  height: auto;
}
</style>
