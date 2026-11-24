<script setup lang="ts">
import { computed, useId } from 'vue'
import { resolvePaper } from './papers'

const props = withDefaults(
  defineProps<{
    /** Wrapping paper key. Unknown keys fall back to the default paper. */
    paper?: string | null
    /** Name shown on the tag (usually the submitter). Omit for no tag. */
    tag?: string
    /** The book's cover proportions (width / height). Clamped; a typical paperback shape when unknown. */
    aspect?: number | null
  }>(),
  { paper: null, tag: undefined, aspect: null },
)

const DEFAULT_ASPECT = 0.67
const MIN_ASPECT = 0.5
const MAX_ASPECT = 1.6

const p = computed(() => resolvePaper(props.paper))
const patternId = `paper-${useId()}`
const fill = computed(() => `url(#${patternId})`)

// The tag is small: one line of up to 10 characters, or two smaller lines of up to 12.
const ONE_LINE_CHARS = 10
const TWO_LINE_CHARS = 12

function clip(text: string, max: number): string {
  return text.length > max ? text.slice(0, max - 1) + '…' : text
}

/** Splits a name over at most two lines, breaking between words where possible. */
function tagLines(raw: string): string[] {
  const text = raw.trim().replace(/\s+/g, ' ')
  if (text.length <= ONE_LINE_CHARS) return text ? [text] : []
  const words = text.split(' ')
  let first = ''
  let i = 0
  while (i < words.length && (first ? first + ' ' + words[i] : words[i]).length <= TWO_LINE_CHARS) {
    first = first ? first + ' ' + words[i] : words[i]
    i++
  }
  if (!first) return [clip(text, TWO_LINE_CHARS)] // one very long word
  const rest = words.slice(i).join(' ')
  return rest ? [first, clip(rest, TWO_LINE_CHARS)] : [first]
}

const lines = computed(() => tagLines(props.tag ?? ''))
const tagText = computed(() => lines.value.length > 0)
// Smaller type for two lines, or for a single line that is longer than 10 characters.
const fontSize = computed(() =>
  lines.value.length > 1 || (lines.value[0]?.length ?? 0) > ONE_LINE_CHARS ? 8.5 : 10.5,
)

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
const cookieStar = star(33, 31, 9, 4.6)
const cookieStarIcing = star(33, 31, 6.8, 3.4)
const starTop = star(0, 2, 4, 1.8)
const bigStar = star(12, 12, 8, 3.4)
const smallStar = star(32, 33, 5, 2.1)

