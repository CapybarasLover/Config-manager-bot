# Config Manager Bot

Telegram-бот для выдачи VLESS-конфигов на серверах с панелью [3x-ui](https://github.com/MHSanaei/3x-ui).
Пользователь выбирает сервер и тип подключения, администратор одобряет заявку,
бот присылает готовые ссылки и ссылку на подписку. Всё управление идёт через
inline-кнопки, без ручной работы в панели.

## Возможности

- **Выдача конфигов** — пользователь получает конфиг в пару нажатий: выбор страны
  (🇱🇻 Латвия / 🇩🇪 Германия), типа подключения (WS, xHTTP или оба) и имени.
- **Одобрение администратором** — каждая заявка приходит админам с кнопками
  «Одобрить» / «Отклонить»; пользователь видит статус по кнопке «Проверить статус».
- **Одна подписка на всё** — у пользователя один клиент в панели (один UUID и subId),
  привязанный к нескольким inbound'ам. Ссылка `/sub/{subId}` сама собирает WS, xHTTP
  и Reality.
- **Ссылки генерирует панель** — бот берёт готовые VLESS-ссылки из API 3x-ui,
  а не собирает их из параметров вручную.
- **Админ-панель в боте** — список пользователей, удаление конфига (из панели и из БД),
  синхронизация базы с панелью.
- **Несколько серверов** — обе панели обслуживает один и тот же код, они отличаются
  только конфигурацией.

## Стек

| | |
| --- | --- |
| Язык | Java 21 |
| Telegram | [telebof](https://github.com/natanimn/telebof) |
| БД | PostgreSQL 18, Hibernate 7, Liquibase |
| HTTP к панели | `java.net.http.HttpClient` (HTTP/1.1), Jackson |
| Сборка | Maven, fat-jar через maven-shade-plugin |
| Деплой | Docker Compose, GitHub Actions на self-hosted runner |

## Архитектура

```
Bot  (telebof: обработчики сообщений и callback-кнопок, фильтр isAdmin)
 └── ConfigManager
       ├── PanelService ── ApiRequests ──▶ 3x-ui API  (Латвия)
       │                                ──▶ 3x-ui API  (Германия)
       └── DbService ── UserDao / ConfigDao ──▶ PostgreSQL
```

- **`PanelConfig`** — настройки одной панели: адрес, API-токен, ID inbound'ов, база ссылки подписки.
- **`PanelServiceImpl`** — логика «создать или дозаполнить»: если клиента нет — создаёт его
  сразу в нужных inbound'ах, если есть — привязывает недостающие, сохраняя UUID и subId.
- **`ApiRequestsImpl`** — HTTP-клиент к панели с авторизацией `Authorization: Bearer <token>`
  и повтором запроса при ошибке.
- **`DatabaseMigration`** — накатывает миграции Liquibase при старте, до начала polling.

## Как это работает

**Пользователь**

1. `/start` → кнопка «Получить конфиг».
2. Выбор страны. Для Германии — согласие с условиями (запрет торрентов) и выбор типа:
   WS, xHTTP или оба. На латвийском сервере только WS + Reality, выбора типа нет.
3. Имя конфига берётся из @username; если его нет — бот просит ввести имя.
   К имени всегда добавляется суффикс `_config`.
4. Клиент создаётся в панели, заявка уходит администраторам.
5. После одобрения бот присылает ссылки: WS, xHTTP, Reality и подписку.

**Администратор** (`/admin`)

| Кнопка | Текстовая команда | Действие |
| --- | --- | --- |
| Синхронизировать с панелью | `/syncPanel` | Подтянуть клиентов из панели в БД и обновить ссылки |
| Список пользователей | `/users` | Все пользователи и их конфиги |
| Удалить конфиг | — | Удалить клиента из панели и записи из БД |
| Выйти из режима | `/exAdmin` | Выйти из админ-режима |

Администраторы задаются списком chat id в `ADMIN_CHATS`.

## Запуск

### Docker

```bash
cp .env.example .env    # заполнить значения
docker compose up --build -d
docker compose logs -f bot
```

Поднимаются два контейнера: `bot` и `db` (PostgreSQL, наружу на порт `5491`).
Внутри сети compose `DB_LINK_PROD` должен указывать на хост `db`, например
`jdbc:postgresql://db:5432/<DB_NAME>`.

### Локально

```bash
mvn clean package -DskipTests

# dev-профиль: своя БД и свои ID inbound'ов
java -Dapp.env=dev -jar target/Config_bot-2.0-SNAPSHOT.jar

# prod
java -Dapp.env=prod -jar target/Config_bot-2.0-SNAPSHOT.jar
```

Профиль `app.env` (`prod` | `dev`) определяет строку подключения к БД
и номера inbound'ов в латвийской панели. Переменные окружения нужно задать заранее.

## Переменные окружения

API-токен панели создаётся в 3x-ui: **Settings → Security / API**.
Базовые адреса панелей должны заканчиваться на `/`.

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `BOT_TOKEN` | — | Токен бота от @BotFather |
| `ADMIN_CHATS` | — | Chat id администраторов через запятую |
| `DB_LINK_PROD`, `DB_LINK_DEV` | — | JDBC-адреса БД для prod и dev |
| `DB_USER`, `DB_PASSWORD`, `DB_NAME` | — | Доступ к PostgreSQL |
| **Германия** | | |
| `GERMAN_PANEL_HOME_URL` | — | Адрес панели |
| `XUI_API_TOKEN_GERM` | — | API-токен панели |
| `GERM_SUB_BASE_URL` | — | База ссылки подписки, например `https://host:2096/sub/` |
| `GERM_WS_INBOUND` | `3` | ID WS inbound'а |
| `GERM_XHTTP_INBOUND` | `2` | ID xHTTP inbound'а |
| `GERM_REALITY_INBOUND` | `5` | ID Reality inbound'а (`0` — выключить) |
| **Латвия** | | |
| `LATV_PANEL_HOME_URL` | — | Адрес панели |
| `XUI_API_TOKEN_LATV` | — | API-токен панели |
| `LATV_SUB_BASE_URL` | см. `PanelConfig` | База ссылки подписки |
| `LATV_WS_INBOUND_PROD` / `_DEV` | `1` / `3` | ID WS inbound'а |
| `LATV_REALITY_INBOUND_PROD` | `8` | ID Reality inbound'а в prod (`0` — выключить) |
| `LATV_REALITY_INBOUND_DEV` | выключен | ID Reality inbound'а в dev |

## База данных

Миграции — SQL-changeset'ы Liquibase в `src/main/resources/db/changeset/`,
мастер-файл `db.changelog-master.yaml`.

| Таблица | Что хранит |
| --- | --- |
| `tg_user` | Пользователь Telegram: chat id, @username, есть ли конфиг, статус заявки (`w` — ждёт, `a` — одобрен) |
| `config` | Конфиг пользователя: имя (email в панели), ссылки WS / xHTTP / Reality / подписки, страна |

### Бэкапы

```bash
./scripts/backup-postgres.sh
```

Скрипт снимает `pg_dump` из контейнера `db` в `backups/pg_<дата>.sql.gz` и удаляет дампы
старше `RETENTION_DAYS` дней (по умолчанию 14). Пример строки для cron и команда
восстановления — в комментарии в начале скрипта.

## Утилиты

**`MergeInbounds`** — разовая миграция клиентов на модель «один клиент — несколько inbound'ов»:
привязывает xHTTP и Reality inbound'ы ко всем клиентам бота. Идемпотентна, ничего не удаляет.

```bash
java -Dapp.env=prod -cp target/Config_bot-2.0-SNAPSHOT.jar com.petr.tools.MergeInbounds [latv|germ|both]
```

**`PanelSmokeTest`** — смоук-тест против живой панели: создаёт временного тестового клиента
(в БД он не попадает), проверяет сценарий «сначала один тип, потом оба», наличие Reality
в подписке и раскладку ссылок, затем удаляет клиента. Реальных клиентов не трогает.

```bash
java -Dapp.env=prod -cp target/Config_bot-2.0-SNAPSHOT.jar com.petr.tools.PanelSmokeTest [latv|germ|both]
```

## CI/CD

GitHub Actions (`.github/workflows/push_workflow.yml`) при пуше в `master` на self-hosted runner:

1. собирает `.env` из секретов репозитория;
2. пересобирает и перезапускает контейнеры;
3. ждёт, пока контейнер бота станет `healthy`;
4. проверяет подключение к БД (`SELECT COUNT(*) FROM tg_user`);
5. сохраняет логи и отчёт о деплое как артефакты сборки на 30 дней.

## Совместимость с 3x-ui

Бот работает с клиент-центричным API 3x-ui (`/panel/api/clients/*`), появившимся в v3.2.5;
проверено на v3.8.5. Старые эндпоинты `inbounds/addClient` и `delClientByEmail`
в этих версиях удалены, поэтому более ранние версии панели не поддерживаются.
