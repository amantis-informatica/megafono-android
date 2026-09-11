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
        val reduccionAcople: Float = 1f,
        /** Callados a proposito para romper el lazo de acople. */
        val enSilencioPorAcople: Boolean = false,
        /** Milisegundos que quedan de ese silencio. */
        val msSilencioRestante: Int = 0
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
        val frecuencia: Int = 0,
        /**
         * El micro que pedimos NO es por el que entra el sonido de verdad.
         * Android nos ha ignorado: hay que avisar, no callar.
         */
        val entradaNoRespetada: Boolean = false,
        /** Se renuncio al AEC del sistema para poder usar el micro elegido. */
        val aecCedidoPorMicro: Boolean = false
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
     * Al detectar acople, callar del todo un segundo.
     *
     * Bajar el volumen a veces no basta: mientras quede algo de sonido el
     * lazo sigue vivo y el pitido vuelve a subir. Cortar por completo mata
     * el lazo de raiz y la sala se queda limpia.
     */
    @Volatile var silenciarAlAcoplar: Boolean = true

    /** Cuanto dura ese silencio, en milisegundos. */
    @Volatile var msSilencioAcople: Int = 1000

    /**
     * Pulsar para hablar: mientras esta activo, solo sale sonido si
     * `hablando` es cierto.
     */
    @Volatile var modoPulsar: Boolean = false
    @Volatile var hablando: Boolean = false

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

    /**
     * Si tocamos el modo de audio del movil hay que devolverlo como estaba,
     * o el resto de apps (y las llamadas) se quedan raras.
     */
    private var modoPrevio: Int? = null
    private var pusimosDispositivoComunicacion = false

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

    // Muestras que quedan de silencio forzado por acople. Se cuenta en
    // muestras y no en reloj para no depender de cuando llega cada bloque.
    private var muestrasDeSilencio = 0

    // Volumen que se aplica de verdad, persiguiendo al objetivo poco a poco
    // para que ningun corte suene como un chasquido.
    private var factorSuave = 0f

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

        // Que micro quiere el usuario (null = que elija Android).
        val microPedido: AudioDeviceInfo? = idEntradaElegida.let { id ->
            if (id == null) {
                buscarMicrofonoExterno(audioManager)
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.id == id }
            }
        }

        // AQUI ESTA EL CONFLICTO, y no se puede tener todo:
        //
        // VOICE_COMMUNICATION es lo unico que enciende el cancelador de eco,
        // pero en ese modo el enrutado lo manda la politica de comunicacion
        // del sistema, no nosotros: `preferredDevice` es una PREFERENCIA, y
        // Android la ignora tranquilamente y se queda con el micro interno.
        // Por eso el lavalier no entraba aunque se eligiera en la lista.
        //
        // Con un micro USB el AEC ademas no sirve de mucho: esta calibrado
        // para la geometria micro-interno/altavoz del propio movil.
        //
        // Decision: si el usuario ha pedido un micro EXTERNO, mandamos el
        // micro y renunciamos al AEC del sistema. El eco lo tapan la puerta
        // de ruido, el filtro de graves y el antiacople, que ya estaban.
        val micExterno = microPedido != null && esExterno(microPedido)
        val cederAec = quiereAec && micExterno

        val fuente = if (quiereAec && !cederAec) {
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

        // `setPreferredDevice` devuelve si la peticion valia. Antes se tiraba
        // el resultado con el setter de Kotlin y no nos enterabamos de nada.
        // Ojo: true solo dice "peticion aceptada", NO que se este usando ese
        // micro. Quien manda la verdad es `routedDevice`, ya grabando.
        if (microPedido != null) {
            grabador?.setPreferredDevice(microPedido)
        }

        // Si seguimos en modo comunicacion, hay que pedir el enrutado por la
        // via buena (API 31+). Aun asi el sistema puede decir que no.
        if (fuente == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            fijarRutaDeComunicacion(audioManager, microPedido)
        }

        val sesion = grabador!!.audioSessionId
        val parAec = activarAec(sesion, quiereAec && !cederAec)
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
            frecuencia = frecuencia,
            entradaNoRespetada = nosIgnoraronElMicro(microPedido),
            aecCedidoPorMicro = cederAec
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

                    // Bajar el volumen no siempre rompe el lazo: mientras
                    // quede algo de sonido el pitido puede volver a subir.
                    // Callar del todo un segundo lo mata y limpia la sala.
                    if (silenciarAlAcoplar && muestrasDeSilencio <= 0) {
                        muestrasDeSilencio = (frecuencia * msSilencioAcople) / 1000
                    }
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

            // Silencio total tras detectar acople: corta el lazo de raiz.
            val callados = muestrasDeSilencio > 0
            if (callados) {
                muestrasDeSilencio -= leidas
                if (muestrasDeSilencio < 0) muestrasDeSilencio = 0
                // Al volver, arrancamos bajito: si se vuelve al volumen de
                // antes de golpe, el pitido reaparece en el acto.
                if (muestrasDeSilencio == 0) reduccion = 0.30f
            }

            // Pulsar para hablar: con el dedo fuera del boton, no sale nada.
            val dejaPasar = !modoPulsar || hablando

            val factor = if (callados || !dejaPasar) {
                0f
            } else {
                ganancia * suavizadoPuerta * reduccion
            }
            var picoSalida = 0f

            for (i in 0 until leidas) {
                // Llegar al volumen nuevo poco a poco dentro del bloque: si
                // se salta de golpe a cero (o vuelve de cero), se oye un
                // "clac" muy feo en el altavoz.
                factorSuave += (factor - factorSuave) * 0.02f
                var m = (salida[i] / 32768f) * factorSuave

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
                    reduccionAcople = reduccion,
                    enSilencioPorAcople = callados,
                    msSilencioRestante = (muestrasDeSilencio * 1000) / frecuencia
                )
                alCambiarEstado?.invoke(estado)
            }
        }
    }

    // ------------------------------------------------------------------------

    /** Un micro que NO es el del propio movil. */
    private fun esExterno(d: AudioDeviceInfo): Boolean = when (d.type) {
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        else -> false
    }

    /**
     * Comprueba si Android nos hizo caso, preguntando por donde entra el
     * sonido DE VERDAD. Se llama ya grabando: antes `routedDevice` es null.
     */
    private fun nosIgnoraronElMicro(pedido: AudioDeviceInfo?): Boolean {
        if (pedido == null) return false
        val real = try { grabador?.routedDevice } catch (e: Exception) { null }
            ?: return false
        return real.id != pedido.id
    }

    /**
     * La via oficial para mandar el enrutado en modo comunicacion (API 31+).
     *
     * Detalle importante: `setCommunicationDevice` SOLO acepta dispositivos
     * de SALIDA; el micro que le corresponde lo elige la plataforma sola. Con
     * un lavalier USB que solo tiene microfono, esto no lo puede seleccionar,
     * y por eso no es la solucion para este caso. Lo dejamos por si el USB
     * tambien saca sonido (auricular USB con micro), que entonces si arrastra
     * la entrada.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
    private fun fijarRutaDeComunicacionS(
        am: AudioManager,
        microPedido: AudioDeviceInfo
    ) {
        try {
            // Buscamos la SALIDA que pertenece al mismo cacharro.
            val gemelo = am.getAvailableCommunicationDevices().firstOrNull {
                it.type == microPedido.type &&
                    it.productName == microPedido.productName
            } ?: return

            if (modoPrevio == null) modoPrevio = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            pusimosDispositivoComunicacion = am.setCommunicationDevice(gemelo)
        } catch (e: Exception) {
            // Si el fabricante no lo soporta, seguimos: ya hay aviso en pantalla.
        }
    }

    /** Envoltorio con la comprobacion de version, para no repetirla. */
    private fun fijarRutaDeComunicacion(am: AudioManager, microPedido: AudioDeviceInfo?) {
        if (microPedido == null) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            fijarRutaDeComunicacionS(am, microPedido)
        }
    }

    /**
     * `lo Queremos` no es `quiereAec` a secas: si hemos cedido el AEC para
     * poder usar el micro externo, aqui va false aunque el interruptor de la
     * pantalla siga puesto. Asi el diagnostico no miente.
     */
    private fun activarAec(sesion: Int, loQueremos: Boolean): Pair<Boolean, Boolean> {
        if (!AcousticEchoCanceler.isAvailable()) return Pair(false, false)
        return try {
            aec = AcousticEchoCanceler.create(sesion)
            aec?.enabled = loQueremos
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

    /**
     * Aplica la eleccion del usuario sin parar el audio.
     *
     * Devuelve `true` si hace falta reabrir el micro: cambiar entre micro
     * interno y externo cambia la FUENTE (VOICE_COMMUNICATION vs MIC), y eso
     * no se puede cambiar en caliente. Quien llama decide si reinicia.
     */
    fun aplicarDispositivosElegidos(): Boolean {
        val am = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val idE = idEntradaElegida
        val microPedido = if (idE == null) {
            buscarMicrofonoExterno(am)
        } else {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == idE }
        }

        // Si el nuevo micro exige otra fuente distinta a la que esta abierta,
        // no vale con reenrutar: hay que reabrir.
        val queremosExterno = microPedido != null && esExterno(microPedido)
        val fuenteQueTocaria = if (quiereAec && !queremosExterno) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val fuenteAbierta = grabador?.audioSource
        if (fuenteAbierta != null && fuenteAbierta != fuenteQueTocaria) return true

        if (microPedido != null) grabador?.setPreferredDevice(microPedido)

        val idS = idSalidaElegida
        reproductor?.preferredDevice = if (idS == null) {
            null
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == idS }
        }

        // Releer por donde va de verdad tras el cambio.
        capacidades = capacidades.copy(
            nombreEntrada = describirEntrada(am),
            nombreSalida = describirSalida(am),
            entradaNoRespetada = nosIgnoraronElMicro(microPedido)
        )
        return false
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
        // Devolver el movil como estaba. Si nos dejamos MODE_IN_COMMUNICATION
        // puesto, el resto de apps y las llamadas se quedan tocadas.
        try {
            val am = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (pusimosDispositivoComunicacion &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            ) {
                am.clearCommunicationDevice()
            }
            modoPrevio?.let { am.mode = it }
        } catch (e: Exception) {
        } finally {
            pusimosDispositivoComunicacion = false
            modoPrevio = null
        }

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
        muestrasDeSilencio = 0
        factorSuave = 0f
        historialEnergia.fill(0f)
    }
}
