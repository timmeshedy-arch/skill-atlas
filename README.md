# skill-atlas

CLI-тула для сканирования GitHub-репозиториев на предмет **Claude Code skills** —
структурированных артефактов (`SKILL.md`, `commands/*.md`), которые репозиторий
предоставляет для Claude Code / Claude агентов.

## Пример использования

```bash
skill-atlas scan https://github.com/JetBrains/kotlin
skill-atlas scan JetBrains/kotlin                       # короткая форма owner/repo
GITHUB_TOKEN=ghp_xxx skill-atlas scan JetBrains/youtrack  # приватный репозиторий
```

## Задача

Дать быстрый обзор того, какие Claude Code скилы и команды определены в произвольном
GitHub-репозитории — без необходимости клонировать его и лезть в файлы руками.

## Что ищем ("skill")

Артефакт считается **Claude Code skill**, если это один из:

| Тип | Паттерн пути | Обязательные признаки |
|-----|--------------|------------------------|
| Skill | `**/SKILL.md` | YAML frontmatter с `name`, `description` |
| Command (slash-команда) | `**/commands/*.md` | Markdown-файл, опционально frontmatter (`name`, `description`) |

Команда без frontmatter валидна: имя выводится из имени файла (`fmt.md` → `/fmt`).

## Источник данных

Доступ к репозиторию — **исключительно через GitHub REST API**, без `git clone`:

1. `GET /repos/{owner}/{repo}` — метаданные, default branch.
2. `GET /repos/{owner}/{repo}/git/trees/{ref}?recursive=1` — полное дерево файлов одним
   запросом (лимит GitHub — 100k записей / 7MB на ответ; см. "Известные ограничения").
3. Фильтрация дерева по паттернам путей (см. таблицу выше) — локально, без похода в API.
4. `GET raw.githubusercontent.com/{owner}/{repo}/{ref}/{path}` — только для файлов,
   прошедших фильтр. `raw` вместо Contents API: отдаёт файл как есть, без base64-обёртки
   и без лимита в 1MB на файл.
5. Парсинг YAML frontmatter каждого найденного файла.

Расход запросов на скан: 2 (метаданные + дерево) + по одному на каждый найденный артефакт.

## CLI-интерфейс

```
skill-atlas scan <repo> [--ref <branch|tag|sha>] [--token <token>]
                        [--format table|json|md] [-o <file>] [-v]
```

- `<repo>` — `https://github.com/owner/repo` или короткая форма `owner/repo`.
- `--ref` — по умолчанию default branch репозитория.
- `--token` — GitHub PAT; по умолчанию берётся из `GITHUB_TOKEN`. Без токена лимит
  60 запросов/час на IP; для приватных репозиториев нужен scope `repo` (не `public_repo`).
- Exit codes: `0` — успех, `1` — репо/ref не найден, `2` — rate limit или нет доступа,
  `3` — ошибка аргументов, `4` — сетевая ошибка.

## Формат отчёта (`table`, пример)

Для каждого артефакта выводятся имя, статус валидации, **ссылка на файл в репозитории**
(permalink по sha) и **описание** из frontmatter.

```
skill-atlas scan report — JetBrains/kotlin @ master (sha: 8f3a1c2)

SKILLS (2)
  ✔ release-checklist — valid
    https://github.com/JetBrains/kotlin/blob/8f3a1c2e.../.claude/skills/release-checklist/SKILL.md
    Checklist for cutting a release branch: version bump, changelog, tagging.
  ✖ — — invalid: missing 'description'
    https://github.com/JetBrains/kotlin/blob/8f3a1c2e.../.claude/skills/broken/SKILL.md

COMMANDS (1)
  ✔ /fmt — valid
    https://github.com/JetBrains/kotlin/blob/8f3a1c2e.../.claude/commands/fmt.md
    Run ktlint over the changed files.

Total: 3 artifacts found, 2 valid, 1 invalid
```

Если в репо есть похожие артефакты (см. «Похожие артефакты»), перед `Total:` печатается
секция `SIMILAR (N)`: для каждой группы — score и её участники (имя — путь). Без групп
секции нет.

