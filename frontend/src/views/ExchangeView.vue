<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import { claimBook, getBooks, type Book } from '../api/books'
import { getExchange, type Exchange } from '../api/exchanges'
import { asParty, getParty, type PartyState } from '../api/party'
import BookPresent from '../components/BookPresent.vue'
import ClaimedRevealDialog from '../components/ClaimedRevealDialog.vue'
import ConfirmClaimDialog from '../components/ConfirmClaimDialog.vue'
import HostSection from '../components/HostSection.vue'
import PartyQueue from '../components/PartyQueue.vue'
import TreeIllustration from '../components/TreeIllustration.vue'
import { viewLinkLabel } from '../lib/bookLink'
import { claimability, claimFailureMessage } from '../lib/claim'
import { forgetExchange, rememberExchange } from '../lib/lastExchange'
import { getMySubmission } from '../lib/mySubmission'
import { openLiveUpdates, type LiveConnection } from '../lib/liveUpdates'
import { useParticipantStore } from '../stores/participant'

const props = defineProps<{ exchangeCode: string }>()
const participant = useParticipantStore()

// Live updates (Server-Sent Events) tell this page the moment something changes. While that connection is
// up, polling is switched off; if it drops (or the browser can't do it), the page falls back to re-reading
// the pile every few seconds.
const REFRESH_MS = 5000
const RECONCILE_MS = 150

const state = ref<'loading' | 'ready' | 'notfound' | 'error'>('loading')
const exchange = ref<Exchange | null>(null)
const books = ref<Book[]>([])

// The party (a queue of turns), when one is running, and a clock that counts its turn down on this device.
const party = ref<PartyState | null>(null)
let partyReceivedAt = Date.now()
const clock = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | undefined
const partyNotice = ref('') // "your turn passed", "the party has ended", ...

let run = 0 // each load gets a number; only the newest load may update the page
let inFlight = false
let timer: ReturnType<typeof setInterval> | undefined
let live: LiveConnection | null = null
let liveUp = false // true while the live connection is open
let reconcileTimer: ReturnType<typeof setTimeout> | undefined

async function load(options: { force?: boolean } = {}) {
  if (inFlight && !options.force) return
  const mine = ++run
  inFlight = true
  try {
    const [ex, list] = await Promise.all([getExchange(props.exchangeCode), getBooks(props.exchangeCode)])
    if (mine !== run) return // the exchange or the viewer changed meanwhile: this answer is stale
    if (!Array.isArray(list)) throw new Error('Unexpected response') // never render a malformed pile
    // A party's queue is a nice-to-have: if it can't be read, the rest of the page still shows.
    const queue = ex.mode === 'PARTY' ? asParty(await getParty(props.exchangeCode).catch(() => null)) : null
    if (mine !== run) return
    exchange.value = ex
    books.value = list
    if (queue || ex.mode !== 'PARTY') applyParty(queue)
    state.value = 'ready'
    rememberExchange({ code: ex.code, name: ex.name }) // so the home page can offer "Continue to ..."

  } catch (e) {
    if (mine !== run) return
    if (e instanceof ApiError && e.status === 404) {
      state.value = 'notfound'
      forgetExchange(props.exchangeCode)
    } else if (state.value !== 'ready') {
      state.value = 'error' // a failed refresh keeps what is on screen
    }
  } finally {
    if (mine === run) inFlight = false
  }
}

function restart() {
  state.value = 'loading'
  exchange.value = null
  books.value = []
  party.value = null
  partyNotice.value = ""
  clearTimeout(reconcileTimer)
  connectLive() // a different exchange needs its own stream
  load({ force: true })
}

/**
 * After applying a live event in place (so it shows instantly), re-read the true state shortly afterwards.
 * This corrects anything the event didn't carry (a claimer's own book link), and several events in a row
 * cost one read.
 */
function reconcileSoon() {
  clearTimeout(reconcileTimer)
  reconcileTimer = setTimeout(() => load({ force: true }), RECONCILE_MS)
}

