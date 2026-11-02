#!/usr/bin/env bash
# Сквозная проверка: раздел 11.2 specs/design.md. Запуск: bash scripts/check-chain.sh
# Начинается со сброса базы проекта и при успехе заканчивается им же: docker compose down -v.
# Контрольных чисел здесь нет: скрипт читает их из таблиц README.
set -Eeuo pipefail

# Настройки окружения не должны менять папку, файл compose, вывод grep, сборку и разбор текста
unset CDPATH COMPOSE_FILE GREP_OPTIONS
export DOCKER_BUILDKIT=1 LC_ALL=C.UTF-8

cd "$(dirname "${BASH_SOURCE[0]}")/.."

export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' # Git Bash не переписывает аргументы как пути
export KKD_RUN_POLL_MS=2000                       # значение из .env могло бы сломать ожидания

LIMIT_BUILD=1500  # up --build с пустыми кэшами
LIMIT_COMPOSE=240 # down, up без сборки, run, restart: готовность базы занимает до 90 с
LIMIT_WAIT=180    # ожидание состояния сервиса или базы
LIMIT_SQL=60      # один вызов psql
LIMIT_CURL=15     # один запрос к сервису
READER_LIMIT=120  # читатель ждёт откат около минуты и завершается сам

SUMMARY=/api/kkd/summary
ISSUES=/api/kkd/issues
G="$ISSUES?run_id=RUN-002"
UNITS='HQ-CIB CIB-NORTH CIB-SOUTH'
READER_APP=kkd_check_reader
PSQL_CMD='exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -qAt -v ON_ERROR_STOP=1'
NL=$'\n'
CRLF2=$'\r\n\r\n'

SNAPSHOT_TOO_OLD='Снимка нет, а дата снимка раньше последнего завершённого прогона'

STEP='подготовка'
CHECKS=0
B=''
READER_PID=''
AWAITED_PID=''
REPORTED=''
PROJECT_TOUCHED=''
LAST_LOG=''
SQL_OUT=''
CAPTURED=''
FRAG=''
WAIT_FACT=''
HTTP_STATUS=''
HTTP_HEADERS=''
HTTP_BODY=''

T=$(mktemp -d "${TMPDIR:-/tmp}/kkd-check.XXXXXX") || {
  echo "Не удалось создать временную папку" >&2
  exit 2
}
T=$(cd "$T" && pwd) # TMPDIR может быть с обратными косыми чертами, а awk разбирает их как escape
NOISE="$T/commands.log"
: > "$NOISE"
NOISE_FROM=1 # хвост журнала при отказе берётся только за текущий шаг

# --- Отказ и уборка ---------------------------------------------------------

fail() { # что проверялось, ожидание, факт, необязательный фрагмент ответа
  trap - ERR
  REPORTED=1
  {
    printf '\nРАСХОЖДЕНИЕ. Шаг: %s\n' "$STEP"
    printf '  Что проверялось: %s\n' "$1"
    printf '  Ожидание: %s\n' "${2:0:1000}"
    printf '  Факт: %s\n' "${3:0:1000}"
    if [ "$#" -ge 4 ] && [ -n "$4" ]; then
      printf '  Фрагмент ответа: %s\n' "${4:0:1500}"
    fi
    if [ "$(tail -n +"$NOISE_FROM" "$NOISE" | wc -l)" -gt 0 ]; then
      printf '\nХвост журнала команд этого шага:\n'
      tail -n +"$NOISE_FROM" "$NOISE" | tail -n 30
    fi
    if [ -n "$PROJECT_TOUCHED" ]; then
      printf '\nСостояние: docker compose ps -a\nЖурналы: docker compose logs app, docker compose logs migrate\n'
      printf 'База оставлена для разбора. Сброс: docker compose down -v\n'
    fi
    printf 'Весь журнал и ответы: %s\n' "$T"
  } >&2
  exit 1
}

on_err() { # код, строка, команда
  # Ошибка в подоболочке доходит до основного процесса кодом возврата
  if [ "$BASH_SUBSHELL" -gt 0 ]; then
    exit "$1"
  fi
  fail "команда в строке $2 скрипта: $3" 'код 0' "код $1"
}

stop_pid() { # процесс и его потомки. В Git Bash сигнал не доходит до docker.exe и плагина docker-compose.exe
  local pid=$1
  if [ -r "/proc/$pid/winpid" ]; then
    taskkill /PID "$(cat "/proc/$pid/winpid")" /T /F > /dev/null 2>&1 || true
  fi
  kill "$pid" 2> /dev/null || true
}

on_exit() {
  local code=$?
  trap - EXIT ERR
  if [ -n "$READER_PID" ]; then
    stop_pid "$READER_PID"
  fi
  if [ -n "$AWAITED_PID" ]; then
    stop_pid "$AWAITED_PID"
  fi
  if [ "$code" = 0 ]; then
    rm -rf "${T:?}"
  elif [ -z "$REPORTED" ]; then
    printf '\nПрервано, код %s. Шаг: %s\nВременная папка оставлена: %s\n' "$code" "$STEP" "$T" >&2
  fi
  exit "$code"
}

trap 'on_err "$?" "$LINENO" "$BASH_COMMAND"' ERR
trap on_exit EXIT
trap 'exit 130' INT TERM

# --- Команды с пределом времени ---------------------------------------------

# Вывод таких команд идёт только в файлы: зависший процесс не держит конвейер и $(...)
await() { # pid, предел в секундах: код команды. По пределу процесс снимается, код 124
  local pid=$1 limit=$2 start=$SECONDS code=0
  AWAITED_PID=$pid
  while kill -0 "$pid" 2> /dev/null; do
    if [ $((SECONDS - start)) -ge "$limit" ]; then
      stop_pid "$pid"
      sleep 2
      kill -9 "$pid" 2> /dev/null || true
      wait "$pid" 2> /dev/null || true
      AWAITED_PID=''
      return 124
    fi
    sleep 0.1
  done
  wait "$pid" || code=$?
  AWAITED_PID=''
  return "$code"
}

capture() { # предел, команда: stdout без \r в CAPTURED, stderr в журнал команд
  local limit=$1 code=0
  shift
  "$@" < /dev/null > "$T/capture.out" 2>> "$NOISE" &
  await "$!" "$limit" || code=$?
  CAPTURED=$(tr -d '\r' < "$T/capture.out")
  return "$code"
}

dc() { # предел, аргументы docker compose: вывод в журнал команд и в LAST_LOG
  local limit=$1 code=0
  shift
  LAST_LOG="$T/last-command.log"
  docker compose "$@" < /dev/null > "$LAST_LOG" 2>&1 &
  await "$!" "$limit" || code=$?
  {
    printf '\n$ docker compose %s\n' "$*"
    tr -d '\r' < "$LAST_LOG"
    printf '(код %s)\n' "$code"
  } >> "$NOISE"
  return "$code"
}

must_dc() { # предел, аргументы docker compose: код 0 или отказ
  local limit=$1 code=0
  shift
  dc "$limit" "$@" || code=$?
  [ "$code" = 0 ] || fail "docker compose $*" "код 0 за $limit с" "код $code (124 значит предел времени)"
  ok "docker compose $*: код 0"
}

sql() { # запрос через stdin psql: результат в SQL_OUT, ошибка это отказ
  local code=0
  printf '%s\n' "$1" > "$T/sql.in"
  docker compose exec -T db sh -c "$PSQL_CMD" < "$T/sql.in" > "$T/sql.out" 2>> "$NOISE" &
  await "$!" "$LIMIT_SQL" || code=$?
  SQL_OUT=$(tr -d '\r' < "$T/sql.out")
  [ "$code" = 0 ] || fail "SQL: $1" "код 0 за $LIMIT_SQL с" "код $code (124 значит предел времени)" "$SQL_OUT"
}

api() { # путь, опции curl: заполняет HTTP_STATUS, HTTP_HEADERS, HTTP_BODY
  local path=$1 out code=0
  shift
  # -q первым: настройки ~/.curlrc и прокси не должны менять запрос к своему сервису
  out=$(curl -q --noproxy '*' -s -i --max-time "$LIMIT_CURL" -w '\n%{http_code}' "$@" "$B$path") || code=$?
  HTTP_STATUS=${out##*"$NL"}
  out=${out%"$NL"*}
  HTTP_HEADERS=${out%%"$CRLF2"*}
  HTTP_BODY=${out#*"$CRLF2"}
  # Обрыв после заголовков: статус 200, а тело неполное. Код curl виден и в сообщении об отказе
  [ "$code" = 0 ] || HTTP_STATUS="$HTTP_STATUS, код curl $code (7 нет соединения, 28 предел времени, 18 обрыв)"
}

get() { # путь, опции curl: статус 200 или отказ
  api "$@"
  [ "$HTTP_STATUS" = 200 ] || fail "GET $1" 'статус 200' "статус $HTTP_STATUS" "$HTTP_BODY"
}

wait_until() { # предел, что ждём, условие и его аргументы
  local limit=$1 what=$2 start=$SECONDS
  shift 2
  WAIT_FACT='условие ни разу не выполнилось'
  until "$@"; do
    if [ $((SECONDS - start)) -ge "$limit" ]; then
      fail "ожидание: $what" "дождаться за $limit с" "$WAIT_FACT"
    fi
    sleep 0.5
  done
  ok "дождались: $what"
}

# --- Проверки ---------------------------------------------------------------

ok() {
  CHECKS=$((CHECKS + 1))
  printf '  ок: %s\n' "$1"
}

eq() { # что проверялось, факт, ожидание
  [ "$2" = "$3" ] || fail "$1" "$3" "$2"
  ok "$1"
}

has() { # что проверялось, строка, текст
  case "$3" in
    *"$2"*) ok "$1" ;;
    *) fail "$1" "в ответе есть строка $2" 'строки нет' "$3" ;;
  esac
}

has_not() { # что проверялось, строка, текст: пустой ответ это отказ
  [ -n "$3" ] || fail "$1" "непустой ответ без строки $2" 'ответ пуст'
  case "$3" in
    *"$2"*) fail "$1" "в ответе нет строки $2" 'строка есть' "$3" ;;
    *) ok "$1" ;;
  esac
}

