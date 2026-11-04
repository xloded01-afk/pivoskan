# Citywide Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Исправить ограничение Магнита одним адресом и получить автоматический обход доступных магазинов четырёх сетей на телефоне с обычными ценами, объединёнными товарами и честным отчётом покрытия.

**Architecture:** Общий координатор запускает независимые очереди прямых и браузерных источников, а репозиторий атомарно хранит ассортимент каждого адреса. Один идентификатор запуска контролирует отмену и допустимость записей; группировка и минимум вычисляются отдельно от загрузки. Магнит и городской Fix Price поставляются раньше браузерных подключений, возможности которых сначала проверяются на Android.

**Tech Stack:** Kotlin/JVM 17, Android XML/ViewBinding, minSdk 24, targetSdk 35, existing AndroidX/Gson/JUnit, HTTPS, WebView, AtomicFile, Node.js для JS-тестов.

**Spec:** `docs/superpowers/specs/2026-11-04-citywide-catalog-design.md` (согласовано 4 ноября 2026 года).

## Global Constraints

- Без отдельного сервера; компьютер не нужен для использования приложения.
- Сканирование выполняется только пока приложение открыто, без фонового обхода при сворачивании или выключенном экране.
- После выбора города обход запускается автоматически.
- При повторном открытии приложения автоматически начинается новый обход.
- Участвуют Магнит, Дикси, Пятёрочка и Fix Price.
- Минимум считается по цене, доступной без карты лояльности.
- Нельзя подменять магазин соседним адресом или общим городским прайсом.
- Сохраняется Kotlin/XML, пакет `ru.pivoskan.app`, minSdk 24 и targetSdk 35.
- Используются города существующего `CityCatalog`; расширение географии отдельно не запрошено.
- Сбой Дикси или Пятёрочки не препятствует проверке и поставке исправления Магнита.
- Приложение не считывает пароли; cookies/токены не экспортируются. Никакого автоматического решения проверок сайта.
- Только проверенный полный ассортимент заменяет старый; симуляция не доказывает работоспособность сайта на телефоне.

## Review Focus

1. Сохранённая точка Кудаковского и вторая страница адресов: не ограничивают обход Магнита (задачи 2, 4).
2. Отмена во время ответа/записи и немедленная смена города: старый запуск не загрязняет новый кеш (задача 3).
3. Поворот, переход к товару/входу, выключение экрана: внутренний переход не создаёт дубль, настоящая потеря видимости прекращает сбор (задача 5).
4. Успешный вход, но неподтверждённый адрес/пустой DOM: не считается полученным ассортиментом и не удаляет прошлые цены (задачи 3, 6).
5. Дешёвая старая/городская/карточная цена и похожая упаковка: не создают ложного актуального победителя (задачи 7, 8).

---

## Выполнение и границы этапов

Это один связанный план: источники, хранение и выдача используют общий контракт.
Задачи 1–5 образуют самостоятельно проверяемое исправление Магнита; задача 6
подключает браузерные сети; задачи 7–8 дают объединённые карточки; задача 9 завершает поставку.
Не откладывать исправление Магнита из-за недоступного входа в другую сеть.
У неизвестных контрактов сайта есть явный этап проверки, а не выдуманные endpoint/селекторы.
Отрицательный результат этой проверки допускает только явный ограниченный режим.

Работать в подходящем текущем checkout; проверить `git status` и инструкции репозитория.
Новые зависимости для продуктового кода не требуются. Не менять SDK/Kotlin/AGP в этой работе.
Пути ниже относительны корню проекта; каталоги `app/src/main/java/ru/pivoskan/app/`
и `app/src/test/java/ru/pivoskan/app/` далее обозначены `MAIN/` и `TEST/` только для таблицы.
В каждом задании Files содержит полные пути относительно репозитория.

### Карта файлов и ответственности

| Файлы | Ответственность |
|---|---|
| `MAIN/data/scan/ScanModels.kt`, `ScanPolicy.kt`, `CitySource.kt` | Общие контракты, охват, ошибки, конечные лимиты |
| `MAIN/data/scan/ScanDiagnostics.kt` | Локальный отчёт из разрешённых полей |
| `MAIN/data/remote/MagnitSource.kt`, новый `MagnitCitySource.kt`, `FixPriceCitySource.kt` | Прямые адаптеры, полная пагинация, адресная достоверность |
| `MAIN/data/scan/CityScanRepository.kt`, `CityScanCodec.kt`, `LegacyCatalogMigration.kt` | Новый адресный кеш, миграция, проверка поколения записи |
| `MAIN/data/scan/CityScanCoordinator.kt` | Две последовательные очереди, независимость сетей |
| `MAIN/data/scan/ScanVisibility.kt`, `PivoskanApplication.kt`, `data/CatalogViewModel.kt` | Жизненный цикл и единственный запуск |
| `MAIN/browser/BrowserSourceSession.kt`, `BrowserCatalogAdapter.kt`, `DixyBrowserAdapter.kt`, `PyaterochkaBrowserAdapter.kt` | Вход, смена точки, извлечение полного проверенного каталога |
| `MAIN/data/ProductIdentity.kt`, `data/OfferQuery.kt`, `data/Models.kt` | Сопоставление, свежесть, обычный минимум, данные изображений |
| `MAIN/ui/BeerAdapter.kt`, `CompareAdapter.kt`, новый `ProductImageLoader.kt`, XML | Раскрываемые товарные карточки и изображения |
| `MainActivity.kt`, `BeerDetailsActivity.kt`, `CatalogBrowserActivity.kt` | Подключение общего состояния вместо отдельных запусков |
| `docs/verification/2026-11-04-citywide-source-capabilities.md` | Факты проверок источников и ограничения |

