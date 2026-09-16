# Архитектура

Зависимости ацикличны:

```text
sample-app ──> arkotlinreserve ──> ar-tracking-opencv ──> ar-core
                         └───────> ar-renderer ─────────> ar-core
```

- `ar-core`: immutable модели, конфигурация, координатная математика, сглаживание и публичные контракты без Android/OpenCV/OpenGL.
- `ar-tracking-opencv`: ArUco, IPPE-поза, репроекция и перевод в модели core.
- `ar-renderer`: жизненный цикл GLES 2.0, cube/plane/axes, проекция и локальные трансформации.
- `arkotlinreserve`: `ARKotlinReserve`, `AndroidARSession`, `ARView`, CameraX, lifecycle, JSON и связывание трекера с renderer.
- `sample-app`: разрешение камеры, навигация, настройки, локальный контент и диагностика.

Кадр принадлежит CameraX до выхода из analyzer. Analyzer копирует только Y-плоскость с учётом `rowStride`/`pixelStride`, не хранит `ImageProxy` и закрывает его в `finally`. `STRATEGY_KEEP_ONLY_LATEST` не допускает очереди устаревших кадров. OpenCV работает на одном выделенном executor; UI получает низкочастотный `StateFlow`, а наблюдения идут отдельным `SharedFlow` с буфером 1.

Preview и GL находятся в одном `ARView`. Оба используют fit-center; renderer создаёт совпадающий letterbox viewport. GL-ресурсы создаются callback-ами render thread и пересоздаются с EGL context. Межпоточная передача сцены выполняется immutable snapshot через `AtomicReference`.
