// Демо фичи multi-repo (ветка multi-repo): список репо вместо ветки, общий скан, similar между репо.
// Страница уже открыта на url; сценарий только кликает и подписывает шаги.
export default async ({ page, caption, pause, type }) => {
  const repo = page.locator("#repo");
  const scanned = () => page.waitForSelector("#result:not([hidden])", { timeout: 120000 });

  await caption("Branch selection removed: repos are added to a list");
  await pause(1500);

  await type(repo, "anthropics/skills");
  await caption("Enter adds a repo…");
  await repo.press("Enter");
  await pause(1200);

  await type(repo, "https://github.com/anthropics/claude-code");
  await caption("…and so does the Add button (any <repo> format works)");
  await page.click("#add");
  await pause(1200);

  await type(repo, "Anthropics/Skills");
  await caption("Duplicates are rejected (case-insensitive)");
  await repo.press("Enter");
  await pause(1800);
  await repo.fill("");

  await type(repo, "foo");
  await caption("Unparsable input is rejected");
  await repo.press("Enter");
  await pause(1800);
  await repo.fill("");

  await type(repo, "nope/does-not-exist");
  await repo.press("Enter");
  await caption("A repo that doesn't exist, to show per-repo errors");
  await pause(1500);

  await caption("× removes a repo");
  await type(repo, "octocat/Hello-World");
  await repo.press("Enter");
  await pause(800);
  await page.click('button[title="Remove octocat/Hello-World"]');
  await pause(1200);

  await caption("Scan: one request for all repos");
  await page.click("#scan");
  await scanned();
  await caption("One line per repo: ref + sha, or that repo's error");
  await page.locator(".meta").scrollIntoViewIfNeeded();
  await pause(2800);

  await caption("Combined list, each item labelled with its repo");
  await page.mouse.wheel(0, 500);
  await pause(1800);
  await page.mouse.wheel(0, 900);
  await pause(1800);

  await caption("The name filter works across all repos");
  await page.locator("#filter").scrollIntoViewIfNeeded();
  await page.locator("#filter").pressSequentially("frontend", { delay: 80 });
  await pause(2800);
  await page.locator("#filter").fill("");
  await pause(500);

  await caption("Possibly similar across repos: same skill in skills and claude-code");
  await page.locator("ul.similar").scrollIntoViewIfNeeded();
  await page.mouse.wheel(0, 400);
  await pause(3500);

  await page.goto(page.url());
  await caption("Shareable link ?repo=…&repo=… prefills the list and scans automatically");
  await scanned();
  await pause(3000);
};
