# Фича: экран успеха записи (BS-002) как полноценный экран навигации

## Симптом / цель

По дизайн-ревью (`RR-D03`, зафиксировано в `01-analysis/3-design-brief/design-review.md`
и в `01-analysis/5-mobile-app-spec/BS-002-booking-success.md`) экран «Вы записаны»
должен быть **полноэкранным экраном**, а не bottom sheet/оверлеем. Формально
вёрстка (`BookingSuccessSheet` в `BookingFormScreen.kt`) уже была на весь экран
(`Modifier.fillMaxSize()`), но архитектурно это был **оверлей поверх
`BookingFormScreen`**, управляемый условием `state.createdBooking != null`, а
не отдельный пункт навигации:

- нет собственного URL/route — нельзя вернуться на него системным back после
  ухода, не отображается отдельно в `NavHost`;
- системный back на этом состоянии был завязан на `BookingFormIntent.SuccessDismissed`
  (тот же интент, что закрывает форму), а не на навигацию;
  спека прямо требует «свободного закрытия свайпом/бэкдропом нет — уход только
  по двум кнопкам» — раньше это не было явно закреплено в коде, просто работало
  как побочный эффект отсутствия обработчика.

Отмечено как техдолг ранее в `todo_after_review.md` (пункт 9: «Переделать success
после записи из bottom sheet overlay в полноценный экран»).

## Решение

Чисто клиентская правка, без изменений backend/API.

1. **Новый пункт навигации** `BookingSuccessDestination` (`AppNavigation.kt`) —
   `data object`, без параметров: экран читает уже загруженный
   `BookingFormState.createdBooking`/`totalPrice` из существующего
   `BookingFormStore` (тот же паттерн, что уже используют `SlotBookingDestination`
   и другие "sub-destinations", читающие состояние соответствующего стора
   напрямую, а не через параметры маршрута).

2. **Новый эффект стора** `BookingFormEffect.BookingCreated` — отправляется из
   `BookingFormStore.submit()` сразу после успешного `createBooking` (в
   дополнение к уже существующей записи `createdBooking` в состояние). Эффект
   слушается в `VolnaApp.kt` и вызывает
   `navController.navigate(BookingSuccessDestination) { popUpTo<SlotBookingDestination> { inclusive = true } }`
   — экран оформления записи заменяется экраном успеха в бэкстеке, а не
   складывается поверх него.

3. **`BookingFormScreen.kt`**: убран `Box`-оверлей и параметры `onDone`/
   `onOpenBookings` — форма теперь ничего не знает про экран успеха. Приватная
   `BookingSuccessSheet` вынесена в публичный top-level `BookingSuccessScreen`
   (сама вёрстка не менялась — она и была полноэкранной).

4. **`MainTabs.kt`**: добавлен `composable<BookingSuccessDestination>` в
   `NavHost`. Если `createdBooking` неожиданно `null` (например, процесс убит
   и восстановлен без сохранённого состояния стора) — редирект на список
   слотов вместо краша/пустого экрана. Кнопки «Готово»/«Мои бронирования»
   работают как раньше (сброс `createdBooking`, обновление списков,
   `popUpTo<SlotsDestination>`), просто теперь это переходы между реальными
   экранами навигации, а не смена локального состояния оверлея.

5. **`VolnaApp.kt`** (system back): было
   `bookingFormState.createdBooking != null -> { bookingFormStore.accept(SuccessDismissed); true }`
   (уводило обратно на форму записи). Стало
   `currentDestination?.hasRoute<BookingSuccessDestination>() == true -> true`
   без вызова какого-либо интента — системный back **полностью проглатывается**
   на этом экране, что прямо соответствует требованию спеки «уход только по
   двум кнопкам» (раньше back фактически «телепортировал» на форму записи,
   что спеке не соответствовало).

6. **`isNavBarVisible`** в `MainTabs.kt`: параметр `bookingFormState` убран
   (стал не нужен), условие скрытия таб-бара переведено на проверку
   `currentDestination?.hasRoute<BookingSuccessDestination>()`.

## Почему не по-другому

- **Передавать `bookingId` параметром маршрута** и грузить бронь заново по
  сети — избыточно: `createBooking` уже возвращает полный объект брони, он уже
  лежит в сторе, повторный запрос не нужен (в спеке явно: «no network requests
  on open»).
- **Оставить оверлей, просто исправить проверку** — не даёт system-back
  экрану своей identity в NavHost/бэкстеке и не убирает архитектурную
  нестыковку («экран» без собственного route).

## Промпты пользователя по этой фиче

1. > нет, пока не будем это делать (про «Поделиться»). расскажи мне про MVP
   > проекта, следуя из этого подумаем, какую фичу делать дальше

2. (в ответ на предложенный список кандидатов) → выбор: **«Успех записи как
   экран»**.