const sameName = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase()

/** Shows a new snapshot of the party, and tells this person what happened to their turn. */
function applyParty(next: PartyState | null) {
  const before = party.value
  const me = participant.name
  if (before?.active && me) {
    const wasMine = !!before.current && sameName(before.current, me)
    const nowMine = !!next?.current && sameName(next.current, me)
    const stillWaiting = next?.queue.some((e) => sameName(e.name, me) && e.status === 'WAITING')
    if (wasMine && !nowMine && stillWaiting) {
      partyNotice.value = "Your turn passed. You're now at the end of the queue."
    }
  }
  if (next && !next.active && next.endedReason) partyNotice.value = next.endedReason
  party.value = next && next.active ? next : null
  partyReceivedAt = Date.now()
  clock.value = partyReceivedAt
}

function connectLive() {
  live?.close()
  liveUp = false
  live = openLiveUpdates(props.exchangeCode, participant.name, {
    onConnected: () => (liveUp = true),
    onDisconnected: () => (liveUp = false),
    onReconnected: () => {
      liveUp = true
      load({ force: true }) // events sent while the connection was down were missed
    },
    onBookAdded(e) {
      if (books.value.some((b) => b.id === e.id)) return
      books.value = [
        ...books.value,
        { id: e.id, submitterName: e.submitterName, wrappingPaper: e.wrappingPaper, claimed: false, viewerIsClaimer: false },
      ]
      if (exchange.value) exchange.value = { ...exchange.value, bookCount: exchange.value.bookCount + 1 }
      reconcileSoon()
    },
    onBookClaimed(e) {
      const before = books.value.find((b) => b.id === e.id)
      if (before?.claimed && before.bookUrl) return // my own claim, already shown with its link
      books.value = books.value.map((b) =>
        b.id === e.id
          ? {
              ...b,
              claimed: true,
              claimedByName: e.claimedByName,
              claimedAt: e.claimedAt,
              bookTitle: e.bookTitle,
              coverImageUrl: e.coverImageUrl,
              viewerIsClaimer: sameName(e.claimedByName, participant.name),
            }
          : b,
      )
      if (exchange.value && before && !before.claimed) {
        exchange.value = { ...exchange.value, claimedCount: exchange.value.claimedCount + 1 }
      }
      reconcileSoon()
    },
    onBookShaped(e) {
      books.value = books.value.map((b) => (b.id === e.id ? { ...b, coverAspectRatio: e.coverAspectRatio } : b))
      // A read that started before the ratio was saved may still land and put the old shape back: read again.
      reconcileSoon()
    },
    onModeChanged(e) {
      if (exchange.value) exchange.value = { ...exchange.value, mode: e.mode }
      if (e.mode !== 'PARTY') party.value = null
      reconcileSoon()
    },
    onPartyChanged(state) {
      applyParty(state)
    },
  })
}

onMounted(() => {
  load({ force: true })
  connectLive()
  timer = setInterval(() => {
    if (!document.hidden && state.value === 'ready' && !liveUp) load()
  }, REFRESH_MS)
  clockTimer = setInterval(() => (clock.value = Date.now()), 1000)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  clearInterval(clockTimer)
  clearTimeout(reconcileTimer)
  live?.close()
  run++ // ignore anything still in flight
})

watch(() => props.exchangeCode, restart)
// The pile looks different to a different person (their own link), so reload when the name changes.
watch(
  () => participant.name,
  () => {
    connectLive() // the party counts people by name, so the live connection carries the new one
    load({ force: true })
  },
)

function onHostChange(updated: Exchange) {
  exchange.value = updated // show a change the host just made (name, mode, ...) straight away
  rememberExchange({ code: updated.code, name: updated.name })
}