```
SIMILAR (1)
  ≈ score 0.54
    code-review — .claude/skills/code-review/SKILL.md
    pr-review — .claude/skills/pr-review/SKILL.md
```

`--format md` — тот же отчёт Markdown-списком со ссылками, для вставки в PR/issue.
`--format json` — машиночитаемая структура с полными (необрезанными) описаниями.

В `md` похожие артефакты — секция `## SIMILAR (N)` перед `**Total:**`, по пункту на группу:

```
## SIMILAR (1)

- ≈ score 0.54
  - [**code-review**](<permalink>) — `.claude/skills/code-review/SKILL.md`
  - [**pr-review**](<permalink>) — `.claude/skills/pr-review/SKILL.md`
```

В `json` — массив `similar` (всегда присутствует, пустой, если групп нет):

```json
"similar": [
  { "score": 0.54, "paths": [".claude/skills/code-review/SKILL.md", ".claude/skills/pr-review/SKILL.md"] }
]
```

## Web UI (`serve`)

```bash
skill-atlas serve [--port 8080] [--token <token>]   # → http://127.0.0.1:8080
```

Минималистичный одностраничник (`src/main/resources/web/index.html`, без сборки и
внешних зависимостей): поле репозитория + опциональный ref, кнопка **Scan**, ниже —
список скилов и команд со статусом, описанием и permalink'ом на файл; под ними, если
`similar` непустой, — блок «Possibly similar» с группами похожих артефактов. Ссылка вида
`/?repo=owner/repo&ref=main` сразу запускает скан — её можно шарить.

Сервер — встроенный `com.sun.net.httpserver.HttpServer`, слушает только `127.0.0.1`;
токен (`--token` / `GITHUB_TOKEN`) остаётся на сервере и в браузер не уходит.

- `GET /` — UI.
- `GET /api/scan?repo=<repo>&ref=<ref>` — та же структура, что `--format json`.
  Ошибки — `{"error": "..."}`: `400` — не разобран `repo`, `404` — репо/ref не найден,
  `429` — rate limit / нет доступа, `502` — сетевая ошибка или 5xx от GitHub.

## Валидация артефакта

Каждый найденный файл парсится и помечается `valid`/`invalid`:

- отсутствует обязательное поле frontmatter (`name`, `description`) → `invalid`;
- YAML не парсится → `invalid: parse error`;
- дублирующееся `name` среди артефактов одного типа → `invalid: duplicate name`.

## Похожие артефакты (возможные дубли)

Помимо точных дублей имени скан ищет **почти-дубли**: артефакты с разными именами, но
по смыслу про одно и то же. Всё считается локально и детерминированно, без LLM и сети.

- Участвуют только **валидные** артефакты с непустым `name`; сравниваются все пары,
  включая пары скил ↔ команда. Точные дубли имени внутри типа уже `invalid` и поэтому
  не участвуют — повторно они не репортятся.
- **Нормализация** `name` и `description` в множество токенов: разбиение camelCase
  (`codeReview` → `code review`), lowercase, разбиение по не-буквенно-цифровым символам,
  выкидывание токенов короче 2 символов и стоп-слов (английские служебные слова вроде
  `the`, `for`, `with`, `use`, `when` и общие для домена `skill`, `command`, `claude`).
- **Score пары** = `0.6 · J(имена) + 0.4 · J(описания)`, где `J` — коэффициент Жаккара
  `|A ∩ B| / |A ∪ B|` (для двух пустых множеств — 0). Отсутствующее описание — пустое
  множество.
- Пара **похожа**, если score ≥ `SIMILARITY_THRESHOLD = 0.4`. Порог подобран так, что
  срабатывает на одинаковые по токенам имена (0.6 и выше, в том числе скил `deploy` и
  команда `/deploy`), на скопированное описание при любом имени (0.4 и выше) и на
  частично пересекающиеся имя и описание вместе. Одно общее слово в имени
  (`release-notes` и `release-checklist`: 0.6 · 1/3 = 0.2) до порога не дотягивает.
- Похожие пары объединяются в **группы транзитивно** (union-find): если A ~ B и B ~ C,
  то A, B и C — одна группа, даже если A и C между собой не похожи. Score группы —
  максимальный score пары внутри неё, округлённый до двух знаков.
