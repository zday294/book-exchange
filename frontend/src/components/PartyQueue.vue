<script setup lang="ts">
import { computed } from 'vue'
import type { PartyState } from '../api/party'

/**
 * The party queue, shown beside the pile: whose turn it is (with the countdown), where you are in line, and
 * everyone in order: people who had their turn, the current person, then those waiting.
 */
const props = defineProps<{
  party: PartyState
  myName: string
  /** Seconds left of the current turn, already counted down on this device. */
  secondsLeft: number | null
}>()

const same = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase()
const isMe = (name: string) => same(name, props.myName)

const iAmCurrent = computed(() => !!props.party.current && isMe(props.party.current))

const clock = computed(() => {
  if (props.secondsLeft == null) return ''
  const s = Math.max(0, props.secondsLeft)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
})
const urgent = computed(() => props.secondsLeft != null && props.secondsLeft <= 15)

function ordinal(n: number): string {
  const rest = n % 100
  if (rest >= 11 && rest <= 13) return `${n}th`
  return `${n}${({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[n % 10] ?? 'th'}`
}

/** Where this person is: "It's your turn", "You're next", "You're 3rd", "You've had your turn", ... */
const place = computed(() => {
  const me = props.party.queue.find((e) => isMe(e.name))
  if (!me) return "You're not in the queue yet. You'll join the end of the line."
  if (me.status === 'DONE') return "You've had your turn."
  if (me.status === 'CURRENT') return "It's your turn!"
  const ahead = props.party.queue.filter((e) => e.status === 'CURRENT').length +
    props.party.queue.slice(0, props.party.queue.indexOf(me)).filter((e) => e.status === 'WAITING').length
  const position = ahead + 1
  return position === 2 ? "You're next!" : `You're ${ordinal(position)}.`
})

// On a narrow screen the full list starts collapsed (the "now" line stays visible); on a wide one it is open.
const wide = typeof window.matchMedia === 'function' ? window.matchMedia('(min-width: 52rem)').matches : true
</script>

<template>
  <section class="queue card" aria-labelledby="queue-title" data-test="party-queue">
    <h2 id="queue-title">The queue</h2>

    <p v-if="party.current" class="now" :class="{ mine: iAmCurrent }" data-test="party-now">
      <span v-if="iAmCurrent">🎁 It's your turn!</span>
      <span v-else>▶ {{ party.current }}'s turn</span>
      <span class="clock" :class="{ urgent }" data-test="party-clock" :aria-label="`${clock} left`">{{ clock }}</span>
    </p>
    <p v-else class="now" data-test="party-idle">Waiting for the next person to arrive…</p>

    <p v-if="!iAmCurrent" class="place" data-test="party-place">{{ place }}</p>

    <details class="list" :open="wide">
      <summary>Everyone in line ({{ party.queue.length }})</summary>
      <ol data-test="party-list">
        <li
          v-for="entry in party.queue"
          :key="entry.name"
          :class="[entry.status.toLowerCase(), { me: isMe(entry.name), offline: !entry.online }]"
          :data-test="`entry-${entry.name}`"
        >
          <span class="mark" aria-hidden="true">{{ entry.status === 'DONE' ? '✓' : entry.status === 'CURRENT' ? '▶' : '•' }}</span>
          <span class="name">{{ entry.name }}<template v-if="isMe(entry.name)"> (you)</template></span>
          <span class="state" data-test="entry-state">
            <template v-if="entry.status === 'DONE'">had a turn</template>
            <template v-else-if="entry.status === 'CURRENT'">turn now</template>
          </span>
          <span v-if="!entry.online" class="away" data-test="entry-offline">offline</span>
        </li>
      </ol>
    </details>
  </section>
</template>

<style scoped>
.queue {
  border-color: var(--color-gold);
  border-width: 2px;
}
h2 {
  margin: 0 0 0.5rem;
  font-size: 1.1rem;
}
.now {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 0.5rem;
  margin: 0 0 0.25rem;
  font-weight: 700;
}
.now.mine {
  color: var(--color-green);
}
.clock {
  font-variant-numeric: tabular-nums;
  font-family: ui-monospace, monospace;
}
.clock.urgent {
  color: var(--color-red);
  animation: none;
}
.place {
  margin: 0 0 0.5rem;
  color: var(--color-muted);
}
summary {
  cursor: pointer;
  font-weight: 600;
  margin-bottom: 0.25rem;
}
ol {
  list-style: none;
  margin: 0;
  padding: 0;
}
li {
  display: grid;
  grid-template-columns: 1.25rem 1fr auto auto;
  gap: 0.25rem;
  align-items: baseline;
  padding: 0.2rem 0.25rem;
  border-radius: 6px;
}
li.current {
  background: #fff4d6;
  font-weight: 700;
}
li.done {
  color: var(--color-muted);
}
li.me .name {
  text-decoration: underline;
}
li.offline .name {
  opacity: 0.6;
}
.state,
.away {
  font-size: 0.8rem;
  color: var(--color-muted);
}
</style>
