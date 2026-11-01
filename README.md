# Модуль контроля качества данных по целям (ККД)

Сервис на Java проверяет цели сотрудников и ключевые результаты по правилам, хранит результаты в PostgreSQL и отдаёт их через REST API.

> **Статус: заготовка.** Сейчас здесь только проверка сборки, миграций и отката на временной таблице `probe`. Бизнес-логики пока нет, а метод `GET /api/runs` временно отдаёт строку из этой таблицы.

## Что нужно

- Docker с Compose v2.
- Доступ к Docker Hub и Maven Central при первой сборке.
- Свободные порты 8080 и 15432. Другие порты можно задать в `.env`, образец в `.env.example`.

JDK и Maven для запуска не нужны: сборка идёт внутри Docker.

Проверялось на Windows 11, Docker 29.2, Compose 5.0.

## Запуск

```bash
docker compose up --build
```

Поднимаются база, миграции и сервис. Файл `.env` не обязателен: без него работают те же значения по умолчанию.

```bash
curl http://localhost:8080/api/runs
```

В Windows PowerShell 5.1 вместо `curl` нужен `curl.exe`.

## Тесты

```bash
docker build --target test --progress=plain .
```

## Накат → откат → повторный накат

Миграции выполняет отдельный контейнер `migrate` с Liquibase. Сервис сам миграции не запускает, поэтому не мешает откату и не накатывает изменения обратно при перезапуске.

Команды для Git Bash и Linux, сервис при этом продолжает работать:

```bash
# 1. Накат выполняется при запуске проекта
docker compose up -d --build

# 2. Откат всего: схема и данные
docker compose run --rm migrate rollback --tag=v0-empty
docker compose exec -T db psql -U kkd -d kkd -c "\dt"
# остались только databasechangelog и databasechangeloglock

# 3. Повторный накат, перезапускать сервис не нужно
docker compose run --rm migrate update
docker compose exec -T db psql -U kkd -d kkd -c "\dt"
```

Оговорки:

- Повторный `rollback --tag=v0-empty` без наката завершится ошибкой `Could not find tag`. Это ожидаемо: откатывать уже нечего, а метка ушла вместе с остальным.
- После правки файлов в `db/changelog` нужна пересборка: `docker compose build migrate`.
- `docker compose down -v` — это сброс базы, а не откат.

## Как писалось решение

Решение пишется с ИИ-ассистентом (Claude Code). Спецификация появится в папке `specs/`.
