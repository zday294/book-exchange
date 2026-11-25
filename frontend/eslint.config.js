// Code style and likely-mistake checks for the Vue/TypeScript app and the Node server. Run: npm run lint
import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'

export default tseslint.config(
  { ignores: ['.vite/**', 'dist/**', 'test-results/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
  {
    files: ['src/**/*.{ts,vue}'],
    languageOptions: { globals: { ...globals.browser } },
  },
  {
    // The Node server and the config files.
    files: ['server/**/*.js', '*.config.{js,ts}'],
    languageOptions: { globals: { ...globals.node } },
  },
  {
    files: ['**/*.test.{ts,js}', 'src/__tests__/**', 'src/test-setup.ts'],
    languageOptions: { globals: { ...globals.vitest, ...globals.node } },
  },
)