### Проверки и окружение

В PowerShell перед Gradle использовать имеющийся JDK/SDK (пути не сохранять в исходниках):

```powershell
$env:JAVA_HOME='C:/Users/diygxjs/AppData/Local/Temp/opencode/android-build/jdk17'
$env:ANDROID_HOME='C:/Users/diygxjs/AppData/Local/Temp/opencode/android-build/sdk'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
```

Далее `UT <pattern>` означает точную команду:
`./gradlew.bat :app:testDebugUnitTest --tests '<pattern>' '-PpivoskanBuildDir=C:/Users/diygxjs/AppData/Local/Temp/pivoskan-build' --offline --no-daemon`.
Ожидаемый успех: `BUILD SUCCESSFUL` и ноль failures/errors в новых XML-отчётах
`C:/Users/diygxjs/AppData/Local/Temp/pivoskan-build/app/test-results/testDebugUnitTest/`.
Отсутствие зависимости/SDK не считается ожидаемым падением RED-теста.
Живые ответы/личные данные держать в игнорируемой `.superpowers/`; в fixtures — только очищенные данные.

## Task 1: Контракты обхода, диагностический отчёт и доказательства Магнита

**Files:**
- Create: `app/src/main/java/ru/pivoskan/app/data/scan/ScanModels.kt`, `ScanPolicy.kt`, `CitySource.kt`, `ScanDiagnostics.kt` в том же каталоге.
- Create: `app/src/test/java/ru/pivoskan/app/data/scan/ScanDiagnosticsTest.kt`.
- Create: `docs/verification/2026-11-04-citywide-source-capabilities.md`.
- Modify: `app/src/test/java/ru/pivoskan/app/data/LiveCatalogSmoke.kt`.

**Interfaces:** Consumes existing `City`, `RemoteShop`, `Offer`, `PriceScope`, `HttpTransport`.
Produces the following types in package `ru.pivoskan.app.data.scan` (no Android dependencies):

```kotlin
data class ScanRun(val id: String, val cityId: String, val startedAt: Long)
data class CatalogKey(val cityId: String, val sourceId: String, val shopId: String?) // null = CITY_CATALOG
enum class ProblemKind { NETWORK, HTTP, NEEDS_LOGIN, NEEDS_ACTION, UNSUPPORTED, INVALID_DATA, LIMIT, STORAGE, CANCELLED }
data class SourceProblem(val kind: ProblemKind, val stage: String, val code: String)
data class DiscoveryResult(val shops: List<RemoteShop>, val scope: PriceScope,
    val complete: Boolean, val excludedCount: Int, val problem: SourceProblem?)
sealed interface CatalogResult {
    data class Complete(val key: CatalogKey, val offers: List<Offer>, val checkedAt: Long) : CatalogResult
    data class Failed(val key: CatalogKey, val problem: SourceProblem) : CatalogResult
}
data class StoredCatalog(val key: CatalogKey, val offers: List<Offer>, val checkedAt: Long, val runId: String?)
enum class SourcePhase { WAITING, RUNNING, NEEDS_LOGIN, NEEDS_ACTION, COMPLETE, PARTIAL, UNAVAILABLE, CANCELLED }
data class SourceProgress(val sourceId: String, val discovery: DiscoveryResult?,
    val phase: SourcePhase, val results: Map<CatalogKey, SourceProblem?>, val current: CatalogKey?)
data class CityScanSnapshot(val cityId: String, val runId: String?,
    val catalogs: List<StoredCatalog>, val sources: List<SourceProgress>)
fun interface Cancellable { fun cancel() }
enum class SourceLane { DIRECT, WEB }
interface CitySource {
    val id: String
    val lane: SourceLane
    fun discover(city: City, done: (DiscoveryResult) -> Unit): Cancellable
    fun load(city: City, shop: RemoteShop?, done: (CatalogResult) -> Unit): Cancellable
}
```

`results[key] == null` means successful complete catalog, including confirmed empty;
absent key means not yet completed. `SourceProgress.current` identifies an active load.
`ScanPolicy` constants: discoveryPageSize=100, maxDiscoveryPages=100,
maxShopsPerSource=10000, maxGoodsPages=100, maxOffersPerShop=5000,
directConnectMs=10000, directReadMs=15000, browserStepMs=45000,
browserShopMs=180000, activeRunMs=2700000 (45 min excluding time awaiting user input),
maxTransientRetries=1, retryDelayMs=1000, minimumRequestGapMs=500,
maxJsonBytes=8388608. Limits always imply PARTIAL, never full success.
`ScanDiagnosticEvent(runId: String, sourceId: String, shopId: String?, stage: String,
code: String, at: Long, count: Int)` contains only
public IDs, fixed stage/code vocabulary and numbers; no URL/body/free-form exception.
`ScanDiagnostics.export(events: List<ScanDiagnosticEvent>, appVersion: String,
androidVersion: String, webViewVersion: String): String` returns JSON for user export.