form() { # что проверялось, текст, части по порядку: начинается с первой, кончается последней
  local what=$1 text=$2 rest=$2 first=1 part=''
  shift 2
  for part in "$@"; do
    if [ "$first" = 1 ]; then
      case "$rest" in
        "$part"*) ;;
        *) fail "$what" "начало ответа $part" 'начало другое' "$text" ;;
      esac
      first=0
    else
      case "$rest" in
        *"$part"*) ;;
        *) fail "$what" "дальше по порядку идёт $part" 'этого нет' "$rest" ;;
      esac
    fi
    rest=${rest#*"$part"}
  done
  [ -z "$rest" ] || fail "$what" "ответ кончается на $part" 'после этого есть ещё текст' "$rest"
  ok "$what"
}

matches() { # регулярное выражение, текст: все совпадения по строкам
  printf '%s' "$2" | grep -oE "$1" || true
}

lines() { # текст: число строк
  if [ -z "$1" ]; then
    echo 0
  else
    printf '%s\n' "$1" | awk 'END { print NR }'
  fi
}

ids_of() { # тело ответа: значения id по строкам
  matches '"id":"[^"]*"' "$1" | sed -E 's/"id":"([^"]*)"/\1/'
}

frag() { # текст, начало: в FRAG участок после начала до первой закрывающей скобки
  case "$1" in
    *"$2"*)
      FRAG=${1#*"$2"}
      FRAG=${FRAG%%\}*}
      ;;
    *) FRAG='' ;;
  esac
}

no_match() { # что проверялось, аргументы grep: поиск ничего не находит
  local what=$1 code=0
  shift
  grep "$@" > "$T/grep.out" 2>&1 || code=$?
  case "$code" in
    1) ok "$what: ничего не найдено" ;;
    0) fail "$what" 'ничего не найдено' 'есть совпадения' "$(cat "$T/grep.out")" ;;
    *) fail "$what" 'grep с кодом 1' "grep с кодом $code" "$(cat "$T/grep.out")" ;;
  esac
}

has_match() { # что проверялось, аргументы grep: поиск что-то находит
  local what=$1 code=0
  shift
  grep "$@" > "$T/grep.out" 2>&1 || code=$?
  [ "$code" = 0 ] || fail "$what" 'строка найдена' "grep с кодом $code"
  ok "$what"
}

expect_error() { # статус, код ошибки, путь, опции curl: тело в едином формате
  local want=$1 code=$2 path=$3 what re='^\{"error":\{"code":"'"$2"'","message":"[^"]+"\}\}$'
  shift 3
  what="$want $code: ${*:+$* }$path"
  api "$path" "$@"
  [ "$HTTP_STATUS" = "$want" ] || fail "$what" "статус $want" "статус $HTTP_STATUS" "$HTTP_BODY"
  [[ $HTTP_BODY =~ $re ]] || fail "$what" '{"error":{"code":"'"$code"'","message":"..."}} без других полей' 'другое тело' "$HTTP_BODY"
  case "$HTTP_BODY" in
    *Exception* | *java.* | *org.springframework* | *ru.kkd*)
      fail "$what" 'сообщение без стека и имён классов' 'есть имя класса' "$HTTP_BODY"
      ;;
  esac
  ok "$what"
}

expect_ok() { # путь, опции curl: статус 200
  local path=$1
  shift
  get "$path" "$@"
  ok "200: ${*:+$* }$path"
}

refresh_base() { # адрес сервиса берётся у compose: порт мог быть задан в .env
  local port=''
  capture 30 docker compose port app 8080 || fail 'docker compose port app 8080' 'код 0' 'команда не удалась: сервис не поднялся?'
  port=${CAPTURED//"$NL"/}
  port=${port##*:}
  case "$port" in
    '' | *[!0-9]*) fail 'docker compose port app 8080' 'порт сервиса' "вывод: $CAPTURED" ;;
  esac
  B="http://127.0.0.1:$port"
}

runs_are() { # статус, прогоны: условие для wait_until, упавший прогон это сразу отказ
  local want=$1 run=''
  shift
  api /api/runs
  WAIT_FACT="$B: статус HTTP $HTTP_STATUS: ${HTTP_BODY:0:300}"
  [ "$HTTP_STATUS" = 200 ] || return 1
  for run in "$@"; do
    case "$HTTP_BODY" in
      *'"id":"'"$run"'","status":"'"$want"'"'*) ;;
      *'"id":"'"$run"'","status":"failed"'*) fail "прогон $run" "статус $want" 'статус failed' "$HTTP_BODY" ;;
      *)
        frag "$HTTP_BODY" '"id":"'"$run"'"'
        WAIT_FACT="$B: статус HTTP $HTTP_STATUS, у $run: ${FRAG:-строки нет}. Начало ответа: ${HTTP_BODY:0:200}"
        return 1
        ;;
    esac
  done
}

service_answers() { # условие для wait_until
  api /api/runs
  WAIT_FACT="$B: статус HTTP $HTTP_STATUS"
  [ "$HTTP_STATUS" = 200 ]
}

wait_seed_runs() {
  refresh_base
  wait_until "$LIMIT_WAIT" 'RUN-001 и RUN-002 completed' runs_are completed RUN-001 RUN-002
}

# --- README и карта целей ---------------------------------------------------

readme_bad() { # что не так
  fail 'формат README' 'таблицы контрольных чисел в прежнем виде' "формат README изменился: $1"
}

# Строки таблицы README, ячейки через |. Минус U+2212 становится -, плюс в начале значения убирается
readme_table() { # начало строки заголовка, число колонок, файл результата
  local code=0
  LC_ALL=C awk -v head="$1" -v cols="$2" '
    function cell(s) {
      gsub(/`/, "", s)
      sub(/^[ \t\r]+/, "", s)
      sub(/[ \t\r]+$/, "", s)
      gsub(/−/, "-", s)
      sub(/^\+/, "", s)
      return s
    }
    state == 0 && index($0, head) == 1 {
      state = 1
      if (split($0, c, "|") != cols + 2) { print "в заголовке другое число колонок: " $0; bad = 1 }
      next
    }
    state == 1 {
      state = 2
      if ($0 !~ /^\|[-: |]+\|[ \r]*$/) { print "нет разделителя под заголовком: " $0; bad = 1 }
      next
    }
    state == 2 && /^\|/ {
      if (split($0, c, "|") != cols + 2) { print "другое число колонок: " $0; bad = 1; next }
      row = ""
      for (i = 2; i <= cols + 1; i++) {
        v = cell(c[i])
        if (v == "") { print "пустая ячейка: " $0; bad = 1 }
        row = row (i > 2 ? "|" : "") v
      }
      print row > out
      rows++
      next
    }
    state == 2 { state = 3 }
    END {
      if (state == 0) { print "нет таблицы с заголовком " head; bad = 1 }
      else if (rows == 0) { print "в таблице нет строк: " head; bad = 1 }
      exit bad ? 1 : 0
    }
  ' out="$3" README.md > "$T/awk.out" 2>&1 || code=$?
  [ "$code" = 0 ] || readme_bad "$(cat "$T/awk.out")"
}

t1_value() { # ветка, прогон, колонка: значение из таблицы показателей, у покрытия после знака равенства
  local value
  value=$(LC_ALL=C awk -F'|' -v u="$1" -v r="$2" -v c="$3" '$1 == u && $2 == r { print $c }' "$T/t1")
  printf '%s' "${value##*= }"
}

# Какие проблемы должны быть у прогона по карте целей раздела 3.2 design.md: K, W, C, S
map_ids() { # номер колонки таблицы
  LC_ALL=C awk -F'|' -v col="$1" '
    index($0, "| Цель | Подразделение, уровень |") == 1 { on = 1; getline; next }
    on && !/^\|/ { on = 0 }
    on {
      goal = $2
      gsub(/[ `*]/, "", goal)
      num = goal
      sub(/^G-/, "", num)
      n = split($col, letters, ",")
      for (i = 1; i <= n; i++) {
        l = letters[i]
        gsub(/[ *]/, "", l)
        if (l == "") continue
        else if (l == "K") print "GOAL_NO_KR:" goal
        else if (l == "W") print "KR_WEIGHTS_SUM:" goal
        else if (l == "C") print "CASCADE_NO_PARENT:" goal
        else if (l == "S") print "KR_STALE_PROGRESS:KR-" num "-1"
        else { print "неизвестная метка " l > "/dev/stderr"; exit 1 }
      }
    }
  ' specs/design.md | LC_ALL=C sort
}

# Раздел 3.4: КР на дату прогона в том же виде, что запрос к key_result_snapshot
spec_krs() { # прогон, колонка веса
  LC_ALL=C awk -F'|' -v run="$1" -v w="$2" '
    function t(s) { sub(/^ +/, "", s); sub(/ +$/, "", s); return s }
    index($0, "| `id` | `title` | `measure_unit` |") == 1 { on = 1; getline; next }
    on && !/^\|/ { on = 0 }
    on && t($w) != "нет строки" { print run "|" t($2) "|" t($3) "|" t($4) "|" t($5) "|" t($6) "|" t($w) "|" t($9) }
  ' specs/design.md
}

