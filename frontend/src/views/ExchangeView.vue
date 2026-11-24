<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import { claimBook, getBooks, type Book } from '../api/books'
import { getExchange, type Exchange } from '../api/exchanges'
import BookPresent from '../components/BookPresent.vue'
import ClaimedRevealDialog from '../components/ClaimedRevealDialog.vue'
import ConfirmClaimDialog from '../components/ConfirmClaimDialog.vue'
import TreeIllustration from '../components/TreeIllustration.vue'
import { claimability, claimFailureMessage } from '../lib/claim'
import { useParticipantStore } from '../stores/participant'

const props = defineProps<{ exchangeCode: string }>()
const participant = useParticipantStore()

// Until live updates (story 15) exist, the page simply re-reads the pile every few seconds.
const REFRESH_MS = 5000

const state = ref<'loading' | 'ready' | 'notfound' | 'error'>('loading')
const exchange = ref<Exchange | null>(null)
const books = ref<Book[]>([])

let run = 0 // each load gets a number; only the newest load may update the page
let inFlight = false
let timer: ReturnType<typeof setInterval> | undefined

async function load(options: { force?: boolean } = {}) {
  if (inFlight && !options.force) return
  const mine = ++run
  inFlight = true
  try {
    const [ex, list] = await Promise.all([getExchange(props.exchangeCode), getBooks(props.exchangeCode)])
    if (mine !== run) return // the exchange or the viewer changed meanwhile: this answer is stale
    if (!Array.isArray(list)) throw new Error('Unexpected response') // never render a malformed pile
    exchange.value = ex
    books.value = list
    state.value = 'ready'
  } catch (e) {
    if (mine !== run) return
    if (e instanceof ApiError && e.status === 404) state.value = 'notfound'
    else if (state.value !== 'ready') state.value = 'error' // a failed refresh keeps what is on screen
  } finally {
    if (mine === run) inFlight = false
  }
}

function restart() {
  state.value = 'loading'
  exchange.value = null
  books.value = []
  load({ force: true })
}

onMounted(() => {
  load({ force: true })
  timer = setInterval(() => {
    if (!document.hidden && state.value === 'ready') load()
  }, REFRESH_MS)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  run++ // ignore anything still in flight
})

watch(() => props.exchangeCode, restart)
// The pile looks different to a different person (their own link), so reload when the name changes.
watch(
  () => participant.name,
  () => load({ force: true }),
)

const myBook = computed(() => books.value.find((b) => b.viewerIsClaimer))
const exchangeOpen = computed(() => exchange.value?.status === 'OPEN')

function claimabilityOf(book: Book) {
  return claimability(book, participant.name, !!myBook.value, exchangeOpen.value)
}

// --- claiming: ask first (it can't be undone), then claim, then show what was won ---
const pending = ref<Book | null>(null) // the present waiting for a "yes"
const claiming = ref(false)
const revealed = ref<Book | null>(null) // what the claimer just won
const notice = ref('')

function askToClaim(book: Book) {
  notice.value = ''
  pending.value = book
}

async function confirmClaim() {
  const book = pending.value
  if (!book || claiming.value) return
  claiming.value = true
  try {
    const won = await claimBook(props.exchangeCode, book.id, participant.name)
    pending.value = null
    // Show it as claimed straight away; the reload below confirms it with the server.
    books.value = books.value.map((b) => (b.id === won.id ? won : b))
    revealed.value = won
  } catch (e) {
    pending.value = null
    notice.value = claimFailureMessage(e) // e.g. someone else got there first
  } finally {
    claiming.value = false
    load({ force: true }) // whatever happened, show the true state of the pile
  }
}
const claimedCount = computed(() => books.value.filter((b) => b.claimed).length)
</script>

<template>
  <p v-if="state === 'loading'" role="status" data-test="loading">Loading the exchange…</p>

  <section v-else-if="state === 'notfound'" class="card" data-test="notfound">
    <h1>Exchange not found</h1>
    <p>
      We couldn't find an exchange with the code <strong>{{ exchangeCode }}</strong
      >. Check the code and try again.
    </p>
    <RouterLink class="button" to="/">Back to start</RouterLink>
  </section>

  <section v-else-if="state === 'error'" class="card" data-test="error">
    <h1>Something went wrong</h1>
    <p>We couldn't load this exchange. Please try again.</p>
    <button type="button" data-test="retry" @click="restart">Try again</button>
  </section>

  <template v-else-if="exchange">
    <header class="top">
      <h1 data-test="exchange-name">{{ exchange.name }}</h1>
      <p v-if="exchange.description" class="description">{{ exchange.description }}</p>
      <p class="counts" data-test="counts">
        {{ books.length }} {{ books.length === 1 ? 'present' : 'presents' }} · {{ claimedCount }} taken
      </p>
      <p class="status card" data-test="my-status">
        <strong>{{ participant.name }}</strong
        >,
        <template v-if="myBook">
          you claimed <em>{{ myBook.bookTitle }}</em>
          <a
            v-if="myBook.amazonUrl"
            :href="myBook.amazonUrl"
            target="_blank"
            rel="noopener noreferrer"
            data-test="my-amazon"
            >View on Amazon</a
          >
        </template>
        <template v-else>you haven't claimed a book yet.</template>
      </p>
    </header>

    <TreeIllustration />

    <p v-if="notice" class="notice card" role="alert" data-test="notice">
      {{ notice }}
      <button type="button" class="secondary" data-test="notice-close" @click="notice = ''">OK</button>
    </p>

    <p v-if="books.length === 0" class="empty card" data-test="empty">
      No presents yet — add one!
      <RouterLink class="button" :to="{ name: 'submit-book', params: { exchangeCode } }">Add a book</RouterLink>
    </p>

    <template v-else>
      <ul class="pile" aria-label="The pile of presents" data-test="pile">
        <BookPresent
          v-for="book in books"
          :key="book.id"
          :book="book"
          :claimability="claimabilityOf(book)"
          @claim="askToClaim"
        />
      </ul>
      <p class="add">
        <RouterLink :to="{ name: 'submit-book', params: { exchangeCode } }">Add a book</RouterLink>
      </p>
    </template>

    <ConfirmClaimDialog
      v-if="pending"
      :submitter-name="pending.submitterName"
      :busy="claiming"
      @confirm="confirmClaim"
      @cancel="pending = null"
    />
    <ClaimedRevealDialog v-if="revealed" :book="revealed" @close="revealed = null" />
  </template>
</template>

<style scoped>
.top {
  text-align: center;
}
.description {
  margin: 0.25rem 0;
}
.counts {
  color: var(--color-muted);
  margin: 0.25rem 0 0.75rem;
}
.status {
  display: inline-block;
  margin: 0 0 1rem;
  padding: 0.5rem 1rem;
}
.status a {
  margin-left: 0.5rem;
}
/* The pile sits around the base of the tree: pulled up so the presents overlap its bottom. */
.pile {
  display: flex;
  flex-wrap: wrap;
  justify-content: center; /* a pile of one sits under the tree, not in the corner */
  gap: 1rem 0.75rem;
  padding: 0;
  margin: 0.5rem 0 0;
  align-items: end;
}
.notice {
  display: flex;
  gap: 1rem;
  align-items: center;
  justify-content: center;
  margin: 0.5rem auto 1rem;
  max-width: 32rem;
  border-color: var(--color-red);
}
.empty {
  text-align: center;
  margin-top: 1rem;
}
.empty .button {
  margin-left: 0.5rem;
}
.add {
  text-align: center;
  margin-top: 1.5rem;
}
</style>
