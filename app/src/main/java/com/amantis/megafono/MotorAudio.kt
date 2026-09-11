package com.amantis.megafono

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Coge el sonido del microfono y lo saca por el altavoz, en directo.
 *
 * El orden de la cadena importa: primero limpiamos (AEC y supresor los aplica
 * el propio Android sobre la captura), luego quitamos graves, luego decidimos
 * si dejamos pasar la senal (puerta), y al final controlamos el volumen
 * (antiacople + limitador). Ese ultimo paso es el que evita el pitido.
 */
class MotorAudio(private val contexto: Context) {

    /** Lo que la interfaz necesita saber en cada momento. */
    data class Estado(
        val nivelEntrada: Float = 0f,
        val nivelSalida: Float = 0f,
        val puertaAbierta: Boolean = false,
        val acoplando: Boolean = false,
        val reduccionAcople: Float = 1f
    )

    /** Lo que se pudo activar de verdad en ESTE movil. */
    data class Capacidades(
        val aecDisponible: Boolean = false,
        val aecActivo: Boolean = false,
        val supresorDisponible: Boolean = false,
        val supresorActivo: Boolean = false,
        val agcDisponible: Boolean = false,
        val nombreEntrada: String = "-",
        val nombreSalida: String = "-",
        val frecuencia: Int = 0
    )

    // --- Ajustes que el usuario mueve desde la pantalla ---------------------

    /** Volumen general. 1.0 = tal cual entra. */
    @Volatile var ganancia: Float = 1.0f

    /** Por debajo de este nivel no sale nada. Mata el acople en los silencios. */
    @Volatile var umbralPuerta: Float = 0.015f

    /** Cancelacion de eco: si se fuerza, cambia la fuente de audio. */
    @Volatile var quiereAec: Boolean = true

    /** Antiacople automatico. */
    @Volatile var antiacople: Boolean = true

    /** Filtro de graves: quita el retumbe, que es donde mas acopla. */
    @Volatile var filtroGraves: Boolean = true

    /**
     * Entrada y salida elegidas a mano. `null` = que decida Android
     * (prefiriendo el microfono externo si lo hay).
     *
     * Se guarda el id, no el objeto: al desenchufar y volver a enchufar el
     * lavalier, Android crea otro AudioDeviceInfo y el objeto viejo ya no
     * vale para nada.
     */
    @Volatile var idEntradaElegida: Int? = null
    @Volatile var idSalidaElegida: Int? = null

    /** Una entrada o salida que el usuario puede elegir en la lista. */
    data class Dispositivo(
        val id: Int,
        val nombre: String,
        val esAutomatico: Boolean = false
    )

    // --- Estado interno -----------------------------------------------------

    @Volatile private var corriendo = false
    private var hiloAudio: Thread? = null

    private var grabador: AudioRecord? = null
    private var reproductor: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var supresor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile var capacidades = Capacidades(); private set
    @Volatile var estado = Estado(); private set

    /** Avisos para la interfaz. */
    var alCambiarEstado: ((Estado) -> Unit)? = null
    var alFallar: ((String) -> Unit)? = null

    // Memoria de los filtros (se conserva entre bloques de audio)
    private var pasoAltoX1 = 0f
    private var pasoAltoY1 = 0f
    private var envolventeSalida = 0f
    private var reduccion = 1f
    private var muestrasDesdeVoz = 0
    private var suavizadoPuerta = 0f

    // Historial para detectar el acople (energia de los ultimos bloques)
    private val historialEnergia = FloatArray(24)
    private var indiceHistorial = 0

    private var frecuencia = 48000

    fun estaCorriendo() = corriendo

    // ------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun arrancar(): Boolean {
        if (corriendo) return true

        val audioManager = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 48 kHz es lo nativo en casi todo Android; si no, caemos a 44,1.
        val candidatas = intArrayOf(48000, 44100, 16000)
        var tamanoEntrada = 0
        for (f in candidatas) {
            val t = AudioRecord.getMinBufferSize(
                f, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (t > 0) {
                frecuencia = f
                tamanoEntrada = t
                break
            }
        }
        if (tamanoEntrada <= 0) {
            alFallar?.invoke("Este movil no acepta ninguna frecuencia de grabacion.")
            return false
        }

        // La fuente decide si Android nos da el cancelador de eco.
        // VOICE_COMMUNICATION lo activa.
        val fuente = if (quiereAec) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }

