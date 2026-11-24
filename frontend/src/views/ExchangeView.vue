<script setup lang="ts">
defineProps<{ exchangeCode: string }>()
</script>

<template>
  <h1>Exchange {{ exchangeCode }}</h1>
  <p>Coming soon: the tree and the pile of presents.</p>
  <RouterLink :to="{ name: 'submit-book', params: { exchangeCode } }">Add a book</RouterLink>
</template>
