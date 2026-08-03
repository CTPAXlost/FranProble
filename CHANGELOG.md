# История изменений

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
