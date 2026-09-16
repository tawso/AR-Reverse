# ARKotlinReserve

Лёгкий офлайн-first Android toolkit для marker-based AR на Kotlin. Он объединяет CameraX, ArUco из официального OpenCV Android AAR и прозрачный OpenGL ES 2.0 слой. ARCore, Google Play Services, Unity, Vuforia, сервер, аккаунты и Internet permission не используются.

## Возможности

- задняя камера через CameraX Preview + ImageAnalysis;
- `DICT_4X4_50`, реальные ID 7 и 23;
- оценка позы `SOLVEPNP_IPPE_SQUARE`, проверка глубины и RMS-ошибки репроекции;
- pose-driven cube/plane/axes, локальная трансформация контента;
- состояния observed / temporarily retained / lost и настраиваемый grace period;
- сглаживание перемещения по времени и quaternion slerp;
- JSON-контент и JSON-профили калибровки;
- Compose sample UI на казахском и техническая диагностика.

## Требования и сборка

- Android Studio с Android SDK 37 и Build Tools 36;
- JDK 17 или 21; wrapper использует Gradle 9.6 и AGP 9.4;
- устройство ARM64, Android 8.0+ (minSdk 26).

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME='C:\Users\Rad1x\AppData\Local\Android\Sdk'
.\gradlew.bat test lint :sample-app:assembleDebug --console=plain
```

APK: `sample-app/build/outputs/apk/debug/sample-app-debug.apk`.

## Запуск

1. Распечатайте `markers/markers-print-a4-300dpi.png` в масштабе 100%.
2. Проверьте линейкой: сторона чёрного квадрата должна быть 80 мм.
3. Установите debug APK, разрешите камеру и направьте её на маркер.

Без подходящего профиля `assets/camera-calibration.json` приложение явно работает в приближённом режиме. Детекция остаётся доступна, но точная физическая привязка не обещается. Идеальная OpenGL-проекция не искажает виртуальную графику так же, как объектив и preview; остаточное несовпадение заметнее у краёв кадра. Подробнее: [калибровка](docs/calibration.md) и [координаты](docs/coordinates.md).

Текущий APK намеренно содержит только ARM64. Android lint также сообщает, что `libc++_shared.so` из официального OpenCV 5.0.0 AAR не подтверждает 16-КБ выравнивание; поэтому совместимость с будущими 16-КБ-only устройствами не заявляется. На целевом Android 13 устройстве используется обычная 4-КБ среда.

## Документация

- [Публичный API](docs/public-api.md)
- [Архитектура](docs/architecture.md)
- [Координаты](docs/coordinates.md)
- [Калибровка](docs/calibration.md)
- [Новый проект](docs/new-project.md)
- [Проверка устройства](docs/device-testing.md)

Зависимости сверены с официальными источниками: [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [CameraX](https://developer.android.com/jetpack/androidx/releases/camera), [OpenCV Android](https://docs.opencv.org/5.0/tutorials/introduction/android_binary_package/dev_with_OCV_on_Android.html), [ArucoDetector Java API](https://docs.opencv.org/5.0/javadoc/org/opencv/objdetect/ArucoDetector.html).
