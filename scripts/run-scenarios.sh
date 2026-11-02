#!/usr/bin/env bash
# Прогон всех сценариев бота на локальных фейках Telegram и панелей (см. ScenarioRunner).
# Настоящие панели, Telegram и .env не используются; БД — временный кластер Postgres.
#
# Нужны: JDK 21+ с javac (JAVA_BIN), bin-каталог PostgreSQL (PG_BIN) и уже собранный jar
# с зависимостями в target/ (Lombok для компиляции не нужен: классы с Lombok берутся из jar).
set -euo pipefail

# pwd -W: под Git Bash java.exe не понимает POSIX-пути внутри classpath
ROOT="$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))"
JAVA_BIN="${JAVA_BIN:-/c/Users/andre/AppData/Local/Programs/PyCharm/jbr/bin}"
PG_BIN="${PG_BIN:-/c/Program Files/PostgreSQL/18/bin}"
WORK="${SCENARIO_WORK_DIR:-$ROOT/target/scenarios}"
PG_PORT=55491
JAR="$ROOT/target/Config_bot-2.0-SNAPSHOT.jar"

SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

mkdir -p "$WORK"
OUT="$WORK/classes"
rm -rf "$OUT"
mkdir -p "$OUT"

cd "$ROOT"
# Исходники с Lombok не компилируем: их классы лежат в jar и в этой задаче не менялись
grep -rL 'lombok' src/main/java --include='*.java' > "$WORK/sources.txt"
find src/test/java -name '*.java' >> "$WORK/sources.txt"
"$JAVA_BIN/javac" --release 21 -proc:none -encoding UTF-8 -nowarn -sourcepath "" \
    -cp "$JAR" -d "$OUT" @"$WORK/sources.txt"

if ! "$PG_BIN/pg_isready" -h 127.0.0.1 -p "$PG_PORT" >/dev/null 2>&1; then
    PGDATA_DIR="$WORK/pg"
    rm -rf "$PGDATA_DIR"
    "$PG_BIN/initdb" -D "$PGDATA_DIR" -U botsa --auth=trust -E UTF8 --locale=C >/dev/null
    "$PG_BIN/pg_ctl" -D "$PGDATA_DIR" -l "$WORK/pg.log" -w \
        -o "-p $PG_PORT -c listen_addresses=127.0.0.1" start >/dev/null 2>&1 </dev/null
    trap '"$PG_BIN/pg_ctl" -D "$PGDATA_DIR" -m fast stop >/dev/null 2>&1 </dev/null || true' EXIT
fi
"$PG_BIN/psql" -h 127.0.0.1 -p "$PG_PORT" -U botsa -d postgres -qc "DROP DATABASE IF EXISTS bot_scen" -c "CREATE DATABASE bot_scen"

env -i PATH="$PATH" SYSTEMROOT="${SYSTEMROOT:-}" TEMP="${TEMP:-/tmp}" TMP="${TMP:-/tmp}" \
    BOT_TOKEN=000:test \
    LATV_PANEL_HOME_URL=http://127.0.0.1:18081/ XUI_API_TOKEN_LATV=latv-token LATV_SUB_BASE_URL=https://latv.test:2096/sub/ \
    GERMAN_PANEL_HOME_URL=http://127.0.0.1:18082/ XUI_API_TOKEN_GERM=germ-token GERM_SUB_BASE_URL=https://germ.test:2096/sub/ \
    DB_LINK_PROD="jdbc:postgresql://127.0.0.1:$PG_PORT/bot_scen" DB_LINK_DEV="jdbc:postgresql://127.0.0.1:$PG_PORT/bot_scen" \
    DB_USER=botsa DB_PASSWORD= DB_NAME=bot_scen ADMIN_CHATS=9001,9002 \
    "$JAVA_BIN/java" -Dapp.env=prod -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -Dliquibase.duplicateFileMode=WARN \
    -cp "$OUT${SEP}src/main/resources${SEP}$JAR" com.petr.bot.ScenarioRunner "$WORK/bot.log"
