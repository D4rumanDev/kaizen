---
title: Stack tecnológico
type: stack
tags:
  - stack
  - gradle
  - kotlin
  - android
updated: 2026-11-19
---

# Stack Tecnológico Canónico de Kaizen

Este documento consolida las versiones de compilador, librerías base y configuraciones de entorno requeridas para compilar, probar y depurar Kaizen.

---

## 1. Entorno de Desarrollo y SDKs

| Componente | Versión / Valor | Ubicación / Observaciones |
| --- | --- | --- |
| **Java JDK** | JDK 17 (Adoptium Hotspot) | `/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot` |
| **Android Compile SDK** | 37 | Definido en `app/build.gradle` |
| **Android Target SDK** | 36 | Orientado a compatibilidad Android 14+ TV |
| **Android Min SDK** | 26 | Android 8.0 Oreo (soporte base para Leanback y canales de TV) |
| **Android Gradle Plugin (AGP)** | 9.4.1 | Vía Gradle Wrapper |
| **Kotlin** | 2.4.21 | Plugin `kotlin-gradle-plugin` |
| **Compose Compiler** | 2.4.21 | Acoplado a la versión de Kotlin |
| **KSP (Kotlin Symbol Processing)** | 2.3.12 | Procesamiento de anotaciones Room |

---

## 2. Variantes de Compilación y Arquitectura

- **Variante `tv`**:
  - Hereda de `release` con minificación (`minifyEnabled true`) y contracción de recursos (`shrinkResources true`).
  - Filtro ABI exclusivo: `arm64-v8a` (orientado al procesador Tegra X1 del tvbox).
- **Variante `debug`**:
  - Utilizada para la suite de pruebas unitarias (`:app:testDebugUnitTest`).
  - En AGP 9, las pruebas unitarias se ejecutan contra `debug`, incorporando los mismos recursos y manifiesto definidos para `tv` mediante `sourceSets`.

---

## 3. Claves y Firma

- **Keystore**: `kaizen-tvlibre.jks`
- **Alias**: `origami`
- **Fichero de Contraseña**: `kaizen-tvlibre.pass`
- **Ubicación estándar**: Definida en `local.properties` mediante `KAIZEN_KEYSTORE_DIR` (por defecto `~/.android/`).
- **Comprobación de firma**:

  ```bash
  apksigner verify ../kaizen.apk
  ```

---

## 4. Dispositivo Objetivo de Pruebas

- **Hardware**: tvbox (Android TV).
- **Dirección estándar ADB**: `<IP_SHIELD>:5555`.
- **Estrategia de instalación**: Siempre en almacenamiento interno (`-f`) para evitar degradación de lecturas/escrituras en discos USB lentos.

## Relacionado

- [architecture.md](architecture.md): cómo encaja este stack en la app.
- [CODEBASE-MAP.md](../CODEBASE-MAP.md): dónde está cada cosa y cómo se comprueba un cambio.
- [Constitución de ingeniería](sdd/constitution.md): las reglas que lo gobiernan.
