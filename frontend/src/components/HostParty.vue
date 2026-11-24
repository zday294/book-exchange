<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError } from '../api/client'
import type { Exchange } from '../api/exchanges'
import { endParty, getParty, skipTurn, startParty, type PartyState } from '../api/party'
import ModalDialog from './ModalDialog.vue'

/**
 * Host block: start a party (a queue that gives everyone a turn to claim), skip someone, or end it. The server
 * does the work and everyone's page follows by itself; this only asks first and reports problems.
 */
const props = defineProps<{ exchange: Exchange; party: PartyState | null }>()
const emit = defineEmits<{ changed: [] }>()

const running = computed(() => props.exchange.mode === 'PARTY')
const open = computed(() => props.exchange.status === 'OPEN')
const current = computed(() => props.party?.current)

const asking = ref<'start' | 'skip' | 'end' | null>(null)
const online = ref<string[]>([]) // who would be queued, as the server sees it when the host asks
const busy = ref(false)
const looking = ref(false)
const error = ref('')

const noPresents = computed(() => props.exchange.bookCount - props.exchange.claimedCount <= 0)

async function askToStart() {
  error.value = ''
  looking.value = true
  try {
    // Ask the server right now who is online, so the host sees the real list before confirming.
    online.value = (await getParty(props.exchange.code)).eligible ?? []
    asking.value = 'start'
  } catch (e) {
    error.value = messageFor(e)
  } finally {
    looking.value = false
  }
}

function ask(what: 'skip' | 'end') {
  error.value = ''
  asking.value = what
}

function messageFor(e: unknown): string {
  if (e instanceof ApiError && e.status === 403) {
    return "You're not the host of this exchange on this device, so the party can't be changed."
  }
  return e instanceof ApiError ? e.message : 'Something went wrong. Please try again.'
}

async function confirm() {
  if (busy.value || !asking.value) return
  busy.value = true
  const what = asking.value
  try {
    if (what === 'start') await startParty(props.exchange.code)
    else if (what === 'skip') await skipTurn(props.exchange.code)
    else await endParty(props.exchange.code)
    emit('changed') // the page re-reads; live updates also tell everyone
  } catch (e) {
    error.value = messageFor(e)
  } finally {
    asking.value = null
    busy.value = false
  }
}
</script>

<template>
  <section class="block" aria-labelledby="party-title" data-test="host-party">
    <h3 id="party-title">Party</h3>

    <template v-if="!running">
      <p>Line everyone up and give each person a turn to claim a present, one at a time. Everyone who is online now and
        hasn't claimed a present yet is queued, in a random order.</p>
      <p v-if="noPresents && exchange.bookCount > 0" class="hint" data-test="party-no-presents">
        Every present has been claimed already.
      </p>
      <button type="button" :disabled="!open || looking || noPresents" data-test="party-start" @click="askToStart">
        {{ looking ? 'Looking…' : 'Start party…' }}
      </button>
      <p v-if="!open" class="hint" data-test="party-closed">This exchange is closed.</p>
    </template>

    <template v-else>
      <p data-test="party-running">
        <strong>The party is on.</strong>
        <template v-if="current"> It is {{ current }}'s turn.</template>
        <template v-else> Waiting for someone to arrive.</template>
      </p>
      <div class="actions">
        <button type="button" class="secondary" :disabled="!current" data-test="party-skip" @click="ask('skip')">
          Skip {{ current ?? 'this turn' }}
        </button>
        <button type="button" data-test="party-end" @click="ask('end')">End party…</button>
      </div>
    </template>

    <p v-if="error" class="error" role="alert" data-test="party-error">{{ error }}</p>

    <ModalDialog v-if="asking" labelledby="party-confirm-title" @close="!busy && (asking = null)">
      <template v-if="asking === 'start'">
        <h2 id="party-confirm-title">Start the party?</h2>
        <div data-test="party-online">
          <p v-if="online.length > 0">
            These people are online now and haven't claimed a present. They'll be queued in a random order:
          </p>
          <ul v-if="online.length > 0" class="names">
            <li v-for="name in online" :key="name">{{ name }}</li>
          </ul>
          <p v-else class="warn">Nobody is online who can still claim a present. Ask everyone to open this page first.</p>
        </div>
        <p class="hint">Nobody can add a book during the party. Anyone who arrives later joins the end of the line.</p>
      </template>
      <template v-else-if="asking === 'skip'">
        <h2 id="party-confirm-title">Skip {{ current }}?</h2>
        <p>{{ current }} goes to the end of the line, and the next person's turn starts.</p>
      </template>
      <template v-else>
        <h2 id="party-confirm-title">End the party?</h2>
        <p>Everyone can then claim a present whenever they like. Presents already claimed stay claimed.</p>
      </template>
      <div class="actions">
        <button type="button" class="secondary" data-autofocus :disabled="busy" data-test="party-cancel" @click="asking = null">
          Not yet
        </button>
        <button
          type="button"
          :disabled="busy || (asking === 'start' && online.length === 0)"
          data-test="party-confirm"
          @click="confirm"
        >
          {{ busy ? 'Working…' : asking === 'start' ? 'Start the party' : asking === 'skip' ? 'Skip' : 'End the party' }}
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
.hint {
  color: var(--color-muted);
  font-size: 0.9rem;
}
.warn {
  color: var(--color-red);
}
.error {
  margin: 0.5rem 0 0;
}
.names {
  columns: 2;
  margin: 0.25rem 0 0.75rem;
}
.actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  justify-content: flex-end;
}
</style>