- [ ] **1. Write tests** `exportContainsOnlyAllowedFields` and `limitsAreFinite`:
  `assertEquals(setOf("runId","sourceId","shopId","stage","code","at","count"), eventJson.keySet())`;
  `assertEquals(1, ScanPolicy.maxTransientRetries)`; `assertEquals(2700000L, ScanPolicy.activeRunMs)`.
  Construct an event with public shop ID `502731`; ensure output has no cookie/header/body fields.
- [ ] **2. Run RED:** `UT ru.pivoskan.app.data.scan.ScanDiagnosticsTest`; expected missing new types.
- [ ] **3. Implement contracts/export** in the named files, retain pure value types, fixed diagnostic code mapping at source boundaries.
- [ ] **4. Gather Magnit evidence:** use existing smoke runner with a chosen city and two discovered distinct shops; record list pagination (`totalCount`, raw row count, next offset), explicit format support, storeCode echoed by goods, complete goods pagination and ordinary-price semantics. The existing store fixture contains one row but `totalCount=63`; it is a sample, not evidence of a complete one-store city. Add no fabricated live results. If network fails, record failure and leave the live criterion pending; deterministic work proceeds.
- [ ] **5. Run GREEN:** the same UT command; inspect the evidence document for timestamps, device/network context and a separate fact/inference/untested column. Extend existing smoke output to assert two distinct shop IDs when available, never choose a guessed street.
- [ ] **6. Commit** only the named files: `git commit -m "feat: define city scan contracts and diagnostics"` after explicit `git add` of them.

## Task 2: Все страницы магазинов Магнита и адресные прямые адаптеры

**Files:**
- Modify: `app/src/main/java/ru/pivoskan/app/data/remote/MagnitSource.kt`, `HttpsTransport.kt`, `HttpTransport.kt`.
- Create: `app/src/main/java/ru/pivoskan/app/data/remote/MagnitCitySource.kt`, `FixPriceCitySource.kt`.
- Modify: `app/src/test/java/ru/pivoskan/app/data/remote/MagnitSourceTest.kt`.
- Create: `app/src/test/java/ru/pivoskan/app/data/remote/MagnitDiscoveryTest.kt`, `DirectCitySourceTest.kt`.
- Create: `app/src/test/resources/remote/magnit-stores-page-1.json`, `magnit-stores-page-2.json`, `magnit-stores-empty.json`.

**Interfaces:** consumes Task 1. Add `MagnitSource.discover(city: City): DiscoveryResult`;
retain `shops(city): List<RemoteShop>` only as compatibility wrapper, not the new coordinator API.
Retain `offers(city, shop): List<Offer>` with all-or-error semantics. `MagnitCitySource`
and `FixPriceCitySource` implement `CitySource`; callbacks occur exactly once unless cancelled.
Transport factory creates a separate cancellable transport for each source operation;
`CancellableHttpTransport : HttpTransport` adds `cancel(): Unit`. `HttpsTransport`
implements it by disconnecting its active connection in addition to interrupt checks.
Never cancel another source's transport. Fix Price `load(city, null)` produces city scope only.

- [ ] **1. Write tests** `discoversAllPagesBeforeCityFiltering`, `repeatedPageIsPartial`,
  `foreignStockFailsWholeShop`, `cancelDisconnectsOnlyItsOperation`, `fixIsNeverAddressScoped`.
  Using existing `QueueHttp` pattern, construct page1=100 raw rows and page2=1, totalCount=101;
  assert requested offsets `[0,100]`, second-page city match retained, no arbitrary `.take(100)`;
  repeated IDs without progress => `assertFalse(result.complete)`;
  foreign storeCode => whole load failure; Fix key => `assertNull(key.shopId)`.
  Update the old one-row store fixture test to test a partial list or set its synthetic totalCount=1 explicitly.
- [ ] **2. Run RED:** `UT ru.pivoskan.app.data.remote.*`; expect absent discovery/cancellation API or assertions failing against one-page behavior.
- [ ] **3. Implement discovery** advancing by raw rows/verified source pagination, not city-filtered rows;
  preserve totalCount consistency, deduplicate IDs, reject conflicting ID/address entries.
  If totals shrink/change or page repeats, preserve discovered points but mark incomplete.
  Request only supported official formats verified in Task 1. Explicitly label MM-only scope if other formats remain unverified; never claim every Magnit format.
- [ ] **4. Implement direct adapters/cancellation** and policy limits. Only one retry for timeout/I/O/5xx;
  zero automatic retries for 401/403/429. Ordinary prices require confirmed source semantics;
  unknown/card-only conditions do not become ordinary prices. Address errors are isolated;
  catalog truncation is failure, never `Complete`. Map failures to fixed diagnostic codes.
- [ ] **5. Run GREEN:** the same command; all remote tests pass, including existing wrong-city/kopeck assertions.
- [ ] **6. Commit:** `git commit -m "fix: discover and load multiple Magnit shops"` with only these files staged.

## Task 3: Адресный кеш и атомарная отмена записи

**Files:**
- Create: `app/src/main/java/ru/pivoskan/app/data/scan/CityScanRepository.kt`, `CityScanCodec.kt`, `LegacyCatalogMigration.kt`.
- Modify: `app/src/main/java/ru/pivoskan/app/data/CatalogRepository.kt`, `CatalogImports.kt`, `CatalogImportViewModel.kt`.
- Create: `app/src/test/java/ru/pivoskan/app/data/scan/CityScanRepositoryTest.kt`, `LegacyCatalogMigrationTest.kt`.