const mySubmittedBook = computed(() => books.value.find((b) => sameName(b.submitterName, participant.name)))
/** What this browser remembers of the person's own submission (the server never sends it back). */
const mySubmission = computed(() =>
  mySubmittedBook.value ? getMySubmission(props.exchangeCode, participant.name) : undefined,
)
const myBook = computed(() => books.value.find((b) => b.viewerIsClaimer))
const exchangeOpen = computed(() => exchange.value?.status === 'OPEN')

const submitting = computed(() => exchangeOpen.value && exchange.value?.mode === 'SUBMIT')
/** One book per person (#29): once the current name has a book in the pile, there is nothing more to add. */
const canAddBook = computed(() => submitting.value && !mySubmittedBook.value)
const inParty = computed(() => exchange.value?.mode === 'PARTY')
const partyActive = computed(() => inParty.value && !!party.value?.active)

/** In a party only the person whose turn it is may claim; everyone else is told who they are waiting for. */
const myTurn = computed(() => !!party.value?.current && sameName(party.value.current, participant.name))
const secondsLeft = computed(() => {
  const left = party.value?.turnSecondsLeft
  return left == null ? null : Math.max(0, Math.ceil(left - (clock.value - partyReceivedAt) / 1000))
})
const clockText = computed(() => {
  const s = secondsLeft.value ?? 0
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
})

const claiming = computed(() => {
  if (!exchangeOpen.value) return false
  if (exchange.value?.mode === 'CLAIM') return true
  return inParty.value && myTurn.value
})
const waitingReason = computed(() =>
  inParty.value
    ? party.value?.current
      ? `Waiting for ${party.value.current}`
      : 'Waiting for the next turn'
    : undefined,
)

function claimabilityOf(book: Book) {
  return claimability(book, participant.name, !!myBook.value, exchangeOpen.value, claiming.value, waitingReason.value)
}

// --- claiming: ask first (it can't be undone), then claim, then show what was won ---
const pending = ref<Book | null>(null) // the present waiting for a "yes"
const claimInFlight = ref(false)
const revealed = ref<Book | null>(null) // what the claimer just won
const notice = ref('')

function askToClaim(book: Book) {
  notice.value = ''
  pending.value = book
}

