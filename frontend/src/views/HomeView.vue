<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/client'
import { getExchange, listExchanges, type ExchangeList } from '../api/exchanges'
import ExchangeCard from '../components/ExchangeCard.vue'
import { readLastExchange } from '../lib/lastExchange'

const router = useRouter()

// --- "Have a code?" ---
const code = ref('')
const codeError = ref('')
const checking = ref(false)

/** Codes are upper case; people type them any way, with stray spaces. */
const normalizeCode = (raw: string) => raw.replace(/\s+/g, '').toUpperCase()

async function goToCode() {
  codeError.value = ''
  const wanted = normalizeCode(code.value)
  if (!wanted) {
    codeError.value = 'Enter the code you were given.'
    return
  }
  checking.value = true
  try {
    const exchange = await getExchange(wanted)
    await router.push({ name: 'exchange', params: { exchangeCode: exchange.code } })
  } catch (e) {
    codeError.value =
      e instanceof ApiError && e.status === 404
        ? `We couldn't find an exchange with the code ${wanted}. Check it and try again.`
        : e instanceof ApiError
          ? e.message
          : 'Something went wrong. Please try again.'
  } finally {
    checking.value = false
  }
}

// --- the public list ---
const PAGE_SIZE = 12
const SEARCH_DELAY_MS = 300

const typed = ref('') // what is in the search box right now
const search = ref('') // what the list is actually filtered by (after a short pause in typing)
const hideClosed = ref(false)
const page = ref(0)

const state = ref<'loading' | 'ready' | 'error'>('loading')
const result = ref<ExchangeList | null>(null)
const refreshing = ref(false)

let run = 0 // only the newest request may update the page
let timer: ReturnType<typeof setTimeout> | undefined

async function load() {
  const mine = ++run
  refreshing.value = true
  try {
    const list = await listExchanges({
      query: search.value,
      status: hideClosed.value ? 'OPEN' : undefined,
      page: page.value,
      size: PAGE_SIZE,
    })
    if (mine !== run) return
    result.value = list
    state.value = 'ready'
  } catch {
    if (mine !== run) return
    state.value = 'error'
  } finally {
    if (mine === run) refreshing.value = false
  }
}

// Wait for a short pause in typing before searching, and start again from the first page.
watch(typed, (value) => {
  clearTimeout(timer)
  timer = setTimeout(() => {
    if (value.trim() === search.value.trim()) return
    search.value = value
    page.value = 0
    load()
  }, SEARCH_DELAY_MS)
})
watch(hideClosed, () => {
  page.value = 0
  load()
})

function goToPage(next: number) {
  page.value = next
  load()
}

function clearFilters() {
  clearTimeout(timer)
  typed.value = ''
  search.value = ''
  hideClosed.value = false
  page.value = 0
  load()
}

function retry() {
  state.value = 'loading'
  load()
}

const last = ref(readLastExchange())
onMounted(load)
onBeforeUnmount(() => {
  clearTimeout(timer)
  run++
})
</script>

<template>
  <h1>Find or create an exchange</h1>

  <section class="entry">
    <form class="card code" novalidate @submit.prevent="goToCode" data-test="code-form">
      <label for="code">Have a code?</label>
      <div class="row">
        <input
          id="code"
          v-model="code"
          autocomplete="off"
          autocapitalize="characters"
          spellcheck="false"
          placeholder="For example K7M2PXA"
          :aria-invalid="!!codeError"
          aria-describedby="code-error"
          data-test="code-input"
        />
        <button type="submit" :disabled="checking" data-test="code-go">{{ checking ? 'Looking…' : 'Go' }}</button>
      </div>
      <p id="code-error" class="error" role="alert" data-test="code-error">{{ codeError }}</p>
    </form>

    <div class="card create">
      <span>Starting your own?</span>
      <RouterLink class="button" :to="{ name: 'create-exchange' }" data-test="create-link">Create an exchange</RouterLink>
    </div>
  </section>

  <RouterLink v-if="last" class="card continue" :to="{ name: 'exchange', params: { exchangeCode: last.code } }" data-test="continue">
    Continue to <strong>{{ last.name }}</strong> →
  </RouterLink>

  <section aria-labelledby="public-title">
    <h2 id="public-title">Public exchanges</h2>

    <div class="filters">
      <label class="visually-hidden" for="search">Search exchanges by name</label>
      <input id="search" v-model="typed" type="search" placeholder="Search by name" autocomplete="off" data-test="search" />
      <label class="check">
        <input v-model="hideClosed" type="checkbox" data-test="hide-closed" />
        Hide closed exchanges
      </label>
    </div>

    <p v-if="state === 'loading'" role="status" data-test="loading">Loading exchanges…</p>

    <div v-else-if="state === 'error'" class="card" data-test="error">
      <p>We couldn't load the exchanges. Please try again.</p>
      <button type="button" data-test="retry" @click="retry">Try again</button>
    </div>

    <template v-else-if="result">
      <p v-if="result.items.length === 0 && !search.trim() && !hideClosed" class="empty card" data-test="empty">
        No public exchanges yet — create the first one!
      </p>
      <p v-else-if="result.items.length === 0" class="empty card" data-test="no-match">
        No exchanges match<template v-if="search.trim()"> “{{ search.trim() }}”</template>.
        <button type="button" class="secondary" data-test="clear" @click="clearFilters">Clear search</button>
      </p>

      <template v-else>
        <p class="visually-hidden" role="status" data-test="result-count">
          {{ result.totalItems }} {{ result.totalItems === 1 ? 'exchange' : 'exchanges' }} found
        </p>
        <ul class="list" :aria-busy="refreshing" data-test="list">
          <li v-for="exchange in result.items" :key="exchange.code">
            <ExchangeCard :exchange="exchange" />
          </li>
        </ul>

        <nav v-if="result.totalPages > 1" class="pager" aria-label="Pages of exchanges" data-test="pager">
          <button type="button" class="secondary" :disabled="page === 0" data-test="prev" @click="goToPage(page - 1)">
            Previous
          </button>
          <span data-test="page-label">Page {{ result.page + 1 }} of {{ result.totalPages }}</span>
          <button
            type="button"
            class="secondary"
            :disabled="page + 1 >= result.totalPages"
            data-test="next"
            @click="goToPage(page + 1)"
          >
            Next
          </button>
        </nav>
      </template>
    </template>
  </section>
</template>

<style scoped>
.entry {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 1rem;
  align-items: stretch;
}
@media (max-width: 40rem) {
  .entry {
    grid-template-columns: 1fr;
  }
}
.code label {
  font-weight: 600;
  display: block;
  margin-bottom: 0.35rem;
}
.row {
  display: flex;
  gap: 0.5rem;
}
.error {
  min-height: 1.25em;
  margin: 0.25rem 0 0;
}
.create {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  justify-content: center;
  align-items: flex-start;
}
.continue {
  display: block;
  margin: 1rem 0 0;
  text-decoration: none;
  color: inherit;
}
.continue:hover,
.continue:focus-visible {
  border-color: var(--color-gold);
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1.5rem;
  align-items: center;
  margin-bottom: 1rem;
}
.filters input[type='search'] {
  flex: 1 1 14rem;
  width: auto;
}
.check {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.check input {
  width: auto;
}
.list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr));
  gap: 0.75rem;
}
.list[aria-busy='true'] {
  opacity: 0.6;
}
.empty {
  text-align: center;
}
.pager {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 1rem;
  margin-top: 1rem;
}
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
</style>
