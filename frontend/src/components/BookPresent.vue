<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Book } from '../api/books'
import { viewLinkLabel } from '../lib/bookLink'
import type { Claimability } from '../lib/claim'
import { presentLayout } from '../lib/layout'
import WrappedPresent from '../wrapping/WrappedPresent.vue'

/**
 * One book in the pile. Unclaimed: a wrapped present that reveals nothing but who brought it.
 * Claimed: opened and dimmed, showing the cover, the title and who has it ("Taken").
 * The book link is only ever present in the data for the claimer, so it is only shown then.
 */
const props = defineProps<{ book: Book; claimability?: Claimability }>()
const emit = defineEmits<{ claim: [book: Book] }>()

const layout = computed(() => presentLayout(props.book.id))
// Wrapped presents sit at a slight angle in the pile; opened books are upright so they are easy to read.
const style = computed(() => ({
  transform: props.book.claimed
    ? `translateY(${layout.value.offsetY}px)`
    : `translateY(${layout.value.offsetY}px) rotate(${layout.value.rotateDeg}deg)`,
}))
const tag = computed(() => `From: ${props.book.submitterName}`)

const claimable = computed(() => props.claimability?.state === 'claimable')
const disabledReason = computed(() =>
  props.claimability?.state === 'disabled' ? props.claimability.reason : '',
)
const buttonLabel = computed(() =>
  claimable.value
    ? `Claim the present from ${props.book.submitterName}`
    : `Present from ${props.book.submitterName}${disabledReason.value ? '. ' + disabledReason.value : ''}`,
)

const coverFailed = ref(false)
watch(() => props.book.coverImageUrl, () => (coverFailed.value = false))
const showCover = computed(() => !!props.book.coverImageUrl && !coverFailed.value)

const takenText = computed(() =>
  props.book.viewerIsClaimer ? 'You claimed this' : `Claimed by ${props.book.claimedByName}`,
)
</script>

<template>
  <li class="slot" :class="{ 'can-claim': !book.claimed && claimable }" :style="style" :data-test="`book-${book.id}`">
    <!-- Unclaimed: a real button, so it works with the keyboard and screen readers. When it can't be
         claimed it stays visible but inert (aria-disabled keeps it focusable so the reason is readable). -->
    <button
      v-if="!book.claimed"
      type="button"
      class="present"
      :class="{ unavailable: !claimable, 'can-claim': claimable }"
      :aria-disabled="!claimable"
      :aria-label="buttonLabel"
      :title="disabledReason || undefined"
      data-test="present"
      @click="claimable && emit('claim', book)"
    >
      <WrappedPresent :paper="book.wrappingPaper" :tag="tag" :aspect="book.coverAspectRatio" aria-hidden="true" />
    </button>

    <article v-else class="opened" :class="{ mine: book.viewerIsClaimer }" data-test="opened">
      <div class="cover">
        <img
          v-if="showCover"
          :src="book.coverImageUrl"
          :alt="`Cover of ${book.bookTitle}`"
          data-test="cover"
          @error="coverFailed = true"
        />
        <div v-else class="placeholder" data-test="cover-placeholder">
          <span>{{ book.bookTitle }}</span>
        </div>
      </div>
      <h3 class="title" data-test="title">{{ book.bookTitle }}</h3>
      <p class="taken" data-test="taken">🎁 {{ takenText }}</p>
      <p class="from">From {{ book.submitterName }}</p>
      <a
        v-if="book.bookUrl"
        class="button book-link"
        :href="book.bookUrl"
        target="_blank"
        rel="noopener noreferrer"
        data-test="book-link"
      >
        {{ viewLinkLabel(book.bookUrl) }}
      </a>
    </article>
  </li>
</template>

<style scoped>
.slot {
  list-style: none;
  width: 9rem;
}
.present {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: none;
  border-radius: var(--radius);
  cursor: pointer;
}
.present:focus-visible {
  outline: 3px solid var(--color-gold);
  outline-offset: 2px;
}
/* Can't be claimed by this person: still there, but plainly not clickable. */
.present.unavailable {
  cursor: not-allowed;
  opacity: 0.6;
  filter: grayscale(0.4);
}
/*
 * A present you can claim lifts and grows a little when you point at it (or tab to it). Only `transform` is
 * animated, and it is set on the button, INSIDE the slot that carries the pile's own tilt and nudge, so the
 * two combine instead of one replacing the other and nothing around it moves.
 */
.present.can-claim {
  transition: transform 180ms ease-out, box-shadow 180ms ease-out;
}
/* The slot rises above its neighbours while the present is enlarged. */
.slot.can-claim {
  position: relative;
}
.slot.can-claim:has(.present:focus-visible) {
  z-index: 2;
}
.present.can-claim:focus-visible {
  transform: translateY(-6px) scale(1.05);
}
/* Only devices that really hover, so a tap on a touch screen doesn't leave the present "stuck" lifted. */
@media (hover: hover) {
  .slot.can-claim:hover {
    z-index: 2;
  }
  .present.can-claim:hover {
    transform: translateY(-6px) scale(1.05);
  }
}
/* Reduced motion: nothing moves; the present gets a still highlight instead. */
@media (prefers-reduced-motion: reduce) {
  .present.can-claim {
    transition: none;
  }
  .present.can-claim:focus-visible {
    transform: none;
  }
  .present.can-claim:focus-visible,
  .slot.can-claim:hover .present.can-claim {
    outline: 3px solid var(--color-gold);
    outline-offset: 2px;
    box-shadow: 0 0 0 6px rgba(200, 150, 46, 0.35);
  }
}
@media (hover: hover) and (prefers-reduced-motion: reduce) {
  .present.can-claim:hover {
    transform: none;
  }
}
.opened {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 0.5rem;
  text-align: center;
}
/* The cover is dimmed so a taken book recedes; the text stays fully readable. */
.cover {
  aspect-ratio: 2 / 3;
  max-height: 11rem;
  margin: 0 auto;
  display: grid;
  place-items: center;
  opacity: 0.55;
  filter: grayscale(0.35);
}
.cover img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 4px;
}
.placeholder {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  padding: 0.5rem;
  border-radius: 4px;
  background: linear-gradient(160deg, var(--color-green), #164a31);
  color: #fff;
  font-family: var(--font-display);
  font-size: 0.9rem;
}
.title {
  margin: 0.4rem 0 0.1rem;
  font-size: 0.95rem;
  line-height: 1.2;
}
.taken {
  margin: 0.2rem 0;
  font-weight: 600;
  font-size: 0.85rem;
  color: var(--color-red);
}
.mine .taken {
  color: var(--color-green);
}
.from {
  margin: 0;
  font-size: 0.8rem;
  color: var(--color-muted);
}
.book-link {
  margin-top: 0.5rem;
  font-size: 0.85rem;
}
</style>
