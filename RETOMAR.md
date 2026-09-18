# Cómo retomar este proyecto

Todo lo necesario para seguir trabajando sin el chat donde se hizo.

---

## Lo primero: qué es esto

App Android que coge el sonido del micrófono y lo saca por el altavoz, en
directo.

**Es un proyecto independiente**, con su propio repositorio y su propio
circuito de compilación. No comparte nada con ningún otro proyecto.

- **Carpeta**: `C:\Users\EQUIPO\Documents\Proyectos\megafono-android`
- **Repo**: `git@github.com:amantis-informatica/megafono-android.git`
- **APK lista**: `apk\MegafonoAmantis.apk` (aquí mismo)
- **APK siempre al día**: https://github.com/amantis-informatica/megafono-android/releases/download/ultima/MegafonoAmantis.apk

---

## El escenario real (esto decide TODO)

No es megafonía de sala. Es un **megáfono portátil**:

- La persona lleva el **altavoz Bluetooth en bandolera** y el móvil en la mano
- **Lavalier USB-C a menos de 10 cm** de la boca
- Altavoz a **~1 metro**, apuntando adelante; el micro queda detrás del cono
- La geometría es **casi constante**: se mueven juntos

Cualquier decisión técnica se juzga contra este escenario, no contra una
sala con altavoz fijo.

---

## Lo que ya se midió que NO funciona

Esto está comprobado con números, no supuesto. **No volver a intentarlo
sin datos nuevos.**

| Qué | Resultado medido |
|---|---|
| Cancelador de eco (AEC/NLMS) en lazo cerrado | **0,3–1,5 dB** (nada) |
| El mismo AEC en lazo abierto | 25–37 dB (ahí sí sirve) |
| IA denoiser (RNNoise, DeepFilterNet) | No toca el acople |
| Filtro de voz contra ruido de tráfico | **Lo empeora**: −10,65 → −4,70 dB |
| Retardo de decorrelación + desplazamiento | 2–6 dB; al usuario no le cambió nada |

**Por qué el AEC no puede funcionar aquí**: en un megáfono la señal de
referencia *es la voz del usuario amplificada*. Para el filtro adaptativo,
cancelar el eco y cancelar la voz son el mismo problema (sesgo clásico de
lazo cerrado). Se probó con cola corta de 50 ms, retardo calibrado a
0,4 ms de error y filtro congelado al converger. Sigue sin servir.

**Por qué la IA tampoco**: un denoiser distingue voz de ruido de fondo. El
pitido del acople **es la voz**, amplificada — la red está entrenada para
protegerla. RNNoise (BSD) y DeepFilterNet (Apache 2.0) son libres, no hay
que pagar ni registrarse; el problema es conceptual, no de licencia.

---

## Lo que SÍ funciona

- **Notches adaptativos por frecuencia**: +3,5 dB. Lo único que mejoró el
  acople en la medida. Detecta el tono exacto que pita (FFT + criterios
  PHPR/PNPR) y le clava un filtro de 1/3 de octava, **sin bajar el volumen**.
- **Puerta de ruido adaptativa** con margen 18 dB: −7 dB de ruido con la
  voz intacta. Aprende sola el suelo de la sala.
- **Compresor** y **limitador con anticipación de 5 ms**.
- **Modo pulsar para hablar**: la defensa más fuerte contra el acople.
- Y sobre todo, lo que no es software: **cada vez que se dobla la distancia
  micro-altavoz, +6 dB**. Más que todo el DSP junto.

---

## Una trampa de Android que hay que conocer

**Micro USB-C y cancelador de eco del sistema: hay que elegir uno.**

`setPreferredDevice()` es una *preferencia*, no una orden. Con
`AudioSource.VOICE_COMMUNICATION` (lo único que enciende el
`AcousticEchoCanceler` de Android) el enrutado lo manda la política de
comunicación del sistema, que se queda con el micro interno e ignora el
lavalier.

Por eso: si el usuario elige un micro externo, la app usa `AudioSource.MIC`
y **cede el AEC del sistema**. Es deliberado.

`setCommunicationDevice()` (API 31+) **no vale**: solo acepta dispositivos
de salida; el micrófono lo elige la plataforma.

---

## Estructura del código

