// Запускает сценарий демо в системном Chrome с записью видео; печатает путь к .webm.
// usage: node record.mjs <scenario.mjs> <url> <video-dir>
import { chromium } from "playwright";
import { pathToFileURL } from "node:url";

const [scenarioPath, url, videoDir] = process.argv.slice(2);
const scenario = (await import(pathToFileURL(scenarioPath).href)).default;

const browser = await chromium.launch({ channel: process.env.DEMO_BROWSER_CHANNEL || "chrome", headless: true });
const context = await browser.newContext({
  viewport: { width: 1280, height: 800 },
  recordVideo: { dir: videoDir, size: { width: 1280, height: 800 } },
  colorScheme: "light",
});
const page = await context.newPage();

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Плашка с подписью шага в правом нижнем углу; переживает только до навигации.
const caption = (text) => page.evaluate((t) => {
  let box = document.getElementById("demo-caption");
  if (!box) {
    box = document.createElement("div");
    box.id = "demo-caption";
    Object.assign(box.style, {
      position: "fixed", right: "24px", bottom: "24px", zIndex: 9999, maxWidth: "420px",
      padding: "10px 14px", borderRadius: "8px", background: "rgba(35, 35, 35, .9)", color: "#fff",
      font: "500 15px/1.4 -apple-system, sans-serif", boxShadow: "0 4px 16px rgba(0, 0, 0, .2)",
    });
    document.body.append(box);
  }
  box.textContent = t;
}, text);

const type = async (locator, text) => {
  await locator.click();
  await locator.pressSequentially(text, { delay: 55 });
  await pause(300);
};

let failed = null;
try {
  await page.goto(url);
  await scenario({ page, url, caption, pause, type });
} catch (e) {
  failed = e;
}
const video = page.video();
await context.close();
await browser.close();
if (failed) {
  console.error(`scenario failed: ${failed.message}`);
  process.exit(1);
}
console.log(await video.path());
