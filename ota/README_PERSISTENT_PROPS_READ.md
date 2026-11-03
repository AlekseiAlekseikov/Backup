# PERSISTENT_PROPS_READ.zip — read-only диагностика для recovery

Пакет **ничего не меняет, не стирает и не форматирует**. Он только копирует два файла
в память ГУ и останавливается (надпись `Installation aborted` — штатный конец работы).

## Что делать владельцу

1. Записать `PERSISTENT_PROPS_READ.zip` **на флешку** (USB работает на 1.0.47/1.0.48,
   потому что запись в `mt_gpio` из приложения удалена).
2. Войти в recovery ГУ **тем же способом**, которым ставился пакет
   `3C6025_SW0E22H0128H111100000_user_995/OS/update.zip` (тот же пункт меню).
3. Дождаться на экране двух строк:
   - `Result: /data/media/0/voiceapp3_persist_diag.txt`
   - `Result: /data/media/0/persistent_properties.raw`
   затем `Installation aborted` — это норма.
4. Перезагрузиться в Android, открыть файловый менеджер (или подключить флешку) и забрать
   из памяти ГУ:
   - `voiceapp3_persist_diag.txt`
   - `persistent_properties.raw`
   Прислать оба файла.

## Если на экране `/data NOT mounted - decryption problem`

Значит userdata зашифрован для recovery: пакет ничего не сделал и ничего не испортил
(он останавливается до любых операций). Это тоже ответ — тогда идём через ПК
(`adb connect`/`adb shell` с root-правами или разбор через сам Android).

## Чего в пакете нет

Нет `format`, `delete`, `wipe`, `write_raw_image`, нет записи в `persistent_properties`
(только `cp` в `/data/media/0`). Скрипт целиком — `META-INF/com/google/android/updater-script`,
его можно прочитать любым архиватором до установки.

## Реквизиты сборки

- `update-binary` — **байт-в-байт** из пакета владельца:
  sha256 `656512a91f688bd88ba9f17b076b0fe294947db7b0a767466103beeeb537bcf6`.
- `META-INF/com/android/otacert` = `platform-keys/platform.x509.pem`
  (sha256 `9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14`) — тот же
  сертификат, что и в пакете владельца, поэтому recovery этой ГУ принимает нашу подпись.
- Подпись: whole-file PKCS#7 (SHA-1 + RSA), формат `SignApk -w` — как в пакете владельца.
