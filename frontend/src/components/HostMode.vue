<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError } from '../api/client'
import { setExchangeMode, type Exchange } from '../api/exchanges'
import ModalDialog from './ModalDialog.vue'

/**
 * Host block: switch the exchange between submit mode (people bring books) and claim mode (people take
 * them). The two are exclusive and the server enforces it; this is just the switch.
 */
const props = defineProps<{ exchange: Exchange }>()
const emit = defineEmits<{ saved: [exchange: Exchange] }>()

const asking = ref(false)
const busy = ref(false)
const error = ref('')

const toClaim = computed(() => props.exchange.mode === 'SUBMIT') // the switch goes to the other mode
const target = computed<'SUBMIT' | 'CLAIM'>(() => (toClaim.value ? 'CLAIM' : 'SUBMIT'))
const open = computed(() => props.exchange.status === 'OPEN')
const partyRunning = computed(() => props.exchange.mode === 'PARTY')
const books = computed(() => `${props.exchange.bookCount} ${props.exchange.bookCount === 1 ? 'book' : 'books'}`)

function ask() {
  error.value = ''
  asking.value = true
}

async function confirm() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    emit('saved', await setExchangeMode(props.exchange.code, target.value))
    asking.value = false
  } catch (e) {
    asking.value = false
    error.value =
      e instanceof ApiError && e.status === 403
        ? "You're not the host of this exchange on this device, so the mode can't be changed."
        : e instanceof ApiError
          ? e.message
          : 'Something went wrong. Please try again.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="block" aria-labelledby="mode-title" data-test="host-mode">
    <h3 id="mode-title">Submit or claim</h3>
    <p data-test="mode-description">
      <template v-if="exchange.mode === 'SUBMIT'">
        <strong>Submit mode.</strong> Everyone can add their book, but nobody can claim yet.
      </template>
      <template v-else-if="exchange.mode === 'PARTY'">
        <strong>Party.</strong> Everyone claims in turn, one person at a time. Nobody can add a book.
      </template>
      <template v-else>
        <strong>Claim mode.</strong> Everyone can claim a present, but nobody can add a book any more.
      </template>
    </p>
    <p class="counts" data-test="mode-counts">
      {{ books }} in the pile · {{ exchange.claimedCount }} claimed
    </p>

    <p v-if="error" class="error" role="alert" data-test="mode-error">{{ error }}</p>

    <button type="button" :disabled="!open || partyRunning" data-test="mode-switch" @click="ask">
      {{ toClaim ? 'Start claiming…' : 'Go back to submitting…' }}
    </button>
    <p v-if="partyRunning" class="hint" data-test="mode-party">A party is running. End the party to change the mode.</p>
    <p v-if="!open" class="hint" data-test="mode-closed">This exchange is closed, so the mode can't be used.</p>

    <ModalDialog v-if="asking" labelledby="mode-confirm-title" @close="!busy && (asking = false)">
      <h2 id="mode-confirm-title">{{ toClaim ? 'Start claiming?' : 'Go back to submitting?' }}</h2>
      <p v-if="toClaim" data-test="mode-confirm-text">
        Nobody can submit a book after this, and everyone will be able to claim. You can switch back at any time.
        <strong v-if="exchange.bookCount === 0" class="warn" data-test="mode-no-books">
          No books have been submitted yet, so there is nothing to claim.
        </strong>
      </p>
      <p v-else data-test="mode-confirm-text">
        People will be able to submit books again, and claiming will pause. Books already claimed stay claimed.
      </p>
      <div class="actions">
        <button type="button" class="secondary" data-autofocus :disabled="busy" data-test="mode-cancel" @click="asking = false">
          Not yet
        </button>
        <button type="button" :disabled="busy" data-test="mode-confirm" @click="confirm">
          {{ busy ? 'Switching…' : toClaim ? 'Start claiming' : 'Back to submitting' }}
        </button>
      </div>
    </ModalDialog>
  </section>
</template>

<style scoped>
.block {
  margin-top: 1.5rem;
  padding-top: 1rem;
  border-top: 1px solid var(--color-border);
}
h3,
h2 {
  margin-top: 0;
}
.counts {
  color: var(--color-muted);
  margin: 0 0 0.75rem;
}
.hint {
  color: var(--color-muted);
  font-size: 0.9rem;
}
.warn {
  display: block;
  margin-top: 0.5rem;
  color: var(--color-red);
}
.error {
  margin: 0 0 0.5rem;
}
.actions {
  display: flex;
  gap: 0.5rem;
  justify-content: flex-end;
}
</style>
