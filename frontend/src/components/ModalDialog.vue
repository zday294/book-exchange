<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * A modal dialog: dims the page, keeps keyboard focus inside, closes on Escape or a click on the
 * backdrop, and puts focus back where it was when it closes. Put the content in the slot.
 */
const props = withDefaults(
  defineProps<{
    /** id of the element that names the dialog (its heading) */
    labelledby: string
    /** Close on a click outside the dialog? */
    closeOnBackdrop?: boolean
  }>(),
  { closeOnBackdrop: true }, // (an absent Boolean prop would otherwise be false)
)
const emit = defineEmits<{ close: [] }>()

const root = ref<HTMLElement | null>(null)
let previouslyFocused: Element | null = null

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

function focusables(): HTMLElement[] {
  return root.value ? [...root.value.querySelectorAll<HTMLElement>(FOCUSABLE)] : []
}

onMounted(async () => {
  previouslyFocused = document.activeElement
  await nextTick()
  // Start on the element marked data-autofocus, else the first control, else the dialog itself.
  const target = root.value?.querySelector<HTMLElement>('[data-autofocus]') ?? focusables()[0] ?? root.value
  target?.focus()
})
onBeforeUnmount(() => {
  if (previouslyFocused instanceof HTMLElement && document.contains(previouslyFocused)) previouslyFocused.focus()
})

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }
  if (event.key !== 'Tab') return
  const items = focusables()
  if (items.length === 0) {
    event.preventDefault()
    return
  }
  const first = items[0]
  const last = items[items.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || active === root.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

function onBackdrop(event: MouseEvent) {
  if (props.closeOnBackdrop && event.target === event.currentTarget) emit('close')
}
</script>

<template>
  <div class="backdrop" data-test="backdrop" @click="onBackdrop" @keydown="onKeydown">
    <div ref="root" class="dialog card" role="dialog" aria-modal="true" :aria-labelledby="labelledby" tabindex="-1">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  background: rgb(0 0 0 / 0.5);
  display: grid;
  place-items: center;
  padding: 1rem;
  z-index: 20;
  overflow-y: auto;
}
.dialog {
  width: min(26rem, 100%);
  max-height: 100%;
  overflow-y: auto;
  outline: none;
}
</style>