        // Buffers holgados: un corte de audio suena peor que 20 ms de retardo.
        val bufEntrada = maxOf(tamanoEntrada * 4, frecuencia / 10)

        try {
            grabador = AudioRecord(
                fuente, frecuencia,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufEntrada
            )
        } catch (e: Exception) {
            alFallar?.invoke("No se pudo abrir el microfono: " + e.message)
            return false
        }

        if (grabador?.state != AudioRecord.STATE_INITIALIZED) {
            alFallar?.invoke("El microfono no arranco. Falta el permiso o lo tiene otra app.")
            liberar()
            return false
        }

        // Con VOICE_COMMUNICATION, Android tira del microfono interno por su
        // cuenta y se salta el lavalier. Elegir la fuente NO elige el micro:
        // hay que exigirlo aparte. Asi se queda el AEC Y el micro externo.
        val idE = idEntradaElegida
        grabador?.preferredDevice = if (idE == null) {
            buscarMicrofonoExterno(audioManager)
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == idE }
        }

        val sesion = grabador!!.audioSessionId
        val parAec = activarAec(sesion)
        val parSup = activarSupresor(sesion)
        val agcHay = AutomaticGainControl.isAvailable()

        // Salida: STREAM_MUSIC va al altavoz Bluetooth A2DP si esta conectado.
        val tamanoSalida = AudioTrack.getMinBufferSize(
            frecuencia, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSalida = maxOf(tamanoSalida * 4, frecuencia / 10)

        try {
            reproductor = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(frecuencia)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSalida)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            alFallar?.invoke("No se pudo abrir la salida de audio: " + e.message)
            liberar()
            return false
        }

        val idS = idSalidaElegida
        if (idS != null) {
            reproductor?.preferredDevice = audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.id == idS }
        }

        corriendo = true
        reproductor?.play()
        grabador?.startRecording()

        // El diagnostico se calcula DESPUES de arrancar: `routedDevice` no
        // sabe por donde entra el sonido hasta que la grabacion esta en
        // marcha, y antes devolvia null siempre.
        capacidades = Capacidades(
            aecDisponible = parAec.first,
            aecActivo = parAec.second,
            supresorDisponible = parSup.first,
            supresorActivo = parSup.second,
            agcDisponible = agcHay,
            nombreEntrada = describirEntrada(audioManager),
            nombreSalida = describirSalida(audioManager),
            frecuencia = frecuencia
        )

        hiloAudio = thread(name = "megafono-audio", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            bucle()
        }
        return true
    }

    fun parar() {
        corriendo = false
        hiloAudio?.join(1000)
        hiloAudio = null
        liberar()
        estado = Estado()
        alCambiarEstado?.invoke(estado)
    }

    // ------------------------------------------------------------------------

    /**
     * El bucle de audio. Lee un bloque, lo procesa, lo escribe. Nada mas.
     * Todo lo que se meta aqui cuesta latencia, asi que va al grano.
     */
    private fun bucle() {
        // ~10 ms por bloque: suficiente para que los filtros reaccionen rapido
        // sin que el sistema se ahogue en llamadas.
        val muestrasBloque = frecuencia / 100
        val bloque = ShortArray(muestrasBloque)
        val salida = ShortArray(muestrasBloque)
        var contadorAvisos = 0

        while (corriendo) {
            val leidas = grabador?.read(bloque, 0, muestrasBloque) ?: -1
            if (leidas <= 0) {
                if (leidas < 0) break
                continue
            }

            var picoEntrada = 0f
            var sumaCuadrados = 0f

            for (i in 0 until leidas) {
                var m = bloque[i] / 32768f

                // 1) Filtro de graves (paso alto ~120 Hz). El acople casi
                //    siempre empieza por abajo: quitando retumbe se gana mucho.
                if (filtroGraves) {
                    val y = 0.985f * (pasoAltoY1 + m - pasoAltoX1)
                    pasoAltoX1 = m
                    pasoAltoY1 = y
                    m = y
                }

                val amplitud = abs(m)
                if (amplitud > picoEntrada) picoEntrada = amplitud
                sumaCuadrados += m * m

                // Sin recortar aqui, el filtro puede pasarse de 1.0 y
                // toShort() da la vuelta al signo: un chasquido muy audible.
                salida[i] = (m.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }

            val rms = sqrt(sumaCuadrados / leidas)

            // 2) Puerta de ruido. Si no hablas, no sale nada: es la defensa
            //    mas eficaz contra el acople, porque el pitido nace y crece
            //    justo en los silencios.
            val hayVoz = rms > umbralPuerta
            if (hayVoz) {
                muestrasDesdeVoz = 0
            } else {
                muestrasDesdeVoz += leidas
            }
            // Cola de ~250 ms para no cortar el final de las palabras.
            val colaAbierta = muestrasDesdeVoz < frecuencia / 4
            val objetivoPuerta = if (hayVoz || colaAbierta) 1f else 0f
            // Subida rapida, bajada suave: abrir tarde corta silabas,
            // cerrar de golpe hace un "clac" muy feo.
            val paso = if (objetivoPuerta > suavizadoPuerta) 0.25f else 0.02f
            suavizadoPuerta += (objetivoPuerta - suavizadoPuerta) * paso

            // 3) Antiacople: si la energia se mantiene alta y constante mucho
            //    rato, eso no es voz, es un lazo realimentandose. La voz
            //    fluctua; el pitido no.
            historialEnergia[indiceHistorial] = rms
            indiceHistorial = (indiceHistorial + 1) % historialEnergia.size

            var acoplando = false
            if (antiacople) {
                var media = 0f
                for (v in historialEnergia) media += v
                media /= historialEnergia.size

                if (media > 0.08f) {
                    // Cuanta variacion hay respecto a la media.
                    var varianza = 0f
                    for (v in historialEnergia) {
                        val d = v - media
                        varianza += d * d
                    }
                    varianza /= historialEnergia.size
                    // Energia alta + poca variacion = lazo.
                    acoplando = sqrt(varianza) < media * 0.22f
                }

                if (acoplando) {
                    // Bajar rapido, que el pitido crece en decimas de segundo.
                    reduccion *= 0.90f
                    if (reduccion < 0.12f) reduccion = 0.12f
                } else {
                    // Recuperar despacio, para no volver a provocarlo.
                    reduccion += (1f - reduccion) * 0.004f
                    if (reduccion > 1f) reduccion = 1f
                }
            } else {
                reduccion = 1f
            }

            // 4) Volumen final + limitador. El limitador es la ultima red:
            //    por mucha ganancia que pongas, no deja saturar.
            val factor = ganancia * suavizadoPuerta * reduccion
            var picoSalida = 0f

            for (i in 0 until leidas) {
                var m = (salida[i] / 32768f) * factor

                val amplitud = abs(m)
                // Envolvente del limitador: ataque casi instantaneo.
                envolventeSalida = if (amplitud > envolventeSalida) {
                    amplitud
                } else {
                    envolventeSalida * 0.9995f
                }
                if (envolventeSalida > 0.92f) {
                    m *= 0.92f / envolventeSalida
                }

                if (m > 1f) m = 1f
                if (m < -1f) m = -1f

                val a = abs(m)
                if (a > picoSalida) picoSalida = a
                salida[i] = (m * 32767f).toInt().toShort()
            }

            reproductor?.write(salida, 0, leidas)

            // Avisar a la pantalla ~10 veces por segundo, no en cada bloque.
            contadorAvisos++
            if (contadorAvisos >= 10) {
                contadorAvisos = 0
                estado = Estado(
                    nivelEntrada = picoEntrada,
                    nivelSalida = picoSalida,
                    puertaAbierta = suavizadoPuerta > 0.5f,
                    acoplando = acoplando,
                    reduccionAcople = reduccion
                )
                alCambiarEstado?.invoke(estado)
            }
        }
    }

    // ------------------------------------------------------------------------

    private fun activarAec(sesion: Int): Pair<Boolean, Boolean> {
        if (!AcousticEchoCanceler.isAvailable()) return Pair(false, false)
        return try {
            aec = AcousticEchoCanceler.create(sesion)
            aec?.enabled = quiereAec
            Pair(true, aec?.enabled == true)
        } catch (e: Exception) {
            Pair(true, false)
        }
    }

    private fun activarSupresor(sesion: Int): Pair<Boolean, Boolean> {
        if (!NoiseSuppressor.isAvailable()) return Pair(false, false)
        return try {
            supresor = NoiseSuppressor.create(sesion)
            supresor?.enabled = true
            Pair(true, supresor?.enabled == true)
        } catch (e: Exception) {
            Pair(true, false)
        }
    }

    /** Entradas que el usuario puede elegir, con la opcion automatica delante. */
    fun listarEntradas(): List<Dispositivo> {
        val am = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val lista = mutableListOf(Dispositivo(-1, "Automático", esAutomatico = true))
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            // Los virtuales solo confunden: no son microfonos de verdad.
            if (d.type == AudioDeviceInfo.TYPE_TELEPHONY) continue
            lista.add(Dispositivo(d.id, nombreDeDispositivo(d)))
        }
        return lista
    }

    /** Salidas que el usuario puede elegir. */
    fun listarSalidas(): List<Dispositivo> {
        val am = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val lista = mutableListOf(Dispositivo(-1, "Automático", esAutomatico = true))
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.type == AudioDeviceInfo.TYPE_TELEPHONY) continue
            lista.add(Dispositivo(d.id, nombreDeSalida(d)))
        }
        return lista
    }

    /** Aplica la eleccion del usuario sin tener que parar el audio. */
    fun aplicarDispositivosElegidos() {
        val am = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val idE = idEntradaElegida
        grabador?.preferredDevice = if (idE == null) {
            buscarMicrofonoExterno(am)
        } else {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == idE }
        }

        val idS = idSalidaElegida
        reproductor?.preferredDevice = if (idS == null) {
            null
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == idS }
        }

        // Releer por donde va de verdad tras el cambio.
        capacidades = capacidades.copy(
            nombreEntrada = describirEntrada(am),
            nombreSalida = describirSalida(am)
        )
    }

    /**
     * Busca un microfono enchufado por cable o USB, con preferencia por el USB.
     *
     * Devuelve null si solo esta el del propio movil: en ese caso no forzamos
     * nada y dejamos que Android elija.
     */
    private fun buscarMicrofonoExterno(am: AudioManager): AudioDeviceInfo? {
        val dispositivos = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

        // Un lavalier USB-C se presenta como USB_HEADSET o USB_DEVICE segun
        // el fabricante, asi que hay que mirar los dos.
        val usb = dispositivos.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: dispositivos.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        if (usb != null) return usb

        return dispositivos.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
    }

    /**
     * Nombre del microfono que se esta usando DE VERDAD.
     *
     * Se lo preguntamos al propio grabador (`routedDevice`), no a la lista de
     * dispositivos disponibles: antes decia "USB-C" solo porque hubiera uno
     * enchufado, aunque el sonido viniera del microfono interno.
     */
    private fun describirEntrada(am: AudioManager): String {
        val real = try {
            grabador?.routedDevice
        } catch (e: Exception) {
            null
        }

        if (real != null) return nombreDeDispositivo(real)

        // Si todavia no hay ruta asignada, decimos lo que hay, sin afirmar
        // que se este usando.
        val externo = buscarMicrofonoExterno(am)
        if (externo != null) return nombreDeDispositivo(externo) + " (sin confirmar)"
        return "Microfono interno del movil"
    }

    private fun nombreDeDispositivo(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> "Microfono USB-C (" + d.productName + ")"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Microfono de auriculares con cable"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Microfono Bluetooth (" + d.productName + ")"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfono interno del movil"
        else -> "Entrada: " + d.productName
    }

    /** Por donde sale el sonido DE VERDAD, preguntandoselo al reproductor. */
    private fun describirSalida(am: AudioManager): String {
        val real = try {
            reproductor?.routedDevice
        } catch (e: Exception) {
            null
        }
        if (real != null) return nombreDeSalida(real)

        val dispositivos = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val a2dp = dispositivos.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        if (a2dp != null) return nombreDeSalida(a2dp) + " (sin confirmar)"
        return "Altavoz del movil"
    }

    private fun nombreDeSalida(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Altavoz Bluetooth (" + d.productName + ")"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth manos libres (" + d.productName + ")"
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> "Salida USB-C (" + d.productName + ")"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares con cable"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altavoz del movil"
        else -> "Salida: " + d.productName
    }

    private fun liberar() {
        try { grabador?.stop() } catch (e: Exception) {}
        try { reproductor?.stop() } catch (e: Exception) {}
        aec?.release(); aec = null
        supresor?.release(); supresor = null
        agc?.release(); agc = null
        grabador?.release(); grabador = null
        reproductor?.release(); reproductor = null
        pasoAltoX1 = 0f
        pasoAltoY1 = 0f
        envolventeSalida = 0f
        reduccion = 1f
        suavizadoPuerta = 0f
        muestrasDesdeVoz = 0
        historialEnergia.fill(0f)
    }
}
