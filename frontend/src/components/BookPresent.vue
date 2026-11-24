<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Book } from '../api/books'
import { presentLayout } from '../lib/layout'
import WrappedPresent from '../wrapping/WrappedPresent.vue'

/**
 * One book in the pile. Unclaimed: a wrapped present that reveals nothing but who brought it.
 * Claimed: opened and dimmed, showing the cover, the title and who has it ("Taken").
 * The Amazon link is only ever present in the data for the claimer, so it is only shown then.
 */
const props = defineProps<{ book: Book }>()

const layout = computed(() => presentLayout(props.book.id))
const style = computed(() => ({
  transform: `translateY(${layout.value.offsetY}px) rotate(${layout.value.rotateDeg}deg)`,
}))
const tag = computed(() => `From: ${props.book.submitterName}`)

const coverFailed = ref(false)
watch(() => props.book.coverImageUrl, () => (coverFailed.value = false))
const showCover = computed(() => !!props.book.coverImageUrl && !coverFailed.value)

const takenText = computed(() =>
  props.book.viewerIsClaimer ? 'You claimed this' : `Claimed by ${props.book.claimedByName}`,
)
</script>

<template>
  <li class="slot" :style="style" :data-test="`book-${book.id}`">
    <WrappedPresent v-if="!book.claimed" :paper="book.wrappingPaper" :tag="tag" />

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
        v-if="book.amazonUrl"
        class="button amazon"
        :href="book.amazonUrl"
        target="_blank"
        rel="noopener noreferrer"
        data-test="amazon"
      >
        View on Amazon
      </a>
    </article>
  </li>
</template>

<style scoped>
.slot {
  list-style: none;
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
.amazon {
  margin-top: 0.5rem;
  font-size: 0.85rem;
}
</style>
