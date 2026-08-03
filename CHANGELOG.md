# История изменений

## 2.0.3 — 2026-08-03

- Исправлена несовместимость зависимостей с `compileSdk 36`: AndroidX Core 1.19.0 требовал API 37.
- AndroidX Core/Core-KTX жёстко зафиксированы на 1.17.0.
- Compose переведён с BOM 2026.06.00 на стабильный BOM 2025.08.00 (Compose 1.9).
- Activity Compose зафиксирован на 1.11.0, Lifecycle — на 2.9.4.
- В GitHub Actions добавлен `dependencyInsight` и явный запрет разрешения Core 1.18/1.19.
- Версия приложения и имя APK обновлены до 2.0.3.

## 2.0.2 — 2026-08-03

- Исправлена остановка GitHub Actions на установке несуществующего пакета `platforms;android-37`.
- `compileSdk` переведён на стабильный Android 16 / API 36; `targetSdk` оставлен 36.
- Workflow теперь устанавливает `platforms;android-36` и `build-tools;36.0.0`.
- Имя APK и версия приложения обновлены до 2.0.2.
- Проверка исходников теперь контролирует согласованность SDK и версии между Gradle, workflow и документацией.

## 2.0.1 — 2026-08-03

- Исправлена остановка GitHub Actions на проверке `gradle-wrapper.jar`.
- Неверная контрольная сумма заменена официальной SHA-256 для Wrapper Gradle 9.5.0.
- SHA-256 дистрибутива Gradle теперь передаётся прямо задаче `wrapper` и проверяется в properties.
- Добавлена проверка дерева исходников до запуска Gradle.
- `actions/upload-artifact` обновлён до Node.js 24-совместимой версии v7.
- Сборка APK запускается с `--no-daemon`; отсутствие APK теперь считается ошибкой.

## 2.0.0 — 2026-08-03

Полное пересоздание FranProbe с нуля.

- Послойная диагностика: сеть → DNS → IP → TCP/UDP → TLS/SNI → HTTP.
- `NOT_TESTED` вместо ложного `BLOCKED`, если зависимый тест не запускался.
- Системный DNS и прямые DNS-запросы UDP/TCP с проверкой transaction ID и вопроса.
- A/AAAA, CNAME, RCODE, TTL и повтор DNS по TCP при truncation.
- DoT и настоящий DoH с HTTP/2/HTTP/1.1 и штатной проверкой сертификата.
- Продолжение тестов по IP, полученному независимым резолвером.
- Отдельные IPv4/IPv6 TCP-проверки.
- Матрица SNI: без SNI, пользовательские SNI, TLS 1.2/1.3, ALPN и Host без SNI.
- Subject/SAN сертификата и ручная проверка соответствия SNI.
- UDP без ответа получает `INCONCLUSIVE`, а не ложную блокировку.
- История последних 20 запусков.
- Экспорт ZIP: TXT, JSON, CSV, RAW.LOG и README.
- GitHub Actions: unit-тесты, lint и сборка APK без `continue-on-error`.
