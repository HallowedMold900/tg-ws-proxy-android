# TG WS Proxy для Android

Автор: [HallowedMold900](https://github.com/HallowedMold900) · репозиторий: https://github.com/HallowedMold900/tg-ws-proxy-android

Локальный MTProto-прокси для Telegram на телефоне по мотивам [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) (MIT).
Telegram → `127.0.0.1:порт` → WebSocket (TLS) → Telegram DC.

## Сборка
1. Откройте папку в Android Studio (Ladybug или новее, JDK 17) и дождитесь Gradle Sync.
2. Run ▶ на устройстве или Build → Build APK(s). Минимальный Android — 8.0 (API 26).

## Использование
1. Нажмите «Запустить» (на Android 13+ разрешите уведомления).
2. Нажмите «В Telegram» и подтвердите добавление прокси (или скопируйте ссылку `tg://proxy…`).

## Маршруты (порядок как в оригинале)
1. Прямой WebSocket на `kwsN.web.telegram.org` через IP из «DC → IP»; при неудаче — domain fronting (SNI `sprinthost.ru`).
2. Cloudflare Worker: `wss://<worker>/apiws?dst=<IP DC>&dc=N`. Код Worker’а — в docs/RU/CfWorker.md репозитория Flowseal.
3. CF-прокси: `kwsN.<домен>`. Без своего домена берётся список по умолчанию с GitHub (обновляется раз в час), запасной список встроен.
4. Прямой TCP на настоящий адрес DC (с паузой 30 с … 1 ч после неудач).

## Дополнительно
- **Fake TLS (ee-ключ):** поле «Домен Fake TLS» в настройках. Без профилирования размера записи сайта-маски (размер случайный 64–160 байт).
- **HTTP/2 для медиа через CF:** упрощённый порт cf_h2.py (OkHttp), экспериментально, по умолчанию выключен.
- **Раздача в Wi-Fi:** QR-код, белый список адресов (IP или CIDR), список подключённых устройств с кнопками «Отключить» и «Запретить».
- **Виджет** рабочего стола и **плитка** в шторке, перезапуск при смене сети, динамические цвета.

## Не реализовано
Режим «без TLS» (порт 80), пул предсозданных WS-соединений, проверка обновлений.
