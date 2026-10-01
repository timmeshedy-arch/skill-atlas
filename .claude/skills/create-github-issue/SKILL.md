---
name: create-github-issue
description: Create a GitHub issue (task) in the skill-atlas repository from a free-form description. Use when the user asks to create/open/file a task, issue, bug or feature request in GitHub for this project, e.g. "заведи таску", "создай issue", "/create-github-issue <описание>".
argument-hint: "<описание задачи>"
allowed-tools: Bash(gh repo view:*), Bash(gh issue list:*), Bash(gh label list:*), Bash(gh issue create:*), Read, Grep, Write
---

# Create GitHub issue

Заводит issue в GitHub-репозитории skill-atlas через `gh`. Аргумент — описание задачи
в свободной форме: `$ARGUMENTS`. Если аргумента нет, спроси, что нужно сделать.

## 1. Контекст

- Репозиторий: `gh repo view --json nameWithOwner -q .nameWithOwner` (ожидается
  `timmeshedy-arch/skill-atlas`). Если `gh` не залогинен — скажи пользователю выполнить
  `! gh auth login` и остановись.
- Прочитай нужные разделы `README.md` — это спецификация проекта. Задача должна ссылаться
  на раздел README, который она меняет (CLI, формат отчёта, Web UI, валидация, тесты…).
- Проверь дубли: `gh issue list --state all --search "<ключевые слова>" -L 10`. Если есть
  похожая открытая задача — покажи её и спроси, заводить ли новую.
- Метки: `gh label list`. Используй только существующие (`bug`, `enhancement`,
  `documentation`, …), новые не создавай.

## 2. Черновик

Заголовок — коротко, в повелительном наклонении, по-английски, в стиле коммитов проекта
(`Web UI: filter skills by name`, `Scan: detect similar skills`). Тело — по-русски, по шаблону
ниже, в том же формате, что и `scripts/tasks/*.md` и `.github/pull_request_template.md`.
Пустые разделы не оставляй: если про риски сказать нечего, убери раздел.

```markdown
## Задача

<что и зачем, с точки зрения пользователя тулы>

**Затрагивает:** CLI (`scan`) / web UI (`serve`) / формат отчёта (table, md, json) / CI
**Раздел README:** <ссылка на раздел или «новый раздел …»>

## Требования

- <конкретное, проверяемое поведение>
- <что явно НЕ входит в scope>

## Тесты

- <сценарии и корнер-кейсы для интеграционных тестов (StubGitHub) и/или Playwright>

## Риски

- <совместимость формата отчёта/JSON, GitHub rate limit, производительность, токен>

## Definition of Done

- [ ] README обновлён до кода
- [ ] Сначала падающий тест, потом код
- [ ] `./gradlew build` зелёный локально и на CI
```

Для бага вместо «Требований» — «Шаги воспроизведения», «Ожидается», «Фактически»
(с точной командой `skill-atlas scan …` и выводом).

Не додумывай требования, которых нет в описании и README: неясные места вынеси
в раздел «Открытые вопросы».

## 3. Подтверждение

Создание issue видно всем, поэтому перед созданием покажи пользователю заголовок,
метки и тело целиком и дождись явного «ок». Правки — вноси и показывай снова.

## 4. Создание

Тело запиши во временный файл (не передавай многострочный текст через `--body`, ломаются
кавычки и backticks):

```bash
gh issue create --title "<title>" --body-file /tmp/skill-atlas-issue.md --label enhancement
```

Добавь `--assignee @me`, только если пользователь попросил взять задачу на себя.

Верни ссылку на созданный issue и его номер.
