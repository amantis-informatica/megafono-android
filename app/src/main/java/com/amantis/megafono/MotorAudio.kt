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
        /** Cuantos filtros hay puestos ahora mismo contra el acople. */
        val notchesPuestos: Int = 0,
        /** En que frecuencia se detecto el ultimo pitido (Hz), o -1. */
        val hzAcople: Float = -1f,
        /** Cuanto esta apretando el compresor, en dB. */
        val reduccionCompresorDb: Float = 0f,
        /** Nivel de ruido de fondo que ha aprendido la puerta. */
        val sueloRuido: Float = 0f,
        /**
         * Lo que da el microfono ANTES de tocarlo. Si llega a 1,0 el micro
         * entra recortado de origen y ningun filtro posterior lo arregla.
         */
        val picoCrudo: Float = 0f,
        /** Cuanto esta cancelando el cancelador de eco, en dB (ERLE). */
        val erleDb: Float = 0f,
        /** Retardo medido entre altavoz y microfono, en ms. -1 si no lo sabe. */
        val retardoEcoMs: Float = -1f,
        /** 0..1, cuanta estructura de voz ve el filtro de voz. */
        val probabilidadVoz: Float = 0f,
        /** Tono fundamental de la voz detectado, en Hz. -1 si no hay. */
        val tonoVozHz: Float = -1f
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

    /** Volumen de SALIDA. 1.0 = tal cual sale de la cadena. */
    @Volatile var ganancia: Float = 1.0f

    /**
     * Sensibilidad del MICROFONO: multiplica lo que entra, antes de todo.
     *
     * Es el mando que de verdad arregla "coge mucho sonido": si el micro
     * entra pasado, la puerta da por buena la sala entera y el compresor
     * aprieta de mas. Eso no se arregla luego bajando el volumen.
     */
    @Volatile var gananciaEntrada: Float = 1.0f

    /** Cancelacion de eco: si se fuerza, cambia la fuente de audio. */
    @Volatile var quiereAec: Boolean = true

    /** Antiacople automatico. */
    @Volatile var antiacople: Boolean = true

    /**
     * Cancelador de eco PROPIO (el de la cadena, no el del sistema).
     *
     * Ataca la voz repetida. Viene APAGADO de fabrica, y no por prudencia
     * boba: medido, en lazo cerrado (que es como funciona un megafono, con la
     * propia voz saliendo por el altavoz) no aporta nada, entre -0,4 y
     * +0,5 dB, y en la prueba de ganancia antes de acoplar incluso la
     * empeoraba. Donde SI da sus 25-37 dB es en lazo abierto, o sea cuando lo
     * que sale por el altavoz no es la voz que entra por el microfono. El
     * interruptor esta ahi para quien tenga ese caso. Ver la cabecera de
     * [CanceladorEco] para el porque.
     */
    @Volatile var cancelarEco: Boolean = false

    /**
     * Filtro de voz: deja pasar lo que tiene estructura armonica de voz y
     * corta lo demas. Ataca el ruido de sala.
     */
    @Volatile var filtroVoz: Boolean = false

    /** Filtro de graves: quita el retumbe, que es donde mas acopla. */
    @Volatile var filtroGraves: Boolean = true

    /**
     * Retardo de decorrelacion en ms. Sube el margen antes de acoplar a
     * cambio de latencia. 0 = apagado.
     */
    @Volatile var msDecorrelacion: Float = 0f

    /**
     * Desplazamiento de frecuencia en Hz. Rompe el lazo desafinando la senal
     * unos pocos Hz, sin gastar latencia como el retardo. 0 = apagado.
     */
    @Volatile var hzDesplazamiento: Float = 0f

    /** Compresor: iguala la voz para que no sature al levantar la voz. */
    @Volatile var compresor: Boolean = true

    /** Puerta de ruido con umbral que se aprende solo. */
    @Volatile var puertaActiva: Boolean = true

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

    /**
     * Toda la cadena de proceso. Se crea al arrancar, cuando ya se sabe la
     * frecuencia de muestreo: los filtros dependen de ella.
     */
    private var cadena: CadenaAntiacople? = null

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

        // La cadena se crea aqui: sus filtros dependen de la frecuencia de
        // muestreo, que no se conoce hasta ahora.
        //
        // Se le pasa la sensibilidad ANTES de reiniciar() y no solo dentro del
        // bucle: el suavizado de la ganancia de entrada tiene que arrancar ya
        // en el valor que el usuario dejo puesto. Si no, la cadena nace en x1
        // y el primer bloque sale con la sensibilidad de fabrica -- con el
        // mando al minimo eso son ~20 ms de micro casi diez veces mas fuerte
        // de lo pedido, justo el petardazo al arrancar que queriamos evitar.
        cadena = CadenaAntiacople(frecuencia).also {
            it.gananciaEntrada = gananciaEntrada
            it.reiniciar()
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
        var contadorAvisos = 0

        while (corriendo) {
            val leidas = grabador?.read(bloque, 0, muestrasBloque) ?: -1
            if (leidas <= 0) {
                if (leidas < 0) break
                continue
            }

            // Toda la cadena vive en CadenaAntiacople: paso alto, notches
            // contra el acople, puerta adaptativa, compresor y limitador.
            // Aqui solo se le pasan los ajustes y se le da el bloque.
            val c = cadena ?: continue
            c.pasoAltoActivo = filtroGraves
            c.notchesActivos = antiacople
            c.aecActivo = cancelarEco
            c.filtroVozActivo = filtroVoz
            c.compresorActivo = compresor
            c.puertaActiva = puertaActiva

            // Pulsar para hablar: con el dedo fuera del boton, no sale nada.
            val dejaPasar = !modoPulsar || hablando
            c.gananciaEntrada = gananciaEntrada
            c.msDecorrelacion = msDecorrelacion
            c.hzDesplazamiento = hzDesplazamiento
            c.ganancia = if (dejaPasar) ganancia else 0f

            c.procesa(bloque, leidas)

            reproductor?.write(bloque, 0, leidas)

            // Avisar a la pantalla ~10 veces por segundo, no en cada bloque.
            contadorAvisos++
            if (contadorAvisos >= 10) {
                contadorAvisos = 0
                estado = Estado(
                    nivelEntrada = c.picoEntrada,
                    nivelSalida = c.picoSalida,
                    puertaAbierta = c.puertaAbierta,
                    // Ahora "acoplando" significa que hay notches puestos:
                    // el pitido se quita por frecuencia, no bajando el volumen.
                    acoplando = c.notchesPuestos > 0,
                    reduccionAcople = 1f,
                    notchesPuestos = c.notchesPuestos,
                    hzAcople = c.hzAcople,
                    reduccionCompresorDb = c.reduccionCompresor,
                    sueloRuido = c.sueloRuido,
                    picoCrudo = c.picoCrudoMedido,
                    erleDb = c.erleAec,
                    retardoEcoMs = c.retardoEcoMs,
                    probabilidadVoz = c.probabilidadVoz,
                    tonoVozHz = c.tonoVozHz
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
        cadena?.reiniciar()
        cadena = null
    }
}
