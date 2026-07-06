# Фича: push-уведомления (LOGIC-007)

## Симптом / цель

Push-напоминания о прогулке (FR-33, NFR-13, LOGIC-007) были полностью
не реализованы — ни на бэкенде, ни на клиенте. Это подтвердил аудит проекта
на предыдущей сессии (см. `FEATURE_slot-card-images.md`/обсуждение MVP): нет
`registerPushToken`/`deletePushToken`, нет `is_first_booking`/`reminder_hours`
в ответе `createBooking`, нет запроса системного разрешения на клиенте.

Задача — реализовать логику **строго по `LOGIC-007`**: системный запрос
разрешения показывается **один раз**, только после **первой** успешной брони,
на экране BS-002 («Вы записаны»), с ненавязчивой подводкой «Напомним за N
часов до старта»; отказ ничего не блокирует и не повторяется.

## Побочный эффект по ходу работы: откат полной регенерации OpenAPI

Push-эндпоинты (`registerPushToken`/`deletePushToken`) уже были в
`01-analysis/api/auth/api.yaml`, но backend ни разу не перегенерировал Go-код
из спеки после того, как эти эндпоинты (и заодно — access/refresh токены,
канонические коды ошибок и т.д.) были добавлены при QA-ревью. Полная
перегенерация (`go generate ./internal/http/openapi`) была выполнена один раз
для проверки — она вытащила **сразу оба** гэпа: push-токены и access/refresh
токены (`VerifyCodeResponse.Token` → `Tokens TokenPair`), поскольку оба
изменения жили в одном bundled `auth.yaml`.

**Решение с пользователем**: только push, регенерация отменена
(`git checkout -- backend/internal/http/openapi/`). Access/refresh токены —
отдельная, не начатая задача. Из-за этого push-эндпоинты и
`is_first_booking`/`reminder_hours` реализованы **вручную**, в обход
oapi-codegen, а не через обычный generate-пайплайн.

## Решение — Backend

- **Миграция** `backend/migrations/00003_push_tokens.sql` — таблица
  `push_tokens (id, client_id, token, platform, created_at)`, `UNIQUE(token)`
  (регистрация идемпотентна — повтор с тем же токеном переписывает
  `client_id`/`platform`, а не создаёт дубликат).
- **`backend/internal/service/push/service.go`** — `Service.Register`/`Delete`,
  резолвят клиента по bearer-токену тем же паттерном, что `profile`/`booking`
  сервисы (`ClientBySessionTokenHash` + `auth.HashToken`). Здесь же —
  канонический `push.ReminderHours = []int{24, 2}` (сервер владеет конфигом,
  клиент не хардкодит).
- **`backend/internal/storage/postgres/push_tokens.go`** — репозиторий,
  `Register` через `ON CONFLICT (token) DO UPDATE`, `Delete` — идемпотентный.
- **`backend/internal/http/handlers/push.go`** — `PushTokenRequest`/
  `PushTokenDeleteRequest` **написаны вручную** (не сгенерированы), с
  комментарием почему. Роуты `POST/DELETE /auth/push-tokens` подключены в
  `router.go` напрямую через новый интерфейс `httpapi.PushHandler` (не через
  `ServerInterface` конкретного домена) — `RouterOptions.Push`.
- **`is_first_booking`/`reminder_hours` в ответе `createBooking`**:
  - `postgres.BookingRepository.Create` теперь возвращает
    `(booking.Booking, bool, error)` — бул считается внутри той же
    транзакции (`isFirstBookingForClient`: есть ли у клиента другие брони,
    кроме только что созданной/идемпотентно повторной).
  - `booking.Service.Create` и `booking.Repository` — сигнатура обновлена;
    `service_test.go`'s `fakeRepo` подогнан.
  - `handlers/bookings.go` — новый **hand-written** тип
    `createBookingResponse` (анонимно встраивает `bookingsapi.Booking` +
    добавляет `is_first_booking`/`reminder_hours` в JSON — Go `encoding/json`
    поднимает поля встроенной структуры на верхний уровень).
- Все Go-тесты (`go test ./...`) прошли; backend пересобран и перезапущен в
  Docker; проверено вручную `curl`: создание первой брони действительно
  возвращает `is_first_booking: true, reminder_hours: [24, 2]`; повторная
  бронь — `is_first_booking: false`; `POST/DELETE /auth/push-tokens` —
  `204`, строка в БД появляется/пропадает.

## Решение — Client

Новый пакет `client/shared/src/commonMain/kotlin/com/volna/app/push/`:

- **`PushContracts.kt`** — `PushRepository` (register/delete через
  `VolnaApiClient.sendUnit`), `PushPreferences` (флаг
  `push_permission_requested` + локальный «токен устройства» — **не
  сбрасывается при логауте**, хранится отдельно от `SessionStorage`, ровно
  как требует LOGIC-007: «флаг не сбрасывается между сессиями»),
  `PlatformPushPermission` expect-объект (`platform: "ios"/"android"/"web"` +
  `requestPermission()`).