**Interfaces:** `CityScanRepository.begin(cityId: String): ScanRun`, `invalidate(run: ScanRun): Unit`,
`read(cityId: String): CityScanSnapshot`, `commit(run: ScanRun, result: CatalogResult.Complete): Boolean`,
`recordSource(run: ScanRun, progress: SourceProgress): Boolean`.
Inject clock, ID supplier and `CityScanDisk.read(cityId): String? / write(cityId, json): Unit`
for deterministic tests; production disk uses AtomicFile. `CityScanCodec.encode/decode`
accepts/returns `CityScanSnapshot`, schema=2. `LegacyCatalogMigration.decode(json, cityId)`
returns valid individual `StoredCatalog` entries from schema1, with `runId=null`.

- [ ] **1. Write tests** `twoMagnitShopsSurviveRestart`, `cancelledRunCannotCommit`,
  `newCityRejectsLateOldReply`, `confirmedEmptyReplacesOnlyItsKey`, `partialFailureKeepsOld`,
  `migrationKeepsValidRowsWhenOneIsInvalid`, `diskFailureDoesNotPublishFreshMemory`.
  Assertions: two keys `502731`/`test-second` both retained; `assertFalse(repo.commit(oldRun, late))`;
  second-city read has zero first-city offers; empty Complete removes only its key's offers;
  failed atomic write leaves previously decoded bytes and no fresh state.
  Use temporary JVM directories/fake disk and latches, not timing sleeps.
- [ ] **2. Run RED:** `UT ru.pivoskan.app.data.scan.*`; expected absent repository/codec.
- [ ] **3. Implement repository/codec** under a shared generation-and-write lock: begin/invalidate and
  commit validation cannot interleave between checking run identity and atomic persistence.
  A write already inside the lock may finish before invalidate returns. Never hold this lock during network work.
  Validate city/source/shop/provenance and nonnegative timestamps; cache paths derive from city catalog IDs.
  Distinguish no record from a fully empty catalog. Bound JSON input with ScanPolicy.
- [ ] **4. Implement migration/import bridge:** read old root and per-Magnit-shop files, validate rows individually,
  preserve each timestamp, resolve duplicates by latest valid timestamp, never invent an address.
  Store under `live-catalog-v2`, leave v1 untouched. New MainActivity must not use old
  selected-shop projection. Complete manual Dixy imports use a separately validated non-current-run write:
  add `importVerified(catalog: StoredCatalog): Unit`, force `runId=null`, and serialize it through
  the same lock. Partial manual pages use `mergeManualOffers(cityId: String, offers: List<Offer>): Boolean`:
  retain them as historical only when no complete catalog exists for that key, merge by offer ID without
  deleting absent products; if a complete catalog exists, return false and explain that a complete refresh
  is required. Add `StoredCatalog.complete: Boolean = true` and persist false for this partial case.
  Manual pages never count as a completed shop and never create a fresh minimum.
- [ ] **5. Run GREEN:** `UT ru.pivoskan.app.data.scan.*` and `UT ru.pivoskan.app.data.CatalogImportsTest`;
  verify stale manual import cannot overwrite a newer completed shop (compare checkedAt).
- [ ] **6. Commit:** `git commit -m "feat: persist city catalogs per source and shop"` with listed files staged.

## Task 4: Единый обход и независимые результаты сетей

**Files:**
- Create: `app/src/main/java/ru/pivoskan/app/data/scan/CityScanCoordinator.kt`.
- Modify: `app/src/main/java/ru/pivoskan/app/data/CatalogViewModel.kt`, `MainActivity.kt`.
- Modify: `app/src/main/java/ru/pivoskan/app/BeerDetailsActivity.kt`, `data/scan/ScanModels.kt`, `data/scan/CityScanCodec.kt`.
- Modify: `app/src/main/res/layout/activity_main.xml`, `app/src/main/res/values/strings.xml`.
- Create: `app/src/test/java/ru/pivoskan/app/data/scan/CityScanCoordinatorTest.kt`.

**Interfaces:** `CityScanCoordinator.start(city: City): ScanRun`, `cancel(): Unit`,
`resumeSource(sourceId: String): Unit`; ViewModel exposes `state: LiveData<CityScanSnapshot>`;
coordinator exposes `onSnapshot: (CityScanSnapshot) -> Unit` to stay JVM-testable.
Constructor consumes repository, `List<CitySource>`, clock and injected scheduler.
`CatalogViewModel.selectCity(cityId: String)`, `refresh()`, `cancel()`,
`resumeSource(sourceId: String)` replace the shopId-dependent load path.

- [ ] **1. Write tests** with controllable fake CitySource instances:
  `savedKudakovskogoPreferenceDoesNotRestrictQueue`: every discovered Magnit ID loaded once;
  `dixyNeedsLoginDoesNotBlockMagnit`: phase NEEDS_LOGIN plus successful Magnit second shop;
  `oneBadShopKeepsOtherShops`: one failed result plus two committed catalogs;
  `incompleteDiscoveryCannotReportComplete`: `assertEquals(PARTIAL, phase)` even if found shops succeeded;
  `runLimitMarksRemaining`: unvisited keys remain uncompleted, no fabricated success;
  `confirmedRemovedShopExcludedOnlyAfterCompleteDiscovery`.
