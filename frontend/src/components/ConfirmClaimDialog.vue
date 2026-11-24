<script setup lang="ts">
import ModalDialog from './ModalDialog.vue'

defineProps<{ submitterName: string; busy: boolean }>()
defineEmits<{ confirm: []; cancel: [] }>()
</script>

<template>
  <ModalDialog labelledby="claim-title" @close="!busy && $emit('cancel')">
    <h2 id="claim-title">Claim this present from {{ submitterName }}?</h2>
    <p>You can only claim one present, and you can't change your mind afterwards.</p>
    <div class="actions">
      <button type="button" class="secondary" data-autofocus :disabled="busy" data-test="claim-cancel" @click="$emit('cancel')">
        Not yet
      </button>
      <button type="button" :disabled="busy" data-test="claim-confirm" @click="$emit('confirm')">
        {{ busy ? 'Claiming…' : 'Claim it' }}
      </button>
    </div>
  </ModalDialog>
</template>

<style scoped>
h2 {
  margin-top: 0;
}
.actions {
  display: flex;
  gap: 0.5rem;
  justify-content: flex-end;
}
</style>