# Разделы 3.2 и 3.3: цели на дату прогона в том же виде, что запрос к goal_snapshot
spec_goals() { # прогон
  LC_ALL=C awk -F'|' -v run="$1" '
    function t(s) { gsub(/`/, "", s); sub(/^ +/, "", s); sub(/ +$/, "", s); return s }
    index($0, "| Цель | Подразделение, уровень |") == 1 { on = 32; getline; next }
    index($0, "| Цель | `title` | `owner_name` |") == 1 { on = 33; getline; next }
    on && !/^\|/ { on = 0 }
    on == 32 {
      g = t($2)
      split(t($3), place, ", ")
      unit[g] = place[1]
      level[g] = place[2]
      parent[g] = t($4)
      if (run == "RUN-002" && match($6, /родитель G-[0-9]+/)) { parent[g] = substr($6, RSTART + RLENGTH - 4, 4) }
    }
    on == 33 {
      g = t($2)
      status = t($6)
      if (status ~ /появляется/ && run == "RUN-001") { next }
      final = "active"
      if (status ~ /^closed/ || (run == "RUN-002" && status ~ /closed$/)) { final = "closed" }
      print run "|" g "|" t($3) "|" unit[g] "|" level[g] "|" t($4) "|" parent[g] "|" final "|" t($5)
    }
  ' specs/design.md
}

unit_name() {
  case $1 in
    HQ-CIB) printf 'Департамент корпоративного бизнеса' ;;
    CIB-NORTH) printf 'Управление корпоративного бизнеса Север' ;;
    CIB-SOUTH) printf 'Управление корпоративного бизнеса Юг' ;;
  esac
}

summary_form() { # что проверялось, подразделение, имя, прогон, дата, compared_to, тело
  form "$1" "$7" \
    '{"run":{"id":"'"$4"'","snapshot_date":"'"$5"'"},"compared_to":'"$6"',"unit":{"id":"'"$2"'","name":"'"$3"'"},"metrics":[' \
    '{"key":"clean_goals_share","title":"Доля корректных целей","unit":"%","value":' \
    ',"higher_is_better":true},{"key":"issues_total","title":"Найдено проблем","unit":"шт.","value":' \
    ',"higher_is_better":false},{"key":"cascade_coverage","title":"Покрытие каскадом","unit":"%","value":' \
    ',"higher_is_better":true}],"issues_by_severity":[{"code":"critical","current":' \
    '},{"code":"major","current":' \
    '},{"code":"minor","current":' \
    '}],"delta":{"new":' \
    ',"persisting":' \
    ',"resolved":' \
    '}}'
}

check_metric_delta() { # ветка, ключ, колонка таблицы показателей, ячейка дельты, higher_is_better, тело сводки
  local delta=${4%% *} trend=${4##* } re='^-?[0-9]+(\.[0-9]+)? (better|worse|same)$'
  [[ $4 =~ $re ]] || readme_bad "ячейка дельты $4"
  frag "$6" '"key":"'"$2"'"'
  has "README: $1 $2 RUN-002: $4" \
    '"value":'"$(t1_value "$1" RUN-002 "$3")"',"previous":'"$(t1_value "$1" RUN-001 "$3")"',"delta":'"$delta"',"trend":"'"$trend"'","higher_is_better":'"$5" \
    "$FRAG"
}

branch_cte() { # подразделение: его ветка для SQL
  printf "WITH RECURSIVE branch AS (SELECT id FROM unit WHERE id = '%s' UNION SELECT u.id FROM unit u JOIN branch b ON u.parent_id = b.id)" "$1"
}

# Важность и тип берутся из справочника, цель, её название и подразделение из снимка прогона, details из kkd_issue.
# Так видна и ошибка API, и неверная копия в строке kkd_issue
issue_rows_ok() { # прогон, подразделение или пусто, тело limit=200: все поля и порядок раздела 8.4
  local run=$1 unit=$2 body=$3 with='' where="i.run_id = '$1'" items
  if [ -n "$unit" ]; then
    with=$(branch_cte "$unit")
    where="$where AND g.unit_id IN (SELECT id FROM branch)"
  fi
  sql "$with SELECT string_agg(format('{\"id\":%s,\"rule_code\":%s,\"severity\":%s,\"object_type\":%s,\"object_id\":%s,\"goal_id\":%s,\"goal_title\":%s,\"unit_id\":%s,\"details\":%s}',
         to_json(i.rule_code || ':' || i.object_id), to_json(i.rule_code), to_json(r.severity), to_json(r.object_type), to_json(i.object_id),
         to_json(g.goal_id), to_json(g.title), to_json(g.unit_id), to_json(i.details)), E'\n'
         ORDER BY array_position(ARRAY['critical', 'major', 'minor'], r.severity), i.rule_code, i.object_id)
       FROM kkd_issue i JOIN kkd_rule r ON r.code = i.rule_code
       LEFT JOIN key_result_snapshot k ON k.run_id = i.run_id AND k.kr_id = i.object_id
       JOIN goal_snapshot g ON g.run_id = i.run_id AND g.goal_id = COALESCE(k.goal_id, i.object_id)
       WHERE $where"
  form "раздел 8.4: $run ${unit:-без фильтра} форма ответа, total по числу строк" "$body" \
    '{"run_id":"'"$run"'","items":[' \
    '],"total":'"$(lines "$SQL_OUT")"',"limit":200,"offset":0}'
  items=${body#*'"items":['}
  items=${items%'],"total":'*}
  if [ "$items" = "${SQL_OUT//"$NL"/,}" ]; then
    ok "раздел 8.4: $run ${unit:-без фильтра} все поля и порядок строк"
  else
    matches '\{"id":[^}]*\}' "$body" > "$T/rows.got"
    printf '%s\n' "$SQL_OUT" > "$T/rows.want"
    fail "раздел 8.4: $run ${unit:-без фильтра} строки" 'как в снимке, справочнике и kkd_issue' 'другие' "$(diff "$T/rows.want" "$T/rows.got" || true)"
  fi
}

save_one() { # файл, путь: ответ 200 сохраняется как есть
  get "$2"
  printf '%s' "$HTTP_BODY" > "$1"
}

save_responses() { # папка: runs.json отдельно, прошлые ответы в past
  local dir=$1 run='' unit='' q=''
  mkdir -p "$dir/past"
  save_one "$dir/runs.json" /api/runs
  for run in RUN-001 RUN-002; do
    for unit in $UNITS; do
      save_one "$dir/past/summary-$run-$unit.json" "$SUMMARY?run_id=$run&unit_id=$unit"
    done
    save_one "$dir/past/issues-$run.json" "$ISSUES?run_id=$run"
    for q in limit=200 unit_id=CIB-NORTH unit_id=CIB-SOUTH severity=critical severity=major severity=minor \
      rule_code=GOAL_NO_KR rule_code=KR_WEIGHTS_SUM rule_code=CASCADE_NO_PARENT rule_code=KR_STALE_PROGRESS \
      rule_code=GOAL_NO_OWNER 'limit=5&offset=5' 'limit=5&offset=1000'; do
      save_one "$dir/past/issues-$run-${q//[=&]/-}.json" "$ISSUES?run_id=$run&$q"
    done
  done
}

same_responses() { # что проверялось, папка, папка
  local code=0
  diff -r "$2" "$3" > "$T/diff.out" 2>&1 || code=$?
  [ "$code" = 0 ] || fail "$1" 'diff пуст' "различия, код diff $code" "$(cat "$T/diff.out")"
  ok "$1"
}

app_state() { # контейнер сервиса, время запуска и число перезапусков в APP_STATE
  local id
  capture 30 docker compose ps -q app || fail 'docker compose ps -q app' 'код 0' 'команда не удалась'
  id=$CAPTURED
  capture 30 docker inspect -f '{{.State.StartedAt}} {{.RestartCount}}' "$id" || fail "docker inspect $id" 'код 0' 'команда не удалась'
  APP_STATE="$id $CAPTURED"
}

tables_are() { # что проверялось, список таблиц
  sql "SELECT string_agg(tablename::text, ',' ORDER BY tablename::text COLLATE \"C\") FROM pg_tables WHERE schemaname = 'public'"
  eq "$1" "$SQL_OUT" "$2"
}

# --- Откат при открытой транзакции ------------------------------------------

reader_holds() { # сколько таблиц держит читатель, список regclass: условие для wait_until
  kill -0 "$READER_PID" 2> /dev/null || fail 'читатель' 'работает и держит таблицы' 'завершился раньше времени' "$(tr -d '\r' < "$T/reader.out")"
  sql "SELECT count(DISTINCT l.relation) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
       WHERE a.application_name = '$READER_APP' AND l.granted AND l.mode = 'AccessShareLock' AND l.relation IN ($2)"
  WAIT_FACT="таблиц под блокировкой читателя: $SQL_OUT"
  [ "$SQL_OUT" = "$1" ]
}

# Читатель держит таблицы в порядке SummaryService, ждёт, пока откат встанет на блокировку, и читает цель.
# Без LOCK TABLE kkd_run в откате этого changeset одна из сторон получает deadlock detected.
rollback_with_reader() { # changeset, цель, таблицы, которые читатель уже держит
  local changeset=$1 target=$2 table='' in_list='' held=0 rollback_code=0 reader_code=0
  shift 2
  {
    printf "SET application_name = '%s';\nSET lock_timeout = '90s';\nBEGIN;\n" "$READER_APP"
    for table in "$@"; do
      printf 'SELECT count(*) FROM %s;\n' "$table"
      in_list="$in_list${in_list:+, }'$table'::regclass"
      held=$((held + 1))
    done
    cat << 'SQL'
DO $$ BEGIN
  FOR i IN 1..6000 LOOP
    PERFORM pg_stat_clear_snapshot();
    IF EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ~* '^(DROP|LOCK) TABLE') THEN RETURN; END IF;
    PERFORM pg_sleep(0.01);
  END LOOP;
  RAISE EXCEPTION 'откат не встал на блокировку за минуту';
END $$;
SQL
    printf 'SELECT count(*) FROM %s;\nCOMMIT;\n' "$target"
  } > "$T/reader.sql"
  docker compose exec -T db sh -c "$PSQL_CMD" < "$T/reader.sql" > "$T/reader.out" 2>&1 &
  READER_PID=$!
  wait_until "$LIMIT_WAIT" "читатель держит $*" reader_holds "$held" "$in_list"
  dc "$LIMIT_COMPOSE" run --rm migrate rollback-count --count=1 || rollback_code=$?
  await "$READER_PID" "$READER_LIMIT" || reader_code=$?
  READER_PID=''
  {
    printf '\n--- читатель %s, код %s\n' "$changeset" "$reader_code"
    tr -d '\r' < "$T/reader.out"
  } >> "$NOISE"
  eq "откат $changeset при открытой транзакции: код 0" "$rollback_code" 0
  eq "читатель при откате $changeset: код 0" "$reader_code" 0
  sql "SELECT to_regclass('public.$target') IS NULL"
  eq "откат $changeset убрал таблицу $target" "$SQL_OUT" t
}

# Версии строк всех таблиц: любая запись их меняет, а автоочистка нет.
# pg_snapshot_xmax здесь не годится: номера транзакций общие на весь сервер, а автоанализ template1 занимает их примерно через 50 с после запуска postgres
row_versions() { # результат в SQL_OUT
  local table='' union=''
  for table in unit goal key_result kkd_rule kkd_run kkd_issue goal_snapshot key_result_snapshot databasechangelog; do
    union="$union${union:+ UNION ALL }SELECT '$table:' || ctid || ':' || xmin AS v FROM $table"
  done
  sql "SELECT md5(string_agg(v, ',' ORDER BY v)) FROM ($union) r"
}

first_tick_done() { # время до запуска сервиса: условие для wait_until
  # Такт, не нашедший running, только читает kkd_run. Соединение простаивает, значит запрос такта завершён
  sql "SELECT count(*) FROM pg_stat_activity
       WHERE backend_start > '$1' AND state = 'idle' AND query LIKE '%FROM kkd_run%' AND query LIKE '%status = ''running''%'"
  WAIT_FACT="соединений сервиса после такта: $SQL_OUT"
  [ "$SQL_OUT" -ge 1 ]
}

step() {
  STEP=$1
  NOISE_FROM=$(($(wc -l < "$NOISE") + 1))
  printf '\n== Шаг %s\n' "$1"
}

# --- Шаг 0 ------------------------------------------------------------------

step '0. Статические проверки и таблицы README (AC-17, AC-18)'

for tool in docker curl grep sed awk sort diff tr tail wc; do
  command -v "$tool" > /dev/null 2>&1 || fail 'нужные программы' "есть $tool" "$tool не найден"
done
capture 30 docker compose version || fail 'нужные программы' 'работает docker compose' 'команда не удалась: нужен Docker с Compose v2'

# Compose передаёт имя папки проекта в заголовке gRPC, а там допустим только ASCII. Родительские папки могут быть любыми
not_ascii=$(printf '%s' "${PWD##*/}" | LC_ALL=C tr -d ' -~')
[ -z "$not_ascii" ] || fail 'имя папки проекта' 'только символы ASCII: иначе docker compose падает с failed to dial gRPC или project name must not be empty' "${PWD##*/}"

no_match 'AC-17: domain без Spring, java.sql, double, float' -rnwE 'org\.springframework|java\.sql|double|float' src/main/java/ru/kkd/domain
no_match 'AC-17: нет LocalDate.now и Bean Validation, нет JPA' -rnE 'LocalDate\.now|@Valid|@Validated|jakarta\.validation|jakarta\.persistence|org\.hibernate' src/main
no_match 'AC-17: нет liquibase-core в зависимостях' -n 'liquibase-core' pom.xml
for class in RuleEngine RuleCatalog Rule GoalNoKrRule KrWeightsSumRule CascadeNoParentRule KrStaleProgressRule; do
  [ -f "src/main/java/ru/kkd/domain/rules/$class.java" ] || fail 'AC-17: классы правил' "есть класс $class" 'файла нет'
done
ok 'AC-17: в domain/rules лежат RuleEngine, RuleCatalog, Rule и четыре класса правил'

has_match 'AC-18: Java 17' -E '<java.version>17<' pom.xml
has_match 'AC-18: Spring Boot 3.5.16' -E '<version>3\.5\.16<' pom.xml
has_match 'AC-18: postgres:17.11' -F 'postgres:17.11' docker-compose.yml
has_match 'AC-18: liquibase:4.31.1' -F 'liquibase:4.31.1' Dockerfile
for name in POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD DB_PORT APP_PORT KKD_RUN_POLL_MS; do
  has_match "AC-18: $name в .env.example" -E "^$name=" .env.example
done
if [ -e .git ] && command -v git > /dev/null 2>&1 && git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
  git ls-files > "$T/tracked.txt"
  no_match 'AC-18: .env не в репозитории' -E '(^|/)\.env$' "$T/tracked.txt"
  git ls-files --eol > "$T/eol.txt"
  no_match 'AC-18: нет CRLF' -E '[iw]/(crlf|mixed)' "$T/eol.txt"
else
  printf '  пропуск: нет git или это не репозиторий, проверки git не выполнялись\n'
fi

readme_table '| Ветка | Прогон |' 8 "$T/t1"
readme_table '| Ветка | Доля: дельта' 4 "$T/t2"
readme_table '| Ветка | critical' 7 "$T/t3"
readme_table '| Фильтр |' 2 "$T/t4"
readme_table '| Прогон | Дата |' 8 "$T/t5"
for unit in $UNITS; do
  for run in RUN-001 RUN-002; do
    [ -n "$(t1_value "$unit" "$run" 7)" ] || readme_bad "в таблице показателей нет строки $unit $run"
  done
  has_match "README: в таблице дельт есть $unit" -E "^$unit\\|" "$T/t2"
  has_match "README: в таблице разбивки есть $unit" -E "^$unit\\|" "$T/t3"
done
for filter in 'без фильтров' unit_id=HQ-CIB unit_id=CIB-NORTH unit_id=CIB-SOUTH severity=critical severity=major severity=minor \
  rule_code=GOAL_NO_KR rule_code=KR_WEIGHTS_SUM rule_code=CASCADE_NO_PARENT rule_code=KR_STALE_PROGRESS rule_code=GOAL_NO_OWNER; do
  has_match "README: в таблице total есть $filter" -F "$filter" "$T/t4"
done
[ "$(awk 'END { print NR }' "$T/t1")" = 6 ] || readme_bad 'в таблице показателей ожидаются ветки и прогоны раздела 3'
[ "$(awk 'END { print NR }' "$T/t5")" = 1 ] || readme_bad 'в таблице третьего прогона одна строка'
ok 'README: таблицы разобраны, все строки на месте'

# --- Шаг 1 ------------------------------------------------------------------

step '1. Запуск с нуля (AC-1, AC-2, AC-3, порт базы из AC-18)'

PROJECT_TOUCHED=1
must_dc "$LIMIT_COMPOSE" down -v --remove-orphans
must_dc "$LIMIT_BUILD" up -d --build
wait_seed_runs

# Не ps --format с шаблоном: Compose до 2.21 такой формат не принимает, а README обещает Docker 23
capture 30 docker compose ps -a -q migrate || fail 'docker compose ps -a -q migrate' 'код 0' 'команда не удалась'
capture 30 docker inspect -f '{{.State.Status}} {{.State.ExitCode}} {{.State.FinishedAt}}' "$CAPTURED" || fail 'docker inspect migrate' 'код 0' 'команда не удалась'
migrate_finished=${CAPTURED##* }
eq 'AC-1: migrate завершился с кодом 0' "${CAPTURED% *}" 'exited 0'
app_state
app_started=${APP_STATE#* }
app_started=${app_started% *}
if printf '%s\n%s\n' "$migrate_finished" "$app_started" | LC_ALL=C sort -c 2> /dev/null; then
  ok 'AC-1: app стартовал после завершения migrate'
else
  fail 'AC-1: порядок запуска' 'app стартует после завершения migrate' "migrate закончил $migrate_finished, app стартовал $app_started"
fi
capture 30 docker compose port db 5432 || fail 'docker compose port db 5432' 'код 0' 'команда не удалась'
case "$CAPTURED" in
  127.0.0.1:*) ok "AC-18: порт базы $CAPTURED" ;;
  *) fail 'AC-18: порт базы' '127.0.0.1:порт' "$CAPTURED" ;;
esac

tables_are 'AC-2: 8 рабочих и 2 служебные таблицы' \
  'databasechangelog,databasechangeloglock,goal,goal_snapshot,key_result,key_result_snapshot,kkd_issue,kkd_rule,kkd_run,unit'
sql 'SELECT count(*), count(*) FILTER (WHERE enabled) FROM kkd_rule'
eq 'AC-2: в справочнике 6 строк, включены 4' "$SQL_OUT" '6|4'
sql "SELECT count(*) BETWEEN 20 AND 30 FROM goal"
eq 'AC-2: целей от 20 до 30' "$SQL_OUT" t
sql "SELECT count(*) FROM kkd_run WHERE status = 'failed'"
eq 'AC-2: неуспешных прогонов нет' "$SQL_OUT" 0
sql "SELECT string_agg(id, ' ' ORDER BY id COLLATE \"C\") FROM unit"
eq 'AC-2: подразделения раздела 3.1' "$SQL_OUT" 'CIB-NORTH CIB-SOUTH HQ-CIB'
{
  spec_krs RUN-001 7
  spec_krs RUN-002 8
} | LC_ALL=C sort > "$T/krs-spec.txt"
sql "SELECT run_id || '|' || kr_id || '|' || title || '|' || measure_unit || '|' || COALESCE(target_value::text, 'пусто') || '|' || COALESCE(fact_value::text, 'пусто') || '|' || COALESCE(weight::text, 'пусто') || '|' || COALESCE(fact_updated_on::text, 'пусто') FROM key_result_snapshot"
printf '%s\n' "$SQL_OUT" | LC_ALL=C sort > "$T/krs-db.txt"
same_responses 'AC-2: КР снимков совпадают с разделом 3.4 design.md' "$T/krs-spec.txt" "$T/krs-db.txt"
{
  spec_goals RUN-001
  spec_goals RUN-002
} | LC_ALL=C sort > "$T/goals-spec.txt"
sql "SELECT run_id || '|' || goal_id || '|' || title || '|' || unit_id || '|' || level || '|' || COALESCE(owner_name, 'пусто') || '|' || COALESCE(parent_goal_id, 'нет') || '|' || status || '|' || COALESCE(due_date::text, 'пусто') FROM goal_snapshot"
printf '%s\n' "$SQL_OUT" | LC_ALL=C sort > "$T/goals-db.txt"
same_responses 'AC-2: цели снимков совпадают с разделами 3.2 и 3.3 design.md' "$T/goals-spec.txt" "$T/goals-db.txt"
no_match 'AC-3: в db/changelog нет вставки в kkd_issue' -ri 'insert into kkd_issue' db/changelog
for run in RUN-001 RUN-002; do
  sql "SELECT string_agg(DISTINCT rule_code, ',' ORDER BY rule_code) FROM kkd_issue WHERE run_id = '$run'"
  eq "AC-3: в $run все четыре правила и нет выключенных" "$SQL_OUT" 'CASCADE_NO_PARENT,GOAL_NO_KR,KR_STALE_PROGRESS,KR_WEIGHTS_SUM'
done

# --- Шаг 2 ------------------------------------------------------------------

step '2. README и контракт (AC-3...AC-8)'

# AC-4
issues_run1=$(t1_value HQ-CIB RUN-001 7)
issues_run2=$(t1_value HQ-CIB RUN-002 7)
get /api/runs
form 'AC-4: форма и порядок /api/runs, totals из README' "$HTTP_BODY" \
  '{"items":[' \
  '{"id":"RUN-002","status":"completed","snapshot_date":"2026-10-26","compared_to_run_id":"RUN-001","totals":{"issues_total":'"$issues_run2"'},"error":null},' \
  '{"id":"RUN-001","status":"completed","snapshot_date":"2026-10-19","compared_to_run_id":null,"totals":{"issues_total":'"$issues_run1"'},"error":null}' \
  '],"total":2}'

# AC-5, AC-6, AC-7: все строки таблицы показателей
number='^[0-9]+$'
share_re='^[0-9]+(\.[0-9]+)?$'
cover_re='^([0-9]+) [^ ]+ ([0-9]+) = ([0-9]+(\.[0-9]+)?)$'
while IFS='|' read -r unit run active with_issues clean share count cover <&3; do
  [[ $active =~ $number && $with_issues =~ $number && $clean =~ $number && $count =~ $number && $share =~ $share_re ]] ||
    readme_bad "значения в строке $unit $run"
  [[ $cover =~ $cover_re ]] || readme_bad "покрытие $cover в строке $unit $run"
  cover_num=${BASH_REMATCH[1]}
  cover_den=${BASH_REMATCH[2]}
  cover_value=${BASH_REMATCH[3]}
  name=$(unit_name "$unit")

  get "$SUMMARY?run_id=$run&unit_id=$unit"
  summary=$HTTP_BODY
  case $run in
    RUN-001) date=2026-10-19 compared=null ;;
    RUN-002) date=2026-10-26 compared='{"id":"RUN-001","snapshot_date":"2026-10-19"}' ;;
    *) readme_bad "неизвестный прогон $run" ;;
  esac
  summary_form "AC-5: форма сводки $unit $run" "$unit" "$name" "$run" "$date" "$compared" "$summary"

  frag "$summary" '"key":"clean_goals_share"'
  has "README: $unit $run доля $share" '"value":'"$share"',' "$FRAG"
  [ "$run" = RUN-002 ] || has "AC-6: $unit $run доля без сравнения" '"value":'"$share"',"previous":null,"delta":null,"trend":null,"higher_is_better":' "$FRAG"
  frag "$summary" '"key":"issues_total"'
  has "README: $unit $run проблем $count" '"value":'"$count"',' "$FRAG"
  [ "$run" = RUN-002 ] || has "AC-6: $unit $run счётчик без сравнения" '"value":'"$count"',"previous":null,"delta":null,"trend":null,"higher_is_better":' "$FRAG"
  frag "$summary" '"key":"cascade_coverage"'
  has "README: $unit $run покрытие $cover_value" '"value":'"$cover_value"',' "$FRAG"
  [ "$run" = RUN-002 ] || has "AC-6: $unit $run покрытие без сравнения" '"value":'"$cover_value"',"previous":null,"delta":null,"trend":null,"higher_is_better":' "$FRAG"
  [ "$run" = RUN-002 ] || has "AC-6: $unit $run счётчики null" '"delta":{"new":null,"persisting":null,"resolved":null}}' "$summary"

  get "$ISSUES?run_id=$run&unit_id=$unit&limit=200"
  list=$HTTP_BODY
  has "README: $unit $run total $count" '"total":'"$count"',"limit":200,"offset":0}' "$list"
  issue_rows_ok "$run" "$unit" "$list"
  goals=$(matches '"goal_id":"[^"]*"' "$list" | LC_ALL=C sort -u)
  eq "README: $unit $run столбец «С проблемами»" "$(lines "$goals")" "$with_issues"

  sum=0
  rest=$summary
  current_re='"current":([0-9]+)'
  while [[ $rest =~ $current_re ]]; do
    sum=$((sum + BASH_REMATCH[1]))
    rest=${rest#*"${BASH_REMATCH[0]}"}
  done
  eq "AC-7: $unit $run total равен сумме current" "$sum" "$count"

  sql "$(branch_cte "$unit")
       SELECT count(*) FILTER (WHERE g.status = 'active'),
              count(*) FILTER (WHERE g.status = 'active' AND g.level <> 'department')
       FROM goal_snapshot g JOIN branch b ON g.unit_id = b.id
       WHERE g.run_id = '$run'"
  eq "README: $unit $run столбец «Активных целей»" "${SQL_OUT%%|*}" "$active"
  eq "README: $unit $run столбец «Корректных»" "$((active - with_issues))" "$clean"
  eq "README: $unit $run знаменатель покрытия" "${SQL_OUT##*|}" "$cover_den"
  cascade=$(matches '"rule_code":"CASCADE_NO_PARENT"' "$list")
  eq "README: $unit $run числитель покрытия" "$((cover_den - $(lines "$cascade")))" "$cover_num"
done 3< "$T/t1"

# Дельты и trend
while IFS='|' read -r unit delta_share delta_count delta_cover <&3; do
  get "$SUMMARY?run_id=RUN-002&unit_id=$unit"
  summary=$HTTP_BODY
  check_metric_delta "$unit" clean_goals_share 6 "$delta_share" true "$summary"
  check_metric_delta "$unit" issues_total 7 "$delta_count" false "$summary"
  check_metric_delta "$unit" cascade_coverage 8 "$delta_cover" true "$summary"
done 3< "$T/t2"

# Разбивка важности и new, persisting, resolved
pair_re='^[0-9]+ [^ ]+ [0-9]+$'
while IFS='|' read -r unit critical major minor new_count persisting resolved <&3; do
  [[ $new_count =~ $number && $persisting =~ $number && $resolved =~ $number ]] || readme_bad "счётчики в строке $unit"
  get "$SUMMARY?run_id=RUN-002&unit_id=$unit"
  summary2=$HTTP_BODY
  get "$SUMMARY?run_id=RUN-001&unit_id=$unit"
  summary1=$HTTP_BODY
  for item in "critical:$critical" "major:$major" "minor:$minor"; do
    code=${item%%:*}
    cell=${item#*:}
    [[ $cell =~ $pair_re ]] || readme_bad "ячейка $cell в строке $unit"
    before=${cell%% *}
    now=${cell##* }
    has "README: $unit $code RUN-002: $cell" '{"code":"'"$code"'","current":'"$now"',"previous":'"$before"',"delta":'"$((now - before))"'}' "$summary2"
    has "README, AC-6: $unit $code RUN-001: $before, null" '{"code":"'"$code"'","current":'"$before"',"previous":null,"delta":null}' "$summary1"
    get "$G&unit_id=$unit&severity=$code"
    has "README: $unit и $code вместе: total $now" '"total":'"$now"',"limit":20,"offset":0}' "$HTTP_BODY"
  done
  has "README: $unit new $new_count, persisting $persisting, resolved $resolved" \
    '"delta":{"new":'"$new_count"',"persisting":'"$persisting"',"resolved":'"$resolved"'}}' "$summary2"
done 3< "$T/t3"

# AC-5: счётчики дочерних в сумме дают родителя, значения из ответов сервиса
delta_re='"delta":\{"new":([0-9]+),"persisting":([0-9]+),"resolved":([0-9]+)\}\}$'
sum_new=0
sum_persisting=0
sum_resolved=0
for unit in CIB-NORTH CIB-SOUTH; do
  get "$SUMMARY?run_id=RUN-002&unit_id=$unit"
  [[ $HTTP_BODY =~ $delta_re ]] || fail "AC-5: счётчики $unit" 'три числа' 'другая форма' "$HTTP_BODY"
  sum_new=$((sum_new + BASH_REMATCH[1]))
  sum_persisting=$((sum_persisting + BASH_REMATCH[2]))
  sum_resolved=$((sum_resolved + BASH_REMATCH[3]))
done
get "$SUMMARY?run_id=RUN-002&unit_id=HQ-CIB"
has 'AC-5: delta CIB-NORTH и CIB-SOUTH в сумме дают HQ-CIB' \
  '"delta":{"new":'"$sum_new"',"persisting":'"$sum_persisting"',"resolved":'"$sum_resolved"'}}' "$HTTP_BODY"

# AC-7: все строки таблицы total
param_re='[a-z_]+=[A-Za-z0-9_-]+'
while IFS='|' read -r filter total <&3; do
  [[ $total =~ $number ]] || readme_bad "total в строке $filter"
  [[ $filter =~ $param_re ]] || readme_bad "фильтр в строке $filter"
  param=${BASH_REMATCH[0]}
  queries="&$param"
  case "$filter" in
    'без фильтров'*) queries="none &$param" ;;
  esac
  for query in $queries; do
    [ "$query" != none ] || query=''
    get "$G$query"
    list=$HTTP_BODY
    form "README: «${filter}» ${query:-без фильтров}: total $total, limit 20, offset 0" "$list" \
      '{"run_id":"RUN-002","items":[' \
      '],"total":'"$total"',"limit":20,"offset":0}'
    eq "README: «${filter}» ${query:-без фильтров}: строк в items столько же" "$(lines "$(ids_of "$list")")" "$((total < 20 ? total : 20))"
    case "$query" in
      *severity=* | *rule_code=*)
        field=${param%%=*}
        distinct=$(matches '"'"$field"'":"[^"]*"' "$list" | LC_ALL=C sort -u)
        if [ "$total" = 0 ]; then
          eq "AC-7: $param пустой items" "$list" '{"run_id":"RUN-002","items":[],"total":0,"limit":20,"offset":0}'
        else
          eq "AC-7: $param в items только это значение" "$distinct" '"'"$field"'":"'"${param#*=}"'"'
        fi
        ;;
    esac
  done
done 3< "$T/t4"
get "$G&rule_code=KR_NO_TARGET"
form 'AC-7: второй выключенный код: 200 и пустой items' "$HTTP_BODY" '{"run_id":"RUN-002","items":[],"total":0,"limit":20,"offset":0}'

# AC-7: страницы и порядок раздела 8.4
get "$G&limit=200"
all_ids=$(ids_of "$HTTP_BODY")
all_total=$(lines "$all_ids")
eq 'README: limit=200 отдаёт все строки' "$all_total" "$issues_run2"
has 'README: limit=200 тот же total' '"total":'"$issues_run2"',"limit":200,"offset":0}' "$HTTP_BODY"
issue_rows_ok RUN-002 '' "$HTTP_BODY"
get "$ISSUES?run_id=RUN-001&limit=200"
issue_rows_ok RUN-001 '' "$HTTP_BODY"

get "$G&limit=5&offset=0"
page1=$(ids_of "$HTTP_BODY")
get "$G&limit=5&offset=5"
page2=$(ids_of "$HTTP_BODY")
unique=$(printf '%s\n%s\n' "$page1" "$page2" | LC_ALL=C sort -u)
eq 'AC-7: две страницы без повторов id' "$(lines "$unique")" "$(($(lines "$page1") + $(lines "$page2")))"
offset=0
joined=''
while [ "$offset" -lt "$all_total" ]; do
  get "$G&limit=5&offset=$offset"
  has "AC-7: страница offset=$offset, total не зависит от offset" '"total":'"$all_total"',"limit":5,"offset":'"$offset"'}' "$HTTP_BODY"
  joined="$joined${joined:+$NL}$(ids_of "$HTTP_BODY")"
  offset=$((offset + 5))
done
eq 'AC-7: все страницы вместе равны limit=200' "$joined" "$all_ids"
for offset in "$all_total" 1000; do
  get "$G&limit=5&offset=$offset"
  form "AC-7: offset=$offset за концом: 200 и пустой items" "$HTTP_BODY" \
    '{"run_id":"RUN-002","items":[],"total":'"$all_total"',"limit":5,"offset":'"$offset"'}'
done

# AC-8 и AC-3 по id и details
get "$ISSUES?run_id=RUN-001&limit=200"
list1=$HTTP_BODY
get "$ISSUES?run_id=RUN-002&limit=200"
list2=$HTTP_BODY
frag "$list1" '"id":"KR_WEIGHTS_SUM:G-07"'
details1=$FRAG
frag "$list2" '"id":"KR_WEIGHTS_SUM:G-07"'
details2=$FRAG
[ -n "$details1" ] || fail 'AC-8: KR_WEIGHTS_SUM:G-07 в RUN-001' 'строка есть' 'строки нет' "$list1"
[ -n "$details2" ] || fail 'AC-8: KR_WEIGHTS_SUM:G-07 в RUN-002' 'строка есть' 'строки нет' "$list2"
details1=${details1##*\"details\":}
details2=${details2##*\"details\":}
[ "$details1" != "$details2" ] || fail 'AC-8: KR_WEIGHTS_SUM:G-07 с разным details' 'тексты различаются' "оба $details1"
ok 'AC-8: KR_WEIGHTS_SUM:G-07 в обоих прогонах с разным details'
has 'AC-8: GOAL_NO_KR:G-10 в RUN-001' '"id":"GOAL_NO_KR:G-10"' "$list1"
has_not 'AC-8: GOAL_NO_KR:G-10 нет в RUN-002' '"id":"GOAL_NO_KR:G-10"' "$list2"
has_not 'AC-8: KR_WEIGHTS_SUM:G-10 нет в RUN-001' '"id":"KR_WEIGHTS_SUM:G-10"' "$list1"
has 'AC-8: KR_WEIGHTS_SUM:G-10 в RUN-002' '"id":"KR_WEIGHTS_SUM:G-10"' "$list2"
has 'AC-3: граница 46 дней нарушает: KR-13-1 в RUN-002' '"id":"KR_STALE_PROGRESS:KR-13-1"' "$list2"
has_not 'AC-3: граница 45 дней не нарушает: KR-03-1 нет в RUN-002' '"id":"KR_STALE_PROGRESS:KR-03-1"' "$list2"

map_code=0
map_ids 7 > "$T/map1.txt" 2>> "$NOISE" || map_code=$?
map_ids 8 > "$T/map2.txt" 2>> "$NOISE" || map_code=$?
[ "$map_code" = 0 ] && [ -s "$T/map1.txt" ] && [ -s "$T/map2.txt" ] || fail 'AC-3: карта целей раздела 3.2 design.md' 'таблица разбирается' 'формат таблицы изменился'
eq 'AC-3: проблемы RUN-001 совпадают с картой целей' "$(ids_of "$list1" | LC_ALL=C sort)" "$(cat "$T/map1.txt")"
eq 'AC-3: проблемы RUN-002 совпадают с картой целей' "$(ids_of "$list2" | LC_ALL=C sort)" "$(cat "$T/map2.txt")"

# --- Шаг 3 ------------------------------------------------------------------

step '3. Ошибки и испорченные запросы (AC-9, раздел 8.1), GET не пишут'

row_versions
versions_before=$SQL_OUT
sql "SELECT md5(string_agg(x::text, ',' ORDER BY x::text)) FROM kkd_issue x"
md5_before=$SQL_OUT

expect_error 404 RUN_NOT_FOUND "$SUMMARY?run_id=RUN-999&unit_id=HQ-CIB"
expect_error 404 UNIT_NOT_FOUND "$SUMMARY?run_id=RUN-002&unit_id=NOPE"
expect_error 404 RUN_NOT_FOUND "$SUMMARY?run_id=abc&unit_id=HQ-CIB"
expect_error 404 RUN_NOT_FOUND "$SUMMARY?run_id=RUN-999&unit_id=NOPE"
expect_error 422 INVALID_PARAM "$SUMMARY"
expect_error 422 INVALID_PARAM "$SUMMARY?run_id=RUN-002"
expect_error 422 INVALID_PARAM "$SUMMARY?unit_id=HQ-CIB"
expect_error 422 INVALID_PARAM "$SUMMARY?run_id=&unit_id=HQ-CIB"
expect_error 422 INVALID_PARAM "$SUMMARY?run_id=RUN-002&unit_id="
expect_error 422 INVALID_PARAM "$SUMMARY?run_id=%00&unit_id=HQ-CIB"
expect_error 422 INVALID_PARAM "$SUMMARY?run_id=RUN-002&unit_id=%00"
expect_error 422 INVALID_PARAM "$SUMMARY?run_id=RUN-002&unit_id=HQ-CIB&%zz=1"
expect_error 422 INVALID_PARAM "$ISSUES"
expect_error 422 INVALID_PARAM "$ISSUES?run_id="
expect_error 422 INVALID_PARAM "$ISSUES?run_id=%00"
expect_error 404 RUN_NOT_FOUND "$ISSUES?run_id=RUN-999"
expect_error 404 UNIT_NOT_FOUND "$G&unit_id=NOPE"
expect_error 422 INVALID_PARAM "$ISSUES?run_id=RUN-999&limit=-1"
expect_error 422 INVALID_PARAM "$G&unit_id="
expect_error 422 INVALID_PARAM "$G&unit_id=%00"
for value in NOPE goal_no_kr ''; do expect_error 422 INVALID_PARAM "$G&rule_code=$value"; done
for value in MINOR blocker ''; do expect_error 422 INVALID_PARAM "$G&severity=$value"; done
for value in abc '' 0 -1 201 1.0 %2B5 %205 %D9%A5; do expect_error 422 INVALID_PARAM "$G&limit=$value"; done
for value in abc '' -1 2147483648 1.5 %2B5 %205 %D9%A5; do expect_error 422 INVALID_PARAM "$G&offset=$value"; done
for query in severity=%zz limit=%zz %zz=1; do expect_error 422 INVALID_PARAM "$G&$query"; done
for query in limit=1 limit=200 offset=0 offset=2147483647 foo=bar; do expect_ok "$G&$query"; done
expect_ok "$SUMMARY?run_id=RUN-002&unit_id=HQ-CIB&limit=abc"

expect_error 405 METHOD_NOT_ALLOWED /api/runs -X POST
has 'AC-9: у 405 заголовок Allow: GET' 'Allow: GET' "$HTTP_HEADERS"
for path in /api/runs "$SUMMARY" "$ISSUES"; do
  for method in POST PUT PATCH DELETE; do expect_error 405 METHOD_NOT_ALLOWED "$path" -X "$method"; done
done
expect_error 405 METHOD_NOT_ALLOWED /api/runs -X PUT -H 'Content-Type: application/x-www-form-urlencoded' --data-binary 'a=%zz'
for path in /api/nope / /error /api/runs/ /api; do expect_error 404 NOT_FOUND "$path"; done
expect_error 404 NOT_FOUND /api/nope -X POST
expect_error 406 REQUEST_NOT_SUPPORTED /api/runs -H 'Accept: application/xml'
expect_error 422 INVALID_PARAM "$G&limit=0" -H 'Accept: application/xml'
expect_ok /api/runs -H 'Content-Type: multipart/form-data'

save_responses "$T/resp/series"
row_versions
eq 'GET не пишут: версии строк таблиц те же' "$SQL_OUT" "$versions_before"
sql "SELECT md5(string_agg(x::text, ',' ORDER BY x::text)) FROM kkd_issue x"
eq 'GET не пишут: kkd_issue та же' "$SQL_OUT" "$md5_before"

# --- Шаг 4 ------------------------------------------------------------------

step '4. Накат, откат, повторный накат при работающем сервисе (AC-10)'

save_responses "$T/resp/a"
app_state
app_before=$APP_STATE
ok "сохранены ответы трёх методов, контейнер сервиса: $app_before"

must_dc "$LIMIT_COMPOSE" run --rm migrate rollback --tag=v0-empty
tables_are 'AC-10: после отката только служебные таблицы' 'databasechangelog,databasechangeloglock'
sql 'SELECT count(*) FROM databasechangelog'
eq 'AC-10: databasechangelog пуст' "$SQL_OUT" 0
expect_error 503 DB_NOT_READY /api/runs
expect_error 503 DB_NOT_READY "$SUMMARY?run_id=RUN-002&unit_id=HQ-CIB"
expect_error 503 DB_NOT_READY "$G"
expect_error 422 INVALID_PARAM "$G&limit=0"
expect_error 422 INVALID_PARAM "$SUMMARY"
expect_error 404 NOT_FOUND /api/nope

second_rollback=0
dc "$LIMIT_COMPOSE" run --rm migrate rollback --tag=v0-empty || second_rollback=$?
case "$second_rollback" in
  0 | 124) fail 'AC-10: повторный откат' 'код не 0 и текст Could not find tag' "код $second_rollback" ;;
esac
has_match "AC-10: повторный откат: код $second_rollback, Could not find tag" -F 'Could not find tag' "$LAST_LOG"

must_dc "$LIMIT_COMPOSE" run --rm migrate update
wait_until "$LIMIT_WAIT" 'после наката RUN-001 и RUN-002 completed' runs_are completed RUN-001 RUN-002
save_responses "$T/resp/b"
same_responses 'AC-10: ответы всех трёх методов побайтно те же' "$T/resp/a" "$T/resp/b"
app_state
eq 'AC-10: сервис не перезапускался' "$APP_STATE" "$app_before"

# --- Шаг 5 ------------------------------------------------------------------

step '5. Откат при открытой транзакции (раздел 9.1: LOCK TABLE в откате 007, 008, 009)'

must_dc "$LIMIT_COMPOSE" run --rm migrate rollback-count --count=1
rollback_with_reader 009 key_result_snapshot kkd_run goal_snapshot
rollback_with_reader 008 goal_snapshot kkd_run
rollback_with_reader 007 kkd_issue kkd_run
must_dc "$LIMIT_COMPOSE" run --rm migrate rollback --tag=v0-empty
tables_are 'после полного отката только служебные таблицы' 'databasechangelog,databasechangeloglock'
must_dc "$LIMIT_COMPOSE" run --rm migrate update
wait_until "$LIMIT_WAIT" 'после наката RUN-001 и RUN-002 completed' runs_are completed RUN-001 RUN-002
save_responses "$T/resp/c"
same_responses 'ответы после отката по шагам те же' "$T/resp/a" "$T/resp/c"
app_state
eq 'сервис не перезапускался' "$APP_STATE" "$app_before"

# --- Шаг 6 ------------------------------------------------------------------

step '6. Третий прогон, прошлое не меняется (AC-12)'

IFS='|' read -r run3 date3 total3 base3 new3 persisting3 resolved3 problem3 < "$T/t5"
[[ $total3 =~ $number && $new3 =~ $number && $persisting3 =~ $number && $resolved3 =~ $number ]] ||
  readme_bad 'значения в таблице третьего прогона'
sql "INSERT INTO kkd_run (id, snapshot_date) VALUES ('$run3', '$date3')"
wait_until "$LIMIT_WAIT" "$run3 completed" runs_are completed "$run3"
get /api/runs
form "AC-12: $run3 первым, completed, база $base3, проблем $total3" "$HTTP_BODY" \
  '{"items":[{"id":"'"$run3"'","status":"completed","snapshot_date":"'"$date3"'","compared_to_run_id":"'"$base3"'","totals":{"issues_total":'"$total3"'},"error":null},{"id":"RUN-002",' \
  ',"total":3}'
get "$SUMMARY?run_id=$run3&unit_id=HQ-CIB"
has "README: $run3 new $new3, persisting $persisting3, resolved $resolved3" \
  '"delta":{"new":'"$new3"',"persisting":'"$persisting3"',"resolved":'"$resolved3"'}}' "$HTTP_BODY"
save_one "$T/resp/run3-summary.json" "$SUMMARY?run_id=$run3&unit_id=HQ-CIB"
save_one "$T/resp/run3-issues.json" "$ISSUES?run_id=$run3&limit=200"
get "$ISSUES?run_id=$run3&limit=200"
has "README: новая проблема $problem3" '"id":"'"$problem3"'"' "$HTTP_BODY"
ids_of "$HTTP_BODY" > "$T/ids3.txt"
ids_of "$list2" > "$T/ids2.txt"
new_ids=$(grep -vxF -f "$T/ids2.txt" "$T/ids3.txt" || true)
eq "README: единственная новая проблема $problem3" "$new_ids" "$problem3"

sql "SELECT (SELECT count(*) FROM goal_snapshot WHERE run_id = '$run3') || '|' || (SELECT count(*) FROM goal)"
eq 'раздел 14: снимок целей равен goal по числу строк' "${SQL_OUT%%|*}" "${SQL_OUT##*|}"
sql "SELECT (SELECT count(*) FROM key_result_snapshot WHERE run_id = '$run3') || '|' || (SELECT count(*) FROM key_result)"
eq 'раздел 14: снимок КР равен key_result по числу строк' "${SQL_OUT%%|*}" "${SQL_OUT##*|}"
sql "SELECT count(*) FROM (SELECT id, title, unit_id, level, owner_name, parent_goal_id, status, due_date FROM goal EXCEPT SELECT goal_id, title, unit_id, level, owner_name, parent_goal_id, status, due_date FROM goal_snapshot WHERE run_id = '$run3') d"
eq 'раздел 14: снимок целей равен goal по значениям' "$SQL_OUT" 0
sql "SELECT count(*) FROM (SELECT id, goal_id, title, measure_unit, target_value, fact_value, weight, fact_updated_on FROM key_result EXCEPT SELECT kr_id, goal_id, title, measure_unit, target_value, fact_value, weight, fact_updated_on FROM key_result_snapshot WHERE run_id = '$run3') d"
eq 'раздел 14: снимок КР равен key_result по значениям' "$SQL_OUT" 0

sql "UPDATE goal SET status = 'closed' WHERE id = 'G-01'"
sql "UPDATE goal SET title = title || ' EDITED'"
save_responses "$T/resp/d"
same_responses 'AC-12: после правки цели старые сводка и issues те же, goal_title из снимка' "$T/resp/a/past" "$T/resp/d/past"
get "$ISSUES?run_id=$run3&limit=200"
has_not "AC-12: у $run3 goal_title тоже из снимка" 'EDITED' "$HTTP_BODY"
sql "UPDATE goal SET status = 'active' WHERE id = 'G-01'"
sql "UPDATE goal SET title = replace(title, ' EDITED', '')"

# --- Шаг 7 ------------------------------------------------------------------

step '7. Граничные случаи (AC-13, AC-15, раздел 11.2)'

sql "INSERT INTO kkd_run (id, snapshot_date, status, error) VALUES ('RUN-F', '2026-11-03', 'failed', 'manual check')"
expect_error 409 RUN_FAILED "$SUMMARY?run_id=RUN-F&unit_id=HQ-CIB"
expect_error 409 RUN_FAILED "$ISSUES?run_id=RUN-F"
expect_error 404 UNIT_NOT_FOUND "$SUMMARY?run_id=RUN-F&unit_id=NOPE"
expect_error 422 INVALID_PARAM "$ISSUES?run_id=RUN-F&limit=-1"
get /api/runs
has 'AC-13: у RUN-F totals и compared_to_run_id равны null' \
  '{"id":"RUN-F","status":"failed","snapshot_date":"2026-11-03","compared_to_run_id":null,"totals":null,"error":"manual check"}' "$HTTP_BODY"

# failed получает только прогон с ранней датой, соседний из той же вставки считается как обычно
sql "INSERT INTO kkd_run (id, snapshot_date) VALUES ('RUN-OLD', '2026-10-01'), ('RUN-004', '2026-11-09')"
wait_until "$LIMIT_WAIT" 'RUN-OLD failed' runs_are failed RUN-OLD
wait_until "$LIMIT_WAIT" 'RUN-004 completed' runs_are completed RUN-004
get /api/runs
has 'AC-13: RUN-OLD failed с постоянным текстом' \
  '{"id":"RUN-OLD","status":"failed","snapshot_date":"2026-10-01","compared_to_run_id":null,"totals":null,"error":"'"$SNAPSHOT_TOO_OLD"'"}' "$HTTP_BODY"
has 'AC-13: RUN-004 сравнивается с RUN-003 мимо RUN-F' \
  '{"id":"RUN-004","status":"completed","snapshot_date":"2026-11-09","compared_to_run_id":"RUN-003","totals":{"issues_total":' "$HTTP_BODY"
for run in RUN-001 RUN-002 RUN-003; do
  has "$run остался completed" '"id":"'"$run"'","status":"completed"' "$HTTP_BODY"
done

# Два прогона на одну дату в обоих порядках вставки: (snapshot_date, id) в такте, snapshot_date DESC, id DESC в списке
sql "INSERT INTO kkd_run (id, snapshot_date) VALUES ('RUN-T2', '2026-11-10'), ('RUN-T1', '2026-11-10')"
wait_until "$LIMIT_WAIT" 'RUN-T1 и RUN-T2 completed' runs_are completed RUN-T1 RUN-T2
sql "INSERT INTO kkd_run (id, snapshot_date) VALUES ('RUN-T3', '2026-11-11'), ('RUN-T4', '2026-11-11')"
wait_until "$LIMIT_WAIT" 'RUN-T3 и RUN-T4 completed' runs_are completed RUN-T3 RUN-T4
get /api/runs
has 'раздел 6.4: RUN-T1 сравнивается с RUN-004' '{"id":"RUN-T1","status":"completed","snapshot_date":"2026-11-10","compared_to_run_id":"RUN-004"' "$HTTP_BODY"
has 'раздел 6.4: RUN-T2 сравнивается с RUN-T1' '{"id":"RUN-T2","status":"completed","snapshot_date":"2026-11-10","compared_to_run_id":"RUN-T1"' "$HTTP_BODY"
has 'раздел 6.4: RUN-T3 сравнивается с RUN-T2' '{"id":"RUN-T3","status":"completed","snapshot_date":"2026-11-11","compared_to_run_id":"RUN-T2"' "$HTTP_BODY"
has 'раздел 6.4: RUN-T4 сравнивается с RUN-T3' '{"id":"RUN-T4","status":"completed","snapshot_date":"2026-11-11","compared_to_run_id":"RUN-T3"' "$HTTP_BODY"

# Выключенное реализованное правило не считается
sql "UPDATE kkd_rule SET enabled = false WHERE code = 'GOAL_NO_KR'; INSERT INTO kkd_run (id, snapshot_date) VALUES ('RUN-NOKR', '2026-11-12')"
wait_until "$LIMIT_WAIT" 'RUN-NOKR completed' runs_are completed RUN-NOKR
sql "UPDATE kkd_rule SET enabled = true WHERE code = 'GOAL_NO_KR'"
get "$ISSUES?run_id=RUN-NOKR&rule_code=GOAL_NO_KR"
has 'выключенное правило не дало строк' '"items":[],"total":0,' "$HTTP_BODY"
get "$ISSUES?run_id=RUN-NOKR&limit=200"
has 'остальные правила считаются' '"rule_code":"KR_WEIGHTS_SUM"' "$HTTP_BODY"

# Завершённый прогон без проблем: 0, а не null
sql "UPDATE kkd_rule SET enabled = false; INSERT INTO kkd_run (id, snapshot_date) VALUES ('RUN-ZERO', '2026-11-13')"
wait_until "$LIMIT_WAIT" 'RUN-ZERO completed' runs_are completed RUN-ZERO
sql "UPDATE kkd_rule SET enabled = true WHERE code IN ('GOAL_NO_KR', 'KR_WEIGHTS_SUM', 'CASCADE_NO_PARENT', 'KR_STALE_PROGRESS')"
get /api/runs
has 'все правила выключены: issues_total 0' \
  '{"id":"RUN-ZERO","status":"completed","snapshot_date":"2026-11-13","compared_to_run_id":"RUN-NOKR","totals":{"issues_total":0},"error":null}' "$HTTP_BODY"
eq 'раздел 8.2: порядок snapshot_date DESC, id DESC' "$(ids_of "$HTTP_BODY" | tr '\n' ' ')" \
  'RUN-ZERO RUN-NOKR RUN-T4 RUN-T3 RUN-T2 RUN-T1 RUN-004 RUN-F RUN-003 RUN-002 RUN-001 RUN-OLD '
get "$ISSUES?run_id=RUN-ZERO"
has 'все правила выключены: пустой список' '"items":[],"total":0,' "$HTTP_BODY"

# AC-15
sql "INSERT INTO unit (id, name, parent_id) VALUES ('CIB-EMPTY', 'Empty unit', 'HQ-CIB')"
get "$SUMMARY?run_id=RUN-002&unit_id=CIB-EMPTY"
summary=$HTTP_BODY
summary_form 'AC-15: форма сводки, compared_to заполнен' CIB-EMPTY 'Empty unit' RUN-002 2026-10-26 '{"id":"RUN-001","snapshot_date":"2026-10-19"}' "$summary"
frag "$summary" '"key":"clean_goals_share"'
has 'AC-15: доля null' '"value":null,"previous":null,"delta":null,"trend":null,' "$FRAG"
frag "$summary" '"key":"cascade_coverage"'
has 'AC-15: покрытие null' '"value":null,"previous":null,"delta":null,"trend":null,' "$FRAG"
frag "$summary" '"key":"issues_total"'
has 'AC-15: счётчик 0, same' '"value":0,"previous":0,"delta":0,"trend":"same",' "$FRAG"
for code in critical major minor; do
  has "AC-15: разбивка $code 0" '{"code":"'"$code"'","current":0,"previous":0,"delta":0}' "$summary"
done
has 'AC-15: счётчики 0' '"delta":{"new":0,"persisting":0,"resolved":0}}' "$summary"
get "$ISSUES?run_id=RUN-002&unit_id=CIB-EMPTY"
eq 'AC-15: пустой список' "$HTTP_BODY" '{"run_id":"RUN-002","items":[],"total":0,"limit":20,"offset":0}'

save_responses "$T/resp/e"
same_responses 'прошлые ответы RUN-001 и RUN-002 не тронуты' "$T/resp/a/past" "$T/resp/e/past"
get "$SUMMARY?run_id=$run3&unit_id=HQ-CIB"
eq "прошлая сводка $run3 не тронута" "$HTTP_BODY" "$(cat "$T/resp/run3-summary.json")"
get "$ISSUES?run_id=$run3&limit=200"
eq "прошлый список $run3 не тронут" "$HTTP_BODY" "$(cat "$T/resp/run3-issues.json")"

# --- Шаг 8 ------------------------------------------------------------------

step '8. Выполняющийся прогон (AC-14)'

sql 'SELECT now()'
before_slow=$SQL_OUT
KKD_RUN_POLL_MS=600000 must_dc "$LIMIT_COMPOSE" up -d --no-build app
wait_until "$LIMIT_WAIT" 'сервис с тактом 600000 мс закончил первый такт' first_tick_done "$before_slow"
refresh_base
wait_until "$LIMIT_WAIT" 'сервис отвечает' service_answers
sql "INSERT INTO kkd_run (id, snapshot_date) VALUES ('RUN-005', '2026-11-16')"
expect_error 409 RUN_IN_PROGRESS "$SUMMARY?run_id=RUN-005&unit_id=HQ-CIB"
expect_error 409 RUN_IN_PROGRESS "$ISSUES?run_id=RUN-005"
expect_error 404 UNIT_NOT_FOUND "$SUMMARY?run_id=RUN-005&unit_id=NOPE"
expect_error 404 UNIT_NOT_FOUND "$ISSUES?run_id=RUN-005&unit_id=NOPE"
get /api/runs
has 'AC-14: RUN-005 running, totals, compared_to_run_id и error равны null' \
  '{"id":"RUN-005","status":"running","snapshot_date":"2026-11-16","compared_to_run_id":null,"totals":null,"error":null}' "$HTTP_BODY"
must_dc "$LIMIT_COMPOSE" up -d --no-build app
refresh_base
wait_until "$LIMIT_WAIT" 'при обычном такте RUN-005 completed' runs_are completed RUN-005
has 'AC-14: RUN-005 сравнивается с RUN-ZERO' '{"id":"RUN-005","status":"completed","snapshot_date":"2026-11-16","compared_to_run_id":"RUN-ZERO"' "$HTTP_BODY"

# --- Шаг 9 ------------------------------------------------------------------

step '9. Другие откаты и перезапуск на свежей базе (AC-11)'

runs_expected=$(cat "$T/resp/a/runs.json")

must_dc "$LIMIT_COMPOSE" down -v --remove-orphans
must_dc "$LIMIT_COMPOSE" up -d --no-build
wait_seed_runs

must_dc "$LIMIT_COMPOSE" run --rm migrate rollback-count --count=1
get /api/runs
eq 'AC-11: после отката стартовых данных /api/runs пуст' "$HTTP_BODY" '{"items":[],"total":0}'
expect_error 404 RUN_NOT_FOUND "$SUMMARY?run_id=RUN-002&unit_id=HQ-CIB"
must_dc "$LIMIT_COMPOSE" run --rm migrate update
wait_until "$LIMIT_WAIT" 'RUN-001 и RUN-002 completed' runs_are completed RUN-001 RUN-002
get /api/runs
eq 'AC-11: после update ответ тот же' "$HTTP_BODY" "$runs_expected"

sql 'SELECT count(*) FROM kkd_issue'
issue_rows=$SQL_OUT
# Пересчёт с теми же правилами даёт те же ответы и то же число строк: разницу видно только по версиям строк
row_versions
versions_before=$SQL_OUT
sql 'SELECT now()'
before_restart=$SQL_OUT
must_dc "$LIMIT_COMPOSE" restart app
refresh_base
wait_until "$LIMIT_WAIT" 'сервис отвечает после перезапуска' service_answers
wait_until "$LIMIT_WAIT" 'первый такт после перезапуска' first_tick_done "$before_restart"
row_versions
eq 'AC-11, FR-8: перезапуск не переписал строки таблиц' "$SQL_OUT" "$versions_before"
get /api/runs
eq 'AC-11: после перезапуска ответ тот же' "$HTTP_BODY" "$runs_expected"
sql 'SELECT count(*) FROM kkd_issue'
eq 'AC-11: после перезапуска строк kkd_issue столько же' "$SQL_OUT" "$issue_rows"
sql 'SELECT count(DISTINCT (run_id, rule_code, object_id)) FROM kkd_issue'
eq 'AC-11: дублей в kkd_issue нет' "$SQL_OUT" "$issue_rows"

sql 'SELECT now()'
before_restart=$SQL_OUT
must_dc "$LIMIT_COMPOSE" down
must_dc "$LIMIT_COMPOSE" up -d --no-build
wait_seed_runs
wait_until "$LIMIT_WAIT" 'первый такт после повторного запуска' first_tick_done "$before_restart"
row_versions
eq 'AC-11, FR-16: повторный запуск с тем же томом не применил миграции и не переписал строки' "$SQL_OUT" "$versions_before"
get /api/runs
eq 'AC-11, FR-16: после повторного запуска ответ тот же' "$HTTP_BODY" "$runs_expected"

must_dc "$LIMIT_COMPOSE" down -v --remove-orphans
must_dc "$LIMIT_COMPOSE" run --rm migrate update-testing-rollback
must_dc "$LIMIT_COMPOSE" up -d --no-build
wait_seed_runs
get /api/runs
eq 'AC-11: после update-testing-rollback ответ тот же' "$HTTP_BODY" "$runs_expected"

# --- Шаг 10 -----------------------------------------------------------------

step '10. Сброс базы'

must_dc "$LIMIT_COMPOSE" down -v --remove-orphans
capture 30 docker compose ps -a -q || fail 'docker compose ps -a -q' 'код 0' 'команда не удалась'
eq 'контейнеров проекта не осталось' "$CAPTURED" ''

printf '\nВсе проверки пройдены: %s за %s мин %s с\n' "$CHECKS" "$((SECONDS / 60))" "$((SECONDS % 60))"