- [ ] **2. Run RED:** `UT ru.pivoskan.app.data.scan.CityScanCoordinatorTest`; missing coordinator/behavior.
- [ ] **3. Implement queue:** at most one DIRECT operation and one WEB operation, Fix city catalog scheduled
  before long Magnit queue. Results published after each verified commit. Browser action-needed suspends
  only its source. Global active-run timer pauses only when no executable source work exists and user input
  is awaited; it resumes when work does. Apply network gap/retry and limits from Task 1.
  Full discovery allows excluding disappeared points from current comparisons; incomplete discovery keeps old
  points explicitly historical. Store this eligibility in the snapshot via new
  `StoredCatalog.active: Boolean = true` (codec default true for migrated entries).
- [ ] **4. Integrate initial UI:** display aggregate and per-source counts, current address, cancel/retry and
  fixed Russian error messages. Replace the Magnit selection action with a list of scan results;
  keep historical preference solely for migration, never an input filter.
  Provide unavailable placeholders for unverified browser sources, not fake adapters.
  Old `CatalogLoader` is not invoked by this flow; legacy manual screens must not force its refresh on return.
  Adapt BeerDetailsActivity now to resolve offer ID and alternatives from CityScanRepository; opening a
  new multi-shop offer must work at Milestone A, before grouped cards are implemented in Task 8.
- [ ] **5. Run GREEN:** coordinator tests plus `UT ru.pivoskan.app.data.*`; inspect an offline two-shop fake
  run and a direct live run if available. No regression to single-store cache on opening details.
- [ ] **6. Commit:** `git commit -m "feat: coordinate independent citywide source scans"` with explicit file staging.

## Task 5: Автоматический запуск только в видимом приложении

