import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Vitest does not expose `afterEach` as a global unless `test.globals` is set,
// so @testing-library/react's automatic cleanup-on-afterEach never registers.
// Register it explicitly so each test starts with a clean DOM.
afterEach(() => {
  cleanup()
})
