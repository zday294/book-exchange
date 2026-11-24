<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from './components/AppHeader.vue'
import NameDialog from './components/NameDialog.vue'
import { useParticipantStore } from './stores/participant'

const route = useRoute()
const participant = useParticipantStore()

// Pages that act as a participant (the exchange and submit pages) need a name first:
// until there is one, the page isn't shown at all, only the name prompt.
const needsName = computed(() => route.meta.requiresName === true && !participant.name)
const dialogVisible = computed(() => needsName.value || participant.dialogOpen)
</script>

<template>
  <AppHeader :inert="dialogVisible" />
  <main class="page" :inert="dialogVisible">
    <RouterView v-if="!needsName" />
  </main>
  <NameDialog v-if="dialogVisible" :required="needsName" />
</template>

<style>
.page {
  max-width: 60rem;
  margin: 0 auto;
  padding: 1.5rem 1rem 3rem;
}
</style>
