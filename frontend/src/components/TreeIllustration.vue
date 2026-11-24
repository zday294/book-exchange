<template>
  <svg viewBox="0 0 240 260" class="tree" role="img" aria-label="A decorated Christmas tree" xmlns="http://www.w3.org/2000/svg">
    <title>A decorated Christmas tree</title>
    <ellipse cx="120" cy="246" rx="100" ry="10" fill="#000" opacity=".1" />
    <rect x="106" y="206" width="28" height="36" rx="3" fill="#6b4226" />
    <polygon points="120,38 52,118 188,118" fill="#2a7d4f" />
    <polygon points="120,76 38,164 202,164" fill="#237043" />
    <polygon points="120,116 22,214 218,214" fill="#1d5f38" />
    <!-- garland -->
    <path d="M70 112Q96 126 120 112T172 112" fill="none" stroke="#f4c542" stroke-width="3" stroke-linecap="round" />
    <path d="M54 158Q86 176 120 158T186 158" fill="none" stroke="#f4c542" stroke-width="3" stroke-linecap="round" />
    <path d="M38 204Q80 226 120 204T202 204" fill="none" stroke="#f4c542" stroke-width="3" stroke-linecap="round" />
    <!-- ornaments -->
    <circle cx="96" cy="100" r="6" fill="#c8202f" />
    <circle cx="146" cy="92" r="5" fill="#f4c542" />
    <circle cx="78" cy="148" r="6" fill="#f4c542" />
    <circle cx="124" cy="140" r="6" fill="#c8202f" />
    <circle cx="166" cy="150" r="5.5" fill="#2b5f8e" />
    <circle cx="62" cy="196" r="6" fill="#c8202f" />
    <circle cx="106" cy="190" r="5.5" fill="#2b5f8e" />
    <circle cx="150" cy="196" r="6" fill="#f4c542" />
    <circle cx="188" cy="198" r="5" fill="#c8202f" />
    <!-- star -->
    <polygon points="120,10 125.9,26 143,26.6 129.5,37.2 134.3,53.6 120,44 105.7,53.6 110.5,37.2 97,26.6 114.1,26" fill="#f4c542" stroke="#c8962e" stroke-width="1.5" stroke-linejoin="round" />
  </svg>
</template>

<style scoped>
.tree {
  display: block;
  width: min(16rem, 70vw);
  height: auto;
  margin: 0 auto;
}
</style>