// ---- the wrapped book: a rectangle in the cover's proportions, bottom-aligned in a 200 x 200 picture ----
const ratio = computed(() => {
  const a = props.aspect
  if (typeof a !== 'number' || !Number.isFinite(a) || a <= 0) return DEFAULT_ASPECT
  return Math.min(MAX_ASPECT, Math.max(MIN_ASPECT, a))
})
const MAX_SIDE = 164
const BASE = 180
const book = computed(() => {
  const r = ratio.value
  const w = r <= 1 ? MAX_SIDE * r : MAX_SIDE
  const h = r <= 1 ? MAX_SIDE : MAX_SIDE / r
  const x = 100 - w / 2
  const y = BASE - h
  return { w, h, x, y, cx: 100, cy: y + h / 2 }
})
const band = computed(() => Math.max(11, Math.min(book.value.w, book.value.h) * 0.13))
const bowScale = computed(() => Math.min(1.15, Math.min(book.value.w, book.value.h) / 120))
const tagScale = computed(() => Math.min(1, book.value.w / 92))
const tagPos = computed(() => {
  const b = book.value
  const half = 31 * tagScale.value
  return { x: Math.min(b.x + b.w - half - 3, b.cx + b.w * 0.14), y: b.y + b.h - 22 * tagScale.value }
})

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

      <pattern v-else-if="p.pattern === 'cookies'" :id="patternId" width="46" height="46" patternUnits="userSpaceOnUse">
        <rect width="46" height="46" :fill="p.base" />
        <!-- a round cookie with an icing edge and sprinkles -->
        <circle cx="13" cy="13" r="9" :fill="p.accent" />
        <circle cx="13" cy="13" r="7" fill="none" stroke="#ffffff" stroke-width="1.4" stroke-dasharray="2.4 1.6" />
        <rect x="9" y="10" width="4" height="1.5" rx=".7" :fill="p.accent2" transform="rotate(30 11 10.7)" />
        <rect x="14" y="14" width="4" height="1.5" rx=".7" fill="#1f6b45" transform="rotate(-40 16 14.7)" />
        <rect x="10" y="16" width="3.5" height="1.5" rx=".7" fill="#2b5f8e" transform="rotate(75 11.7 16.7)" />
        <!-- a star-shaped cookie -->
        <polygon :points="cookieStar" :fill="p.accent" />
        <polygon :points="cookieStarIcing" fill="none" stroke="#ffffff" stroke-width="1.3" stroke-linejoin="round" />
        <circle cx="33" cy="33" r="1.2" :fill="p.accent2" />
      </pattern>

      <pattern v-else-if="p.pattern === 'trees'" :id="patternId" width="44" height="52" patternUnits="userSpaceOnUse">
        <rect width="44" height="52" :fill="p.base" />
        <g transform="translate(22 4)">
          <polygon :points="starTop" fill="#f4c542" />
          <polygon points="0,6 -8,20 8,20" :fill="p.accent" />
          <polygon points="0,12 -11,29 11,29" :fill="p.accent" />
          <polygon points="0,20 -14,38 14,38" :fill="p.accent" />
          <rect x="-2.5" y="38" width="5" height="6" fill="#6b4226" />
          <circle cx="-3" cy="24" r="1.7" :fill="p.accent2" />
          <circle cx="4" cy="31" r="1.7" fill="#f4c542" />
          <circle cx="-6" cy="34" r="1.7" fill="#ffffff" />
          <circle cx="2" cy="16" r="1.5" fill="#f4c542" />
        </g>
        <circle cx="4" cy="8" r="1.2" fill="#ffffff" />
        <circle cx="40" cy="30" r="1.2" fill="#ffffff" />
      </pattern>

      <pattern v-else-if="p.pattern === 'fireplace'" :id="patternId" width="60" height="60" patternUnits="userSpaceOnUse">
        <rect width="60" height="60" :fill="p.base" />
        <!-- brick courses: mortar lines with the joints staggered -->
        <g stroke="#d6bfa0" stroke-width="1.6" opacity=".9">
          <path d="M0 10H60M0 20H60M0 30H60M0 40H60M0 50H60" />
          <path d="M15 0V10M45 0V10M30 10V20M0 10V20M60 10V20M15 20V30M45 20V30M30 30V40M0 30V40M60 30V40M15 40V50M45 40V50M30 50V60M0 50V60M60 50V60" />
        </g>
        <!-- a little fireplace opening with a fire in it -->
        <path d="M14 56V30C14 20 46 20 46 30V56Z" fill="#2b1810" />
        <path d="M30 54C21 54 20 45 25 40C25 44 28 44 28 40C28 36 31 33 32 29C34 34 40 38 38 46C38 50 36 54 30 54Z" :fill="p.accent" />
        <path d="M30 54C26 54 25 49 28 46C29 48 31 48 31 45C33 47 35 50 34 52C33 54 32 54 30 54Z" :fill="p.accent2" />
        <rect x="12" y="55" width="36" height="3" fill="#4b3a2f" />
      </pattern>

      <pattern v-else-if="p.pattern === 'sleighs'" :id="patternId" width="56" height="48" patternUnits="userSpaceOnUse">
        <rect width="56" height="48" :fill="p.base" />
        <g transform="translate(8 14)">
          <!-- runner -->
          <path d="M-2 14C8 17 22 17 28 10C30 8 31 6 29 5" fill="none" :stroke="p.accent2" stroke-width="2" stroke-linecap="round" />
          <path d="M6 14V10M20 14V10" :stroke="p.accent2" stroke-width="1.6" />
          <!-- body and curled seat back -->
          <path d="M2 2H24C24 8 22 11 17 11H8C3 11 2 7 2 2Z" :fill="p.accent" />
          <path d="M2 2C-1 -2 -3 1 -1 4" fill="none" :stroke="p.accent" stroke-width="2" stroke-linecap="round" />
          <path d="M5 6H21" stroke="#ffffff" stroke-width="1" opacity=".6" />
        </g>
        <g fill="#ffffff">
          <circle cx="46" cy="8" r="1.4" /><circle cx="38" cy="38" r="1.2" /><circle cx="6" cy="42" r="1.3" />
          <circle cx="50" cy="26" r="1" /><circle cx="26" cy="6" r="1" />
        </g>
      </pattern>

      <pattern v-else :id="patternId" width="30" height="30" patternUnits="userSpaceOnUse">
        <rect width="30" height="30" :fill="p.base" />
        <circle cx="8" cy="8" r="5" :fill="p.accent" />
        <circle cx="23" cy="23" r="5" :fill="p.accent" />
        <circle cx="23" cy="6" r="1.8" :fill="p.accent2" />
        <circle cx="6" cy="24" r="1.8" :fill="p.accent2" />
      </pattern>
    </defs>

    <!-- soft shadow under the book -->
    <ellipse cx="100" :cy="BASE + 4" :rx="book.w / 2 + 8" ry="6" fill="#000" opacity=".12" />

    <!-- the wrapped book -->
    <rect :x="book.x" :y="book.y" :width="book.w" :height="book.h" rx="3" :fill="fill" data-test="book-shape" />
    <rect :x="book.x" :y="book.y" :width="6" :height="book.h" rx="3" fill="#000" opacity=".1" />
    <rect :x="book.x" :y="book.y" :width="book.w" :height="book.h" rx="3" fill="none" stroke="#000" stroke-opacity=".22" />

    <!-- ribbon crossing in the middle -->
    <rect :x="book.cx - band / 2" :y="book.y" :width="band" :height="book.h" :fill="p.ribbon" />
    <rect :x="book.cx - band / 2" :y="book.y" :width="band" :height="book.h" fill="none" :stroke="p.ribbonShade" stroke-opacity=".5" />
    <rect :x="book.x" :y="book.cy - band / 2" :width="book.w" :height="band" :fill="p.ribbon" />
    <rect :x="book.x" :y="book.cy - band / 2" :width="book.w" :height="band" fill="none" :stroke="p.ribbonShade" stroke-opacity=".5" />

    <!-- bow where the bands meet -->
    <g :transform="`translate(${book.cx} ${book.cy + 4}) scale(${bowScale}) translate(-100 -66)`">
      <path d="M100 66C78 30 44 42 62 62C70 70 90 68 100 66Z" :fill="p.ribbon" :stroke="p.ribbonShade" stroke-width="2" stroke-linejoin="round" />
      <path d="M100 66C122 30 156 42 138 62C130 70 110 68 100 66Z" :fill="p.ribbon" :stroke="p.ribbonShade" stroke-width="2" stroke-linejoin="round" />
      <path d="M100 66C86 50 70 50 66 58M100 66C114 50 130 50 134 58" fill="none" :stroke="p.ribbonShade" stroke-width="1.6" stroke-linecap="round" opacity=".6" />
      <rect x="91" y="58" width="18" height="16" rx="5" :fill="p.ribbon" :stroke="p.ribbonShade" stroke-width="2" />
    </g>

    <!-- name tag on a string -->
    <g v-if="tagText">
      <path :d="`M${book.cx + 4} ${book.cy + 8}C${book.cx + 10} ${(book.cy + tagPos.y) / 2} ${tagPos.x} ${(book.cy + tagPos.y) / 2} ${tagPos.x} ${tagPos.y - 10 * tagScale}`" fill="none" stroke="#6b5a3c" stroke-width="1.4" />
      <g :transform="`translate(${tagPos.x} ${tagPos.y}) rotate(8) scale(${tagScale})`" data-test="tag">
        <path d="M-31-10H25L31-4V14H-31Z" fill="#fffaf0" stroke="#6b5a3c" stroke-width="1.4" stroke-linejoin="round" />
        <circle cx="25" cy="-4" r="1.6" fill="#6b5a3c" />
        <text
          text-anchor="middle"
          font-family="Georgia, serif"
          fill="#2b2b2b"
          :font-size="fontSize"
        >
          <tspan v-for="(line, i) in lines" :key="i" x="-3" :y="lines.length > 1 ? (i === 0 ? 2.5 : 11.5) : 8">{{ line }}</tspan>
        </text>
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
