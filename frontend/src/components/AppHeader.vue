<script setup lang="ts">
import { useParticipantStore } from '../stores/participant'

const participant = useParticipantStore()
</script>

<template>
  <header class="app-header">
    <RouterLink to="/" class="brand">🎄 Christmas Book Exchange</RouterLink>
    <nav>
      <RouterLink to="/">Find an exchange</RouterLink>
      <RouterLink to="/exchanges/new">Create an exchange</RouterLink>
    </nav>
    <span v-if="participant.name" class="who" data-test="participant-name">{{ participant.name }}</span>
  </header>
</template>

<style scoped>
.app-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem 1.5rem;
  padding: 0.75rem 1rem;
  background: var(--color-red);
  color: #fff;
}
.app-header a {
  color: #fff;
  text-decoration: none;
}
.brand {
  font-family: var(--font-display);
  font-size: 1.25rem;
  font-weight: bold;
}
nav {
  display: flex;
  gap: 1rem;
  flex: 1;
}
nav a.router-link-exact-active {
  text-decoration: underline;
}
.who {
  font-size: 0.9rem;
  opacity: 0.9;
}
</style>