async function confirmClaim() {
  const book = pending.value
  if (!book || claimInFlight.value) return
  claimInFlight.value = true
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
    claimInFlight.value = false
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
      <p class="mode-banner" :class="exchange.mode.toLowerCase()" data-test="mode-banner">
        <template v-if="!exchangeOpen">This exchange is closed.</template>
        <!-- The emoji are decoration: screen readers just hear the words. -->
        <template v-else-if="exchange.mode === 'SUBMIT'">
          <span aria-hidden="true">📚</span> Gathering books <span aria-hidden="true">📚</span>
        </template>
        <template v-else-if="exchange.mode === 'PARTY'">
          <span aria-hidden="true">🎉</span> Party time! <span aria-hidden="true">🎉</span>
        </template>
        <template v-else>
          <span aria-hidden="true">🎅</span> Exchange Time! <span aria-hidden="true">🎁</span>
        </template>
      </p>
      <p v-if="submitting" class="status card" data-test="my-status">
        <strong>{{ participant.name }}</strong
        >,
        <template v-if="mySubmittedBook"
          >you have submitted a book<template v-if="mySubmission">: <em>{{ mySubmission.bookTitle }}</em>
          <a
            :href="mySubmission.bookUrl"
            target="_blank"
            rel="noopener noreferrer"
            data-test="my-submitted-link"
            >View the book</a
          ></template>.
        </template>
        <template v-else>you have not submitted a book.</template>
      </p>
      <p v-else class="status card" data-test="my-status">
        <strong>{{ participant.name }}</strong
        >,
        <template v-if="myBook">
          you claimed <em>{{ myBook.bookTitle }}</em>
          <a
            v-if="myBook.bookUrl"
            :href="myBook.bookUrl"
            target="_blank"
            rel="noopener noreferrer"
            data-test="my-book-link"
            >{{ viewLinkLabel(myBook.bookUrl) }}</a
          >
        </template>
        <template v-else>you haven't claimed a book yet.</template>
      </p>
    </header>

    <div class="layout" :class="{ 'with-queue': partyActive }">
    <aside v-if="partyActive && party" class="side" aria-label="The party queue">
      <PartyQueue :party="party" :my-name="participant.name" :seconds-left="secondsLeft" />
    </aside>

    <div class="main">
    <p v-if="partyNotice" class="notice card" role="status" data-test="party-notice">
      {{ partyNotice }}
      <button type="button" class="secondary" data-test="party-notice-close" @click="partyNotice = ''">OK</button>
    </p>

    <p v-if="partyActive && myTurn" class="your-turn" :class="{ urgent: (secondsLeft ?? 99) <= 15 }" role="status" data-test="your-turn">
      <span aria-hidden="true">🎁</span> It's your turn! Pick a present.
      <strong data-test="your-turn-clock">{{ clockText }}</strong> left
    </p>

    <TreeIllustration />

    <p v-if="notice" class="notice card" role="alert" data-test="notice">
      {{ notice }}
      <button type="button" class="secondary" data-test="notice-close" @click="notice = ''">OK</button>
    </p>

    <p v-if="books.length === 0" class="empty card" data-test="empty">
      <template v-if="submitting">
        No presents yet — add one!
        <RouterLink v-if="canAddBook" class="button" :to="{ name: 'submit-book', params: { exchangeCode } }" data-test="add-book"
          >Add a book</RouterLink
        >
      </template>
      <template v-else>No presents were brought to this exchange.</template>
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
      <p v-if="canAddBook" class="add">
        <RouterLink :to="{ name: 'submit-book', params: { exchangeCode } }" data-test="add-book">Add a book</RouterLink>
      </p>
    </template>

    <!-- Only when the server confirms this browser holds the host token. -->
    <HostSection v-if="exchange.isHost" :exchange="exchange" :party="party" @saved="onHostChange" @refresh="load({ force: true })" />
    </div>
    </div>

    <ConfirmClaimDialog
      v-if="pending"
      :submitter-name="pending.submitterName"
      :busy="claimInFlight"
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
/* With a party running, the queue sits to the right of the pile; on a narrow screen it goes above it. */
.layout.with-queue {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 17rem;
  gap: 1.5rem;
  align-items: start;
}
.side {
  grid-column: 2;
  grid-row: 1;
  position: sticky;
  top: 1rem;
}
.main {
  grid-column: 1;
  grid-row: 1;
  min-width: 0;
}
@media (max-width: 52rem) {
  .layout.with-queue {
    grid-template-columns: minmax(0, 1fr);
  }
  .side,
  .main {
    grid-column: 1;
    grid-row: auto;
  }
  .side {
    position: static;
  }
}
.your-turn {
  text-align: center;
  font-weight: 700;
  font-size: 1.1rem;
  margin: 0.5rem 0;
  padding: 0.5rem 1rem;
  border-radius: var(--radius);
  background: #dff1e6;
  color: var(--color-green);
}
.your-turn.urgent {
  background: #f8e1e3;
  color: var(--color-red);
}
.description {
  margin: 0.25rem 0;
}
.counts {
  color: var(--color-muted);
  margin: 0.25rem 0 0.75rem;
}
.mode-banner {
  display: inline-block;
  margin: 0.25rem 0 0.75rem;
  padding: 0.25rem 0.9rem;
  border-radius: 999px;
  font-weight: 600;
  background: #e7e2d6;
  color: var(--color-muted);
}
.mode-banner.submit {
  background: #dff1e6;
  color: var(--color-green);
}
.mode-banner.party {
  background: #fff4d6;
  color: #8a5a00;
}
.mode-banner.claim {
  background: #f8e1e3;
  color: var(--color-red);
}
.status {
  width: fit-content;
  margin: 0 auto 1rem;
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
