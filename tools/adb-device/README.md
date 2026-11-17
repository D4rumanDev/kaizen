# Tool: ADB Device Connector (`tools/adb-device`)

Proporciona la interfaz y comandos para interactuar directamente con el dispositivo Android TV (tvbox u otro dispositivo) a través de ADB por TCP/IP o USB.

---

## Configuración de Entorno

Copiar `.env.example` a `.env` si se requiere personalizar la dirección del dispositivo:
```bash
cp tools/adb-device/.env.example tools/adb-device/.env
```

Variables:
- `ADB_DEVICE`: Dirección IP y puerto del dispositivo (sin valor por defecto: se define en `.env`, que no se versiona).
- `ADB_PACKAGE`: Nombre del paquete Android (por defecto `app.kaizen`).

---

## Verificación de Conexión

Ejecutar el script de prueba para validar que ADB está instalado y el dispositivo es alcanzable:
```bash
bash tools/adb-device/test_connection.sh
```

---

## Comandos Útiles

1. **Conectar**:
   ```bash
   adb connect $ADB_DEVICE
   ```
2. **Instalar en memoria interna (evita ANR)**:
   ```bash
   adb -s $ADB_DEVICE install -r -f app/build/outputs/apk/tv/app-tv.apk
   ```
3. **Lanzar actividad TV**:
   ```bash
   adb -s $ADB_DEVICE shell monkey -p app.kaizen -c android.intent.category.LEANBACK_LAUNCHER 1
   ```
4. **Verificar excepciones / crashes**:
   ```bash
   adb -s $ADB_DEVICE shell "logcat -d | grep -E '(FATAL EXCEPTION|ANR in app.kaizen)'"
   ```
5. **Compilación AOT speed (opcional, cuando la tele no se esté usando)**:
   ```bash
   adb -s $ADB_DEVICE shell cmd package compile -m speed app.kaizen
   ```