- **`data/KtorPushRepository.kt`** — `POST`/`DELETE /auth/push-tokens`.
- **Android** (`androidMain/.../push/PlatformPush.android.kt`) — **реальный**
  системный диалог: `POST_NOTIFICATIONS` (Android 13+) через
  `ComponentActivity.registerForActivityResult`, регистрируется в
  `MainActivity.onCreate` (до `setContent`, как того требует Activity
  Result API). Ниже Android 13 разрешение не нужно — сразу `Authorized`.
  Добавлено `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`
  в манифест.
- **iOS** (`iosMain/.../PlatformPush.ios.kt`) — `UNUserNotificationCenter
  .requestAuthorizationWithOptions` — тоже реальный API, но **не проверено
  вживую** (в этом окружении нет macOS/Xcode для сборки iOS-таргета).
- **Web** (`wasmJsMain/.../PlatformPush.wasm.kt`) — стаб, всегда `Denied`:
  LOGIC-007 прямо ограничивает пуш нативными приложениями («работает в
  нативном мобильном приложении (iOS + Android)»), у веба в MVP пуша нет.
- **«Токен устройства» — демо-заглушка.** Реального FCM/APNs-проекта нет
  (аналогично тому, как OTP-код возвращается в ответе API вместо реальной
  SMS, а карта — схематичная вместо тайлов). `generateRandomPushToken()`
  генерирует случайную стабильную (персистентную) строку один раз на
  устройство/платформу — этого достаточно, чтобы честно проверить весь
  контракт (запрос разрешения → регистрация → снятие при логауте), не
  разворачивая реальную пуш-инфраструктуру.

### Wiring в существующий MVI-код

- `Booking` (домен) и `BookingDto` получили `reminderHours: List<Int>?` —
  ровно туда же, где уже жило `isFirstBooking` (оно было частично
  реализовано ранее, просто не имело back-end подложки — теперь есть).
- `BookingFormStore`: новый интент `RequestPushPermission`. Обработчик —
  `requestPushPermissionIfNeeded()`: не срабатывает, если это не первая
  бронь либо разрешение уже запрашивалось (проверка через
  `PushPreferences`, не через сетевой запрос — оффлайн-safe). При согласии —
  `deviceToken()` + `registerToken` + сохранение `registeredToken`.
- `BookingSuccessScreen` (`BookingFormScreen.kt`): `LaunchedEffect(booking.id)`
  вызывает `onRequestPushPermission()` **при появлении экрана** — так
  подводка «Напомним за 24 и 2 ч. до старта» (показывается только если
  `isFirstBooking == true` и `reminderHours` не пусто, по AC-007) физически
  видна на экране в момент/до появления системного диалога. Бизнес-логика
  «спрашивать один раз» осталась в сторе (тестируемо), а не в Composable.
- `MainTabs.kt` — прокинут `onRequestPushPermission` в
  `composable<BookingSuccessDestination>`.
- `ProfileStore.logout()` — перед вызовом `authRepository.logout()` снимает
  зарегистрированный push-токен (`deleteRegisteredPushToken()`, best-effort,
  ошибки игнорируются) — LOGIC-007: «при выходе из аккаунта... токен
  снимается».
- DI (`AppModule.kt`) — `PushRepository`/`PushPreferences` как синглтоны;
  `BookingFormStore`/`ProfileStore` получили новые зависимости.

### Не реализовано (сознательно, вне скоупа)

- **Реальная доставка пушей** (FCM/APNs сервер, планировщик напоминаний за
  24ч/2ч) — по спеке это ответственность «существующей инфраструктуры», не
  клиентского приложения и не предмет этой правки.
- **Ссылка-подсказка «Включить в настройках телефона»** при отказе (AC-009,
  допустимо, но не обязательно) — не добавлена, чтобы не расширять скоуп;
  кандидат для отдельного follow-up.
- iOS-путь не запускался вживую (нет Xcode в этом окружении) — код написан
  по актуальному Kotlin/Native API UserNotifications, но не подтверждён
  компиляцией/тестом на реальном iOS-таргете.

## Проверка

- `go build ./... && go vet ./... && go test ./...` — зелёные.
- Backend пересобран и перезапущен в Docker; миграция `00003` применена.
- `curl`-сценарий: OTP-логин → создание первой брони →
  `is_first_booking: true, reminder_hours: [24, 2]` → `POST /auth/push-tokens`
  (204) → строка в `push_tokens` подтверждена `psql` → `DELETE` (204) →
  строка пропала.
- `client/shared:allTests` — все тесты общего модуля прошли.
- `androidApp:assembleDebug` — собран, установлен и запущен на эмуляторе.

## Промпты пользователя по этой фиче

1. > сделай push уведомления как указано в спеке. пиши md файл по этой фиче

2. (после обнаружения побочного эффекта регенерации OpenAPI, затрагивающего
   access/refresh токены) → выбор: **«Только push, откатить регенерацию»**.
