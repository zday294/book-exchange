<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Book } from '../api/books'
import ModalDialog from './ModalDialog.vue'

/** Shown to the person who just claimed a book: the cover, the title and their link. */
const props = defineProps<{ book: Book }>()
defineEmits<{ close: [] }>()

const coverFailed = ref(false)
const showCover = computed(() => !!props.book.coverImageUrl && !coverFailed.value)
</script>

<template>
  <ModalDialog labelledby="reveal-title" @close="$emit('close')">
    <h2 id="reveal-title" data-test="reveal-title">You claimed {{ book.bookTitle }}!</h2>
    <div class="cover">
      <img
        v-if="showCover"
        :src="book.coverImageUrl"
        :alt="`Cover of ${book.bookTitle}`"
        data-test="reveal-cover"
        @error="coverFailed = true"
      />
      <div v-else class="placeholder" data-test="reveal-placeholder">
        <span>{{ book.bookTitle }}</span>
      </div>
    </div>
    <p class="from">Brought by {{ book.submitterName }}</p>
    <a
      v-if="book.amazonUrl"
      class="button"
      :href="book.amazonUrl"
      target="_blank"
      rel="noopener noreferrer"
      data-test="reveal-link"
    >
      View on Amazon
    </a>
    <div class="actions">
      <button type="button" class="secondary" data-autofocus data-test="reveal-close" @click="$emit('close')">
        Close
      </button>
    </div>
  </ModalDialog>
</template>

<style scoped>
h2 {
  margin-top: 0;
  text-align: center;
}
.cover {
  aspect-ratio: 2 / 3;
  max-height: 16rem;
  margin: 0 auto 0.75rem;
  display: grid;
  place-items: center;
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
  padding: 1rem;
  border-radius: 4px;
  background: linear-gradient(160deg, var(--color-green), #164a31);
  color: #fff;
  font-family: var(--font-display);
  text-align: center;
}
.from {
  text-align: center;
  color: var(--color-muted);
  margin: 0 0 0.75rem;
}
.button {
  display: block;
  text-align: center;
}
.actions {
  display: flex;
  justify-content: center;
  margin-top: 0.75rem;
}
</style>