- Порядок стабильный: участники группы отсортированы по пути, группы — по пути первого
  участника.
- Похожесть — **предупреждение**: статус `valid` не меняется, exit code тоже.

Невалидные артефакты не приводят к ошибке скана (exit code остаётся 0) — они просто
отражаются в отчёте, чтобы тулу можно было использовать и для линтинга собственного
репозитория.

## Технологический стек

- **Kotlin** + Gradle.
- CLI-парсинг: [Clikt](https://ajalt.github.io/clikt/).
- HTTP: встроенный `java.net.http.HttpClient`, без внешних HTTP-зависимостей.
- YAML frontmatter — `snakeyaml`, JSON-вывод — `kotlinx.serialization`.
- Тесты — JUnit 5 + `kotlin.test`.
- Сборка: `./gradlew shadowJar` → `build/libs/skill-atlas.jar`.

## Тесты

Только **интеграционные**: проверяется весь путь `аргументы CLI → HTTP → парсинг → отчёт`.
Юнит-тестов на приватные методы нет — они дублировали бы интеграционные и ломались бы
на каждом рефакторинге парсера.

**Как это работает.** Локальный стаб GitHub API на встроенном
`com.sun.net.httpserver.HttpServer` — без WireMock/MockWebServer, стек остаётся без
внешних HTTP-зависимостей. Тест поднимает сервер на случайном порту, отдаёт фикстуры
дерева и файлов, гоняет через него настоящий `GitHubClient` и сравнивает готовый отчёт
целиком (строковое сравнение — формат отчёта это контракт).

Фикстуры — `src/test/resources/fixtures/<case>/`: `tree.json` + содержимое файлов.

```bash
./gradlew test                 # стаб-тесты, без сети
./gradlew test -Dlive=true     # + smoke против реального GitHub (нужен GITHUB_TOKEN)
```

Live-tier помечен `@Tag("live")` и по умолчанию пропускается: он тратит rate limit и
зависит от содержимого чужих репозиториев. В нём два теста — публичный `anthropics/skills`
и приватный `JetBrains/youtrack` (скипается, если у токена нет доступа).

Под тестируемость понадобились две правки продакшн-кода:

- `GitHubClient` снова принимает базовые URL (`apiBase`, `rawBase`) — в MVP они были
  захардкожены константами, и стабу некуда подключиться.
- Ядро команды вынесено в `ScanApp`: он **возвращает** exit code и принимает потоки
  вывода параметрами. Раньше `ScanCommand.run()` звал `exitProcess` напрямую, и любой
  тест на exit-код убивал JVM вместе с тестовым раннером. `exitProcess` остался только
  в Clikt-обёртке, которая ничего, кроме разбора аргументов, не делает.

### Корнер-кейсы

⚠ — тест вскрывает дефект или неоднозначность текущего поведения, нужна правка кода
или явное решение.

**Обнаружение артефактов**

- `SKILL.md` в `.claude/skills/<name>/` → SKILL.
- `SKILL.md` в `.agents/skills/<name>/` → SKILL (кросс-вендорная конвенция, реальный кейс).
- `SKILL.md` на произвольной глубине вне известных корней → SKILL (матч по имени файла).
- `skill.md` / `Skill.md` → не матчится; ⚠ решить, нужен ли регистронезависимый матч.
- `commands/fmt.md` → COMMAND.
- `commands/sub/fmt.md` → не матчится (проверяется только прямой родитель).
- `commands/README.md` → сейчас COMMAND; ⚠ ложное срабатывание, нужен ли блок-лист.
- `src/components/commands/*.js` → не матчится (не `.md`); реальный кейс `JetBrains/youtrack`.
- запись дерева с `type: "tree"` и именем `SKILL.md` → игнорируется (только `blob`).
- пустое дерево → 0 артефактов, `Total: 0 artifacts found, 0 valid, 0 invalid`.
- `truncated: true` в ответе дерева → предупреждение в отчёте, скан не падает.

**Frontmatter скила**

- `name` + `description` → valid.
- frontmatter отсутствует → `invalid: missing frontmatter`.
- открывающий `---` без закрывающего → `invalid: missing frontmatter`.
- пустой frontmatter (`---\n---`) → `invalid: missing frontmatter`.
- frontmatter — YAML-список, а не мапа → `invalid: missing frontmatter`.
- битый YAML (`name: [unclosed`) → `invalid: parse error: ...`.
- BOM перед `---` → парсится штатно.
- CRLF-переводы строк → парсится, `\r` не утекает в значения.
- `name` без `description` → `invalid: missing 'description'`.
- `description` без `name` → `invalid: missing 'name'`.
- `description` из пробелов → `invalid: missing 'description'` (blank ≡ отсутствует).
- многострочный `description` (`>` и `|`) → схлопывается в одну строку.
- `description` длиннее 160 символов → в `table` обрезается с `…`, в `md`/`json` полный.
- эмодзи, кавычки, двоеточия в `description` → не ломают ни одну из трёх выдач.
- `name: 123` (не строка) → `"123"`, valid.
- лишние поля (`allowed-tools`, `model`) → игнорируются.
- `---` в теле документа после frontmatter → берётся первый закрывающий разделитель.

**Frontmatter команды**

- без frontmatter → valid, имя из файла: `fmt.md` → `/fmt`.
- `name: /custom` во frontmatter → берётся из frontmatter, не из имени файла.
- frontmatter есть, `name` нет → сейчас `invalid: missing 'name'`; ⚠ несогласованно с тем,
  что команда вообще без frontmatter валидна.
- `description` без `name` → описание показывается, статус invalid.
- `my.cmd.md` → `/my.cmd` (обрезается только последнее `.md`).

**Дубликаты**

- два скила с одинаковым `name` → оба `invalid: duplicate name '<name>'`.
- скил и команда с одинаковым `name` → оба valid (дубликаты считаются внутри типа).
- три и более дубликатов → все помечены invalid.
- дубликат имени у уже невалидного артефакта → исходная ошибка не перетирается.

**Похожие артефакты**

- два скила с разными именами и близкими описаниями (`code-review` / `pr-review`) →
  секция `SIMILAR (1)` со score, оба остаются valid.
- скил и команда с близким описанием → одна группа (сравнение между типами).
- артефакты с одним общим словом в имени и разными описаниями → секции `SIMILAR` нет.
- A ~ B, B ~ C, A ≁ C → одна группа из трёх, участники отсортированы по пути.
- невалидные артефакты (дубликат имени, нет `description`) в сравнении не участвуют,
  даже если по имени совпали бы с валидными.
- `json` без похожих → `"similar": []`; с похожими → `score` и `paths` группы.
- ⚠ стемминга нет: `review` и `reviewer` — разные токены.

**GitHub API и сеть**

- 404 на `/repos/{owner}/{repo}` → exit `1`.
- 403 при `x-ratelimit-remaining: 0` → exit `2`, сообщение про rate limit.
- 403 при `x-ratelimit-remaining > 0` → exit `2`, сообщение про доступ/авторизацию;
  ⚠ сейчас оба 403 дают одно слитное сообщение «rate limit exceeded or authentication required».
- 401 (битый токен) → ⚠ сейчас exit `4` как сетевая ошибка; нужен внятный auth-код и текст.
- 429 → exit `2`. 500 → exit `4`. Обрыв соединения и таймаут → exit `4`.
- `--ref` уходит в URL как есть, `default_branch` из `/repos` не запрашивается зря.
- несуществующий `--ref` → exit `1`.
- токен задан → заголовок `Authorization: Bearer` есть; не задан → заголовка нет.
- `--token` перебивает `GITHUB_TOKEN`.
- токен не попадает ни в `-v`-лог, ни в текст ошибок.
- путь с пробелом или юникодом → ⚠ сейчас `URI.create` падает необработанным
  `IllegalArgumentException`; путь нужно URL-энкодить.
- `raw` отдал 404 на файл, который есть в дереве → ⚠ сейчас валится весь скан с exit `1`
  и текстом про ненайденный репозиторий; должен быть `invalid` у одного артефакта.

**Разбор аргумента `<repo>`**

- `https://github.com/owner/repo`, `http://…`, `owner/repo`, `git@github.com:owner/repo.git`.
- trailing slash и суффикс `.git` отбрасываются.
- `https://github.com/owner/repo/tree/main/sub` → берутся `owner`/`repo`, хвост игнорируется.
- `foo` без слеша и пустая строка → exit `3`.

**Форматы отчёта**

- `table` печатает пустые группы (`COMMANDS (0)`), `md` — пропускает.
- `json` — валидный JSON, `null` в отсутствующих `name`/`description`/`error`.
- `-o <file>` → отчёт в файле, stdout пуст.
- неизвестный `--format` → exit `3`.

## Definition of Done

Изменение готово, когда выполнены **все** пункты. Порядок важен: спека → красный тест →
код → зелено локально → зелено на CI.

**Спека и код**

- [ ] Поведение соответствует README. Если поведение меняется осознанно — сначала правится
      README, потом код.
- [ ] `./gradlew shadowJar -q` собирается без ошибок и без warnings.
- [ ] Мёртвого кода нет: неиспользуемые поля, параметры конструктора и зависимости удалены.
- [ ] Секретов в репозитории нет. Токен — только через `--token`, `GITHUB_TOKEN` или CI-secret.

**Тесты**

- [ ] Новое поведение и каждый найденный баг сначала закрываются падающим тестом, потом код.
- [ ] Каждый корнер-кейс из раздела «Тесты» либо покрыт, либо явно помечен там как отложенный.
- [ ] ⚠-отметки снимаются только вместе с реальной правкой поведения, не «по факту описания».
- [ ] `./gradlew test` зелёный локально.
- [ ] Live-tier (`./gradlew test -Dlive=true`) прогнан локально хотя бы раз.

**Ручная проверка**

- [ ] `scan anthropics/skills` и `scan JetBrains/youtrack` отрабатывают во всех трёх
      форматах (`table`, `md`, `json`).
- [ ] `scan --help` не упоминает несуществующих опций и типов артефактов.

**CI**

- [ ] Пайплайн зелёный: тот же `./gradlew build`, что и локально.
- [ ] Тесты на CI гоняются на JDK 21 (toolchain компиляции проекта — 17).
- [ ] Fat-jar выкладывается артефактом сборки.

### CI (GitHub Actions)

`.github/workflows/ci.yml`: `checkout` → `setup-java` (temurin 17 + 21) →
`gradle/actions/setup-gradle` (кэш) → `./gradlew build -PtestJdk=21` → `upload-artifact` с fat-jar.

Две вещи, на которых наивный конфиг ломается:

- **Rate limit.** Раннеры Actions сидят на общих IP, и анонимные 60 запросов/час выжигаются
  чужими джобами. Токен обязателен даже для публичных репозиториев; встроенного
  `secrets.GITHUB_TOKEN` достаточно — 1000 запросов/час на репозиторий.
- **Приватный репозиторий.** Встроенный `GITHUB_TOKEN` видит только сам этот репозиторий,
  на `JetBrains/youtrack` он отдаст 404. Значит либо live-тест на приватный репо скипается
  на CI (по `env.CI`), либо в secrets кладётся отдельный PAT со scope `repo`.

Стаб-тесты сети не требуют и на CI идут без всяких токенов — на них и держится основное
покрытие; live-tier на CI опционален.

## Известные ограничения

- `git/trees?recursive=1` обрезает результат на очень больших монорепо (>100k файлов или
  >7MB ответа, `truncated: true` в ответе). Тула печатает предупреждение в отчёт;
  фолбэка на поштучный обход поддиректорий пока нет.
- Приватные репозитории требуют токен со scope `repo` (не `public_repo`).
- Сабмодули не разворачиваются — их содержимое не сканируется.

## Roadmap (после MVP)

- Автоподхват токена из `gh auth token`, когда ни `--token`, ни `GITHUB_TOKEN` не заданы —
  чтобы не приходилось каждый раз писать `GITHUB_TOKEN=$(gh auth token) skill-atlas …`.
- Batch-режим: список репозиториев из файла (`skill-atlas scan --from-file repos.txt`).
- Сравнение двух ревизий одного репо (`skill-atlas diff <repo> --from <ref1> --to <ref2>`).
- Кэширование дерева файлов по sha, чтобы повторные сканы не тратили rate limit.
