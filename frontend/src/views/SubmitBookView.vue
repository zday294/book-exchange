<script setup lang="ts">
defineProps<{ exchangeCode: string }>()
</script>

<template>
  <h1>Add a book to {{ exchangeCode }}</h1>
  <p>Coming soon.</p>
  <RouterLink :to="{ name: 'exchange', params: { exchangeCode } }">Back to the exchange</RouterLink>
</template>
