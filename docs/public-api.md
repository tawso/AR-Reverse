# Публичный API

Главные типы: `ARKotlinReserve`, `ARConfig`, `AndroidSession`, `ARView`, `ContentRegistry`, `MarkerDefinition`, `TrackingState` и `MarkerObservation`.

```kotlin
val session = ARKotlinReserve.create(
    context = applicationContext,
    config = ARConfig(markerLossGraceMillis = 350),
    contentSource = BundledAssetContentSource(applicationContext),
)

val arView = ARView(this)
setContentView(arView)
session.attach(this, arView)

lifecycleScope.launch {
    session.state.collect { state -> renderStatus(state) }
}

// При окончательном завершении владельца:
session.close()
```

`create` — suspend-функция: источник контента читается и валидируется до создания сессии. `attach`, `detach`, `updateConfig`, `replaceContent` и `close` вызываются с main thread. `attach` не забирает владение `ARView`; view остаётся в иерархии приложения. `close` идемпотентен и освобождает camera binding, executor и native OpenCV ресурсы. После него сессия повторно не используется.

`state`, `diagnostics` и `activeContent` — immutable `StateFlow`. `observations` — высокочастотный `SharedFlow`; медленный подписчик пропускает старые значения. API не раскрывает `Mat`, `ImageProxy` и GL handles.

`replaceContent` принимает уже валидный immutable реестр. Словарь можно заменить: tracker пересоздаётся на своём executor. Новое сопоставление marker/content не меняет код detector.

Поза выражена в метрах в OpenGL camera coordinates; соглашения перечислены в [coordinates.md](coordinates.md). Публичная бинарная совместимость пока не гарантируется; основной путь подключения — Gradle module dependency `implementation(project(":arkotlinreserve"))`.
