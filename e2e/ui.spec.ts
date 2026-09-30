import path from 'node:path';
import { expect, test, type Page } from '@playwright/test';

const FIXTURE = path.join(__dirname, 'fixtures', 'multi-scan.json');
const REPOS = ['anthropics/skills', 'anthropics/claude-code', 'nope/does-not-exist'];

/** Answers /api/multi-scan with the fixture; returns the `repo` params of every intercepted request. */
async function mockMultiScan(page: Page): Promise<string[][]> {
  const scans: string[][] = [];
  await page.route('**/api/multi-scan**', (route) => {
    scans.push(new URL(route.request().url()).searchParams.getAll('repo'));
    return route.fulfill({ path: FIXTURE });
  });
  return scans;
}

async function expectChips(page: Page, repos: string[]) {
  const chips = page.locator('#chips .chip');
  await expect(chips).toHaveCount(repos.length);
  await expect(chips).toContainText(repos);
}

async function expectScreenshot(page: Page, name: string) {
  // Park the mouse off the content so no :hover state ends up in the baseline.
  await page.mouse.move(0, 0);
  await expect(page).toHaveScreenshot(name, { fullPage: true });
}

test('add repos, scan, filter by name and browse similar groups', async ({ page }) => {
  const scans = await mockMultiScan(page);
  const repoInput = page.locator('#repo');
  const filter = page.locator('#filter');
  const hint = page.locator('#hint');
  const counts = page.locator('#result h2 .count');

  await test.step('01 empty page', async () => {
    await page.goto('/');
    await expect(repoInput).toBeFocused();
    await expectChips(page, []);
    await expect(page.locator('#scan')).toBeDisabled();
    await expectScreenshot(page, '01-empty.png');
  });

  await test.step('02 repos added via Enter and the Add button', async () => {
    await repoInput.fill('anthropics/skills');
    await repoInput.press('Enter');
    await repoInput.fill('https://github.com/anthropics/claude-code');
    await page.locator('#add').click();
    await repoInput.fill('nope/does-not-exist');
    await repoInput.press('Enter');
    await expectChips(page, REPOS);
    await expectScreenshot(page, '02-repos-added.png');
  });

  await test.step('03 duplicate rejected case-insensitively', async () => {
    await repoInput.fill('Anthropics/Skills');
    await repoInput.press('Enter');
    await expect(hint).toHaveText('Anthropics/Skills is already added.');
    await expectChips(page, REPOS);
    await expectScreenshot(page, '03-duplicate-rejected.png');
  });

  await test.step('04 unparsable input rejected', async () => {
    await repoInput.fill('foo');
    await repoInput.press('Enter');
    await expect(hint).toHaveText('Can\'t parse "foo" as owner/repo.');
    await expectChips(page, REPOS);
    await expectScreenshot(page, '04-unparsable-rejected.png');
  });

  await test.step('× on a chip removes the repo', async () => {
    await repoInput.fill('octocat/Hello-World');
    await repoInput.press('Enter');
    await expectChips(page, [...REPOS, 'octocat/Hello-World']);
    await page.getByTitle('Remove octocat/Hello-World').click();
    await expectChips(page, REPOS);
    await expect(hint).toBeEmpty();
  });

  await test.step('05 results: one scan request for the whole list', async () => {
    await page.locator('#scan').click();
    await expect(page.locator('#result')).toBeVisible();
    expect(scans).toEqual([REPOS]);
    await expectScreenshot(page, '05-results.png');
  });

  await test.step('06 filter by name, value synced to the URL', async () => {
    await filter.fill('frontend');
    await expect(page).toHaveURL(/[?&]q=frontend(&|$)/);
    await expect(counts).toHaveText(['2 / 8', '0 / 2', '2']);
    await expectScreenshot(page, '06-filter.png');
  });

  await test.step('07 filter without matches', async () => {
    await filter.fill('no-such-skill');
    await expect(page.locator('#result .empty')).toHaveText(['No matches.', 'No matches.']);
    await expectScreenshot(page, '07-filter-no-matches.png');
  });

  await test.step('08 possibly similar groups', async () => {
    await filter.fill('');
    await expect(counts).toHaveText(['8', '2', '2']);
    await expect(page).not.toHaveURL(/[?&]q=/);
    await expect(page.locator('ul.similar')).toHaveScreenshot('08-similar.png');
  });
});

test('09 shared link prefills the list and scans right away', async ({ page }) => {
  const scans = await mockMultiScan(page);
  await page.goto('/?' + REPOS.map((repo) => `repo=${repo}`).join('&'));
  await expect(page.locator('#result')).toBeVisible();
  await expectChips(page, REPOS);
  expect(scans).toEqual([REPOS]);
  await expectScreenshot(page, '09-shared-link.png');
});
