package com.amantis.megafono

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Bloques de proceso de senal para la cadena antiacople.
 *
 * REGLA DE ORO DE ESTE FICHERO: nada de aqui reserva memoria despues de
 * construirse. Todo son arrays preasignados y variables primitivas. El hilo
 * de audio no puede permitirse que el recolector de basura le pare 5 ms.
 *
 * Fuentes:
 *  - Coeficientes biquad: Robert Bristow-Johnson, "Audio EQ Cookbook"
 *    (https://www.w3.org/TR/audio-eq-cookbook/)
 *  - Criterios de deteccion de acople (PAPR/PHPR/PNPR/IMSD): van Waterschoot
 *    et al., "Comparative Evaluation of Howling Detection Criteria in
 *    Notch-Filter-Based Howling Suppression", JAES 2010.
 *  - Compresor: Giannoulis, Massberg & Reiss, "Digital Dynamic Range
 *    Compressor Design - A Tutorial and Analysis", JAES 2012.
 */

// ---------------------------------------------------------------------------
// 1) BIQUAD
// ---------------------------------------------------------------------------

/**
 * Filtro biquad en forma directa I (transpuesta II para el estado).
 *
 * Se usa para el paso alto y para cada notch. Los coeficientes se recalculan
 * solo cuando cambia la frecuencia o la profundidad, nunca por muestra.
 */
class Biquad {
    // Coeficientes ya normalizados por a0.
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    // Estado (forma directa II transpuesta: solo dos registros).
    private var z1 = 0f
    private var z2 = 0f

    fun reiniciar() {
        z1 = 0f
        z2 = 0f
    }

    /** Deja el filtro en "no hace nada" (paso directo). */
    fun enPlano() {
        b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
    }

    /**
     * Paso alto RBJ.
     *
     * El de ahora (`0.985f * (yPrev + x - xPrev)`) es de primer orden: cae
     * 6 dB/octava, demasiado blando. Este cae 12 dB/octava, que es lo que
     * hace falta para quitar de verdad el retumbe de sala.
     */
    fun pasoAlto(f0: Float, q: Float, fs: Int) {
        val w0 = 2.0 * Math.PI * f0 / fs
        val cs = cos(w0)
        val sn = sin(w0)
        val alpha = sn / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (((1.0 + cs) / 2.0) / a0).toFloat()
        b1 = ((-(1.0 + cs)) / a0).toFloat()
        b2 = b0
        a1 = ((-2.0 * cs) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    /**
     * Notch de profundidad PARCIAL, hecho con un "peaking EQ" de ganancia
     * negativa.
     *
     * Esto es importante y es un error clasico: el notch puro del cookbook
     * es de profundidad INFINITA, no se puede pedir "-6 dB". Para poder
     * profundizar poco a poco (-3, -6, -9...) hay que usar el peaking EQ con
     * dB negativos, que es justo lo que hacen los equipos comerciales.
     *
     * @param db profundidad NEGATIVA en dB (ej: -6f)
     * @param q  1/10 de octava -> Q = 14.42 (ver [qDeOctavas])
     */
    fun notch(f0: Float, q: Float, db: Float, fs: Int) {
        val A = Math.pow(10.0, db / 40.0)          // amplitud (peaking usa /40)
        val w0 = 2.0 * Math.PI * f0 / fs
        val cs = cos(w0)
        val sn = sin(w0)
        val alpha = sn / (2.0 * q)
        val a0 = 1.0 + alpha / A
        b0 = ((1.0 + alpha * A) / a0).toFloat()
        b1 = ((-2.0 * cs) / a0).toFloat()
        b2 = ((1.0 - alpha * A) / a0).toFloat()
        a1 = b1
        a2 = ((1.0 - alpha / A) / a0).toFloat()
    }

    /** Paso bajo RBJ. Se usa para el antialias del decimador. */
    fun pasoBajoRbj(f0: Float, q: Float, fs: Int) {
        val w0 = 2.0 * Math.PI * f0 / fs
        val cs = cos(w0)
        val sn = sin(w0)
        val alpha = sn / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (((1.0 - cs) / 2.0) / a0).toFloat()
        b1 = ((1.0 - cs) / a0).toFloat()
        b2 = b0
        a1 = ((-2.0 * cs) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    /** Una muestra. Sin ramas, sin reservas. */
    fun procesa(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        /**
         * Q equivalente a un ancho de banda en octavas.
         * RBJ: Q = 1 / (2*sinh(ln2/2 * BW))
         *
         * 1/3 oct -> 4.32 | 1/5 oct -> 7.21 | 1/10 oct -> 14.42 | 1/20 -> 28.85
         */
        fun qDeOctavas(octavas: Float): Float =
            (1.0 / (2.0 * sinh(ln(2.0) / 2.0 * octavas))).toFloat()
    }
}

// ---------------------------------------------------------------------------
// 2) FFT
// ---------------------------------------------------------------------------

/**
 * FFT radix-2 iterativa, en el sitio, con tablas precalculadas.
 *
 * Preasigna todo en el constructor. `transforma()` no reserva ni un byte.
 */
class Fft(val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    val re = FloatArray(n)
    val im = FloatArray(n)

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "N debe ser potencia de 2" }
        for (i in 0 until n / 2) {
            cosT[i] = cos(-2.0 * Math.PI * i / n).toFloat()
            sinT[i] = sin(-2.0 * Math.PI * i / n).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transforma() {
        // Reordenado bit-reverso.
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                while (k < half) {
                    val tw = k * step
                    val c = cosT[tw]
                    val s = sinT[tw]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * c - im[b] * s
                    val xi = re[b] * s + im[b] * c
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    k++
                }
                i += len
            }
            len = len shl 1
        }
    }
}

// ---------------------------------------------------------------------------
// 3) DETECTOR ESPECTRAL DE ACOPLE
// ---------------------------------------------------------------------------

/**
 * Detecta el acople por FRECUENCIA, no por energia.
 *
 * Por que el detector actual (energia + varianza en el tiempo) es insuficiente:
 * una vocal sostenida ("aaaah"), un motor o un ventilador tambien dan energia
 * alta y estable, asi que dispara falso; y cuando acierta solo sabe QUE hay
 * acople, no EN QUE FRECUENCIA, por lo que lo unico que puede hacer es bajar
 * el volumen entero. Mirando el espectro sabemos el tono exacto y podemos
 * clavarle un notch, que es lo que hacen los equipos profesionales.
 *
 * Se aplican los criterios clasicos de van Waterschoot (JAES 2010), los
 * mismos que implementa Espressif en ESP-ADF:
 *
 *  - PAPR (Peak-to-Average Power Ratio): el acople acaba teniendo mucha
 *    potencia comparado con la media del espectro.
 *  - PNPR (Peak-to-Neighbouring Power Ratio): el acople es una sinusoide no
 *    amortiguada, o sea de ancho de banda casi CERO. Un pico de voz es ancho;
 *    el del acople es una aguja. Este es el criterio mas discriminante.
 *  - PHPR (Peak-to-Harmonic Power Ratio): la voz es armonica (si hay energia
 *    en f, la hay en 2f y 3f). El acople es un tono puro SIN armonicos. Este
 *    es el que distingue acople de vocal sostenida, que es justo el falso
 *    positivo que mas molesta.
 *  - Persistencia: ademas hay que exigir que el candidato repita en la misma
 *    frecuencia varias tramas seguidas. La voz se mueve, el acople no.
 *
 * Trabaja a frecuencia DECIMADA (12 kHz) porque el acople en megafonia de voz
 * vive entre 200 Hz y 4 kHz; analizar hasta 24 kHz seria pagar el cuadruple
 * de FFT para mirar una zona donde no pasa nada.
 */
class DetectorAcople(
    private val fsAnalisis: Int = 12000,
    private val nFft: Int = 512
) {
    private val fft = Fft(nFft)
    private val bins = nFft / 2
    private val ventana = FloatArray(nFft)
    private val buffer = FloatArray(nFft)      // ventana deslizante
    private val mag = FloatArray(bins)         // potencia por bin

    /**
     * Historial de candidatura de cada bin, como mapa de bits: el bit 0 es la
     * trama actual, el bit 1 la anterior, etc. Contando bits a 1 en los 5
     * ultimos sale directamente el "3 de 5" de la patente, sin reservar nada.
     */
    private val historial = IntArray(bins)

    private var escritos = 0

    // --- Umbrales (dB) -----------------------------------------------------
    //
    // Valores tomados de la Tabla 2 de van Waterschoot (JAES 2010), en el
    // punto de trabajo "PHPR AND PNPR": 95% de aciertos con solo 15% de falsas
    // alarmas, y apenas ~117 multiplicaciones por trama. Es la mejor relacion
    // calidad/coste de todas las combinaciones que midieron.
    //
    // Ojo: en esa combinacion los umbrales BAJAN respecto a usarlos sueltos
    // (PHPR de 27 a 26 dB, PNPR de 14 a 8 dB), porque la exigencia de que se
    // cumplan LOS DOS a la vez ya aporta la selectividad.

    /**
     * Pico contra la media del espectro. No entra en la decision final (es
     * redundante con PNPR), pero sirve de prefiltro barato: descarta enseguida
     * los bins que no destacan, y asi no se paga PHPR ni PNPR por ellos.
     */
    var umbralPapr = 10f

    /**
     * Pico contra sus armonicos 2f y 3f. La voz es armonica; el acople es un
     * tono puro. Este es el criterio que distingue el pitido de una vocal
     * sostenida, que es el falso positivo que mas molesta.
     */
    var umbralPhpr = 26f

    /**
     * Pico contra los vecinos a +-2, +-3 y +-4 bins. El acople es una sinusoide
     * pura: una aguja de ancho casi cero. Un pico de voz es ancho.
     *
     * Los vecinos +-1 se EXCLUYEN a proposito: la ventana siempre derrama algo
     * al bin de al lado, asi que incluirlo empeora la deteccion (en el paper
     * es la peor variante de PNPR).
     */
    var umbralPnpr = 8f

    /**
     * Persistencia: aparecer en 3 de 5 espectros seguidos (patente de Sabine
     * US 5.245.665). Es ventana deslizante, NO racha seguida: el acople puede
     * parpadear una trama por culpa de la voz encima y no queremos perderlo.
     */
    var tramasDeVentana = 5
    var tramasParaDisparar = 3

    /** Resultado: -1 si no hay acople, o la frecuencia en Hz. */
    var frecuenciaDetectada = -1f
        private set

    init {
        // Ventana de BLACKMAN, no de Hann. Esto no es un capricho: el criterio
        // PNPR mide lo estrecho que es el pico, y solo tiene sentido si la
        // ventana no derrama energia a los bins de al lado. Blackman deja los
        // lobulos laterales en -58 dB; con una ventana rectangular el PNPR se
        // viene abajo y el umbral de 8 dB no significaria nada.
        // (van Waterschoot, JAES 2010, seccion 1.2.)
        for (i in 0 until nFft) {
            val t = 2.0 * Math.PI * i / (nFft - 1)
            ventana[i] = (0.42 - 0.5 * cos(t) + 0.08 * cos(2.0 * t)).toFloat()
        }
    }

    fun reiniciar() {
        escritos = 0
        java.util.Arrays.fill(historial, 0)
        frecuenciaDetectada = -1f
    }

    /**
     * Mete una muestra ya decimada. Devuelve true cuando ha completado una
     * trama y la ha analizado (entonces [frecuenciaDetectada] esta al dia).
     */
    fun empuja(x: Float): Boolean {
        buffer[escritos] = x
        escritos++
        if (escritos < nFft) return false

        analiza()

        // Solapamiento del 50%: corremos media ventana. Da una trama nueva
        // cada ~21 ms a 12 kHz, suficiente para cazar el acople antes de que
        // se haga audible, y cuesta la mitad que solapar al 75%.
        System.arraycopy(buffer, nFft / 2, buffer, 0, nFft / 2)
        escritos = nFft / 2
        return true
    }

    private fun analiza() {
        for (i in 0 until nFft) {
            fft.re[i] = buffer[i] * ventana[i]
            fft.im[i] = 0f
        }
        fft.transforma()

        var suma = 0f
        for (k in 0 until bins) {
            val p = fft.re[k] * fft.re[k] + fft.im[k] * fft.im[k]
            mag[k] = p
            suma += p
        }
        val media = suma / bins
        if (media <= 1e-12f) {
            frecuenciaDetectada = -1f
            java.util.Arrays.fill(historial, 0)
            return
        }

        // Solo miramos la banda donde el acople de voz aparece de verdad.
        val kMin = (200f * nFft / fsAnalisis).toInt().coerceAtLeast(2)
        val kMax = (5500f * nFft / fsAnalisis).toInt().coerceAtMost(bins - 3)

        var mejorBin = -1
        var mejorPot = 0f

        // Mascara para quedarnos solo con las ultimas N tramas del historial.
        val mascara = (1 shl tramasDeVentana) - 1

        for (k in kMin..kMax) {
            // Desplazamos el historial de este bin una posicion: hueco para la
            // trama de ahora.
            var h = (historial[k] shl 1) and mascara

            val p = mag[k]
            var esCandidato = false

            // Tiene que ser maximo local, si no no es un tono.
            if (p > mag[k - 1] && p > mag[k + 1]) {
                // --- PAPR: prefiltro barato ---
                val papr = 10f * log10(p / media)
                if (papr >= umbralPapr) {

                    // --- PNPR: pico contra vecinos a +-2, +-3 y +-4 bins ---
                    // Los +-1 se excluyen: la ventana siempre derrama al bin
                    // contiguo y meterlo empeora la deteccion.
                    // Es un AND sobre TODOS los vecinos, no una media: basta
                    // con que un vecino este alto para que no sea una aguja.
                    var pnprMin = Float.MAX_VALUE
                    var d = 2
                    while (d <= 4) {
                        if (k - d >= 0) {
                            val v = 10f * log10(p / (mag[k - d] + 1e-20f))
                            if (v < pnprMin) pnprMin = v
                        }
                        if (k + d < bins) {
                            val v = 10f * log10(p / (mag[k + d] + 1e-20f))
                            if (v < pnprMin) pnprMin = v
                        }
                        d++
                    }

                    if (pnprMin >= umbralPnpr) {
                        // --- PHPR: pico contra sus armonicos 2f y 3f ---
                        // Si hay energia fuerte en 2f/3f es voz, no acople.
                        var phprMin = Float.MAX_VALUE
                        var m = 2
                        while (m <= 3) {
                            val kh = k * m
                            if (kh < bins - 1) {
                                // Maximo local alrededor, por si el armonico
                                // cae justo entre dos bins.
                                var ph = mag[kh]
                                if (mag[kh - 1] > ph) ph = mag[kh - 1]
                                if (mag[kh + 1] > ph) ph = mag[kh + 1]
                                val v = 10f * log10(p / (ph + 1e-20f))
                                if (v < phprMin) phprMin = v
                            }
                            m++
                        }
                        // Si los armonicos se salen del espectro no podemos
                        // juzgar: damos el criterio por bueno.
                        if (phprMin == Float.MAX_VALUE || phprMin >= umbralPhpr) {
                            esCandidato = true
                        }
                    }
                }
            }

            if (esCandidato) h = h or 1
            historial[k] = h

            // "3 de 5": contamos cuantas de las ultimas tramas fue candidato.
            if (esCandidato &&
                Integer.bitCount(h) >= tramasParaDisparar &&
                p > mejorPot
            ) {
                mejorPot = p
                mejorBin = k
            }
        }

        frecuenciaDetectada = if (mejorBin < 0) {
            -1f
        } else {
            // Interpolacion parabolica: afina la frecuencia dentro del bin.
            // Sin esto el error puede ser de +-11 Hz a 12 kHz/512, y un notch
            // de Q=14 mide solo ~35 Hz de ancho a 500 Hz: fallariamos el tiro.
            val a = log10(mag[mejorBin - 1] + 1e-20f)
            val b = log10(mag[mejorBin] + 1e-20f)
            val c = log10(mag[mejorBin + 1] + 1e-20f)
            var delta = 0.5f * (a - c) / (a - 2f * b + c + 1e-20f)
            if (delta > 0.5f) delta = 0.5f
            if (delta < -0.5f) delta = -0.5f
            (mejorBin + delta) * fsAnalisis / nFft
        }

    }

    private fun log10(v: Float): Float =
        (ln(v.toDouble()) / 2.302585092994046).toFloat()
}

// ---------------------------------------------------------------------------
// 4) BANCO DE NOTCHES ADAPTATIVOS
// ---------------------------------------------------------------------------

/**
 * Banco de filtros notch que se colocan solos donde aparece el acople.
 *
 * Esta es la diferencia entre "bajar el volumen cuando pita" (lo de ahora) y
 * lo que hace un equipo profesional: en vez de quitar 18 dB a TODO el sonido,
 * quita 6 dB en 35 Hz de ancho alrededor del tono que pita. El resto de la voz
 * no se entera. Eso es lo que da ganancia extra antes de acoplar (GBF).
 *
 * Estrategia (la de Sabine FBX / dbx AFS):
 *  - Profundidad PROGRESIVA: se entra con -3 dB. Si el tono insiste, se baja a
 *    -6, -9... hasta -18. Empezar directamente muy profundo se carga la voz sin
 *    necesidad, porque muchas veces con -3 dB ya se rompe el lazo.
 *  - Los filtros se LIBERAN despues de un rato sin acople, para no ir dejando
 *    agujeros permanentes en la voz segun avanza la charla.
 *  - Si estan todos ocupados, se reutiliza el que lleve mas tiempo parado.
 */
class BancoNotches(
    private val fs: Int,
    private val cuantos: Int = 8,
    /** 1/10 de octava, el ancho tipico de los equipos comerciales. */
    private val octavas: Float = 0.1f
) {
    private val filtros = Array(cuantos) { Biquad() }
    private val frecuencias = FloatArray(cuantos) { -1f }
    private val profundidades = FloatArray(cuantos)
    /** Muestras que lleva sin que se reactive este notch. */
    private val ociosos = IntArray(cuantos)
    private val qNominal = Biquad.qDeOctavas(octavas)

    /**
     * Ley de anchura hibrida, copiada de dbx AFS2.
     *
     * Un notch de Q constante se estrecha en Hz segun baja la frecuencia: a
     * 1/10 de octava, a 2 kHz mide 139 Hz pero a 100 Hz mide solo 7 Hz. Un
     * acople grave se mueve mas que eso (la sala cambia con la gente y la
     * temperatura) y el filtro lo fallaria. Por eso dbx fija un ANCHO EN Hz
     * por debajo de una frecuencia de codo, y Q constante por encima.
     *
     * Aqui: por debajo de 260 Hz, ancho fijo de 11 Hz; por encima, Q nominal.
     */
    private fun qPara(hz: Float): Float {
        val codo = 260f
        val anchoGraveHz = 11f
        return if (hz < codo) (hz / anchoGraveHz).coerceAtLeast(1f) else qNominal
    }

    /** Ancho en Hz que tendra el notch puesto en esa frecuencia. */
    fun anchoHz(hz: Float): Float = hz / qPara(hz)

    /** Profundidad de entrada y maxima, en dB. */
    var dbInicial = -3f
    var dbPaso = -3f
    var dbMaximo = -18f

    /** Tras 20 s sin reactivarse, el notch se suelta. */
    var muestrasParaSoltar = fs * 20

    var activos = 0
        private set

    fun reiniciar() {
        for (i in 0 until cuantos) {
            frecuencias[i] = -1f
            profundidades[i] = 0f
            ociosos[i] = 0
            filtros[i].enPlano()
            filtros[i].reiniciar()
        }
        activos = 0
    }

    /**
     * Avisa de un tono de acople. Si ya habia un notch cerca, lo profundiza;
     * si no, coge uno libre.
     *
     * Se llama UNA vez por trama de analisis, no por muestra.
     */
    fun notificaAcople(hz: Float) {
        if (hz <= 0f) return

        // Ya hay uno puesto cerca? (dentro de media anchura del notch)
        val margen = hz / qPara(hz) * 0.5f
        for (i in 0 until cuantos) {
            if (frecuencias[i] > 0f && abs(frecuencias[i] - hz) < margen) {
                ociosos[i] = 0
                if (profundidades[i] > dbMaximo) {
                    profundidades[i] += dbPaso
                    if (profundidades[i] < dbMaximo) profundidades[i] = dbMaximo
                    filtros[i].notch(frecuencias[i], qPara(frecuencias[i]), profundidades[i], fs)
                }
                return
            }
        }

        // Uno libre, o el mas parado si no queda ninguno.
        var elegido = -1
        for (i in 0 until cuantos) {
            if (frecuencias[i] < 0f) { elegido = i; break }
        }
        if (elegido < 0) {
            var masViejo = 0
            for (i in 1 until cuantos) if (ociosos[i] > ociosos[masViejo]) masViejo = i
            elegido = masViejo
        } else {
            activos++
        }

        frecuencias[elegido] = hz
        profundidades[elegido] = dbInicial
        ociosos[elegido] = 0
        filtros[elegido].notch(hz, qPara(hz), dbInicial, fs)
        filtros[elegido].reiniciar()
    }

    /** Envejece los notches. Se llama una vez por bloque, no por muestra. */
    fun envejece(muestras: Int) {
        for (i in 0 until cuantos) {
            if (frecuencias[i] < 0f) continue
            ociosos[i] += muestras
            if (ociosos[i] > muestrasParaSoltar) {
                frecuencias[i] = -1f
                profundidades[i] = 0f
                filtros[i].enPlano()
                activos--
                if (activos < 0) activos = 0
            }
        }
    }

    /** Filtra una muestra por todos los notches puestos. */
    fun procesa(x: Float): Float {
        var m = x
        for (i in 0 until cuantos) {
            if (frecuencias[i] > 0f) m = filtros[i].procesa(m)
        }
        return m
    }

    /** Para pintarlo en la pantalla. */
    fun frecuenciaDe(i: Int): Float = frecuencias[i]
    fun profundidadDe(i: Int): Float = profundidades[i]
    fun capacidad(): Int = cuantos
}

// ---------------------------------------------------------------------------
// 5) PUERTA DE RUIDO CON UMBRAL ADAPTATIVO
// ---------------------------------------------------------------------------

/**
 * Puerta de ruido que APRENDE el ruido de la sala.
 *
 * El problema del umbral fijo (0.015 ahora mismo) es que la sala no siempre
 * suena igual: con la sala llena de gente el suelo de ruido sube y la puerta
 * se queda abierta todo el rato; con la sala vacia se queda corta. Aqui el
 * umbral se calcula SOBRE el ruido medido, asi que se ajusta solo. Esto ataca
 * directamente la queja de "capta todo el ruido de sala".
 *
 * Estimacion del suelo de ruido por minimos estadisticos simplificado
 * (Martin, IEEE TSAP 2001): se sigue el minimo con subida MUY lenta y bajada
 * rapida. Como el ruido de fondo es lo mas bajo que suena, el minimo movil
 * converge a el aunque haya voz por encima casi todo el rato.
 *
 * Ademas lleva HISTERESIS: abre a un nivel y cierra a otro mas bajo (3-6 dB
 * por debajo, que es lo que hacen las puertas de directo). Sin eso, con la
 * senal justo en el umbral la puerta abre y cierra decenas de veces por
 * segundo y suena a metralleta.
 */
class PuertaAdaptativa(private val fs: Int) {

    /** Cuantos dB por encima del ruido medido hay que estar para abrir. */
    var margenAperturaDb = 12f
    /** Histeresis: cierra 6 dB por debajo del umbral de apertura. */
    var histeresisDb = 6f
    /**
     * Cuanto se mantiene abierta tras la ultima voz ("hold"). Es el control
     * que de verdad evita el castaneteo: con 60-100 ms se salvan los huecos
     * entre silabas sin alargar la cola.
     */
    var msMantener = 80
    /** Bajada suave al cerrar. */
    var msCaida = 150
    /** Subida al abrir: 3 ms, sin clic pero sin comerse la silaba. */
    var msSubida = 3

    /**
     * SUELO de la puerta, en dB. NO se cierra del todo.
     *
     * Cerrar a silencio absoluto se nota como "agujeros": la sala desaparece
     * de golpe entre frase y frase y suena muy artificial. Dejando -25 dB se
     * quita el ruido de en medio pero el ambiente sigue ahi.
     *
     * Ademas aqui tiene un segundo efecto util: el detector de acople sigue
     * recibiendo algo de senal con la puerta cerrada, asi que no se "queda
     * ciego" y no suelta los notches justo antes de volver a abrir.
     */
    var sueloDb = -25f
    private var sueloLin = 0.056f

    /** Suelo de ruido estimado (RMS lineal). */
    var sueloRuido = 0.001f
        private set

    var abierta = false
        private set
    /** Ganancia suavizada 0..1 que hay que aplicar. */
    var ganancia = 0f
        private set

    private var muestrasMantener = 0

    // Coeficientes del seguidor de minimo, por BLOQUE (no por muestra).
    // Con bloques de 10 ms: subida tau ~2 s, bajada tau ~50 ms.
    private val bloquesPorSegundo = 100f
    private val subeMin = exp((-1.0 / (2.0 * bloquesPorSegundo))).toFloat()
    private val bajaMin = exp((-1.0 / (0.05 * bloquesPorSegundo))).toFloat()

    private var coefSubida = 0f
    private var coefCaida = 0f

    init { recalcula() }

    fun recalcula() {
        coefSubida = exp(-1.0 / (msSubida / 1000.0 * fs)).toFloat()
        coefCaida = exp(-1.0 / (msCaida / 1000.0 * fs)).toFloat()
        sueloLin = Math.pow(10.0, sueloDb / 20.0).toFloat()
    }

    fun reiniciar() {
        sueloRuido = 0.001f
        abierta = false
        ganancia = sueloLin
        muestrasMantener = 0
    }

    /**
     * Se llama UNA vez por bloque con el RMS del bloque.
     * Devuelve si la puerta deberia estar abierta.
     */
    fun actualiza(rmsBloque: Float, muestras: Int): Boolean {
        // Seguimiento del suelo de ruido.
        sueloRuido = if (rmsBloque < sueloRuido) {
            // Baja rapido hacia el nuevo minimo.
            sueloRuido * bajaMin + rmsBloque * (1f - bajaMin)
        } else {
            // Sube muy despacio: asi la voz no arrastra el suelo hacia arriba.
            sueloRuido * subeMin + rmsBloque * (1f - subeMin)
        }
        if (sueloRuido < 1e-6f) sueloRuido = 1e-6f

        val umbralAbrir = sueloRuido * db2lin(margenAperturaDb)
        val umbralCerrar = umbralAbrir * db2lin(-histeresisDb)

        if (!abierta) {
            if (rmsBloque > umbralAbrir) {
                abierta = true
                muestrasMantener = msMantener * fs / 1000
            }
        } else {
            if (rmsBloque > umbralCerrar) {
                muestrasMantener = msMantener * fs / 1000
            } else {
                muestrasMantener -= muestras
                if (muestrasMantener <= 0) abierta = false
            }
        }
        return abierta
    }

    /** Ganancia suave, muestra a muestra. Nunca baja del suelo. */
    fun siguienteGanancia(): Float {
        val objetivo = if (abierta) 1f else sueloLin
        val c = if (objetivo > ganancia) coefSubida else coefCaida
        ganancia = objetivo + (ganancia - objetivo) * c
        return ganancia
    }

    private fun db2lin(db: Float): Float =
        Math.pow(10.0, db / 20.0).toFloat()
}

// ---------------------------------------------------------------------------
// 6) COMPRESOR
// ---------------------------------------------------------------------------

/**
 * Compresor de rango dinamico con rodilla suave, en dominio logaritmico.
 *
 * Implementa el diseno de Giannoulis, Massberg & Reiss ("Digital Dynamic
 * Range Compressor Design - A Tutorial and Analysis", JAES 2012), en la
 * variante feedforward con detector de pico suavizado y rama de ataque/caida
 * ("smooth, decoupled peak detector").
 *
 * Para que sirve aqui: el usuario lleva el micro a menos de 10 cm de la boca.
 * A esa distancia la diferencia entre hablar normal y levantar la voz son 15
 * o 20 dB. Sin comprimir hay que poner poca ganancia para que los picos no
 * saturen, y entonces lo bajito no se oye; o poner mucha y saturar. El
 * compresor aplana eso y deja subir el volumen medio sin acercarse al limite,
 * que es justo lo que ataca la queja de "distorsiona".
 */
class Compresor(private val fs: Int) {

    /** Umbral en dBFS a partir del cual empieza a comprimir. */
    var umbralDb = -24f
    /** Relacion de compresion. 3:1 es lo tipico en voz de megafonia. */
    var ratio = 3f
    /** Ancho de la rodilla en dB (rodilla suave = transicion gradual). */
    var rodillaDb = 6f
    /** Ataque en ms. */
    var msAtaque = 5f
    /** Caida en ms. */
    var msCaida = 120f
    /** Ganancia de compensacion en dB. */
    var makeupDb = 6f

    private var envDb = 0f        // reduccion suavizada, en dB (<= 0)
    private var coefAtaque = 0f
    private var coefCaida = 0f
    private var makeupLin = 1f

    init { recalcula() }

    fun recalcula() {
        // alpha = exp(-1 / (tau * fs)), la formula estandar del articulo.
        coefAtaque = exp(-1.0 / (msAtaque / 1000.0 * fs)).toFloat()
        coefCaida = exp(-1.0 / (msCaida / 1000.0 * fs)).toFloat()
        makeupLin = Math.pow(10.0, makeupDb / 20.0).toFloat()
    }

    fun reiniciar() { envDb = 0f }

    /**
     * Calculador de ganancia con rodilla suave.
     * Las tres ramas son las del articulo (ec. de "soft knee"):
     *   x < T - W/2          -> sin tocar
     *   |x - T| <= W/2       -> parabola de transicion
     *   x > T + W/2          -> compresion plena
     */
    private fun curva(xDb: Float): Float {
        val t = umbralDb
        val w = rodillaDb
        return when {
            2f * (xDb - t) < -w -> xDb
            2f * abs(xDb - t) <= w -> {
                val d = xDb - t + w / 2f
                xDb + (1f / ratio - 1f) * d * d / (2f * w)
            }
            else -> t + (xDb - t) / ratio
        }
    }

    /** Una muestra. Devuelve la muestra ya comprimida. */
    fun procesa(x: Float): Float {
        val a = abs(x)
        val xDb = if (a < 1e-7f) -140f else lin2db(a)

        // OJO AL ORDEN. El suavizado va DESPUES del calculador de ganancia,
        // no antes. Es la topologia "log-domain detector after the gain
        // computer" que recomienda el articulo: da una envolvente suave, SIN
        // retraso de ataque, y permite que la rodilla siga siendo suave.
        // Si se suaviza la entrada y luego se calcula la curva (que es el
        // error habitual), el ataque llega tarde y la rodilla se deforma.

        // 1) Cuanta reduccion PIDE esta muestra, sin suavizar nada.
        val reduccionPedida = curva(xDb) - xDb     // <= 0

        // 2) Suavizado de la REDUCCION, con ataque y caida separados.
        //    Como la reduccion es negativa, "atacar" es ir hacia abajo.
        envDb = if (reduccionPedida < envDb) {
            coefAtaque * envDb + (1f - coefAtaque) * reduccionPedida
        } else {
            coefCaida * envDb + (1f - coefCaida) * reduccionPedida
        }

        return x * db2lin(envDb) * makeupLin
    }

    /** Reduccion de ganancia actual en dB, para pintarla. */
    fun reduccionDb(): Float = envDb

    private fun lin2db(v: Float): Float =
        (20.0 * ln(v.toDouble()) / 2.302585092994046).toFloat()

    private fun db2lin(db: Float): Float =
        Math.pow(10.0, db / 20.0).toFloat()
}

// ---------------------------------------------------------------------------
// 7) LIMITADOR CON ANTICIPACION
// ---------------------------------------------------------------------------

/**
 * Limitador de pico con anticipacion (look-ahead).
 *
 * El limitador de ahora reacciona DESPUES de ver el pico, asi que el primer
 * golpe ya ha pasado recortado: eso es distorsion audible. Con anticipacion
 * se retrasa el audio unas muestras mientras la deteccion va por delante, de
 * forma que la ganancia ya esta bajada cuando el pico llega. 5 ms sobra para
 * voz y es un retardo que no se nota hablando por megafonia.
 *
 * El buffer circular esta preasignado; no reserva nada al procesar.
 */
class Limitador(fs: Int, msAnticipacion: Float = 5f) {

    /** Techo de salida (lineal). -1 dBFS = 0,891. */
    var techo = 0.891f

    /** Muestras de anticipacion pedidas. 5 ms a 48 kHz = 240. */
    private val n = (msAnticipacion / 1000f * fs).toInt().coerceAtLeast(2)

    // --- Dimensionado de la cadena de ganancia -----------------------------
    //
    // OJO, esto es lo que hace que el limitador NO rebase, y es facil de
    // hacer mal: suavizar con medias NO conserva el minimo. Si la ventana del
    // minimo movil dura solo una muestra, las dos medias se comen el valle y
    // la ganancia que llega al pico es mucho mayor que la que hacia falta
    // (medido: pedia 0,178 y aplicaba 0,384, o sea rebase del doble).
    //
    // La condicion correcta es que la MESETA del minimo dure al menos lo que
    // tardan las dos cajas en responder, es decir 2*(M-1) muestras. Con una
    // ventana de minimo de L = 2*M-1 la meseta dura justo eso, y el retardo
    // total de la cadena de ganancia es:
    //
    //     D = (L-1)/2 + (M-1) + (M-1) ... en la practica D = 2*(M-1)
    //
    // El AUDIO tiene que retrasarse exactamente D, ni mas ni menos: con menos
    // el pico se adelanta a su ganancia (rebase) y con mas se oye el bajon
    // antes que el golpe ("bombeo al reves").
    //
    // Elegimos M para que D ~= n, y asi la latencia sigue siendo la pedida.
    private val mCaja = (n / 2 + 1).coerceAtLeast(2)
    /** Ventana del minimo movil. */
    private val lMin = 2 * mCaja - 1
    /** Retardo total de la cadena de ganancia = retardo del audio. */
    private val retardo = 2 * (mCaja - 1)

    // Linea de retardo del AUDIO.
    private val linea = FloatArray(retardo.coerceAtLeast(1))
    private var iLinea = 0

    // --- Minimo movil en tiempo CONSTANTE ---------------------------------
    //
    // Recorrer la ventana entera por cada muestra costaria n comparaciones
    // (240 a 5 ms y 48 kHz), o sea 11,5 millones de comparaciones por segundo
    // solo para esto. Con una cola monotona el coste es CONSTANTE: cada valor
    // entra y sale como mucho una vez, asi que salen ~2 operaciones por
    // muestra en vez de 240.
    //
    // La cola guarda indices de valores en orden creciente. El minimo de la
    // ventana esta siempre en la cabeza.
    private val colaVal = FloatArray(lMin + 1)
    private val colaPos = LongArray(lMin + 1)
    private var cabeza = 0
    private var cola = 0
    private var reloj = 0L

    // Dos filtros de media movil en cascada, para suavizar la curva.
    private val caja1 = FloatArray(mCaja)
    private var iCaja1 = 0
    private var sumaCaja1 = 0f
    private val caja2 = FloatArray(mCaja)
    private var iCaja2 = 0
    private var sumaCaja2 = 0f

    init { reiniciar() }

    fun reiniciar() {
        java.util.Arrays.fill(linea, 0f)
        java.util.Arrays.fill(caja1, 1f)
        java.util.Arrays.fill(caja2, 1f)
        iLinea = 0; iCaja1 = 0; iCaja2 = 0
        sumaCaja1 = mCaja.toFloat()
        sumaCaja2 = mCaja.toFloat()
        // Cola vacia. El reloj arranca en lMin para que la resta
        // "reloj - lMin" no se vaya a negativo en las primeras muestras.
        cabeza = 0; cola = 0; reloj = lMin.toLong()
    }

    /**
     * Una muestra. Devuelve la muestra retrasada [n] y ya limitada.
     *
     * Los tres pasos, en este orden:
     *
     *  1. MINIMO MOVIL de la ganancia sobre la ventana de anticipacion. Esto
     *     es lo que garantiza que nunca haya rebase: la ganancia que se aplica
     *     ahora ya tiene en cuenta el pico mas fuerte que va a llegar dentro
     *     de la ventana.
     *  2. DOS MEDIAS MOVILES en cascada. Suavizan la curva de ganancia (una
     *     sola deja un codo; dos dan una rampa triangular, sin esquinas).
     *     CUIDADO: la media SI puede subir el valle (no conserva el minimo).
     *     Por eso la ventana del minimo dura [lMin] = 2*[mCaja]-1: asi la
     *     meseta es mas larga que la respuesta de las dos cajas y el valle
     *     llega entero al pico.
     *  3. El audio sale retrasado [retardo] muestras, que es EXACTAMENTE el
     *     retardo de la cadena de ganancia, para que pico y ganancia casen.
     *
     * Todo con sumas corridas: coste constante por muestra, sin recorrer la
     * ventana y sin reservar nada.
     */
    fun procesa(x: Float): Float {
        // Ganancia que pide ESTA muestra (la que aun no ha salido).
        val a = abs(x)
        val pedida = if (a > techo) techo / a else 1f

        // --- 1) Minimo movil, en tiempo constante ---
        // Quitamos por la cola todo lo que sea mayor o igual: ya no puede ser
        // nunca el minimo, porque el nuevo valor es menor y ademas mas joven.
        val tam = colaVal.size
        while (cola != cabeza) {
            val ultimo = if (cola == 0) tam - 1 else cola - 1
            if (colaVal[ultimo] >= pedida) cola = ultimo else break
        }
        colaVal[cola] = pedida
        colaPos[cola] = reloj
        cola++
        if (cola >= tam) cola = 0

        // Y quitamos por la cabeza lo que ya se ha salido de la ventana.
        while (colaPos[cabeza] <= reloj - lMin) {
            cabeza++
            if (cabeza >= tam) cabeza = 0
        }
        reloj++

        val minimo = colaVal[cabeza]

        // --- 2) Dos medias moviles en cascada ---
        sumaCaja1 += minimo - caja1[iCaja1]
        caja1[iCaja1] = minimo
        iCaja1++
        if (iCaja1 >= mCaja) iCaja1 = 0
        val suave1 = sumaCaja1 / mCaja

        sumaCaja2 += suave1 - caja2[iCaja2]
        caja2[iCaja2] = suave1
        iCaja2++
        if (iCaja2 >= mCaja) iCaja2 = 0
        val suave2 = sumaCaja2 / mCaja

        // --- 3) Audio retrasado por la ganancia suavizada ---
        val retrasada = linea[iLinea]
        linea[iLinea] = x
        iLinea++
        if (iLinea >= linea.size) iLinea = 0

        var y = retrasada * suave2
        // Red de seguridad. Se recorta contra el TECHO, no contra 1,0: las
        // sumas corridas de las cajas acumulan un error de coma flotante de
        // milesimas de dB, y un limitador no puede pasarse de su techo ni por
        // eso. Recortar en 1,0 no servia de nada (el techo es 0,891).
        val t = techo
        if (y > t) y = t
        if (y < -t) y = -t
        return y
    }

    /** Retardo que introduce, en muestras. Hay que contarlo en la latencia. */
    fun retardoMuestras(): Int = retardo
}

// ---------------------------------------------------------------------------
// 8) DECIMADOR
// ---------------------------------------------------------------------------

/**
 * Baja de 48 kHz a 12 kHz para el analisis espectral.
 *
 * Por que decimar: el acople en voz vive entre 200 Hz y 4 kHz. Analizar hasta
 * 24 kHz es pagar una FFT cuatro veces mas grande para mirar una zona donde
 * no pasa nada. A 12 kHz, una FFT de 512 da 23,4 Hz de resolucion con ventana
 * de 42,7 ms; a 48 kHz haria falta una de 2048 para la misma resolucion, que
 * cuesta casi 5 veces mas.
 *
 * Lleva un paso bajo antialias a 5 kHz (dos biquads en cascada, 24 dB/oct)
 * antes de quedarse con 1 de cada 4 muestras. Sin ese filtro, el ruido de
 * arriba se plegaria sobre la banda de interes y saldrian picos fantasma que
 * el detector confundiria con acople.
 */
class Decimador(fs: Int, private val factor: Int = 4) {
    private val lp1 = Biquad()
    private val lp2 = Biquad()
    private var cuenta = 0

    init {
        // Paso bajo Butterworth de 4o orden a 5 kHz (el Nyquist de 12 kHz es
        // 6 kHz, asi que 5 kHz deja un margen razonable de transicion).
        // 0,5412 y 1,3066 son los Q de las dos secciones de un Butterworth
        // de orden 4; en cascada dan una caida plana de 24 dB/octava.
        lp1.pasoBajoRbj(5000f, 0.5412f, fs)
        lp2.pasoBajoRbj(5000f, 1.3066f, fs)
    }

    fun reiniciar() {
        lp1.reiniciar(); lp2.reiniciar(); cuenta = 0
    }

    /**
     * Mete una muestra a 48 kHz. Devuelve true y deja el valor en [salida]
     * cuando toca entregar una muestra decimada.
     */
    var salida = 0f
        private set

    fun empuja(x: Float): Boolean {
        val y = lp2.procesa(lp1.procesa(x))
        cuenta++
        if (cuenta >= factor) {
            cuenta = 0
            salida = y
            return true
        }
        return false
    }
}

// ---------------------------------------------------------------------------
// 9) LA CADENA COMPLETA
// ---------------------------------------------------------------------------

/**
 * La cadena antiacople entera, lista para meter en el bucle de audio.
 *
 * ORDEN Y POR QUE ESE ORDEN:
 *
 *   entrada -> PASO ALTO -> NOTCHES -> [analisis] -> PUERTA -> COMPRESOR
 *           -> ganancia -> LIMITADOR -> salida
 *
 *  1. PASO ALTO el primero. Quita el retumbe y el ruido de manejo ANTES de
 *     medir nada, para que no ensucien ni el calculo del RMS ni el espectro.
 *  2. NOTCHES antes de la puerta. Es lo mas importante del orden: el acople
 *     nace y crece cuando la puerta esta ABIERTA, y hay que matarlo dentro del
 *     lazo. Si los notches fuesen despues del compresor, el compresor estaria
 *     reaccionando al pitido y bajando toda la voz por su culpa.
 *  3. EL ANALISIS mira la senal DESPUES de los notches. Asi el detector ve el
 *     efecto de sus propios filtros: si el notch de -3 dB no ha bastado, el
 *     tono sigue ahi, vuelve a detectarse y el notch profundiza a -6. Ese
 *     lazo cerrado es lo que da la profundidad progresiva.
 *  4. PUERTA despues de los notches. Si estuviera antes, al cerrarse dejaria
 *     al detector sin senal y este creeria que el acople ha desaparecido,
 *     soltando los notches justo antes de volver a abrir. Pingpong asegurado.
 *  5. COMPRESOR despues de la puerta. Comprimir antes de cerrar la puerta
 *     seria amplificar el ruido de sala en los silencios, que es justo de lo
 *     que se queja el usuario.
 *  6. LIMITADOR el ultimo, siempre. Es la red de seguridad: nada que vaya
 *     detras de el puede volver a pasarse de 0 dBFS.
 *
 * El detector trabaja sobre la senal DECIMADA a 12 kHz, en paralelo: no esta
 * en el camino del audio, solo mira.
 *
 * COSTE, por bloque de 10 ms (480 muestras) a 48 kHz, contando
 * multiplicaciones:
 *
 *   9 biquads (1 paso alto + 8 notches)  ~21.600
 *   decimador (2 biquads)                 ~4.800
 *   compresor + limitador                 ~5.760
 *   FFT de 512 (salta 1 de cada ~2 bloques, amortizada)  ~4.320
 *   ------------------------------------------------------------
 *   TOTAL                                ~36.500 mult/bloque
 *                                        = ~3,6 millones/segundo
 *
 * Para cualquier movil ARM de los ultimos diez anos eso no es nada. El grueso
 * son los BIQUADS, no la FFT: si algun dia hubiera que recortar, se quitan
 * notches antes que tocar el analisis.
 *
 * LATENCIA: 5 ms, los del limitador. La deteccion tarda ~43 ms de ventana mas
 * 3 tramas de confirmacion (~64 ms) en clavar un notch, pero eso NO retrasa el
 * audio: el sonido pasa siempre de largo.
 */
class CadenaAntiacople(private val fs: Int) {

    // --- Ajustes ------------------------------------------------------------
    @Volatile var pasoAltoActivo = true
    @Volatile var notchesActivos = true
    @Volatile var puertaActiva = true
    @Volatile var compresorActivo = true

    /**
     * Retardo de decorrelacion en milisegundos (0 = apagado).
     *
     * Sube el margen antes de acoplar a cambio de latencia. Con el altavoz
     * por Bluetooth el retardo ya es grande, asi que unos pocos ms mas no
     * cambian la sensacion y si reparten los picos del lazo.
     */
    @Volatile var msDecorrelacion = 0f

    /**
     * Desplazamiento de frecuencia en Hz (0 = apagado).
     *
     * La otra forma de romper el lazo, y mas barata en latencia que el
     * retardo: en vez de mover CUANDO vuelve la senal, mueve QUE frecuencia
     * vuelve. 3-5 Hz no se notan en la voz y suben bastante el margen.
     */
    @Volatile var hzDesplazamiento = 0f

    /**
     * Sensibilidad del microfono: multiplica la senal segun ENTRA, antes de
     * que la toque nada.
     *
     * No es lo mismo que `ganancia`, que multiplica al SALIR. Si el micro
     * entra demasiado fuerte, bajarlo al final no arregla nada: la puerta ya
     * dio por buena la sala entera, el detector ya vio picos donde no los
     * habia y el compresor ya apreto de mas. Se corrige aqui o no se corrige.
     */
    @Volatile var gananciaEntrada = 1f

    /** Volumen de salida (lineal). Multiplica al final de la cadena. */
    @Volatile var ganancia = 1f

    // --- Bloques ------------------------------------------------------------
    private val pasoAlto = Biquad()
    // 1/3 de octava, no 1/10. La nota tecnica 158 de Rane lo dice claro: los
    // notches estrechos rinden PEOR cuando la sala cambia (alguien se mueve,
    // se gira el altavoz), porque el pico del acople se les escapa al lado y
    // hay que meterles 20 dB para conseguir poco. Uno mas ancho y suave
    // sigue tapando el pitido aunque se desplace un poco, y se nota menos en
    // la voz.
    private val notches = BancoNotches(fs, cuantos = 8, octavas = 0.33f)
    private val decimador = Decimador(fs, factor = 4)
    private val detector = DetectorAcople(fsAnalisis = fs / 4, nFft = 512)
    private val puerta = PuertaAdaptativa(fs)
    private val compresor = Compresor(fs)
    private val limitador = Limitador(fs, msAnticipacion = 5f)
    private val decorrelacion = RetardoDecorrelacion(fs, msMaximos = 40f)
    private val desplazador = DesplazadorFrecuencia(fs, taps = 101)

    // --- Medidas para la pantalla ------------------------------------------
    @Volatile var picoEntrada = 0f; private set
    @Volatile var picoSalida = 0f; private set
    @Volatile var hzAcople = -1f; private set
    @Volatile var notchesPuestos = 0; private set
    @Volatile var puertaAbierta = false; private set
    @Volatile var sueloRuido = 0f; private set
    @Volatile var reduccionCompresor = 0f; private set

    /**
     * Pico del microfono ANTES de tocarlo. Si esto llega a 1,0 el micro ya
     * entra recortado y no hay filtro que lo arregle: hay que bajar la
     * sensibilidad o separar el micro de la boca.
     */
    @Volatile var picoCrudoMedido = 0f; private set

    // Suavizado de la ganancia del usuario, para que mover el mando no
    // produzca un chasquido.
    private var gananciaSuave = 0f
    // Arranca en el valor que ya tenga el mando, no en 1f fijo: la cadena se
    // construye de cero cada vez que se pulsa "arrancar", y sembrarla en 1f
    // haria que los primeros 20 ms sonaran con la sensibilidad de fabrica en
    // vez de con la que el usuario dejo puesta.
    private var gananciaEntradaSuave = gananciaEntrada
    private val coefGanancia = exp(-1.0 / (0.02 * fs)).toFloat()   // 20 ms

    /**
     * Buffer intermedio en coma flotante, PREASIGNADO.
     *
     * Entre la primera y la segunda pasada NO se puede volver a 16 bits: el
     * paso alto puede sacar picos por encima de 1,0 (sobre todo con graves
     * fuertes) y guardarlos en Short los recortaria antes de que el limitador
     * tenga ocasion de hacer su trabajo; ademas se anadiria ruido de
     * cuantificacion en mitad de la cadena. Se dimensiona generoso (medio
     * segundo) para aceptar cualquier tamano de bloque que mande Android.
     */
    private val intermedio = FloatArray(fs / 2)

    init {
        // 12 dB/octava a 120 Hz, Q de Butterworth (0,7071).
        pasoAlto.pasoAlto(120f, 0.7071f, fs)
    }

    fun reiniciar() {
        pasoAlto.reiniciar()
        notches.reiniciar()
        decimador.reiniciar()
        detector.reiniciar()
        puerta.reiniciar()
        compresor.reiniciar()
        limitador.reiniciar()
        decorrelacion.reiniciar()
        desplazador.reiniciar()
        gananciaSuave = 0f
        gananciaEntradaSuave = gananciaEntrada
    }

    /** Suelta todos los notches (boton "reiniciar antiacople"). */
    fun soltarNotches() = notches.reiniciar()

    /**
     * Retardo que anade la cadena, en milisegundos.
     *
     * Lo pone entero el limitador con su anticipacion. Conviene tenerlo a la
     * vista: en megafonia el retardo se SUMA al del camino acustico, y cuanto
     * mas retardo hay, mas juntas caen las frecuencias donde la sala puede
     * acoplar. 5 ms es un precio pequeno por quitar la distorsion de picos,
     * pero no conviene subirlo alegremente.
     */
    fun retardoMs(): Float {
        // El desplazador solo cuenta si esta encendido: apagado no llega a
        // meter la senal en su linea de retardo, asi que no retrasa nada.
        val msDesplaza =
            if (hzDesplazamiento != 0f) desplazador.retardoMuestras() * 1000f / fs else 0f
        return limitador.retardoMuestras() * 1000f / fs + msDecorrelacion + msDesplaza
    }

    /**
     * Procesa un bloque, EN EL SITIO, de PCM 16 bits.
     *
     * No reserva memoria. Todo lo que usa esta preasignado en el constructor.
     *
     * @param bloque muestras de entrada; se sobreescriben con la salida
     * @param n      cuantas muestras valen de verdad
     */
    fun procesa(bloque: ShortArray, n: Int) {
        // Nunca deberia pasar (el bloque son ~10 ms y el buffer medio segundo),
        // pero mas vale procesar de menos que reventar el hilo de audio con una
        // excepcion de indice.
        val muestras = if (n > intermedio.size) intermedio.size else n
        // Con un bloque vacio el RMS seria 0/0 = NaN, y ese NaN se colaria en
        // el suelo de ruido de la puerta, que es un estado PEGAJOSO: una vez
        // NaN, toda comparacion da false y la puerta se queda cerrada para
        // siempre. Se sale antes de tocar nada.
        if (muestras <= 0) return

        var pico = 0f
        var picoCrudo = 0f
        var sumaCuadrados = 0f

        // --- Primera pasada: paso alto, notches, analisis y medidas ---------
        for (i in 0 until muestras) {
            var m = bloque[i] * (1f / 32768f)

            // 0) Sensibilidad del microfono. Lo PRIMERO de todo: lo que pase
            //    de aqui ya condiciona a la puerta, al detector y al
            //    compresor. Se mide antes de aplicarla para poder avisar de
            //    que el micro entra saturado de origen, que no se arregla
            //    con ningun filtro posterior.
            val crudo = abs(m)
            if (crudo > picoCrudo) picoCrudo = crudo

            //    OJO AL ORDEN: primero se actualiza el suavizado y luego se
            //    aplica, igual que con la ganancia de salida del paso 6. Al
            //    reves la primera muestra de cada bloque usaria el valor
            //    viejo y los dos mandos irian desfasados entre si.
            gananciaEntradaSuave = gananciaEntrada +
                (gananciaEntradaSuave - gananciaEntrada) * coefGanancia
            m *= gananciaEntradaSuave

            // 1) Paso alto.
            if (pasoAltoActivo) m = pasoAlto.procesa(m)

            // 2) Notches: dentro del lazo, antes de medir.
            if (notchesActivos) m = notches.procesa(m)

            // 3) Analisis espectral sobre la senal decimada.
            //    Va en paralelo: mira, no toca.
            if (notchesActivos && decimador.empuja(m)) {
                if (detector.empuja(decimador.salida)) {
                    val hz = detector.frecuenciaDetectada
                    hzAcople = hz
                    if (hz > 0f) notches.notificaAcople(hz)
                }
            }

            val a = abs(m)
            if (a > pico) pico = a
            sumaCuadrados += m * m

            // En coma flotante, sin recortar: lo que se pase de 1,0 lo
            // arreglara el limitador al final, que para eso esta.
            intermedio[i] = m
        }

        picoEntrada = pico
        picoCrudoMedido = picoCrudo
        val rms = kotlin.math.sqrt(sumaCuadrados / muestras)

        // --- Decisiones por bloque (no por muestra) -------------------------
        decorrelacion.muestras = ((msDecorrelacion * fs) / 1000f).toInt()
        puerta.actualiza(rms, muestras)
        puertaAbierta = puerta.abierta
        sueloRuido = puerta.sueloRuido
        notches.envejece(muestras)
        notchesPuestos = notches.activos

        // --- Segunda pasada: puerta, compresor, ganancia y limitador --------
        var picoOut = 0f
        // Se lee UNA vez por bloque, no por muestra: si el usuario mueve el
        // mando a mitad de bloque, el salto de fase del oscilador daria un
        // chasquido. Asi el cambio cae siempre en el borde de un bloque.
        val hzDesp = hzDesplazamiento
        for (i in 0 until muestras) {
            var m = intermedio[i]

            // 3b) Desplazamiento de frecuencia. Va DENTRO del lazo (despues
            //     de los notches, antes de la puerta): tiene que estar en el
            //     camino de la senal amplificada para que lo que vuelva al
            //     microfono venga ya desafinado.
            m = desplazador.procesa(m, hzDesp)

            // 4) Puerta.
            if (puertaActiva) m *= puerta.siguienteGanancia()

            // 5) Compresor.
            if (compresorActivo) m = compresor.procesa(m)

            // 6) Ganancia del usuario, suavizada.
            gananciaSuave = ganancia + (gananciaSuave - ganancia) * coefGanancia
            m *= gananciaSuave

            // 7) Retardo de decorrelacion: mueve las frecuencias donde la
            //    sala realimenta, para que ninguna se lleve toda la ganancia.
            m = decorrelacion.procesa(m)

            // 8) Limitador con anticipacion. Siempre el ultimo.
            m = limitador.procesa(m)

            val a = abs(m)
            if (a > picoOut) picoOut = a
            bloque[i] = (m * 32767f).toInt().toShort()
        }

        picoSalida = picoOut
        reduccionCompresor = compresor.reduccionDb()
    }

    // --- Acceso a los notches para pintarlos --------------------------------
    fun notchFrecuencia(i: Int) = notches.frecuenciaDe(i)
    fun notchProfundidad(i: Int) = notches.profundidadDe(i)
    fun notchesCapacidad() = notches.capacidad()
}

// ---------------------------------------------------------------------------
// 10) RETARDO DE DECORRELACION
// ---------------------------------------------------------------------------

/**
 * Retrasa la salida unos milisegundos.
 *
 * Es una de las tecnicas clasicas de megafonia y parece contraintuitiva:
 * anadir retardo para que acople MENOS. La razon es que el lazo de la sala
 * refuerza unas frecuencias concretas (las que vuelven en fase); al cambiar
 * el tiempo de vuelta, esas frecuencias se mueven y se reparten, en vez de
 * juntarse siempre en los mismos picos. Ningun tono concreto se lleva toda
 * la ganancia del lazo.
 *
 * Se paga en latencia, asi que conviene poco: 5-20 ms.
 */
class RetardoDecorrelacion(fs: Int, msMaximos: Float = 40f) {

    private val buffer = FloatArray(((fs * msMaximos) / 1000f).toInt().coerceAtLeast(1))
    private var escribe = 0

    /** Retardo pedido, en muestras. 0 = pasa directo. */
    @Volatile var muestras = 0
        set(v) {
            field = v.coerceIn(0, buffer.size - 1)
        }

    fun reiniciar() {
        buffer.fill(0f)
        escribe = 0
    }

    fun procesa(x: Float): Float {
        val n = muestras
        if (n <= 0) return x

        buffer[escribe] = x
        // Leer n muestras por detras de donde escribimos.
        var lee = escribe - n
        if (lee < 0) lee += buffer.size
        val y = buffer[lee]

        escribe++
        if (escribe >= buffer.size) escribe = 0
        return y
    }

    fun capacidadMs(fs: Int): Float = buffer.size * 1000f / fs
}

// ---------------------------------------------------------------------------
// 11) DESPLAZADOR DE FRECUENCIA
// ---------------------------------------------------------------------------

/**
 * Desplaza TODO el espectro unos pocos Hz hacia arriba.
 *
 * Es la tecnica de Schroeder (1964), y es la hermana fina del retardo de
 * decorrelacion. El lazo de la sala se realimenta porque la senal vuelve al
 * microfono EN FASE consigo misma: cada vuelta refuerza la anterior y el
 * tono crece hasta el pitido. Si cada vuelta sale con la frecuencia movida
 * unos pocos Hz, la senal ya nunca coincide consigo misma: tras varias
 * vueltas el tono se ha ido tan lejos del pico de la sala que deja de
 * encontrar ganancia, y el lazo no llega a cerrarse. Se ganan del orden de
 * 6 a 10 dB antes de acoplar.
 *
 * Por que 5 Hz y no 50: el oido no nota un desplazamiento pequeno en la voz
 * (los formantes se mueven todos juntos y el cerebro lo ignora), pero SI
 * nota que las relaciones armonicas se rompen. A 5 Hz la voz suena igual;
 * pasado de ahi empieza a sonar metalica, y con musica se nota mucho antes
 * porque los acordes se desafinan entre si.
 *
 * COMO se hace: modulacion SSB (banda lateral unica). Multiplicar por un
 * coseno sin mas no vale, porque genera las DOS bandas laterales (una
 * subida y otra bajada) y el resultado suena a modulador en anillo. Hay que
 * construir primero la senal analitica (la senal y su version desfasada 90
 * grados, que da la transformada de Hilbert) y luego modular en cuadratura:
 *
 *     y = re * cos(2*pi*f*t) - im * sin(2*pi*f*t)
 *
 * El termino que se resta cancela la banda inferior y solo sobrevive la
 * superior, que es justo el espectro entero corrido +f Hz.
 *
 * La parte imaginaria sale de un FIR de Hilbert, y la real del MISMO
 * numero de muestras de retardo, porque si no las dos partes no hablarian
 * del mismo instante y la cancelacion de la banda no seria limpia.
 */
class DesplazadorFrecuencia(private val fs: Int, taps: Int = 101) {

    /**
     * El FIR de Hilbert tiene que tener un numero IMPAR de taps (101, no
     * 100) para que el centro caiga sobre una muestra exacta. Asi el retardo
     * del filtro son (taps-1)/2 muestras JUSTAS y la parte real se puede
     * alinear con un simple retardo entero. Con taps par el centro caeria
     * entre dos muestras y haria falta interpolar.
     */
    private val n = if (taps % 2 == 0) taps + 1 else taps
    private val centro = n / 2

    /** Coeficientes del FIR de Hilbert, con ventana de Blackman. */
    private val h = FloatArray(n)

    /**
     * Linea de retardo circular COMPARTIDA por las dos ramas: la imaginaria
     * la convoluciona entera y la real solo lee la muestra del centro. Un
     * unico buffer y una unica escritura por muestra.
     */
    private val linea = FloatArray(n)
    private var escribe = 0

    // Oscilador por recurrencia: girar un vector unitario multiplicandolo
    // por (cosPaso, sinPaso) en cada muestra sale mucho mas barato que
    // llamar a sin/cos, que en el hilo de audio si se notan. El precio es
    // que el vector pierde modulo por redondeo, asi que se renormaliza cada
    // cierto tiempo (ver mas abajo).
    private var oscCos = 1f
    private var oscSin = 0f
    private var pasoCos = 1f
    private var pasoSin = 0f
    private var cuentaNormaliza = 0

    /** Hz del ultimo paso calculado, para no rehacer el seno por muestra. */
    private var hzActual = Float.NaN

    init {
        // Respuesta ideal del Hilbert: h[k] = 2/(pi*k) para k impar, 0 para
        // k par. Se trunca a n taps y se enventana con Blackman, que deja el
        // rizado de la banda de paso por debajo de 0,1 dB; con truncado a
        // secas (ventana rectangular) el rizado de Gibbs llegaria a 1 dB y
        // la banda lateral no cancelaria bien en los extremos.
        //
        // OJO AL SIGNO: en la convolucion el tap h[i] multiplica a la
        // muestra retrasada i, asi que el nucleo se recorre AL REVES que el
        // eje k de la formula. Si se escribe +2/(pi*k) tal cual, lo que sale
        // es MENOS la transformada de Hilbert, y entonces la modulacion en
        // cuadratura cancela la banda que no toca: el espectro baja 5 Hz en
        // vez de subirlos. Medido: el pico salia en 995 Hz, no en 1005. De
        // ahi el signo negativo.
        for (i in 0 until n) {
            val k = i - centro
            val ideal = if (k % 2 == 0) 0.0 else -2.0 / (Math.PI * k)
            val t = 2.0 * Math.PI * i / (n - 1)
            val w = 0.42 - 0.5 * cos(t) + 0.08 * cos(2.0 * t)
            h[i] = (ideal * w).toFloat()
        }
    }

    /**
     * Retardo que introduce, en muestras. Es el del FIR y es FIJO: no
     * depende del desplazamiento pedido. Se expone para que la cadena lo
     * pueda sumar a lo que le ensena al usuario.
     */
    fun retardoMuestras(): Int = centro

    fun reiniciar() {
        linea.fill(0f)
        escribe = 0
        oscCos = 1f
        oscSin = 0f
        cuentaNormaliza = 0
    }

    /**
     * Procesa una muestra.
     *
     * @param x  muestra de entrada
     * @param hz desplazamiento pedido. 0 = paso directo.
     */
    fun procesa(x: Float, hz: Float): Float {
        // Apagado: se sale ANTES de tocar la linea de retardo. Asi no se
        // gasta CPU en convolucionar 101 taps para nada y, sobre todo, no se
        // anade el retardo del FIR cuando el usuario no ha pedido nada. La
        // contrapartida es que al encender se arranca con la linea llena de
        // ceros; son 101 muestras a 48 kHz (2 ms), no se oye.
        if (hz == 0f) return x

        // El paso del oscilador solo se recalcula cuando el usuario mueve el
        // mando, no en cada muestra.
        if (hz != hzActual) {
            hzActual = hz
            val w = 2.0 * Math.PI * hz / fs
            pasoCos = cos(w).toFloat()
            pasoSin = sin(w).toFloat()
        }

        // Meter la muestra en la linea circular.
        linea[escribe] = x
        escribe++
        if (escribe >= n) escribe = 0

        // Convolucion del Hilbert. Tras avanzar, `escribe` apunta a la
        // muestra MAS ANTIGUA (la que se pisara la proxima vez), que es
        // justo la que multiplica a h[0].
        var im = 0f
        var idx = escribe
        for (i in 0 until n) {
            // La mitad de los taps son cero (los de indice par respecto al
            // centro): saltarselos ahorra la mitad de las multiplicaciones.
            if ((i - centro) % 2 != 0) im += h[i] * linea[idx]
            idx++
            if (idx >= n) idx = 0
        }

        // Parte real: la misma senal retardada `centro` muestras, para que
        // las dos ramas hablen del mismo instante.
        var idxRe = escribe + centro
        if (idxRe >= n) idxRe -= n
        val re = linea[idxRe]

        // Modulacion en cuadratura. El signo menos es lo que cancela la
        // banda lateral inferior y deja solo la superior (desplazamiento
        // hacia ARRIBA).
        val y = re * oscCos - im * oscSin

        // Girar el oscilador una muestra: (cos,sin) *= (pasoCos,pasoSin).
        val nuevoCos = oscCos * pasoCos - oscSin * pasoSin
        val nuevoSin = oscSin * pasoCos + oscCos * pasoSin
        oscCos = nuevoCos
        oscSin = nuevoSin

        // La recurrencia pierde modulo poco a poco (en float se nota en
        // segundos). Sin esto la senal se iria apagando o creciendo sola.
        // Renormalizar lleva una raiz cuadrada, asi que se hace una vez cada
        // 1024 muestras (21 ms a 48 kHz): el error acumulado en ese tramo es
        // despreciable y el coste se reparte.
        cuentaNormaliza++
        if (cuentaNormaliza >= 1024) {
            cuentaNormaliza = 0
            val mod = kotlin.math.sqrt(oscCos * oscCos + oscSin * oscSin)
            if (mod > 1e-6f) {
                oscCos /= mod
                oscSin /= mod
            } else {
                // No deberia pasar nunca, pero si el modulo llegase a cero el
                // oscilador quedaria muerto y la salida muda para siempre.
                oscCos = 1f
                oscSin = 0f
            }
        }

        return y
    }
}
