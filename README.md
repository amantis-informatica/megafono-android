# Megáfono Amantis

App Android que coge el sonido del micrófono y lo saca por el altavoz, en
directo. Pensada para megafonía con un lavalier USB-C y un altavoz Bluetooth.

## Qué hace

- Usa el **micrófono USB-C** si hay uno conectado; si no, el del móvil.
- Saca el sonido por donde esté sonando el móvil: **altavoz Bluetooth**,
  USB-C, auriculares o el altavoz interno.
- Sigue funcionando con la pantalla apagada (servicio en primer plano, con
  su notificación y su botón de parar).
- Dice en pantalla **qué micrófono y qué altavoz** está usando de verdad,
  para que no haya dudas.

## Contra el eco y el acople

Son dos problemas distintos y conviene no mezclarlos:

**Eco** es oír tu propia voz devuelta con retardo. Lo ataca el cancelador de
eco del sistema (`AcousticEchoCanceler`), que la app activa cuando el móvil
lo ofrece.

**Acople** es el pitido que sale cuando el altavoz alimenta al micrófono en
bucle. El cancelador de eco **no** lo resuelve. Lo que hace la app:

| Defensa | Qué hace |
|---|---|
| Cancelador de eco (AEC) | Lo del sistema, si el móvil lo tiene |
| Supresor de ruido | Limpia el ruido de fondo |
| Filtro de graves | Quita el retumbe, que es por donde empieza el pitido |
| Puerta de ruido | Si no hablas, no sale nada — la defensa más eficaz |
| Antiacople automático | Detecta el lazo y baja el volumen solo |
| Limitador | No deja saturar por mucho volumen que pongas |

### Lo que hay que saber

El **Bluetooth añade 150–250 ms de retardo** que no se puede quitar por
software: es del códec A2DP. Si te oyes a ti mismo, lo vas a notar. Para voz
en directo va mucho mejor un altavoz por cable o USB-C.

Con el **micrófono y el altavoz en la misma sala y cerca**, el acople es
física de la sala. Ninguna app lo arregla del todo. Lo que sí funciona:

1. Aleja el altavoz del micrófono y apúntalo en otra dirección.
2. Deja el volumen en x1.0 y súbelo poco a poco.
3. Sube la puerta de ruido si pita en los silencios.
4. Deja el filtro de graves puesto.

### El interruptor de cancelación de eco

Activarlo cambia la fuente de audio a `VOICE_COMMUNICATION`, que es lo que
hace que Android ofrezca el AEC. El problema: **algunos móviles, con esa
fuente, ignoran el micrófono USB-C y usan el interno**. Depende del
fabricante.

Por eso es un interruptor. Prueba las dos posiciones con tu móvil y tu
lavalier, y mira en "Por dónde va el sonido" cuál te respeta el micro
externo. No hay una respuesta que valga para todos los móviles.

## Compilar

La APK se compila sola en GitHub Actions con cada push a `main`, o a mano
desde la pestaña **Actions** → *Compilar APK* → *Run workflow*.

Cuando termine, la APK está en el run, abajo del todo, en **Artifacts** →
`MegafonoAmantis-apk`.

Va firmada con la clave de depuración: se instala directamente (hay que
permitir "orígenes desconocidos"), no vale para Play Store.

Para compilarla en local hace falta JDK 17 y el SDK de Android:

```
./gradlew assembleRelease
```

La APK queda en `app/build/outputs/apk/release/`.

## Permisos

- **Micrófono**: sin esto no hace nada.
- **Notificaciones**: para el aviso de que el micro está abierto.
- **Bluetooth**: para poder nombrar el altavoz conectado.

---

Amantis Informática
