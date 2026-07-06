# Фича: шаринг карточки слота

## Симптом / цель

На карточке слота (`SCR-003`) в хедере есть иконка «Поделиться», но она была
чисто декоративной — `onClick = {}`, без какой-либо логики
(`client/shared/src/commonMain/kotlin/com/volna/app/catalog/presentation/SlotDetailsScreen.kt`).

По дизайн-спеке ([SCR-003](../01-analysis/5-mobile-app-spec/SCR-003-slot-card.md),
решение RR-D06 в [design-review.md](../01-analysis/3-design-brief/design-review.md))
шаринг слота формально отложен на Phase 2 и в MVP не специфицировался — иконку
предлагалось либо скрыть, либо оставить неактивной. По запросу пользователя это
решение пересмотрено, и функция реализована в текущем MVP уже под Android.

> **Важно:** документы `SCR-003-slot-card.md` (AC-008, секция «Хедер») и
> `feature-list.md` (§7 «Не входит в MVP») по-прежнему фиксируют старое решение
> (RR-D06, «декор / Phase 2») и не были обновлены вместе с кодом — это
> сознательное расхождение с процессом, о котором стоит знать при следующей
> синхронизации спеки с реализацией.

## Решение

- Назначение фичи — **шеринг текстового описания слота** (без ссылки/deep
  link): дата и время, название маршрута, цена за место, место встречи. Ссылку
  на слот в приложении не прикладываем — deep link/universal link в проекте не
  реализован (подтверждено при анализе кода и `01-analysis/`), а по продуктовой
  спеке у приложения нет веб-версии для перехода по такой ссылке.
- Платформа в этой итерации — **только Android** (нативный share-sheet через
  `Intent.ACTION_SEND`). На iOS и Web иконка вызывает пустую реализацию — это
  сознательно, чтобы не расширять скоуп без явного запроса; при необходимости
  включается позже без изменения общего API (просто дописать `actual`).
- Платформенный доступ к share-sheet вынесен в отдельный модуль по тому же
  паттерну `expect`/`actual`, что уже используется в проекте для карт
  (`MapLauncher`/`PlatformMapLauncher`) и пуш-разрешений:
  - `client/shared/src/commonMain/kotlin/com/volna/app/share/ShareContracts.kt` —
    интерфейс `ShareLauncher` (`shareText(text: String)`) и
    `expect object PlatformShareLauncher`.
  - `client/shared/src/androidMain/kotlin/com/volna/app/share/PlatformShareLauncher.android.kt` —
    `actual`-реализация: `Intent.ACTION_SEND` + `Intent.createChooser`, контекст
    инициализируется один раз при старте приложения (`initialize(context)`),
    как и у `PlatformMapLauncher`.
  - `client/shared/src/iosMain/.../PlatformShareLauncher.ios.kt` и
    `client/shared/src/wasmJsMain/.../PlatformShareLauncher.wasm.kt` — пустые
    `actual`-заглушки (обязательны для компиляции всех целей KMP-проекта:
    Android/iOS/Web), реализации нет.
- `client/androidApp/src/main/kotlin/com/volna/app/android/MainActivity.kt` —
  добавлен вызов `PlatformShareLauncher.initialize(applicationContext)` рядом
  с аналогичной инициализацией `PlatformMapLauncher`.
- `SlotDetailsScreen.kt`: кнопка «Поделиться» теперь вызывает
  `shareLauncher.shareText(slot.toShareText())` (параметр `shareLauncher`
  с дефолтом `PlatformShareLauncher` — тот же приём, что и у `RouteMapSheet`
  с `MapLauncher`, для подмены в превью/тестах). Текст собирается функцией
  `Slot.toShareText()` из уже имеющихся на экране данных слота
  (`route.name`, `startAt` через существующий форматтер
  `toSlotCardStartText()`, `price.value`, `meetingPoint.title`) — без новых
  полей API.

Сборка проверена для всех трёх KMP-таргетов (`:shared:compileDebugKotlinAndroid`,
`:shared:compileKotlinIosSimulatorArm64`, `:shared:compileKotlinWasmJs`) и
собран/установлен debug APK на эмулятор — пользователь проверил кнопку вручную,
работает.

## Промпты пользователя по этой фиче

1. > на SCR-003 есть в правом верхнем углу иконка "поделиться". как она может
   > быть использована?

2. > давай сделаем ее (оформи ка отдельную фичу). назначение — шеринг ссылки/
   > карточки слота. после этого напиши md файл по примеру
   > FEATURE_slot-card-images.md. если будут вопросы, спрашивай

3. Уточняющие вопросы от ассистента и ответы пользователя:
   - Контент шаринга (текст vs текст+ссылка) → **«Только текст»**.
   - Платформы (все три vs только Android) → **«Только Android»**.

4. > насколько сложнее будет сделать это под все платформы сразу?
   (пользователь уточнял оценку трудозатрат перед тем, как принять решение
   ограничиться Android)

5. > я уже проверил вручную, работает. сделай md файл