**Files:**
- Create: `app/src/main/java/ru/pivoskan/app/PivoskanApplication.kt`, `data/scan/ScanVisibility.kt`.
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/java/ru/pivoskan/app/MainActivity.kt`,
  `CityPickerActivity.kt`, `BeerDetailsActivity.kt`, `CatalogBrowserActivity.kt`, `data/CatalogViewModel.kt`.
- Create: `app/src/test/java/ru/pivoskan/app/data/scan/ScanVisibilityTest.kt`.
- Create: `docs/verification/citywide-device-checklist.md`.

**Interfaces:** pure `ScanVisibility` accepts `activityStarted(id: String)`,
`activityStopped(id: String, changingConfiguration: Boolean)`, `screenInteractive(value: Boolean)`
and emits `VisibilityTransition.VISIBLE/HIDDEN` only when effective visibility changes.
Application owns one coordinator and registers ActivityLifecycleCallbacks plus screen state receiver.
ViewModels observe it without retaining Activity/WebView in Application; browser host uses attach/detach handles.

- [ ] **1. Write tests** `rotationKeepsSameRun`, `internalLoginReturnKeepsSameRun`,
  `screenOffCancelsAndScreenOnVisibleRestarts`, `backgroundReturnStartsExactlyOnce`,
  `cancelDoesNotImmediatelyRestart`, `cityPickerStartsOnlyChosenCity`.
  Drive ordered lifecycle events for overlapping activities; assert run ID equal on internal transitions,
  different after genuine hidden→visible, and zero new source requests after HIDDEN.
- [ ] **2. Run RED:** `UT ru.pivoskan.app.data.scan.ScanVisibilityTest`; absent state machine.
- [ ] **3. Implement visibility:** count started activities rather than only MainActivity.onResume;
  rotation explicitly detaches/cancels the browser's current operation without starting another city run,
  then retries that uncommitted step on the new host within the same run. Request scheduling is disabled
  during the configuration gap. Screen-off stops immediately even if an activity is still started.
  Recheck PowerManager.isInteractive before issuing requests. No service, WorkManager or wake lock.
  On HIDDEN invalidate run before cancelling handles; late callbacks cannot write. On real return,
  start once for saved city; internal picker changes trigger exactly one replacement run.
- [ ] **4. Wire browser teardown:** abort JS fetches and stop WebView loading before detach.
  Do not assume WebView.onPause stops JavaScript. Destroy inactive browser host and rebuild when needed;
  ordinary cookies survive, operation state does not. Apply this to the manual browser as well.
- [ ] **5. Run GREEN:** same UT, then record device checklist: Home, screen lock, rotation, details/back,
  login/back, city change, cancel, process death. A fresh process reads saved prices and starts a fresh run.
  Actual site transfer cancellation must be checked on Android; JVM event tests alone are insufficient.
- [ ] **6. Commit:** `git commit -m "feat: scan automatically only while app is visible"`.

**Milestone A:** Магнит получает несколько адресов, имеет отчёт полного/частичного обнаружения,
перестаёт зависеть от Кудаковского; Fix Price остаётся явно городским. Провести задачи 9.1–9.3
перед отдельной тестовой поставкой этого этапа, даже если задача 6 ещё не подтверждена.

## Task 6: Проверенные браузерные подключения Дикси и Пятёрочки

**Files:**
- Create: `app/src/main/java/ru/pivoskan/app/browser/BrowserCatalogAdapter.kt`, `BrowserSourceSession.kt`,
  `DixyBrowserAdapter.kt`, `PyaterochkaBrowserAdapter.kt`.
- Modify: `app/src/main/java/ru/pivoskan/app/CatalogBrowserActivity.kt`, `DixyCityScanActivity.kt`,
  `DixyCityScanModel.kt`, `DixyCityScanState.kt`, `data/remote/DixyCatalogParser.kt`, `data/remote/DixyShops.kt`.
- Modify: `app/src/main/assets/DixyCityScan.js`, `dixy-catalog.js`.
- Create only after verification: `app/src/main/assets/pyaterochka-catalog.js`.
- Modify: `docs/verification/2026-11-04-citywide-source-capabilities.md`.
- Create: `app/src/test/java/ru/pivoskan/app/browser/BrowserSourceSessionTest.kt`,
  `app/src/test/java/ru/pivoskan/app/data/remote/PyaterochkaCatalogParserTest.kt`,
  `app/src/test/js/browser-source-session.test.cjs`.

**Interfaces:** `BrowserSourceSession` implements CitySource using a `BrowserCatalogAdapter`:
`sourceId: String`, `entryUrl: String`, `allowedOrigin(url: String): Boolean`,
`discoverScript(): String`, `selectScript(shop: RemoteShop): String`, `extractScript(): String`,
`parseDiscovery(json: String, city: City): DiscoveryResult`,
`parseComplete(json: String, city: City, expectedShop: RemoteShop): CatalogResult`.
Add `PyaterochkaCatalogParser.parseComplete(json, city, expectedShop)` with the same result contract;
inputs are a normalized browser snapshot, not an invented private API.
`BrowserSourceSession.attach(webView: WebView)` / `detach()` are UI-thread only;
`resumeSource` rechecks actual catalog readiness instead of trusting an activity result.

- [ ] **1. Verify feasibility before implementation:** on real Android, user opens official site and enters
  account credentials themselves. For each network record two distinct public shops, selection confirmation,
  header/store ID evidence, ordinary price fields, next-page/complete evidence and product image host.
  Verify official redirect/login origin chain; add only observed HTTPS origins, never a broad arbitrary wildcard.
  Check available official address discovery for Fix Price too; if city-only is the outcome, document it.
  If verification fails, keep that source explicitly unavailable/needs-action and continue other tasks;
  do not invent selectors, silently substitute one store or label the feature complete.
- [ ] **2. Write failing tests** using sanitized normalized snapshots from the successful checks:
  `loginClosedIsNotAuthenticated`, `wrongHeaderAddressFails`, `emptyDomDoesNotClearCache`,
  `expiredSessionSuspendsOnlyItsSource`, `catalogCompleteEmptyIsDistinct`, `lateJsResultIgnored`,
  `optionalAnalytics403IsNotCatalogFailure`, `sourceLogoutDoesNotEraseOtherSource`.
  Assertions: closed login => NEEDS_LOGIN; wrong address => INVALID_DATA; no cache commit;
  unsupported address => error for that key, then next supported shop; CAPTCHA => NEEDS_ACTION without retry.
- [ ] **3. Run RED:** `UT ru.pivoskan.app.browser.*` and `node app/src/test/js/browser-source-session.test.cjs`;
  when Pyaterochka is verified, also `UT ru.pivoskan.app.data.remote.PyaterochkaCatalogParserTest`.
- [ ] **4. Implement adapter/session** with serialized select→fresh catalog→confirmed address→all pages→commit.
  Use regular website interactions and WebView cookies without reading credentials into application storage.
  Every callback carries operation ID and ScanRun, limits are from ScanPolicy. User can open login/verification
  or logout for one network; closing login does not stop direct sources. If logout cannot be observed reliably,
  mark source NEEDS_LOGIN and explain session state; never indiscriminately clear all WebView cookies.
  Document source-only or city-only capability honestly. Confirmed complete-empty requires explicit source evidence.
- [ ] **5. Integrate into coordinator:** old Dixy standalone screen delegates to the common session/coordinator;
  it cannot create a second scanner. Background/session detach follows Task 5. Add a user-triggered report export
  using ScanDiagnostics. Existing manual import remains labeled partial and cannot overwrite a full shop catalog.
- [ ] **6. Run GREEN:** preceding commands plus both existing Dixy JS suites and Dixy parser tests;
  repeat live two-address verification after integration, with all four source states visible.
- [ ] **7. Commit:** `git commit -m "feat: integrate verified browser catalog sources"`; release notes list only capabilities actually verified.

## Task 7: Идентичность товара, обычные цены и честный минимум

**Files:**
- Modify: `app/src/main/java/ru/pivoskan/app/data/Models.kt`, `OfferQuery.kt`, `remote/ProductNames.kt`,
  `remote/MagnitSource.kt`, `remote/FixPriceSource.kt`, `remote/DixyCatalogParser.kt` and verified Pyaterochka parser.
- Create: `app/src/main/java/ru/pivoskan/app/data/ProductIdentity.kt`.
- Modify: `app/src/main/java/ru/pivoskan/app/data/scan/CityScanCodec.kt`.
- Create: `app/src/test/java/ru/pivoskan/app/data/ProductIdentityTest.kt`, `ProductRowsTest.kt`.

**Interfaces:** extend Offer with defaulted `product: ProductDescriptor? = null` and
`priceEvidence: OrdinaryPriceEvidence = UNKNOWN`. Enum values `CONFIRMED, UNKNOWN, CONDITIONAL`.
Source parsers set CONFIRMED only with tested ordinary-price semantics, not merely a positive number.
`ProductDescriptor(sourceProductId: String, gtin: String?, manufacturer: String?, brand: String?,
line: String?, variant: String?, volumeMl: Int?, packageType: String?, units: Int?,
abvPermille: Int?, imageUrl: String?)`. GTIN must pass length/checksum; contradicting known characteristics split groups.
`ProductIdentity.key(sourceId: String, product: ProductDescriptor): String` uses checked GTIN,
else complete normalized characteristic tuple, else sourceId+sourceProductId. Fallback never joins different networks.
`ProductRow(id: String, title: String, imageUrl: String?, fresh: List<Offer>, historical: List<Offer>,
cityCatalog: List<Offer>, minimum: Offer?, minimumIsHistorical: Boolean)`;
`PriceEngine.products(snapshot: CityScanSnapshot, storeIds: Set<String>, search: String,
sort: SortMode): List<ProductRow>`. Fields are additive; legacy rows can remain until UI migrates.

- [ ] **1. Write tests** `sameSourceProductAcrossShopsGroups`, `differentPackageOrVolumeNeverGroups`,
  `missingAttributesDoNotJoinNetworks`, `gtinConflictSplits`, `cardPriceNeverWins`,
  `staleCheaperDoesNotBeatFresh`, `cityRangeHasNoAddressWinner`, `filterRecalculatesMinimum`.
  Concrete assertions: 450ml vs 500ml => 2 rows; can vs bottle => 2 rows; same GTIN with conflicting
  450/500 => 2 rows; fresh ordinary=10000 and historical=8000 => minimum=10000;
  conditional=7000 excluded; city-only range => minimum=null; source filter recomputes cheapest allowed shop.
- [ ] **2. Run RED:** `UT ru.pivoskan.app.data.Product*Test`; no new grouping API yet.
- [ ] **3. Implement identity and normalization:** source-stable IDs are independent of shop ID;
  keep strength/type/multipack distinctions. Parse only explicit structured fields or unambiguous title tokens.
  Unknown units/packaging/variant are not wildcards. Use connected grouping only where every group member
  has compatible known attributes; avoid transitive fuzzy merges through an incomplete record.
- [ ] **4. Implement products query/codec:** filter before grouping, exclude inactive shops, freshness uses
  non-null `snapshot.runId` equal to `StoredCatalog.runId` plus `StoredCatalog.complete`, never just current
  wall-clock date (null run IDs must not accidentally make migrated data fresh). Prefer current ordinary STORE
  price, then labeled historical STORE minimum; city scope/ranges stay separate. Stable tie order sourceId,
  address, offer ID. Sort DISCOUNT only from verified ordinary old/current prices; unknown discount=0.
  A source product with unknown ordinary price may be shown without a winner, never with its card price substituted.
  Migrate ordinary-price evidence only for previously supported source formats whose old parser semantics
  have been verified; other legacy rows retain UNKNOWN, original price/date and an explicit unverified label.
- [ ] **5. Run GREEN:** new tests plus existing `OfferQueryTest` and all source money/parser tests;
  serialize/restore metadata and verify malicious/non-HTTPS image URLs are not accepted.
- [ ] **6. Commit:** `git commit -m "feat: group equivalent products by ordinary address prices"`.

## Task 8: Раскрываемая карточка с изображением и адресами

**Files:**
- Modify: `app/src/main/java/ru/pivoskan/app/ui/BeerAdapter.kt`, `CompareAdapter.kt`,
  `MainActivity.kt`, `BeerDetailsActivity.kt`.
- Create: `app/src/main/java/ru/pivoskan/app/ui/ProductImageLoader.kt`.
- Modify: `app/src/main/res/layout/item_beer.xml`, `item_compare.xml`, `activity_main.xml`,
  `activity_beer_details.xml`, `app/src/main/res/values/strings.xml`.
- Create: `app/src/main/java/ru/pivoskan/app/ui/ProductImagePolicy.kt`,
  `app/src/test/java/ru/pivoskan/app/ui/ProductImagePolicyTest.kt`.

**Interfaces:** `BeerAdapter(onOfferClick: (Offer) -> Unit, images: ProductImageLoader)` consumes
ProductRow, expanded IDs stored as a set of product keys in SavedState/ViewModel;
clicking header toggles inline address list, clicking address opens existing details.
`ProductImageLoader.load(url: String?, target: ImageView): Cancellable`, `clear(target)`;
`ProductImagePolicy.allowed(url: String): Boolean` uses source/CDN hosts verified in Task 6
or direct-source evidence, HTTPS only and no credentials/query tokens from account pages.

- [ ] **1. Write tests** `rejectsForeignImageOrigins`, `rejectsCredentialUrl`, `nullImageUsesPlaceholder`;
  assert false for HTTP, userinfo and unverified host; null URL invokes no request.
  Add device checklist assertions: recycled cell never shows previous item's picture; expanded row remains
  expanded after another shop result; TalkBack announces expanded/collapsed and each address/price.
- [ ] **2. Run RED:** `UT ru.pivoskan.app.ui.ProductImagePolicyTest`; missing policy.
- [ ] **3. Implement image loader:** existing platform HTTPS/BitmapFactory on executor, 2 concurrent downloads,
  2MiB body limit, decoded maximum 256px target after bounds inspection, reject >16MP source,
  memory LRU 8MiB, disk LRU 32MiB in app cache. No cookies/auth headers; validate each redirect origin,
  max 3 redirects. Bind callbacks to product URL/tag, clear on recycling; failed image uses neutral placeholder.
  Disk cache may be evicted by Android; absence must never block prices.
- [ ] **4. Implement UI:** minimum label «Минимум среди проверенных», historical «Ранее: …» with timestamp,
  separate «Каталог города — цена по адресу не подтверждена». Show all equal-price addresses;
  city-only product remains visible without falsely choosing a shop. Display source coverage and actionable
  error/login status outside product cards; provide cancel and report export without automatic sending.
  Keep search/filter position and stable product IDs during incremental updates. Update details navigation
  so selecting a shop does not restart the city scan or project cache back to selectedShop.
- [ ] **5. Run GREEN:** policy tests and full JVM suite; on Android inspect phone-size layout, long addresses,
  large system font, missing image, empty/current/historical-only products and many expanded addresses.
  Verify no network or bitmap decoding on UI thread.
- [ ] **6. Commit:** `git commit -m "feat: show grouped product cards with address prices and photos"`.

## Task 9: Проверка на Android и поставка с подтверждённым охватом

**Files:**
- Modify: `docs/verification/citywide-device-checklist.md`, `docs/verification/2026-11-04-citywide-source-capabilities.md`,
  `README.md`, `VERIFICATION.md`, `app/build.gradle.kts`, `app/src/main/res/values/strings.xml`.
- Use: `scripts/package-source.py` and ignored `dist/` for distribution.

**Interfaces:** consumes source evidence, production APK, diagnostic export and all earlier tests.
Produces signed installable APK, checksums, verification record and release notes describing actual coverage.
First full release candidate: versionName `1.4`, versionCode `5` if unused at execution time;
if an intermediate APK was distributed, choose the next unused greater versionCode and record it.

- [ ] **1. Run required automated checks:**
  `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:lintRelease '-PpivoskanBuildDir=C:/Users/diygxjs/AppData/Local/Temp/pivoskan-build' --offline --no-daemon`;
  `node app/src/test/js/dixy-city-scan.test.cjs`;
  `node app/src/test/DixyCityScanScriptTest.js`;
  `node app/src/test/js/browser-source-session.test.cjs` when implemented.
  Expected: BUILD SUCCESSFUL, all current tests pass, zero lint errors, no new unreviewed warnings.
- [ ] **2. Validate real Magnit scenario:** upgrade old installation retaining shop preference `502731`, select
  Balashikha, verify at least two distinct supported addresses with separately checked prices and all discovered
  addresses accounted for. Verify first/next discovery pages with deterministic fixtures even if live city <100 stores.
  Disable/fail browser networks and confirm Magnit still completes. Match a sample of ordinary prices to source
  at the same time; do not infer availability from existence of a public address.
- [ ] **3. Validate lifecycle/cache:** run every item of Task 5 checklist; compare run IDs before/after,
  verify no new requests while backgrounded, no cross-city records, old-price labeling offline,
  atomic recovery after process kill and cache migration from installed 1.3.
- [ ] **4. Validate browser/UI:** real user login and two supported addresses for each claimed browser source,
  expired session and unavailable point, partial pagination, card-only price, zero results, image failure,
  range/city scope and product-variant separation. Record missing device access as UNVERIFIED, not PASS.
  Without confirmed browser behavior publish only a test release with explicit limited coverage.
- [ ] **5. Prepare release:** compare signing certificate to previous release using existing apksigner;
  verify package/version using aapt2; package source with existing script; inspect archive excludes keystore,
  passwords, diagnostic sessions and caches. Compute SHA256 for APK/source/docs and record actual test counts.
  Do not replace a stable release with an unverified candidate. Existing user authorization permits GitHub
  publication after checks; no force push or secret upload.
- [ ] **6. Commit and publish reviewed changes:** commit explicit code/docs files, push intended branch to
  the existing repository without overwriting unrelated work; create release with notes-file and attach artifacts.
  Verify remote release version and asset digests. Final report names working networks, address/city coverage,
  tested device and remaining unavailable networks, with a direct APK link.

## Самопроверка плана

- Все десять подтверждённых требований покрыты: lifecycle 5; четыре сети 2/4/6;
  обычные цены/группировка/фото 7/8; ошибки и покрытие 1–4/6; без сервера — общий контракт.
- Review Focus имеет конкретные проверки в указанных задачах, включая старую настройку Кудаковского.
- Контракты источников и репозитория едины; browser callbacks используют те же CatalogResult и ScanRun.
- Неизвестные возможности сайтов определяются задачами 1/6; отрицательный результат не блокирует Магнит
  и не превращается в обещание полной поддержки. Селекторы/форматы не зафиксированы без доказательств.
- Результат планирования — этот документ. Продуктовый код и APK на этом этапе не меняются.

Технические ссылки для исполнителя: [ActivityLifecycleCallbacks](https://developer.android.com/reference/android/app/Application.ActivityLifecycleCallbacks)
для общего наблюдения за активностями; [WebView.onPause](https://developer.android.com/reference/android/webkit/WebView#onPause())
не останавливает JavaScript, поэтому отмена браузерных операций в задаче 5 обязательна.
