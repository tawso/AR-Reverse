# Новый проект на toolkit

1. Подключите модули к новому Android application и добавьте `implementation(project(":arkotlinreserve"))`.
2. Измените `applicationId`, namespace, название, тему и UI только в application module.
3. Скопируйте `ar-content.json`, выберите поддержанный словарь и замените marker definitions. ID обязан входить в словарь, размер задаётся в метрах.
4. Сгенерируйте маркеры тем же словарём и укажите фактический размер чёрного квадрата.
5. Создайте `ARView`, затем `ARKotlinReserve.create(...).attach(lifecycleOwner, view)`.
6. Подпишитесь на state/activeContent и оформите продуктовый UI. Tracker и renderer менять не требуется.

`ContentSource` — точка расширения. Сейчас реализован только bundled-assets source. Будущий remote source должен проверить schema, подпись/хэш и полный реестр до атомарной замены, сохранять последнюю корректную копию локально и никогда не отключать доступный офлайн-контент из-за неудачного обновления. Сетевой код, URL и Internet permission намеренно отсутствуют.

Поддерживаемые primitive types: `cube`, `plane`, `axes`. Загрузка glTF/GLB/FBX не реализована и не заявляется.
