import { defineConfig } from '@playwright/test';

// Not 8765/8080: a dev `serve` is usually running there.
const PORT = 18765;
const BASE_URL = `http://127.0.0.1:${PORT}`;

export default defineConfig({
  // Fonts differ per OS, so every platform keeps its own baselines.
  snapshotPathTemplate: '{testDir}/__screenshots__/{platform}/{arg}{ext}',
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  expect: {
    // Absolute budget: 1% of a full-page shot (~28k px) would let a changed word (~4k px) pass.
    toHaveScreenshot: { animations: 'disabled', caret: 'hide', maxDiffPixels: 100 },
  },
  use: {
    baseURL: BASE_URL,
    browserName: 'chromium',
    // Locally: installed Google Chrome. CI / Docker (CI set): Chromium bundled in the Playwright image.
    channel: process.env.PW_CHANNEL ?? (process.env.CI ? undefined : 'chrome'),
    headless: true,
    viewport: { width: 1280, height: 800 },
    deviceScaleFactor: 1,
    colorScheme: 'light',
    locale: 'en-US',
    timezoneId: 'UTC',
  },
  webServer: {
    command: `java -jar ../build/libs/skill-atlas.jar serve --port ${PORT}`,
    url: `${BASE_URL}/`,
    reuseExistingServer: false,
  },
});