```
app/src/main/java/com/amantis/megafono/
├── Dsp.kt             ← toda la cadena de proceso (lo gordo)
├── MotorAudio.kt      ← captura, reproducción, elección de dispositivos
├── MainActivity.kt    ← la pantalla (construida en código, sin XML)
├── MegafonoService.kt ← servicio en primer plano (sigue con pantalla apagada)
└── Megafono.kt        ← comparte el motor entre pantalla y servicio
```

**Regla de oro de `Dsp.kt`**: cero reservas de memoria en el camino de
audio. Todo preasignado en los constructores. El recolector de basura no
puede parar el hilo de audio.

Orden de la cadena: entrada → ganancia entrada → AEC → paso alto → notches
→ filtro voz → puerta → compresor → ganancia salida → decorrelación →
desplazador → limitador.

---

## Cómo compilar

**No hace falta Android Studio ni Java.** Lo hace GitHub Actions:

```bash
git add -A
git commit -m "lo que sea"
git push
```

El push dispara el workflow. En 2–3 minutos la APK está en:
- **Releases** → `ultima` (descarga directa, sin credenciales)
- **Actions** → el run → Artifacts (requiere estar identificado)

Para verlo: https://github.com/amantis-informatica/megafono-android/actions

**La clave SSH** para el push es `~/.ssh/id_github` (autentica como
`amantis-informatica`). Si git no la coge sola:

```bash
export GIT_SSH_COMMAND="ssh -o IdentitiesOnly=yes -i /c/Users/EQUIPO/.ssh/id_github"
```

---

## Cómo verificar cambios sin un móvil

Hay herramientas en esta máquina para compilar y probar el DSP de verdad:

- JDK 17: `C:\Users\EQUIPO\tools\jdk17`
- android.jar API 34: `C:\Users\EQUIPO\tools\android-sdk\platforms\android-34\android.jar`
- kotlinc 1.9.24: en `C:\Users\EQUIPO\tools`
- Dependencias AndroidX/Material: caché de Gradle en `C:\Users\EQUIPO\.gradle\caches`

⚠️ `kotlinc.bat` parte el classpath por los `;`. Hay que pasarle los
argumentos por fichero (`@kargs.txt`) con el classpath entrecomillado.

`Dsp.kt` no importa nada de Android, así que **se puede compilar y ejecutar
contra la JVM sola** para medir números reales (ERLE, atenuación, latencia).
Así se cazaron la mayoría de los fallos de este proyecto — leyendo el
código no habrían aparecido.

---

## Cosas que se rompieron y por qué

Por si vuelven a aparecer:

- **`gradlew` con finales CRLF** → el runner de Linux falla con
  «bad interpreter». Lo fija `.gitattributes`; no quitarlo.
- **Subir por el navegador se salta las carpetas ocultas** → sin
  `.github/workflows` no compila nada, y Actions muestra el catálogo de
  plantillas en vez de un run. Mejor `git push`.
- **El limitador suavizaba la ganancia con medias móviles**, y eso *no
  conserva el mínimo*: los picos aislados se colaban y saturaban. Era la
  causa real de la distorsión que notaba el usuario.
- **En lazo cerrado los pesos del AEC divergían a Infinity** en medio
  segundo; la energía quedaba en NaN y no volvía a adaptar en toda la
  sesión. Hay guardia de divergencia.

---

## Qué queda por hacer

- **Probarla en la calle** con la bandolera puesta. Nada de esto se ha
  probado en un móvil real: el altavoz Bluetooth distorsiona y la
  referencia no es exacta, cosas que la simulación no reproduce.
- **Comprobar el lavalier**: si es omnidireccional, capta el altavoz igual
  de bien que la voz. Uno cardioide podría valer **10–15 dB**, más que todo
  el DSP. Merece la pena mirarlo antes de tocar más código.
- Comprobar que el altavoz no radia también hacia atrás (muchos Bluetooth
  portátiles son omnidireccionales, y ahí está buena parte del problema).

---

## Para pedir cambios en un chat nuevo

Basta con decir:

> En el proyecto `megafono-android` de Documents\Proyectos, quiero que la
> app haga X. Lee primero RETOMAR.md.

El historial de commits lleva el **porqué** de cada decisión, no solo el
qué. Merece la pena leerlo antes de cambiar algo que parezca raro: casi
siempre está así por una medida.
