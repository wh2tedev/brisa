# Brisa

Navegador Android mínimo basado en `WebView`, con Material 3 y tema oscuro al estilo Chrome.
Buscador por defecto: Google. Sin analíticas, sin cuentas, sin servicios de terceros.

## Compilar

Requisitos: JDK 17 y Android SDK 34.

- **Android Studio:** abrir la carpeta y pulsar *Run*.
- **Terminal:** `gradle assembleDebug` (Gradle 8.7). El APK queda en `app/build/outputs/apk/debug/`.
- **GitHub Actions:** el workflow `build.yml` compila y sube el APK como artefacto.

Para generar el wrapper: `gradle wrapper --gradle-version 8.7`.

## Privacidad y seguridad

- Solo HTTPS: `http://` se mejora a `https://` y el tráfico en claro está prohibido (`usesCleartextTraffic=false`).
- Errores de certificado: la carga se cancela, sin opción de continuar.
- Safe Browsing activado; contenido mixto bloqueado.
- Acceso a archivos locales y a content providers desactivado.
- Cookies de terceros bloqueadas por defecto.
- Cabeceras `DNT` y `Sec-GPC` en la navegación principal (configurable).
- Permisos web (cámara, micrófono, ubicación) siempre denegados.
- Esquemas no web (`intent:`, `file:`, `javascript:`…) bloqueados en navegación.
- Sin historial persistente propio, sin copias de seguridad (`allowBackup=false`).
- Opción para borrar datos al cerrar y para borrarlos ahora.
- Único permiso: `INTERNET`.

## Limitaciones actuales

Una sola pestaña, sin subida de archivos, sin marcadores ni pestañas privadas.

## Licencia

MIT
