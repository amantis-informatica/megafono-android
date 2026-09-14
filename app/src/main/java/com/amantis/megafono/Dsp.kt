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
     * Cancelador de eco con senal de referencia.
     *
     * Ataca la VOZ REPETIDA, que es un problema distinto del pitido. Ver la
     * cabecera de [CanceladorEco].
     */
    @Volatile var aecActivo = false

    /**
     * Filtro de voz: deja pasar lo que tiene estructura armonica de voz y
     * atenua lo demas. Ataca el RUIDO DE SALA.
     */
    @Volatile var filtroVozActivo = false

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
    // Cola de 50 ms, no de 200. En un megafono portatil el camino acustico es
    // CORTO (3 ms de vuelo a 1 m mas la cola del cuerpo y la ropa, que se
    // apaga en 20-30 ms); los 150-250 ms del Bluetooth son un retardo PURO que
    // resuelve la alineacion, no algo que el filtro tenga que modelar. Un
    // filtro corto converge mucho mas rapido y con menos ruido de gradiente,
    // y ademas cuesta 4 veces menos.
    private val aec = CanceladorEco(fs, msCola = 50f)
    private val filtroVoz = FiltroVoz(fs)
    private val calibrador = CalibradorEco(fs)

    // --- Medidas para la pantalla ------------------------------------------
    @Volatile var picoEntrada = 0f; private set
    @Volatile var picoSalida = 0f; private set
    @Volatile var hzAcople = -1f; private set
    @Volatile var notchesPuestos = 0; private set
    @Volatile var puertaAbierta = false; private set
    @Volatile var sueloRuido = 0f; private set
    @Volatile var reduccionCompresor = 0f; private set

    /** Cuanto esta cancelando el AEC, en dB (ERLE). */
    @Volatile var erleAec = 0f; private set

    /** Retardo medido entre altavoz y microfono, en ms. -1 si aun no lo sabe. */
    @Volatile var retardoEcoMs = -1f; private set

    /** Si el AEC esta adaptandose ahora mismo. */
    @Volatile var aecAdaptando = false; private set

    /** Si el AEC cree que hay doble habla. */
    @Volatile var aecDobleHabla = false; private set

    /** 0..1: cuanta estructura de voz ve el filtro de voz. */
    @Volatile var probabilidadVoz = 0f; private set

    /** Tono fundamental detectado por el filtro de voz, en Hz. -1 si no hay. */
    @Volatile var tonoVozHz = -1f; private set

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
        aec.reiniciar()
        filtroVoz.reiniciar()
        erleAec = 0f
        retardoEcoMs = -1f
        aecAdaptando = false
        aecDobleHabla = false
        probabilidadVoz = 0f
        tonoVozHz = -1f
        gananciaSuave = 0f
        gananciaEntradaSuave = gananciaEntrada
    }

    /** Suelta todos los notches (boton "reiniciar antiacople"). */
    fun soltarNotches() = notches.reiniciar()

    // --- CALIBRACION DEL CAMINO DE ECO --------------------------------------

    /**
     * Si la calibracion esta en marcha. Mientras lo este, por el altavoz sale
     * la senal de prueba y NO la voz: el usuario tiene que callarse.
     */
    @Volatile var calibrando = false; private set

    /** Progreso de la calibracion, 0..1, para pintar una barra. */
    val calibracionProgreso: Float get() = calibrador.progreso

    /** Retardo que midio la ultima calibracion, en ms. -1 si no hay. */
    @Volatile var calibracionRetardoMs = -1f; private set

    /** Lo clara que salio la ultima calibracion, 0..1. */
    @Volatile var calibracionCalidad = 0f; private set

    /**
     * Arranca la calibracion: 1-2 segundos emitiendo una rafaga de ruido para
     * medir el retardo real del camino altavoz -> aire -> microfono.
     *
     * Se llama desde la pantalla (boton "Calibrar"). El trabajo lo hace el
     * hilo de audio dentro de [procesa].
     */
    fun calibra() {
        calibrador.arranca()
        calibracionRetardoMs = -1f
        calibracionCalidad = 0f
        calibrando = true
    }

    /**
     * PREAJUSTE "MEGAFONO PORTATIL".
     *
     * Deja la cadena lista para el escenario real de una vez, para que el
     * usuario no tenga que entender ocho mandos. Cada valor tiene su motivo:
     *
     *  - AEC APAGADO. Medido en este mismo escenario: 30-33 dB en lazo
     *    abierto pero solo 0,3-1,5 dB en el lazo cerrado de un megafono, con
     *    la calibracion puesta y el filtro congelado. Ver el cuerpo del
     *    metodo. El interruptor sigue a la vista para el uso de manos libres.
     *  - CONGELACION PREPARADA, por si se enciende el AEC a mano: con esta
     *    geometria es lo correcto, porque el camino no cambia.
     *  - NOTCHES ENCENDIDOS. Siguen siendo lo unico que mata el PITIDO.
     *  - FILTRO DE VOZ APAGADO. Se midio y EMPEORA el ruido de trafico
     *    (-10,65 -> -4,70 dB): el paso alto ya se lo habia llevado. Ver el
     *    cuerpo del metodo.
     *  - PUERTA ENCENDIDA Y AGRESIVA (margen 18 dB). Con el lavalier a 10 cm
     *    de la boca la voz entra muy por encima de la calle, asi que subir el
     *    margen quita mas ruido y apenas toca la voz.
     *  - DECORRELACION Y DESPLAZAMIENTO APAGADOS. Con el AEC funcionando
     *    sobran, y los dos tienen un precio: el retardo suma latencia al lazo
     *    y el desplazamiento desafina la voz. Ver [preajustePortatil] en el
     *    informe: medidos, no aportan con el AEC puesto.
     *  - GANANCIAS SENSATAS. Entrada a 1,0 (el lavalier ya entra fuerte a
     *    10 cm) y salida a 2,0, que con el compresor y el limitador detras da
     *    volumen de sobra sin acercarse al recorte.
     */
    fun preajustePortatil() {
        // EL AEC SE QUEDA APAGADO, y hay que explicarlo porque es lo contrario
        // de lo que parece que deberia salir del cambio de escenario.
        //
        // Se remidio todo con la geometria del megafono portatil (camino
        // corto, cola de 25 ms, microfono en el lobulo trasero, retardo del
        // Bluetooth de 200 ms) y con TODOS los arreglos puestos: cola del
        // filtro de 50 ms, calibracion que clava el retardo con 0,4 ms de
        // error, y congelacion al converger. Resultado:
        //
        //   - LAZO ABIERTO (referencia ajena): 30-33 dB. Muy bien.
        //   - LAZO CERRADO (megafono de verdad): entre 0,3 y 1,5 dB, y da
        //     igual el ajuste. Se probo con caminos de -17, -10 y -6 dB y con
        //     ganancias x2 y x4: la mejora nunca paso de 1,5 dB.
        //
        // La razon es la de siempre y no la arregla ninguna geometria: en un
        // megafono la referencia ES la voz del usuario amplificada, asi que
        // "cancelar el eco" y "cancelar la voz" son el mismo problema para el
        // filtro. La geometria fija ayudaba a CONVERGER, que era la hipotesis,
        // y efectivamente converge; lo que no puede es distinguir que parte
        // del microfono hay que quitar.
        //
        // Encenderlo por defecto seria gastar bateria y CPU para 1 dB, con el
        // riesgo de que en un movil real (donde el altavoz distorsiona y la
        // referencia no es exacta) haga mas mal que bien. Se deja el
        // interruptor a la vista para quien use el movil como manos libres,
        // que es donde si da sus 30 dB.
        aecActivo = false
        // Si alguien lo enciende a mano, que lo haga con la congelacion
        // puesta: es lo correcto para esta geometria.
        aec.congelaSiConverge = true
        notchesActivos = true
        // FILTRO DE VOZ APAGADO, y va contra lo que parecia obvio.
        //
        // La idea era que en la calle (trafico, gente) tenia que ayudar. Se
        // midio con la persona andando, lavalier a 10 cm y ruido a -34 dB de
        // la voz, comparando el ruido que sale en los SILENCIOS entre frases:
        //
        //   ruido de TRAFICO (grave):     sin el -10,65 dB | con el -4,70 dB
        //   ruido de BANDA ANCHA (gente): sin el  -5,16 dB | con el -5,77 dB
        //
        // O sea: con ruido de trafico lo EMPEORA casi 6 dB, y con ruido de
        // banda ancha lo mejora medio dB escaso. Y en los dos casos se come
        // algo mas de voz (de -0,85 a -1,3 dB).
        //
        // La razon es que el paso alto de 120 Hz ya se ha llevado el retumbe
        // del trafico, que es donde estaba casi toda la energia; lo que el
        // filtro de voz anade encima son artefactos de reconstruccion en la
        // banda que queda. Sigue estando el interruptor para quien tenga un
        // ruido de fondo muy de banda ancha, pero por defecto no.
        filtroVozActivo = false
        pasoAltoActivo = true
        puertaActiva = true
        compresorActivo = true
        // Con el AEC puesto, estas dos no aportan y si cuestan.
        msDecorrelacion = 0f
        hzDesplazamiento = 0f
        gananciaEntrada = 1f
        ganancia = 2f
        // La puerta puede ser mas agresiva: el lavalier esta a 10 cm de la
        // boca, asi que la voz entra muy por encima de la calle y subir el
        // margen no se come la voz.
        //
        // Medido con ruido de banda ancha, el ruido que sale en los silencios:
        //   margen 12 dB -> -6,51 dB (voz -0,85)
        //   margen 15 dB -> -6,75 dB (voz -0,89)
        //   margen 18 dB -> -7,08 dB (voz -1,03)
        // Se coge 18: es el que mas ruido quita y la voz solo pierde 0,2 dB
        // respecto a 12, que no se oye.
        puerta.margenAperturaDb = 18f
        puerta.recalcula()
    }

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
        // El AEC y el filtro de voz trabajan por bloques, asi que cada uno
        // suma su bloque de retardo cuando esta encendido.
        val msAec = if (aecActivo) aec.retardoMuestras() * 1000f / fs else 0f
        val msVoz = if (filtroVozActivo) filtroVoz.retardoMuestras() * 1000f / fs else 0f
        return limitador.retardoMuestras() * 1000f / fs + msDecorrelacion + msDesplaza + msAec + msVoz
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

        // --- CALIBRACION -----------------------------------------------------
        //
        // Mientras dura, la cadena NO procesa voz: por el altavoz sale la
        // rafaga de prueba y lo unico que se hace con el microfono es
        // guardarlo para correlarlo. Se sale antes de tocar nada mas.
        //
        // Va lo PRIMERO de todo a proposito: si la voz del usuario se colara
        // en la salida durante la calibracion, esa voz volveria por el camino
        // acustico y ensuciaria la correlacion, que es justo lo que la
        // calibracion viene a evitar.
        if (calibrando) {
            for (i in 0 until muestras) {
                val micro = bloque[i] * (1f / 32768f)
                val v = calibrador.siguienteMuestra(micro)
                bloque[i] = (v * 32767f).toInt().toShort()
                // La referencia del AEC tiene que llevar TAMBIEN la rafaga:
                // es lo que de verdad esta saliendo por el altavoz.
                aec.empujaReferencia(v)
            }
            if (calibrador.terminado) {
                calibrando = false
                calibracionCalidad = calibrador.calidad
                calibracionRetardoMs = calibrador.retardoMs
                // Solo se le pasa al AEC si la medida es de fiar. Si no lo es
                // (altavoz mudo, volumen al minimo, ruido enorme), se deja el
                // AEC como estaba: un retardo inventado es peor que ninguno.
                val ret = calibrador.retardoMuestras
                if (ret >= 0) aec.fijaRetardo(ret)
            }
            return
        }

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

            // 0b) CANCELADOR DE ECO, lo primero despues de la sensibilidad.
            //
            // Va ANTES que todo lo demas a proposito. El AEC compara lo que
            // entra por el microfono con lo que se mando al altavoz, y esa
            // comparacion solo tiene sentido si la senal del microfono aun no
            // ha pasado por nada: en cuanto el paso alto, los notches o la
            // puerta la tocan, deja de parecerse a la referencia y el filtro
            // adaptativo no encuentra la relacion que busca. Dicho de otra
            // forma: el AEC modela la SALA, no la sala mas nuestros filtros.
            if (aecActivo) m = aec.procesa(m)

            // 1) Paso alto.
            if (pasoAltoActivo) m = pasoAlto.procesa(m)

            // 2) Notches: dentro del lazo, antes de medir.
            if (notchesActivos) m = notches.procesa(m)

            // 2b) FILTRO DE VOZ, despues de los notches.
            //
            // Detras de los notches y no delante: si fuera delante, el filtro
            // de voz veria el pitido del acople como parte de la senal y, al
            // ser un tono sin armonicos, lo trataria como "no voz" y cerraria
            // la banda entera... incluida la voz que comparte esa zona. Con
            // los notches delante, el pitido ya viene rebajado y el filtro de
            // voz juzga sobre una senal limpia.
            if (filtroVozActivo) {
                m = filtroVoz.procesa(m)
                probabilidadVoz = filtroVoz.probabilidadVoz
                tonoVozHz = filtroVoz.tonoHz
            }

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

        // La ganancia del supresor residual se lee UNA vez por bloque, igual
        // que el desplazamiento: es una decision por bloque y ya viene
        // suavizada de dentro del AEC.
        val gananciaNlpAec = if (aecActivo) aec.gananciaResidual() else 1f

        // Metricas del AEC para la pantalla.
        erleAec = aec.erleDb
        retardoEcoMs = aec.retardoMs
        aecAdaptando = aec.adaptando
        aecDobleHabla = aec.dobleHabla
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

            // 9) SUPRESOR RESIDUAL DEL AEC.
            //
            // Va aqui, al final, y no pegado al AEC: es una ganancia que
            // multiplica, asi que aplicarla despues del limitador no puede
            // hacer que nada se pase de techo (solo baja), y en cambio actua
            // sobre la senal ya con su volumen definitivo, que es donde el
            // residuo de eco se oye.
            if (aecActivo) m *= gananciaNlpAec

            val a = abs(m)
            if (a > picoOut) picoOut = a

            // 10) EMPUJAR A LA REFERENCIA DEL AEC.
            //
            // Esta es la pieza que cierra el lazo y hace posible todo lo
            // demas: lo que se apunta aqui es EXACTAMENTE lo que va a salir
            // por el altavoz, byte a byte, porque es la misma muestra que se
            // escribe en el bloque de salida justo debajo. Si se apuntara la
            // senal en cualquier otro punto de la cadena (antes de la
            // ganancia, antes del limitador...) la referencia no seria la que
            // suena y el AEC estaria intentando cancelar un eco de una senal
            // que nunca existio.
            aec.empujaReferencia(m)

            bloque[i] = (m * 32767f).toInt().toShort()
        }

        picoSalida = picoOut
        reduccionCompresor = compresor.reduccionDb()
    }

    // --- Acceso a los notches para pintarlos --------------------------------
    fun notchFrecuencia(i: Int) = notches.frecuenciaDe(i)
    fun notchProfundidad(i: Int) = notches.profundidadDe(i)
    fun notchesCapacidad() = notches.capacidad()

    // --- Ajustes finos del AEC y del filtro de voz, para quien los quiera ---
    fun aecPaso(v: Float) { aec.mu = v }
    fun aecNlp(activo: Boolean) { aec.nlpActivo = activo }

    /** Fija el retardo del AEC a mano (lo usa la calibracion y las pruebas). */
    fun aecFijaRetardo(muestras: Int) = aec.fijaRetardo(muestras)

    /** Congelar el filtro del AEC cuando converja (geometria fija). */
    fun aecCongela(activo: Boolean) { aec.congelaSiConverge = activo }

    /** Si el filtro del AEC esta congelado ahora mismo. */
    fun aecCongelado(): Boolean = aec.filtroCongelado
    fun aecDivergencias(): Int = aec.divergencias
    fun aecNorma(): Float = aec.normaPesos()
    fun filtroVozAtenuacionDb(v: Float) { filtroVoz.atenuacionMaximaDb = v }

    /** Margen de apertura de la puerta, en dB sobre el ruido medido. */
    fun puertaMargenDb(v: Float) { puerta.margenAperturaDb = v; puerta.recalcula() }
    fun filtroVozUmbral(v: Float) { filtroVoz.umbralVoz = v }
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

// ---------------------------------------------------------------------------
// 12) ESTIMADOR DE RETARDO POR CORRELACION CRUZADA
// ---------------------------------------------------------------------------

/**
 * Averigua cuanto tarda en volver al microfono lo que sale por el altavoz.
 *
 * POR QUE ES CRITICO. El filtro adaptativo del AEC cubre una cola de 200 ms.
 * Si el altavoz es Bluetooth, el codec A2DP mete entre 150 y 250 ms de retardo
 * el solo, ANTES de que el sonido salga siquiera. Si no se compensa ese
 * retardo, el eco cae fuera de la ventana del filtro y el AEC no cancela nada
 * de nada: el filtro busca una correlacion que en su ventana no existe, todos
 * los coeficientes se quedan cerca de cero y el usuario sigue oyendo su voz
 * repetida. Este es EL fallo clasico de un AEC que "no funciona".
 *
 * COMO. Correlacion cruzada entre la envolvente de la referencia y la
 * envolvente del microfono. Tres detalles que importan:
 *
 *  1. Se correlan las ENVOLVENTES (energia por bloque), no las formas de onda.
 *     La forma de onda del eco esta filtrada por la sala y por el altavoz: su
 *     correlacion directa con la referencia puede ser floja y con varios picos
 *     de signo alterno. La envolvente sobrevive a todo eso, porque la silaba
 *     sigue siendo la misma silaba aunque la sala se coma los agudos.
 *
 *  2. Se hace con FFT, no a lo bruto. La correlacion directa de 1024 puntos
 *     contra 1024 son un millon de multiplicaciones; por el teorema de la
 *     correlacion son dos FFT, un producto conjugado y una IFFT: unas 50.000.
 *     Veinte veces menos.
 *
 *  3. Se RECALCULA cada pocos segundos, no una sola vez. El retardo del
 *     Bluetooth no es estable: cambia al reconectar, al cambiar de codec, y
 *     cuando el buffer del altavoz se rellena tras un corte. Un AEC que
 *     estima el retardo al arrancar y ya no lo vuelve a mirar deja de
 *     funcionar a la primera microcorte de la conexion.
 *
 * La envolvente se muestrea a bloques de 128 muestras (2,67 ms a 48 kHz), asi
 * que la resolucion del retardo estimado es de 2,67 ms. Eso sobra: lo que no
 * clave el estimador lo cubre el filtro adaptativo, que para eso tiene 200 ms
 * de cola.
 */
class EstimadorRetardo(private val fs: Int, msMaximo: Float) {

    /** Muestras que se resumen en un punto de envolvente. */
    private val decim = 128
    /** Cuantos puntos de envolvente cubren el retardo maximo buscado. */
    /**
     * Puntos de envolvente que cubren el retardo maximo buscado.
     *
     * Se le suma un 25% de margen: la correlacion de dos envolventes de voz es
     * una joroba ANCHA, y si el retardo de verdad cae justo en el borde de la
     * zona de busqueda, media joroba se queda fuera y el pico que se ve dentro
     * esta desplazado (o directamente no destaca lo suficiente y se descarta).
     * Se midio: con la busqueda ajustada a 400 ms clavados, un retardo real de
     * 350 ms se estimaba en 61 ms, o sea mal del todo.
     */
    private val maxPuntos =
        ((msMaximo * 1.25f / 1000f * fs) / decim).toInt().coerceAtLeast(8)

    /**
     * Tamano de la FFT de correlacion. Tiene que ser al menos el doble del
     * retardo maximo buscado para que la correlacion circular no se enrolle
     * sobre si misma y de un retardo falso (el "wrap-around" clasico).
     */
    private val n = siguientePotenciaDe2(maxPuntos * 4).coerceAtLeast(256)
    private val mitad = n / 2

    private val fft = Fft(n)

    // Envolventes en buffer circular: n/2 puntos de cada una.
    private val envRef = FloatArray(mitad)
    private val envMic = FloatArray(mitad)
    private var escribe = 0
    private var llenos = 0

    // Acumuladores del bloque de decimacion en curso.
    private var accRef = 0f
    private var accMic = 0f
    private var cuenta = 0

    // Espacios de trabajo, preasignados.
    private val aRe = FloatArray(n)
    private val aIm = FloatArray(n)
    private val bRe = FloatArray(n)
    private val bIm = FloatArray(n)

    /** Cada cuantos puntos de envolvente se vuelve a estimar. */
    private val cadaPuntos = (2f * fs / decim).toInt()   // ~2 segundos
    private var desdeUltima = 0

    /** Retardo estimado en muestras. -1 mientras no haya una estimacion fiable. */
    var muestras = -1
        private set

    /**
     * Calidad del ultimo pico de correlacion, 0..1. Es el pico normalizado
     * contra la media: por debajo de 0,2 no nos fiamos y no se toca nada.
     */
    var calidad = 0f
        private set

    fun reiniciar() {
        java.util.Arrays.fill(envRef, 0f)
        java.util.Arrays.fill(envMic, 0f)
        escribe = 0; llenos = 0
        accRef = 0f; accMic = 0f; cuenta = 0
        desdeUltima = 0
        muestras = -1
        calidad = 0f
    }

    /**
     * Mete una pareja de muestras alineadas EN EL TIEMPO DE LLEGADA: `r` es lo
     * que se mando al altavoz en este instante y `m` lo que entro por el
     * microfono en este mismo instante. El desfase entre las dos es justo lo
     * que buscamos.
     *
     * Devuelve true cuando acaba de recalcular la estimacion.
     */
    fun empuja(r: Float, m: Float): Boolean {
        // Envolvente = energia del bloque. Se usa el valor absoluto y no el
        // cuadrado porque el cuadrado exagera los picos y hace que la
        // correlacion la domine una sola silaba fuerte.
        accRef += if (r < 0f) -r else r
        accMic += if (m < 0f) -m else m
        cuenta++
        if (cuenta < decim) return false

        envRef[escribe] = accRef / decim
        envMic[escribe] = accMic / decim
        accRef = 0f; accMic = 0f; cuenta = 0
        escribe++
        if (escribe >= mitad) escribe = 0
        if (llenos < mitad) llenos++

        desdeUltima++
        // Hasta que no hay historial entero no se estima: con la mitad del
        // buffer a ceros el pico de correlacion sale donde no es.
        if (llenos < mitad || desdeUltima < cadaPuntos) return false
        desdeUltima = 0

        estima()
        return true
    }

    /**
     * Correlacion cruzada por FFT: corr = IFFT( conj(FFT(mic)) * FFT(ref) ).
     *
     * OJO AL ORDEN DE LA CONJUGACION, que es donde se equivoca todo el mundo y
     * sale el retardo con el signo cambiado. Queremos el desfase k tal que
     * mic[t] se parece a ref[t-k], o sea el microfono va DETRAS. Con
     * conj(FFT(ref)) * FFT(mic) el pico sale en k positivo. Verificado en la
     * prueba numerica con un retardo conocido.
     */
    private fun estima() {
        // Copiar las envolventes ya desenrolladas (el mas viejo primero) y
        // quitarles la media: sin quitar la media, el termino continuo de las
        // dos envolventes (que siempre es positivo, son valores absolutos)
        // domina la correlacion y el pico sale siempre en cero.
        var mediaR = 0f
        var mediaM = 0f
        for (i in 0 until mitad) {
            mediaR += envRef[i]
            mediaM += envMic[i]
        }
        mediaR /= mitad
        mediaM /= mitad

        java.util.Arrays.fill(aRe, 0f)
        java.util.Arrays.fill(aIm, 0f)
        java.util.Arrays.fill(bRe, 0f)
        java.util.Arrays.fill(bIm, 0f)

        var idx = escribe
        for (i in 0 until mitad) {
            aRe[i] = envRef[idx] - mediaR
            bRe[i] = envMic[idx] - mediaM
            idx++
            if (idx >= mitad) idx = 0
        }

        // FFT de la referencia. OJO: hay que poner la parte imaginaria a cero
        // A MANO. Los arrays `re`/`im` son de la propia Fft y conservan el
        // resultado de la llamada anterior; si no se limpian, la segunda
        // estimacion transforma la referencia con la parte imaginaria de la
        // correlacion pasada metida dentro, y el pico sale donde le parece.
        System.arraycopy(aRe, 0, fft.re, 0, n)
        java.util.Arrays.fill(fft.im, 0f)
        fft.transforma()
        System.arraycopy(fft.re, 0, aRe, 0, n)
        System.arraycopy(fft.im, 0, aIm, 0, n)

        // FFT del microfono.
        System.arraycopy(bRe, 0, fft.re, 0, n)
        java.util.Arrays.fill(fft.im, 0f)
        fft.transforma()

        // conj(REF) * MIC, en el sitio sobre los arrays de la fft.
        for (k in 0 until n) {
            val ar = aRe[k]
            val ai = -aIm[k]          // conjugado de la referencia
            val br = fft.re[k]
            val bi = fft.im[k]
            fft.re[k] = ar * br - ai * bi
            fft.im[k] = ar * bi + ai * br
        }

        // IFFT = conj( FFT( conj(X) ) ) / n. Como solo miramos donde esta el
        // pico y no nos importa la escala absoluta, basta con conjugar,
        // transformar y quedarse con la parte real.
        for (k in 0 until n) fft.im[k] = -fft.im[k]
        fft.transforma()

        // Buscar el pico en la zona de retardos POSITIVOS y plausibles.
        // Los negativos no tienen sentido fisico: el eco no puede llegar
        // antes de que salga el sonido.
        // --- De donde empieza a buscarse, y por que no desde cero ------------
        //
        // En un megafono el lazo esta CERRADO: lo que sale por el altavoz es
        // la propia voz que acaba de entrar por el microfono, procesada. Eso
        // significa que la referencia y el microfono se parecen muchisimo a
        // desfase CASI CERO, no porque haya eco, sino porque son la misma voz.
        // Ese parecido produce un pico de correlacion en los primeros puntos
        // que es mas alto que el del eco de verdad.
        //
        // Se vio al probar la cadena entera (no se veia en las pruebas del AEC
        // suelto, donde el microfono solo recibia eco): con retardos reales de
        // 50 y de 150 ms, el estimador contestaba 2,7 ms en los dos casos. Y
        // con esa alineacion el AEC no cancela nada, porque el eco de verdad
        // le queda fuera de la ventana.
        //
        // La solucion es no mirar los primeros milisegundos. Un eco acustico
        // de verdad no puede llegar antes de que el sonido viaje hasta el
        // altavoz y vuelva; con el altavoz pegado al microfono eso ya son unos
        // milisegundos, y con Bluetooth de por medio, cientos. Saltarse los
        // primeros 20 ms no pierde ningun eco real y quita de en medio el pico
        // falso del lazo.
        val kInicio = ((0.020f * fs) / decim).toInt().coerceAtLeast(1)

        var mejor = -1
        var mejorV = 0f
        for (k in kInicio until maxPuntos) {
            val v = fft.re[k] / n
            if (v > mejorV) { mejorV = v; mejor = k }
        }
        if (mejor < 0 || mejorV <= 0f) { calidad = 0f; return }

        // --- Cuanto destaca el pico, y como NO hay que medirlo --------------
        //
        // La primera version media el valor absoluto de TODA la zona de
        // busqueda y comparaba el pico con esa media. Medido: no funcionaba.
        // La correlacion de dos envolventes de voz no es un pico limpio sobre
        // cero, es una JOROBA ancha (la voz tiene silabas de 200-300 ms, asi
        // que la correlacion es ancha por fuerza). Esa joroba sube la media
        // ella sola, el pico solo destacaba x2,0 sobre su propia falda, no
        // llegaba al umbral de 2,5 y la estimacion se descartaba SIEMPRE:
        // 0 ms de retardo estimado con el retardo real fuera el que fuera, y
        // con el, 0 dB de cancelacion.
        //
        // Lo correcto es comparar el pico con el suelo de la correlacion LEJOS
        // del pico, que es donde de verdad se ve el ruido. Se toma la mediana
        // aproximada por el metodo barato: la media de la mitad baja de los
        // valores, que es robusta a la joroba.
        var suma = 0f
        var cuantos = 0
        for (k in 1 until maxPuntos) {
            // Todo lo que quede a mas de 8 puntos (21 ms) del pico es suelo.
            if (k >= kInicio && (k < mejor - 8 || k > mejor + 8)) {
                val v = fft.re[k] / n
                suma += if (v < 0f) -v else v
                cuantos++
            }
        }
        val suelo = if (cuantos > 0) suma / cuantos else 0f

        val destaca = if (suelo > 1e-12f) mejorV / suelo else 0f
        // Techo en 4: un pico 4 veces el suelo ya es una deteccion redonda.
        calidad = (destaca / 4f).coerceIn(0f, 1f)

        // --- Que sea un PICO de verdad, no una ladera ------------------------
        //
        // Esto es lo que impide dar un retardo inventado cuando el lazo esta
        // cerrado, y hace falta explicarlo porque es el limite real de este
        // metodo.
        //
        // En un megafono, lo que sale por el altavoz ES la voz que acaba de
        // entrar por el microfono, amplificada. Asi que referencia y microfono
        // son casi la misma senal, y su correlacion no tiene un pico en el
        // retardo del eco: baja suavemente desde k=0, dominada por el parecido
        // de la voz consigo misma. Se dibujo la correlacion en lazo cerrado
        // con un eco real de 150 ms y NO hay ningun pico en 150 ms: la curva
        // cae monotona de 0,000618 en k=0 hasta hacerse negativa. El eco esta
        // ahi, pero la correlacion de envolventes no lo puede ver por encima
        // del parecido de la voz consigo misma.
        //
        // Antes que devolver un numero inventado (que descoloca la alineacion
        // del filtro y lo deja cancelando NADA), se exige que el maximo sea un
        // maximo LOCAL con laderas a los dos lados. En una curva que solo baja
        // eso no se cumple, no se acepta la medida, [muestras] se queda como
        // estaba y el AEC trabaja con la alineacion que tuviera: su filtro de
        // 200 ms se apana solo mientras el eco caiga dentro.
        // Ademas el pico tiene que GANARLE al parecido de la voz consigo
        // misma, que es lo que se ve en los primeros puntos. Si la
        // correlacion en k pequenos es tan alta como el supuesto pico, lo que
        // estamos viendo es el lazo, no el eco.
        var corrCercana = 0f
        var cuantasCercanas = 0
        for (k in 1 until kInicio.coerceAtLeast(2)) {
            corrCercana += fft.re[k] / n
            cuantasCercanas++
        }
        if (cuantasCercanas > 0) corrCercana /= cuantasCercanas

        val esPicoDeVerdad = mejor > kInicio + 2 &&
            mejor < maxPuntos - 3 &&
            fft.re[mejor] / n > fft.re[mejor - 2] / n &&
            fft.re[mejor] / n > fft.re[mejor + 2] / n &&
            mejorV > corrCercana

        // Solo se acepta si el pico destaca de verdad. Con la sala en silencio
        // la correlacion es puro ruido y aceptarla meteria un retardo al azar,
        // que es peor que no tener ninguno.
        // El umbral es ALTO (x3 sobre el suelo) a proposito, y conviene saber
        // que se esta comprando con el: en lazo abierto (probando el AEC con
        // un eco puro) el pico es limpisimo y pasa de sobra, asi que la
        // estimacion funciona y se aprovecha. En lazo cerrado, que es como
        // corre esto en el movil, casi nunca pasa, y entonces el retardo se
        // queda en 0 y el filtro adaptativo cubre el eco con su propia cola de
        // 200 ms. Es la decision correcta: un retardo INVENTADO desalinea el
        // filtro y lo deja cancelando cero, mientras que no estimar nada solo
        // cuesta no poder cubrir ecos de mas de 200 ms.
        // Quien decide de verdad es la FORMA del pico ([esPicoDeVerdad]): que
        // sea un maximo con laderas a los dos lados y que le gane al parecido
        // de la voz consigo misma. Eso es lo que separa el caso util (lazo
        // abierto: hay un pico limpio en el retardo del eco) del caso
        // imposible (lazo cerrado: la correlacion solo baja desde cero).
        //
        // El umbral de magnitud se queda en 1,8 y no mas alto: subirlo a 3
        // tambien se cargaba las estimaciones BUENAS del lazo abierto (con
        // 250 ms de eco real el ERLE caia de 25 a -0,4 dB por no poder
        // alinear), sin aportar nada que la comprobacion de forma no hiciera
        // ya.
        if (destaca >= 1.8f && esPicoDeVerdad) {
            muestras = mejor * decim
        }
    }

    private fun siguientePotenciaDe2(v: Int): Int {
        var x = 1
        while (x < v) x = x shl 1
        return x
    }
}

// ---------------------------------------------------------------------------
// 13) CANCELADOR DE ECO CON SENAL DE REFERENCIA (AEC)
// ---------------------------------------------------------------------------

/**
 * Cancelador de eco acustico por filtro adaptativo en FRECUENCIA, particionado
 * (PBFDAF: Partitioned Block Frequency Domain Adaptive Filter).
 *
 * QUE ATACA, Y EN QUE SE DIFERENCIA DEL ANTIACOPLE. No son lo mismo y atacan
 * sintomas distintos:
 *
 *  - El ANTIACOPLE (los notches) mata el PITIDO: el lazo realimentandose en
 *    una frecuencia concreta hasta que oscila. Es un tono puro.
 *  - Esto de aqui ataca la VOZ REPETIDA: lo que salio por el altavoz hace
 *    200 ms, ha rebotado en la sala y ha vuelto a entrar por el microfono. Se
 *    oye como hablar con eco. Los notches no pueden hacer NADA contra eso,
 *    porque el eco de voz ocupa todo el espectro de la voz, no una frecuencia
 *    suelta: poner notches donde hay eco seria borrar la voz entera.
 *
 * POR QUE AQUI SI SE PUEDE Y CON LOS NOTCHES NO. Porque tenemos la senal de
 * REFERENCIA: lo que escribimos en el AudioTrack. Sabemos exactamente que
 * sonido salio por el altavoz. El eco es esa misma senal pasada por la sala:
 *
 *   micro(t) = voz(t) + SUMA_k h(k) * referencia(t - retardo - k)
 *                       \__________ eco ____________/
 *
 * Si estimamos h(k) podemos predecir el eco y restarlo. Lo que queda tras
 * restar es el ERROR; y como el error es justo lo que queremos que sea
 * pequeno, el propio error sirve para corregir la estimacion. Eso es el NLMS.
 *
 * POR QUE EN FRECUENCIA Y PARTICIONADO, Y NO UN NLMS EN EL TIEMPO. Hay que
 * cubrir ~200 ms de cola (el Bluetooth solo ya mete 150-250 ms). A 48 kHz eso
 * son 9600 coeficientes, y el NLMS cuesta 2 multiplicaciones por coeficiente y
 * por muestra: 2 * 9600 * 48000 = 920 millones de multiplicaciones por
 * segundo. Inviable en un movil.
 *
 * Particionando en trozos de 256 muestras y trabajando en frecuencia, la
 * convolucion se convierte en un producto y el coste cae a ~46 millones de
 * operaciones por segundo: 4 FFT de 512 mas 38 productos complejos de 512
 * bins, 187 veces por segundo. Un 1% de un nucleo ARM. Y el particionado tiene
 * una segunda ventaja que aqui es decisiva: la latencia es la del BLOQUE
 * (256 muestras = 5,3 ms), no la del filtro entero. Un FDAF sin particionar
 * obligaria a esperar 200 ms antes de sacar nada, inaceptable en megafonia.
 *
 * Metodo: Soo y Pang, "Multidelay block frequency domain adaptive filter",
 * IEEE Trans. ASSP 38(2), 1990.
 *
 * LO QUE ESTO NO ARREGLA, dicho sin adornos. El AEC cancela el eco LINEAL: lo
 * que la sala hace con la senal y se puede describir con una convolucion. No
 * cancela lo que el altavoz distorsiona (un altavoz Bluetooth pequeno a
 * volumen alto recorta, y un recorte no es lineal), ni cancela el acople
 * cuando el lazo ya esta oscilando.
 *
 * ================== EL LIMITE DE VERDAD, MEDIDO ==========================
 *
 * ESTE BLOQUE FUNCIONA MUY BIEN EN LAZO ABIERTO Y CASI NADA EN LAZO CERRADO,
 * y quien venga detras tiene que saberlo antes de tocar nada, porque parece
 * un fallo y no lo es.
 *
 * Medido con las pruebas de este mismo trabajo:
 *
 *   - LAZO ABIERTO (el microfono recibe SOLO el eco: es el caso del manos
 *     libres, donde la voz que sale por el altavoz es la del otro
 *     interlocutor): 25 a 30 dB de cancelacion lineal, y 37 dB con el
 *     supresor residual. El estimador de retardo clava el retardo con un
 *     error de 7 ms en todo el rango de 0 a 400 ms.
 *
 *   - LAZO CERRADO (un MEGAFONO: por el altavoz sale la MISMA voz que acaba
 *     de entrar por el microfono, amplificada): entre -0,4 y +0,5 dB. O sea,
 *     nada. Y no se arregla con la decorrelacion: se probo con retardo de
 *     15 ms y desplazamiento de 5 Hz, en las cuatro combinaciones, y sigue
 *     entre -0,4 y +0,5 dB.
 *
 * POR QUE, que es lo importante. El NLMS busca la parte del microfono que se
 * explica por la referencia. En un megafono la referencia ES la voz del
 * usuario (amplificada y con unos ms de retraso), asi que la voz del usuario
 * se explica perfectamente por la referencia... y el filtro no tiene forma de
 * saber que esa parte NO hay que cancelarla. Cancelar el eco y cancelar la voz
 * son, matematicamente, el mismo problema. Es el sesgo clasico del filtrado
 * adaptativo en lazo cerrado, no un error de programacion.
 *
 * Se ve tambien en el estimador de retardo: se dibujo la correlacion entre
 * referencia y microfono en lazo cerrado con un eco real de 150 ms, y NO hay
 * pico en 150 ms; la curva baja monotona desde el desfase cero, dominada por
 * el parecido de la voz consigo misma.
 *
 * ENTONCES, PARA QUE SE QUEDA. Por dos razones:
 *
 *  1. Porque el usuario puede usar el movil como manos libres o con la voz
 *     entrando por otro sitio, y ahi SI da sus 25-37 dB.
 *  2. Porque el interruptor esta a la vista y se puede apagar. Lo que NO se
 *     puede hacer es venderlo como la solucion al pitido: contra el acople
 *     de un megafono lo que funciona son los NOTCHES, el volumen y, sobre
 *     todo, separar el altavoz del microfono.
 * =========================================================================
 */
class CanceladorEco(
    private val fs: Int,
    /** Cola que se quiere cubrir, en ms. 200 ms cubre sala mas Bluetooth. */
    msCola: Float = 200f,
    /**
     * Cuanto retardo de BUS puede haber entre lo que escribimos y lo que se
     * oye. El A2DP mete 150-250 ms; se deja margen hasta 400.
     */
    msRetardoMaximo: Float = 400f
) {
    // --- Geometria del filtro ----------------------------------------------
    //
    // b es el salto (hop) y el tamano del bloque nuevo que entra cada vez.
    // n = 2*b es la FFT, porque el metodo de solapamiento y descarte
    // (overlap-save) necesita el doble para que la convolucion circular de la
    // FFT coincida con la lineal de verdad en la segunda mitad del bloque.
    private val b = 256
    private val n = 2 * b
    /** Cuantas particiones de b muestras cubren la cola pedida. */
    private val p = ((msCola / 1000f * fs) / b).toInt().coerceIn(1, 96)

    private val fft = Fft(n)

    // --- Pesos del filtro, en frecuencia -----------------------------------
    // Un bloque de n bins complejos por particion, en un array PLANO: una sola
    // reserva y acceso lineal, que es lo que le gusta a la cache del movil.
    private val wRe = FloatArray(p * n)
    private val wIm = FloatArray(p * n)

    // --- Historial de la REFERENCIA ya transformada ------------------------
    // Buffer circular de espectros. Se guarda transformado para no repetir p
    // veces la misma FFT en cada bloque.
    private val xRe = FloatArray(p * n)
    private val xIm = FloatArray(p * n)
    /** Indice de la particion mas reciente dentro del circular. */
    private var xCabeza = 0

    // --- Buffer circular de la REFERENCIA en el tiempo ----------------------
    //
    // Aqui se guarda lo que mandamos al altavoz. Tiene que dar para el retardo
    // del Bluetooth MAS la cola del filtro MAS margen. Si se queda corto, el
    // AEC busca un eco cuya referencia ya se ha sobreescrito y no cancela.
    private val refCap = (((msRetardoMaximo + msCola + 200f) / 1000f) * fs)
        .toInt().coerceAtLeast(4 * n)
    private val ref = FloatArray(refCap)
    private var refEscribe = 0
    /** Cuantas muestras de referencia se han empujado en total. */
    private var refTotal = 0L

    // --- Acumuladores de bloque --------------------------------------------
    /** Muestras de microfono a la espera de completar un bloque. */
    private val micBloque = FloatArray(b)
    private var micLlenas = 0
    /** Salida ya cancelada, que se va entregando muestra a muestra. */
    private val salidaBloque = FloatArray(b)
    /** Por donde va la entrega. Arranca "agotado": todavia no hay nada. */
    private var salidaLeidas = 0

    // --- Espacios de trabajo, todos preasignados ---------------------------
    /** Ventana de referencia del overlap-save: mitad vieja + mitad nueva. */
    private val refVentana = FloatArray(n)
    /** Espectro del bloque de referencia recien entrado. */
    private val rRe = FloatArray(n)
    private val rIm = FloatArray(n)
    /** Acumulador del eco estimado, en frecuencia. */
    private val yRe = FloatArray(n)
    private val yIm = FloatArray(n)
    /** Espectro del error, para la actualizacion. */
    private val eRe = FloatArray(n)
    private val eIm = FloatArray(n)
    /** Bloque de error en el tiempo, con la primera mitad a cero. */
    private val errTiempo = FloatArray(n)

    // --- Potencia por bin: la "N" de NLMS (Normalized) ---------------------
    private val potBin = FloatArray(n)
    /**
     * Olvido de la potencia por bin, que es el denominador del NLMS.
     *
     * 0,995 sobre bloques de 5,3 ms son unos 1,1 segundos de memoria. Parece
     * mucho y es a proposito: aqui interesa la potencia MEDIA de la
     * referencia, no la instantanea.
     *
     * Por que, medido: con 0,9 (unos 50 ms de memoria) el denominador seguia
     * a la voz silaba a silaba, asi que en los valles entre silabas se hacia
     * pequenisimo y el paso efectivo se disparaba justo donde menos senal
     * habia. Con voz sintetica y 37 particiones el filtro se quedaba en 3-5 dB
     * de ERLE por esa causa. Alargando la memoria a ~1 s, el mismo montaje
     * pasa a 32 dB. Con ruido blanco la diferencia no se ve (todos los bloques
     * tienen la misma energia), que es justo por lo que conviene probar con
     * senales parecidas a las de verdad y no solo con ruido.
     */
    private val olvidoPot = 0.995f

    // --- Ajustes ------------------------------------------------------------
    /**
     * Paso de adaptacion.
     *
     * OJO: aqui NO va de 0 a 1 como en el NLMS de los libros. La escala
     * depende de como este normalizado el denominador, y en esta version el
     * denominador lleva ya el factor n de la FFT y el reparto entre las p
     * particiones, asi que el numero que toca es del orden de 100. No es un
     * valor "grande" ni una chapuza: es el mismo paso de siempre escrito en
     * otras unidades.
     *
     * 128 sale de barrer el valor con voz sintetica, eco conocido y 37
     * particiones: 8 daba 3,7 dB de ERLE, 32 daba 16 dB y 128 daba 32 dB, con
     * la norma de los pesos quieta en 5,1 (o sea, sin divergir). Por encima de
     * ahi empieza a temblar cuando la referencia baja de golpe.
     */
    @Volatile var mu = 128f

    /**
     * Regularizacion. Evita dividir por cero cuando no hay referencia y, mas
     * importante, frena la adaptacion en los bins sin energia: sin esto el
     * filtro se vuelve loco en las zonas vacias del espectro y mete ruido
     * propio, que se oye como un siseo que va y viene.
     */
    @Volatile var delta = 1e-6f

    /**
     * FUGA de los pesos (leaky NLMS). Cada actualizacion los encoge un
     * 0,02 por mil. Parece una tonteria y es lo que separa un AEC que
     * funciona de uno que no.
     *
     * POR QUE HACE FALTA, medido en la prueba. El NLMS solo corrige los pesos
     * en las direcciones que la referencia EXCITA. La voz no excita todas las
     * frecuencias por igual ni todas a la vez, asi que en las direcciones que
     * quedan a oscuras los pesos hacen un paseo aleatorio: nada los corrige y
     * nada los frena. Se vio clarisimo en la prueba de 20 segundos: la norma
     * de los pesos subia sin parar, 0,5 -> 7,7 -> 11,8 -> 16,9 -> 38 -> 54, y
     * el ERLE pasaba de +13 dB en el segundo 7 a -11 dB en el 18. O sea que
     * el "cancelador" acababa metiendo 11 dB MAS de eco del que habia.
     *
     * La fuga pone un muelle que tira de todos los pesos hacia cero. Donde la
     * referencia manda, el gradiente gana al muelle y el peso se queda donde
     * toca; donde no hay informacion, el muelle gana y el peso se muere en vez
     * de irse de paseo. El precio es un sesgo pequenisimo en la solucion
     * (unas decimas de dB de ERLE), que es un cambio excelente.
     */
    @Volatile var fuga = 0.99998f

    /** Supresor no lineal del residuo (NLP). */
    @Volatile var nlpActivo = true

    // --- CONGELACION DEL FILTRO (geometria fija) ----------------------------
    //
    // ESTO SOLO TIENE SENTIDO EN UN MEGAFONO PORTATIL, y conviene explicar por
    // que, porque en megafonia de sala seria un disparate.
    //
    // En sala el camino acustico cambia sin parar: la gente se mueve, las
    // reflexiones bailan. Congelar el filtro ahi significa quedarse con un
    // modelo caducado, asi que hay que readaptar siempre.
    //
    // En un megafono de bandolera la geometria es CASI FIJA: el altavoz y el
    // microfono se mueven JUNTOS, siempre a ~1 m, con el microfono en el
    // lobulo trasero del cono. El camino real cambia poquisimo (el balanceo al
    // andar, y poco mas). Ahi seguir readaptando no aporta nada y si tiene un
    // coste serio: en lazo cerrado la referencia ES la voz del usuario, asi
    // que cada actualizacion arrastra un sesgo que tira los pesos hacia
    // cancelar la voz en vez de solo el eco. Cuanto mas tiempo adapta, mas se
    // desvia.
    //
    // Congelar cuando ha convergido corta ese sesgo de raiz: se aprende el
    // camino cuando las condiciones son buenas y luego se deja quieto.
    @Volatile var congelaSiConverge = false

    /**
     * ERLE (dB) a partir del cual se considera que ha convergido.
     *
     * 22 dB y no 9, y esto se midio. Con el umbral en 9 dB el filtro congelaba
     * A MEDIA CONVERGENCIA: las curvas de ERLE por medio segundo con cola de
     * 40-60 ms pasan por 9-10 dB a los 2,5 s pero no se asientan en 30+ dB
     * hasta los 5-6 s. Congelando en 9 dB el resultado final caia a 16,6 dB,
     * o sea que la congelacion EMPEORABA las cosas (sin congelar: 30,2 dB).
     *
     * El umbral tiene que estar donde el filtro ya esta bueno de verdad, no
     * donde empieza a funcionar.
     */
    @Volatile var erleParaCongelar = 22f

    /**
     * Cuantos bloques seguidos tiene que estar el ERLE por encima del umbral
     * antes de congelar. 800 bloques son ~4,3 s.
     *
     * Es mucho a proposito: la voz tiene silencios, y durante un silencio el
     * ERLE medido no significa nada. Exigir que se mantenga alto durante
     * varios segundos asegura que se ha visto al filtro trabajar con voz de
     * verdad y no solo en un hueco tranquilo.
     */
    @Volatile var bloquesParaCongelar = 800

    /**
     * Si el ERLE cae por debajo de esto estando congelado, se DESCONGELA: la
     * geometria ha cambiado de verdad (se ha descolgado la bandolera, ha
     * cambiado de mano el movil) y hay que volver a aprender.
     *
     * Va bastante por debajo del umbral de congelar para que no haya un
     * pingpong congela/descongela con las variaciones normales del andar.
     */
    @Volatile var erleParaDescongelar = 10f

    /** Bloques seguidos de ERLE malo antes de descongelar (~1 s). */
    @Volatile var bloquesParaDescongelar = 200

    /** Si el filtro esta congelado ahora mismo. Solo para la pantalla. */
    @Volatile var filtroCongelado = false; private set

    /**
     * Norma maxima que se le consiente a los pesos antes de darlos por
     * divergidos. Ver la guardia del apartado h2.
     */
    @Volatile var limiteNorma = 8f

    /**
     * Cuantas veces ha habido que tirar los pesos por divergencia. Si esto
     * sube sin parar es que el paso [mu] es demasiado grande para el lazo que
     * hay montado, o que el volumen de salida esta tan alto que el sistema no
     * es estable de ninguna manera.
     */
    @Volatile var divergencias = 0; private set

    private var bloquesBuenos = 0
    private var bloquesMalos = 0

    /**
     * Cuanto puede atenuar el NLP como mucho, en dB (negativo). -18 dB basta
     * para enterrar el residuo bajo el ruido de sala sin que se note el
     * bombeo.
     */
    @Volatile var nlpMaximoDb = -18f

    // --- Metricas para la pantalla -----------------------------------------
    /** Cancelacion conseguida en dB (ERLE: Echo Return Loss Enhancement). */
    @Volatile var erleDb = 0f; private set
    /** Retardo estimado entre referencia y microfono, en ms. -1 si no hay. */
    @Volatile var retardoMs = -1f; private set
    /** Si ahora mismo esta adaptando. */
    @Volatile var adaptando = false; private set
    /** Si se ha detectado doble habla (el usuario habla encima del eco). */
    @Volatile var dobleHabla = false; private set

    // Energias suavizadas, para el ERLE y para el detector de doble habla.
    private var enMic = 1e-10f
    private var enErr = 1e-10f
    private var enRef = 1e-10f
    private var enEco = 1e-10f

    /**
     * Acoplamiento estimado del lazo altavoz->sala->microfono, en energia:
     * cuanta energia entra por el microfono por cada unidad de energia que
     * sale por el altavoz. Es lo que permite detectar la doble habla sin
     * preguntarle nada al filtro adaptativo (ver el apartado f del bloque).
     */
    private var acoplamiento = 0f

    /** Bloques con referencia vistos. Hasta que no hay unos cuantos, el
     * acoplamiento aprendido no vale para juzgar la doble habla. */
    private var bloquesConReferencia = 0

    // --- Estimador de retardo ----------------------------------------------
    private val estimador = EstimadorRetardo(fs, msRetardoMaximo)
    /**
     * Retardo aplicado, en muestras: lo que se le resta al puntero de lectura
     * de la referencia para alinearla con el microfono. El filtro adaptativo
     * cubre lo que quede a partir de ahi.
     */
    private var retardoAplicado = 0

    /**
     * Cuando el estimador cambia el retardo hay que TIRAR los pesos: estaban
     * aprendidos para otra alineacion y con la nueva son basura que tarda mas
     * en desaprenderse que en volver a aprenderse de cero.
     */
    private var retardoAnterior = -1

    fun reiniciar() {
        java.util.Arrays.fill(wRe, 0f)
        java.util.Arrays.fill(wIm, 0f)
        java.util.Arrays.fill(xRe, 0f)
        java.util.Arrays.fill(xIm, 0f)
        java.util.Arrays.fill(ref, 0f)
        java.util.Arrays.fill(refVentana, 0f)
        java.util.Arrays.fill(potBin, 0f)
        java.util.Arrays.fill(micBloque, 0f)
        java.util.Arrays.fill(salidaBloque, 0f)
        xCabeza = 0
        refEscribe = 0
        refTotal = 0L
        micLlenas = 0
        salidaLeidas = 0
        enMic = 1e-10f; enErr = 1e-10f; enRef = 1e-10f; enEco = 1e-10f
        acoplamiento = 0f
        bloquesConReferencia = 0
        erleDb = 0f
        retardoMs = -1f
        adaptando = false
        dobleHabla = false
        retardoAplicado = 0
        retardoAnterior = -1
        filtroCongelado = false
        bloquesBuenos = 0
        bloquesMalos = 0
        retardoFijado = false
        retardoPendiente = -1
        divergencias = 0
        estimador.reiniciar()
    }

    /**
     * Fija el retardo A MANO, con el valor que ha medido la CALIBRACION.
     *
     * Es la pieza que hace util al AEC en un megafono. El estimador por
     * correlacion de envolventes no puede ver el retardo en lazo cerrado
     * (la referencia ES la voz del usuario, asi que la correlacion solo dice
     * "se parecen a desfase cero"), y sin alineacion el eco cae fuera de la
     * ventana del filtro y no se cancela NADA. La calibracion resuelve eso
     * midiendo con una senal que el usuario no esta generando.
     *
     * Cuando el retardo viene de aqui, el estimador automatico deja de tocar
     * la alineacion: lo medido en silencio con una senal conocida es mejor
     * dato que lo que pueda adivinar en lazo cerrado.
     */
    /**
     * Retardo que la calibracion quiere aplicar pero que todavia no se puede
     * aplicar. Ver [fijaRetardo]. -1 = no hay nada pendiente.
     */
    private var retardoPendiente = -1

    fun fijaRetardo(muestrasRetardo: Int) {
        // NO SE PUEDE ALINEAR CON EL BUFFER DE REFERENCIA VACIO.
        //
        // La calibracion acaba justo cuando termina la rafaga, y en ese
        // momento el buffer de referencia lleva dentro la rafaga y poco mas:
        // `potBin` todavia no se ha aprendido y el historial de espectros esta
        // a ceros. Si se alinea ahi, el primer bloque normaliza el paso contra
        // una potencia que no significa nada y el filtro sale disparado.
        //
        // Medido con referencia ajena y camino portatil: fijando el retardo en
        // t=0 salian 0,00 dB y 3.852 divergencias; el MISMO valor aplicado en
        // t=1 s daba 32,40 dB y cero divergencias.
        //
        // Asi que si aun no hay referencia suficiente, el retardo se guarda y
        // se aplica en cuanto la haya. `refTotal` cuenta las muestras de
        // referencia empujadas desde el arranque; con un par de colas enteras
        // del filtro ya hay material de sobra.
        val minimo = (p * b * 2).coerceAtLeast(2 * fs / 10)
        if (refTotal < minimo) {
            retardoPendiente = muestrasRetardo
            // Se apunta ya para la pantalla, aunque no este aplicado todavia.
            retardoMs = muestrasRetardo * 1000f / fs
            retardoFijado = true
            return
        }
        aplicaRetardo(muestrasRetardo)
    }

    private fun aplicaRetardo(muestrasRetardo: Int) {
        // EL MARGEN DE SEGURIDAD, Y POR QUE AQUI ES DISTINTO QUE EN EL
        // ESTIMADOR AUTOMATICO.
        //
        // El estimador por envolventes se resta un bloque entero (`est - b`)
        // porque su resolucion es de 128 muestras y su pico baila: mas vale
        // que el filtro cubra un poco de mas por delante a que el eco caiga
        // ANTES del principio de su ventana, que eso no lo puede arreglar.
        //
        // La calibracion NO necesita ese margen tan grande: mide la forma de
        // onda, con resolucion de UNA muestra y un error medido de 0,4 ms. Si
        // se le resta un bloque entero (5,3 ms) se esta desalineando a
        // proposito una medida que era buena.
        //
        // Medido, y es la diferencia entre que funcione y que no: con el
        // camino portatil y la referencia ajena (lazo abierto), el mismo AEC
        // daba 30,41 dB dejando estimar solo y 0,00 dB con 3.949 divergencias
        // cuando se le fijaba el retardo restandole el bloque. El filtro se
        // quedaba modelando un eco que le llegaba antes de su ventana.
        //
        // Se deja un margen de un cuarto de bloque, que absorbe el error de
        // 0,4 ms de la calibracion sin tirar por la borda su precision.
        val margen = b / 4
        val nuevo = (muestrasRetardo - margen).coerceAtLeast(0)
        retardoAplicado = nuevo
        retardoAnterior = nuevo
        retardoMs = muestrasRetardo * 1000f / fs
        retardoFijado = true
        // Alineacion nueva: los pesos viejos no valen, igual que cuando la
        // mueve el estimador.
        java.util.Arrays.fill(wRe, 0f)
        java.util.Arrays.fill(wIm, 0f)
        acoplamiento = 0f
        bloquesConReferencia = 0
        filtroCongelado = false
        bloquesBuenos = 0
        bloquesMalos = 0

        // HAY QUE TIRAR TAMBIEN EL HISTORIAL DE LA REFERENCIA Y LA POTENCIA
        // POR BIN, y esto es lo que hacia divergir el AEC al calibrar.
        //
        // El sintoma, medido con referencia ajena (lazo abierto) y el camino
        // portatil: llamando a fijaRetardo en t=0 salian 0,00 dB de ERLE y
        // 3.852 divergencias; llamando exactamente al mismo valor en t=1 s
        // salian 32,40 dB y CERO divergencias. El retardo era el mismo, asi
        // que no era cuestion de alineacion.
        //
        // La causa: nada mas arrancar, `potBin` esta a ceros y el historial de
        // espectros `xRe`/`xIm` tambien. El paso del NLMS se normaliza
        // dividiendo por esa potencia, con un suelo que se calcula como una
        // fraccion de la potencia MEDIA... que tambien es cero. Asi que el
        // primer bloque tras fijar el retardo divide por el suelo de `delta`
        // (1e-6) y da un paso enorme guiado por un gradiente que aun no
        // significa nada. Con eso el filtro sale disparado y ya no vuelve.
        //
        // Cuando quien mueve la alineacion es el estimador automatico esto no
        // pasaba nunca, porque el estimador no se pronuncia hasta los ~2 s y
        // para entonces `potBin` lleva rato con valores buenos. La calibracion
        // rompe esa suposicion: fija el retardo cuando le da la gana, que
        // normalmente es nada mas arrancar.
        //
        // Poniendo el historial a cero Y reiniciando el contador de bloques,
        // la potencia se vuelve a aprender desde el primer bloque con
        // referencia de verdad, y el primer paso ya sale normalizado con algo
        // que significa algo.
        java.util.Arrays.fill(xRe, 0f)
        java.util.Arrays.fill(xIm, 0f)
        xCabeza = 0
        // Las energias suavizadas tambien se resiembran: venian de la
        // alineacion vieja y el detector de doble habla las usa.
        enMic = 1e-10f; enErr = 1e-10f; enRef = 1e-10f; enEco = 1e-10f

        // OJO: `potBin` NO se toca, y es importante.
        //
        // La tentacion es ponerlo a cero tambien, "para empezar limpio". Se
        // probo y es exactamente lo que NO hay que hacer: `potBin` es el
        // denominador del NLMS, y con el a cero el suelo de normalizacion cae
        // a `delta` (1e-6), el primer paso se dispara y el filtro diverge.
        // Medido: poniendolo a cero, el caso que daba 32,40 dB pasaba a 0,00
        // dB con 3.725 divergencias. La potencia aprendida sigue siendo buena
        // aunque cambie la alineacion, porque la referencia es la misma senal.
    }

    /** Si el retardo lo ha puesto la calibracion en vez del estimador. */
    private var retardoFijado = false

    /**
     * Retardo que anade el AEC, en muestras. Es el del bloque: la muestra que
     * entra sale b muestras despues. Hay que sumarlo a la latencia que se le
     * ensena al usuario.
     */
    fun retardoMuestras(): Int = b

    /**
     * Norma de los pesos del filtro. Solo sirve para diagnosticar: si crece
     * sin parar, el filtro esta divergiendo. No se llama desde el hilo de
     * audio.
     */
    /** Solo para diagnostico. */
    fun retardoAplicadoDbg(): Int = retardoAplicado

    /** Cuantas particiones tiene el filtro. Solo para diagnostico. */
    fun particiones(): Int = p

    fun normaPesos(): Float {
        var s = 0.0
        for (i in wRe.indices) s += wRe[i] * wRe[i] + wIm[i] * wIm[i]
        return kotlin.math.sqrt(s).toFloat()
    }

    /**
     * Apunta en el buffer circular una muestra de lo que se manda al altavoz.
     *
     * Se llama al FINAL de la cadena, con la muestra ya lista para el
     * AudioTrack. Es la pieza que hace posible todo lo demas: sin referencia
     * no hay AEC, solo supresion a ciegas.
     */
    fun empujaReferencia(x: Float) {
        ref[refEscribe] = x
        refEscribe++
        if (refEscribe >= refCap) refEscribe = 0
        refTotal++
    }

    /**
     * Procesa una muestra de microfono y devuelve la muestra ya cancelada.
     *
     * OJO: devuelve la muestra de HACE b muestras, no esta. El proceso es por
     * bloques y no hay forma de que sea de otra manera; ese retardo esta
     * contado en [retardoMuestras].
     */
    fun procesa(m: Float): Float {
        // 1) Alimentar el estimador de retardo con la pareja alineada en el
        //    tiempo de llegada: lo ultimo que se mando al altavoz contra lo
        //    que entra ahora por el microfono.
        var iUlt = refEscribe - 1
        if (iUlt < 0) iUlt += refCap
        // Si hay un retardo de calibracion esperando a que haya referencia
        // suficiente, se aplica en cuanto la haya. Ver [fijaRetardo].
        if (retardoPendiente >= 0) {
            val minimo = (p * b * 2).coerceAtLeast(2 * fs / 10)
            if (refTotal >= minimo) {
                val v = retardoPendiente
                retardoPendiente = -1
                aplicaRetardo(v)
            }
        }

        // Si el retardo lo puso la CALIBRACION, el estimador no lo toca: una
        // medida hecha en silencio con una senal conocida es mejor dato que
        // lo que la correlacion pueda adivinar con el lazo cerrado.
        if (!retardoFijado && estimador.empuja(ref[iUlt], m)) {
            val est = estimador.muestras
            if (est >= 0) {
                retardoMs = est * 1000f / fs
                // Se deja un margen de seguridad de medio bloque por debajo de
                // lo estimado: mas vale que el filtro tenga que cubrir un poco
                // de mas por delante (lo puede hacer, tiene coeficientes de
                // sobra) a que el eco caiga ANTES del principio de su ventana,
                // que eso no lo puede arreglar de ninguna manera.
                val nuevo = (est - b).coerceAtLeast(0)

                // CUANDO SE MUEVE LA ALINEACION, Y POR QUE TAN POCAS VECES.
                //
                // Cambiar el retardo obliga a tirar los pesos: estaban
                // aprendidos para otra alineacion y con la nueva son basura.
                // Pero tirarlos cuesta varios segundos de reconvergencia, asi
                // que hacerlo a la ligera es peor que no hacerlo.
                //
                // El estimador tiene una resolucion de 2,7 ms y su pico baila
                // un punto arriba o abajo de una medida a otra (en la prueba
                // alternaba entre 149,3 y 152,0 ms para un retardo real de
                // 150). Si se reacciona a ese baile, el filtro se queda
                // reiniciandose cada dos segundos y no converge NUNCA: se
                // midio ERLE ~0 dB por esta causa.
                //
                // Por eso solo se mueve si el cambio es de MEDIA COLA del
                // filtro. Menos que eso lo absorbe el propio filtro adaptativo
                // sin despeinarse, que para eso tiene 200 ms de coeficientes.
                val saltoMinimo = (p * b) / 2
                if (retardoAnterior < 0 ||
                    kotlin.math.abs(nuevo - retardoAplicado) > saltoMinimo
                ) {
                    retardoAplicado = nuevo
                    retardoAnterior = nuevo
                    // Alineacion nueva: los pesos viejos ya no valen.
                    java.util.Arrays.fill(wRe, 0f)
                    java.util.Arrays.fill(wIm, 0f)
                    // Y el acoplamiento aprendido tampoco: estaba medido
                    // contra otra alineacion de la referencia.
                    acoplamiento = 0f
                    bloquesConReferencia = 0
                }
            }
        }

        // 2) ENTREGAR lo que ya estaba hecho, ANTES de meter la muestra nueva.
        //    El orden importa y es el mismo detalle que en el FiltroVoz: si se
        //    acumula primero y, al completar el bloque, se entrega ya la
        //    muestra 0 de ese mismo bloque, la salida se adelanta un bloque
        //    entero a su sitio. Asi el retardo es exactamente b muestras, que
        //    es lo que declara [retardoMuestras] y lo que la cadena suma a la
        //    latencia.
        //
        //    Mientras no haya un bloque hecho se devuelve silencio: son los
        //    primeros 5,3 ms tras arrancar y no se oyen.
        val y = if (salidaLeidas < b) salidaBloque[salidaLeidas++] else 0f

        // 3) Acumular hasta completar un bloque.
        micBloque[micLlenas] = m
        micLlenas++
        if (micLlenas >= b) {
            micLlenas = 0
            procesaBloque()
            salidaLeidas = 0
        }

        return y
    }

    /**
     * El bloque entero: overlap-save, filtrado en frecuencia, error,
     * actualizacion de pesos y supresor residual.
     */
    private fun procesaBloque() {
        // --- a) Ventana de referencia alineada -----------------------------
        //
        // Hay que coger las n = 2b muestras de referencia que corresponden a
        // este bloque de microfono, retrasadas [retardoAplicado].
        //
        // El bloque de microfono que acabamos de cerrar ocupa las ultimas b
        // muestras de tiempo. Su referencia alineada empieza b + retardo
        // muestras por detras de donde escribe la referencia, y hay que coger
        // n = 2b: las b nuevas mas las b anteriores, que es lo que pide el
        // overlap-save.
        var inicio = refEscribe - retardoAplicado - 2 * b
        while (inicio < 0) inicio += refCap
        var idx = inicio
        for (i in 0 until n) {
            refVentana[i] = ref[idx]
            idx++
            if (idx >= refCap) idx = 0
        }

        // --- b) FFT de la referencia y guardado en el historial -------------
        System.arraycopy(refVentana, 0, fft.re, 0, n)
        java.util.Arrays.fill(fft.im, 0f)
        fft.transforma()
        System.arraycopy(fft.re, 0, rRe, 0, n)
        System.arraycopy(fft.im, 0, rIm, 0, n)

        // El historial es circular hacia atras: la cabeza es la particion mas
        // nueva y se retrocede para las mas viejas.
        xCabeza--
        if (xCabeza < 0) xCabeza = p - 1
        System.arraycopy(rRe, 0, xRe, xCabeza * n, n)
        System.arraycopy(rIm, 0, xIm, xCabeza * n, n)

        // --- Potencia por bin: el denominador del NLMS -----------------------
        //
        // Hace que el paso sea grande donde hay poca energia y pequeno donde
        // hay mucha, o sea que todas las frecuencias converjan a la misma
        // velocidad. Sin esto, los graves (que llevan casi toda la energia de
        // la voz) convergerian y los agudos no llegarian nunca.
        //
        // La potencia que se guarda es la del bloque que acaba de entrar,
        // suavizada. El hecho de que el gradiente sume p particiones se
        // compensa en el paso de adaptacion, que lleva un /p (ver apartado h):
        // es lo mismo y sale mucho mas barato que recorrer las p particiones
        // de todos los bins en cada bloque.
        for (k in 0 until n) {
            val pot = rRe[k] * rRe[k] + rIm[k] * rIm[k]
            potBin[k] = olvidoPot * potBin[k] + (1f - olvidoPot) * pot
        }

        // --- c) Filtrado: eco estimado = SUMA_j W_j * X_(cabeza+j) ---------
        java.util.Arrays.fill(yRe, 0f)
        java.util.Arrays.fill(yIm, 0f)
        for (j in 0 until p) {
            var part = xCabeza + j
            if (part >= p) part -= p
            val ox = part * n
            val ow = j * n
            for (k in 0 until n) {
                val ar = wRe[ow + k]
                val ai = wIm[ow + k]
                val br = xRe[ox + k]
                val bi = xIm[ox + k]
                yRe[k] += ar * br - ai * bi
                yIm[k] += ar * bi + ai * br
            }
        }

        // IFFT del eco estimado. Se hace conjugando, transformando y
        // conjugando otra vez, dividido por n (la Fft de la casa solo hace la
        // directa).
        System.arraycopy(yRe, 0, fft.re, 0, n)
        for (k in 0 until n) fft.im[k] = -yIm[k]
        fft.transforma()
        // Overlap-save: solo vale la SEGUNDA mitad. La primera esta
        // contaminada por el envolvimiento circular de la FFT y hay que
        // tirarla; es exactamente el motivo de hacer la FFT del doble de
        // tamano que el bloque.
        val inv = 1f / n

        // --- d) Error = microfono - eco estimado ---------------------------
        var sumMic = 0f
        var sumErr = 0f
        var sumEco = 0f
        for (i in 0 until b) {
            val eco = fft.re[b + i] * inv
            val mic = micBloque[i]
            val err = mic - eco
            salidaBloque[i] = err
            errTiempo[b + i] = err
            sumMic += mic * mic
            sumErr += err * err
            sumEco += eco * eco
        }
        // La primera mitad del bloque de error va a CERO. Es la otra mitad del
        // truco del overlap-save: al transformar el error hay que decirle a la
        // FFT que solo las ultimas b muestras son error de verdad, o el
        // gradiente sale contaminado y el filtro no converge al sitio.
        java.util.Arrays.fill(errTiempo, 0, b, 0f)

        // --- e) Energias suavizadas y metricas ------------------------------
        val gEn = 0.9f
        enMic = gEn * enMic + (1f - gEn) * (sumMic / b)
        enErr = gEn * enErr + (1f - gEn) * (sumErr / b)
        enEco = gEn * enEco + (1f - gEn) * (sumEco / b)
        var sumRef = 0f
        for (i in b until n) sumRef += refVentana[i] * refVentana[i]
        enRef = gEn * enRef + (1f - gEn) * (sumRef / b)

        erleDb = if (enErr > 1e-12f && enMic > 1e-12f) {
            (10.0 * kotlin.math.ln((enMic / enErr).toDouble()) / 2.302585092994046)
                .toFloat().coerceIn(-20f, 60f)
        } else 0f

        // --- f) Control de adaptacion ---------------------------------------
        //
        // DOS razones para NO adaptar, y las dos son importantes:
        //
        //  1. SIN REFERENCIA no se adapta. Si no esta saliendo nada por el
        //     altavoz, el error es voz pura y el gradiente es puro ruido: el
        //     filtro divergiria hacia cualquier sitio. Es el fallo numero uno
        //     de un AEC mal hecho.
        //
        //  2. DOBLE HABLA. Si el usuario habla mientras suena el eco, el error
        //     lleva su voz dentro, y el NLMS interpreta esa voz como "eco que
        //     no he cancelado" y corrompe los pesos intentando cancelarla.
        //     Tras dos segundos de doble habla el filtro esta destrozado y
        //     tarda otros tantos en recuperarse. Por eso en cuanto se
        //     sospecha, se congela.
        //
        // COMO SE DETECTA, Y EL ERROR QUE HAY QUE EVITAR. La tentacion es
        // comparar el eco ESTIMADO con el microfono: "si el eco que he
        // estimado es flojo y aun asi entra mucho por el micro, es voz". Eso
        // esta MAL y se comprobo midiendo: es circular. Un filtro recien
        // arrancado estima eco CERO, asi que siempre parece doble habla,
        // asi que nunca adapta, asi que nunca deja de estimar cero. Bloqueo
        // permanente: en la prueba daba 0% de adaptacion y 0 dB de ERLE.
        //
        // Lo correcto es comparar el microfono con la REFERENCIA, que es un
        // dato independiente del estado del filtro (Geigel, Bell Labs 1981).
        // Se lleva una estimacion del acoplamiento del lazo -- cuanta senal
        // de micro produce una unidad de referencia -- quedandose con el
        // MAXIMO observado en tramos sin sospecha. Si de pronto entra por el
        // microfono bastante mas de lo que ese acoplamiento puede explicar,
        // lo que sobra no viene del altavoz: es voz cercana.
        val hayReferencia = enRef > 1e-8f

        // Se compara con las energias de ESTE bloque, no con las suavizadas:
        // la doble habla empieza de golpe y hay que congelarse en el acto. Con
        // las suavizadas (que arrastran ~50 ms) la deteccion llegaba tarde y
        // los pesos ya se habian estropeado.
        val micAhora = sumMic / b
        val refAhora = sumRef / b

        // Cuanto microfono explica el lazo con la referencia que hay ahora.
        val esperado = acoplamiento * refAhora

        // Margen de 6 dB (x4 en energia) sobre lo que el lazo puede explicar.
        // Menos margen daria falsos positivos cada vez que el usuario sube la
        // voz o se acerca al altavoz.
        // El acoplamiento se aprende del propio lazo, asi que al principio vale
        // cero y no sirve para juzgar nada. Mientras no haya visto unos
        // cuantos bloques con referencia, NO se acusa a nadie de doble habla:
        // si no, el detector salta desde el primer bloque, congela el filtro y
        // este no llega a converger nunca (medido: adaptaba solo el 24% del
        // tiempo y el ERLE se quedaba en 0 dB).
        if (hayReferencia) bloquesConReferencia++
        // 400 bloques son ~2 s de referencia, justo el tiempo que tarda el
        // estimador de retardo en dar su primera medida y dejar la alineacion
        // en su sitio. Con una espera corta (40 bloques) el detector empezaba
        // a juzgar cuando el acoplamiento aun no estaba aprendido: con el
        // altavoz cerca (retardos de 0 a 60 ms) el eco directo es fuerte y el
        // detector lo confundia con voz del usuario, congelaba el filtro el
        // 64% del tiempo y el ERLE se quedaba en 1-3 dB. Con 2 s de margen,
        // los mismos casos pasan a adaptar con normalidad.
        val yaSabemos = bloquesConReferencia > 400

        dobleHabla = hayReferencia && yaSabemos &&
            acoplamiento > 0f && micAhora > 4f * esperado

        // EL ACOPLAMIENTO SOLO SE APRENDE CUANDO NO HAY DOBLE HABLA, y esto es
        // lo que fallaba. Si se deja que aprenda tambien durante la doble
        // habla, la propia voz del usuario sube el listón que deberia
        // delatarla: el detector aprende "pues resulta que este lazo devuelve
        // muchisimo" y a partir de ahi ya no salta nunca. Medido: adaptaba el
        // 100% del tiempo durante la doble habla, que es justo lo contrario de
        // lo que tiene que hacer.
        //
        // Sube rapido (hay que aprender el nivel del lazo en cuanto arranca) y
        // baja despacio (que un bloque flojo no lo desmonte).
        if (hayReferencia && !dobleHabla) {
            val acoplaAhora = micAhora / refAhora
            // Sube rapido y baja tambien con soltura.
            //
            // La bajada NO puede ser lentisima, aunque la intuicion diga que
            // asi el listón "no se cae". El acoplamiento se mide con la
            // referencia YA ALINEADA por el retardo estimado, y ese retardo
            // cambia cuando el estimador se pronuncia por primera vez (a los
            // dos segundos). Antes de eso el cociente micro/referencia es el
            // de dos senales que no se corresponden, y sale disparatadamente
            // alto; si luego solo puede bajar al 1% por bloque, el listón se
            // queda por las nubes y TODO parece doble habla.
            //
            // Medido: con bajada al 1% y un retardo de 250 ms el filtro solo
            // adaptaba el 5% del tiempo y el ERLE se quedaba en 0 dB, mientras
            // que a 150 ms (donde el desajuste inicial era menor) adaptaba el
            // 91% y daba 25 dB. Con bajada al 10% el listón se recoloca en
            // cuanto la alineacion es buena.
            acoplamiento = if (acoplaAhora > acoplamiento) {
                0.7f * acoplamiento + 0.3f * acoplaAhora
            } else {
                0.9f * acoplamiento + 0.1f * acoplaAhora
            }
        }

        // --- f2) CONGELACION POR CONVERGENCIA (geometria fija) --------------
        //
        // Ver el comentario de [congelaSiConverge]. La idea es que en un
        // megafono portatil el camino no cambia, asi que una vez aprendido no
        // hay nada que ganar readaptando y si mucho que perder: en lazo
        // cerrado cada actualizacion arrastra el sesgo de que la referencia
        // es la propia voz del usuario.
        //
        // El ERLE se mira YA SUAVIZADO (enMic/enErr llevan ~50 ms de memoria),
        // asi que un bloque suelto no decide nada; ademas se exige que se
        // mantenga varios segundos seguidos.
        if (congelaSiConverge) {
            if (!filtroCongelado) {
                // Solo cuenta como bloque bueno si ademas habia referencia:
                // un ERLE alto con el altavoz mudo no significa nada (no hay
                // eco que cancelar, asi que cancelar "todo" es gratis).
                // EL CONTADOR SUBE Y BAJA, NO SE REINICIA DE GOLPE.
                //
                // Exigir una racha SEGUIDA de bloques buenos no funciona con
                // voz: entre silaba y silaba hay huecos donde no hay eco que
                // cancelar, el ERLE medido se desploma y el contador se
                // quedaria a cero una y otra vez. Medido: con reinicio de
                // golpe, el filtro no llegaba a congelar NUNCA en 20 s, aunque
                // llevara desde el segundo 6 dando 30 dB.
                //
                // Subiendo de uno en uno con los bloques buenos y bajando de
                // uno en uno con los malos, lo que se mide es el BALANCE: si
                // la mayoria de los bloques con voz van bien, el contador
                // sube, y los huecos solo lo frenan un poco.
                if (hayReferencia && erleDb >= erleParaCongelar) {
                    bloquesBuenos++
                    if (bloquesBuenos >= bloquesParaCongelar) {
                        filtroCongelado = true
                        bloquesMalos = 0
                    }
                } else if (bloquesBuenos > 0) {
                    bloquesBuenos--
                }
            } else {
                // Congelado: se vigila que siga cancelando. Si deja de
                // hacerlo durante un rato es que la geometria ha cambiado de
                // verdad y toca volver a aprender.
                if (hayReferencia && erleDb < erleParaDescongelar) {
                    bloquesMalos++
                    if (bloquesMalos >= bloquesParaDescongelar) {
                        filtroCongelado = false
                        bloquesMalos = 0
                        bloquesBuenos = 0
                    }
                } else {
                    bloquesMalos = 0
                }
            }
        } else if (filtroCongelado) {
            // Han apagado la congelacion desde la pantalla: se descongela.
            filtroCongelado = false
            bloquesBuenos = 0
            bloquesMalos = 0
        }

        adaptando = hayReferencia && !dobleHabla && !filtroCongelado

        if (!adaptando) return

        // --- g) FFT del error ------------------------------------------------
        System.arraycopy(errTiempo, 0, fft.re, 0, n)
        java.util.Arrays.fill(fft.im, 0f)
        fft.transforma()
        System.arraycopy(fft.re, 0, eRe, 0, n)
        System.arraycopy(fft.im, 0, eIm, 0, n)

        // --- h) Actualizacion NLMS: W_j += mu * conj(X_j) * E / potencia -----
        //
        // El conjugado de X es lo que hace que esto sea un gradiente y no
        // cualquier otra cosa: la derivada del error cuadratico respecto a un
        // peso complejo lleva el conjugado de la entrada. Sin conjugar, el
        // filtro se va a hacer puñetas (diverge o converge al sitio
        // equivocado, segun la fase).
        // --- El factor de escala del overlap-save, que NO es opcional --------
        //
        // Aqui hay un factor 2 (en general n/b) que no aparece en la formula
        // del NLMS de los libros porque los libros la escriben en el tiempo.
        // Al trabajar con overlap-save, del bloque de n muestras que entra en
        // la FFT solo b son error de verdad (la otra mitad se pone a cero), asi
        // que la correlacion que sale del producto conj(X)*E se queda a la
        // mitad de lo que deberia. Medido con un gradiente de una sola pasada,
        // eco de ganancia 0,5 y mu=1: el filtro estimaba h[0] = 0,238 en vez
        // de 0,5, justo la mitad.
        //
        // Compensarlo NO es cosmetico: sin el factor, "mu = 0,3" no vale 0,3
        // sino el doble en la practica, y el filtro trabajaba por encima de su
        // limite de estabilidad. De ahi que en las pruebas divergiera siempre:
        // con una sola particion y un eco trivial la norma de los pesos se iba
        // a 6,8e11 y el ERLE a -190 dB.
        // --- Suelo de la normalizacion, contra los bins vacios ---------------
        //
        // El NLMS divide por la potencia de cada bin. En un bin donde la
        // referencia casi no tiene energia, esa division se dispara y el paso
        // se vuelve enorme justo donde MENOS informacion hay: el peso de ese
        // bin da un salto gigante guiado por puro ruido. Con ruido blanco de
        // laboratorio no pasa (todos los bins tienen la misma energia), pero
        // con VOZ pasa constantemente, porque la voz deja medio espectro
        // practicamente vacio en cada instante. Por eso el filtro aguantaba
        // ruido blanco a mu=32 y en cambio divergia con voz a mu=16.
        //
        // El arreglo estandar es no dejar que ningun bin se normalice por
        // debajo de una fraccion de la potencia MEDIA del espectro: donde hay
        // senal manda la potencia del bin, y donde no la hay manda el suelo,
        // que frena el paso en vez de dispararlo.
        var potMedia = 0f
        for (k in 0 until n) potMedia += potBin[k]
        potMedia /= n
        val sueloPot = potMedia * 0.01f + delta

        val m2 = mu
        for (j in 0 until p) {
            var part = xCabeza + j
            if (part >= p) part -= p
            val ox = part * n
            val ow = j * n
            for (k in 0 until n) {
                //
                // EL FACTOR n, QUE ES EL QUE FALTABA Y HACIA DIVERGIR TODO.
                // La potencia por bin que sale de la FFT no es la energia de
                // la senal: la FFT sin normalizar multiplica por n. Si se
                // normaliza el paso con esa potencia "en crudo", el paso
                // efectivo es n veces (aqui 512) mayor de lo que uno cree, y
                // el filtro trabaja muy por encima de su limite de
                // estabilidad.
                //
                // Se vio midiendo: con el AEC de UNA particion y un eco
                // trivial (ganancia 0,5, retardo cero) TODOS los pasos
                // divergian, incluso mu = 0,003, cosa imposible en un NLMS
                // bien escrito, que es estable para cualquier mu < 2. Con el
                // factor n puesto, el mismo montaje converge a 120 dB de ERLE
                // y la norma de los pesos se queda quieta en su valor bueno.
                val paso = m2 / (maxOf(potBin[k], sueloPot) * n * p)
                val ar = xRe[ox + k]
                val ai = -xIm[ox + k]        // conjugado
                val br = eRe[k]
                val bi = eIm[k]
                // Fuga (leaky NLMS): los pesos se encogen un pelin en cada
                // actualizacion. Ver el comentario de [fuga] sobre por que sin
                // esto el filtro crece sin parar y acaba metiendo mas eco del
                // que quita.
                wRe[ow + k] = fuga * wRe[ow + k] + paso * (ar * br - ai * bi)
                wIm[ow + k] = fuga * wIm[ow + k] + paso * (ar * bi + ai * br)
            }
        }

        // --- h2) GUARDIA CONTRA LA DIVERGENCIA -------------------------------
        //
        // ESTO NO ES UN ADORNO DEFENSIVO: sin ello el AEC se va a Infinity en
        // MEDIO SEGUNDO en cuanto se le alinea bien el retardo en lazo
        // cerrado, y de ahi a NaN. Medido con la cadena entera, camino
        // portatil y retardo calibrado a 203 ms:
        //
        //   t=0,2 s  norma  372      ERLE  -4 dB
        //   t=0,5 s  norma  1,8e13   ERLE -20 dB
        //   t=0,7 s  norma  Infinity ERLE   0 dB  <- y ya no vuelve
        //
        // Y el NaN es PEGAJOSO: enRef se queda en NaN, `enRef > 1e-8` da false
        // para siempre, `hayReferencia` nunca vuelve a ser cierto y el AEC no
        // adapta NUNCA MAS en toda la sesion. El usuario ve "ERLE 0,0 dB" y no
        // hay forma de que se recupere sin reiniciar la cadena entera.
        //
        // POR QUE DIVERGE, que es lo que hay que entender. En lazo cerrado la
        // referencia del AEC es SU PROPIA SALIDA amplificada (se apunta al
        // final de la cadena, paso 10). Si el filtro se pasa de frenada y
        // amplifica en vez de cancelar, esa salida mas grande vuelve como
        // referencia mas grande, que hace un gradiente mayor, que amplifica
        // mas: realimentacion positiva pura. La fuga (leaky NLMS) frena el
        // paseo aleatorio de los pesos, pero no puede con una realimentacion
        // que crece exponencialmente.
        //
        // No pasaba en las pruebas de lazo abierto porque alli la referencia
        // es una senal externa fija: por mucho que el filtro se desmadre, la
        // referencia no crece con el.
        //
        // LA GUARDIA. Si la norma de los pesos se dispara, el filtro se tira y
        // se vuelve a empezar de cero. Perder la convergencia cuesta unos
        // segundos; quedarse en NaN cuesta la sesion entera.
        //
        // El limite se pone sobre la norma porque es la medida que avisa
        // ANTES: cuando un filtro va a divergir, la norma crece mucho antes de
        // que el audio suene mal. Un camino acustico real con el microfono
        // detras del cono tiene una norma muy por debajo de 1 (atenua 15-20
        // dB), asi que un valor de 8 deja muchisimo margen a cualquier
        // solucion legitima y sigue cazando la divergencia a tiempo.
        var norma2 = 0f
        for (i in wRe.indices) norma2 += wRe[i] * wRe[i] + wIm[i] * wIm[i]
        // Se comprueba tambien que sea un numero: en cuanto aparece un NaN o
        // un infinito hay que tirar los pesos, porque cualquier comparacion
        // posterior contra ellos daria false y el filtro se quedaria zombi.
        if (!(norma2 < limiteNorma * limiteNorma)) {
            java.util.Arrays.fill(wRe, 0f)
            java.util.Arrays.fill(wIm, 0f)
            java.util.Arrays.fill(potBin, 0f)
            // Las energias suavizadas tambien pueden llevar NaN dentro: si se
            // dejan, el NaN vuelve a envenenar `hayReferencia` en el siguiente
            // bloque y no habriamos arreglado nada.
            enMic = 1e-10f; enErr = 1e-10f; enRef = 1e-10f; enEco = 1e-10f
            acoplamiento = 0f
            bloquesConReferencia = 0
            filtroCongelado = false
            bloquesBuenos = 0
            bloquesMalos = 0
            divergencias++
            return
        }

        // --- i) Restriccion del gradiente, que NO es opcional ----------------
        //
        // Aqui hay una trampa clasica. Los pesos en frecuencia representan una
        // respuesta al impulso de b muestras, pero nada impide que la
        // actualizacion les meta componentes que corresponden a un impulso mas
        // largo que el bloque. Esa parte "de mas" produce un envolvimiento
        // circular que el overlap-save no puede quitar, y el filtro converge a
        // una solucion que cancela menos de lo que podria (tipicamente 6-10 dB
        // menos de ERLE).
        //
        // El arreglo es pasar cada particion de pesos al tiempo, poner a cero
        // la segunda mitad, y volver a frecuencia. Es la version "restringida"
        // del algoritmo, la unica que converge a la solucion optima de Wiener.
        // Cuesta 2 FFT mas por particion, asi que se hace por TURNOS: una
        // particion distinta en cada bloque. Convergen igual, con un pelin mas
        // de rizado, y el coste se reparte.
        restringeUna()
    }

    /** Particion a la que le toca el turno de restriccion. */
    private var turnoRestriccion = 0

    private fun restringeUna() {
        val j = turnoRestriccion
        turnoRestriccion++
        if (turnoRestriccion >= p) turnoRestriccion = 0
        val ow = j * n

        // --- Pesos al dominio del tiempo (IFFT) -----------------------------
        //
        // La Fft de la casa solo hace la transformada DIRECTA, asi que la
        // inversa se monta como  ifft(X) = conj( fft( conj(X) ) ) / n.
        //
        // OJO: la conjugacion de SALIDA hay que hacerla en TODO el array y la
        // division por n tambien, ANTES de tocar nada mas. La primera version
        // conjugaba y dividia solo los b primeros indices (que eran los que se
        // iban a conservar) y dejaba el resto a medias; como ademas la vuelta
        // a frecuencia no volvia a conjugar, cada pasada por aqui multiplicaba
        // los pesos en vez de dejarlos como estaban. Se vio forzando la
        // restriccion de las p particiones en cada bloque: la norma de los
        // pesos se iba a 5e5 y el ERLE a -93 dB. Una operacion que tiene que
        // ser IDEMPOTENTE estaba amplificando.
        val inv = 1f / n
        System.arraycopy(wRe, ow, fft.re, 0, n)
        for (k in 0 until n) fft.im[k] = -wIm[ow + k]
        fft.transforma()
        for (k in 0 until n) {
            fft.re[k] = fft.re[k] * inv
            fft.im[k] = -fft.im[k] * inv
        }

        // La primera mitad es la respuesta al impulso valida; la segunda es lo
        // que sobra (el envolvimiento circular) y se tira.
        java.util.Arrays.fill(fft.re, b, n, 0f)
        java.util.Arrays.fill(fft.im, b, n, 0f)

        // Y de vuelta a frecuencia: esta si es una directa tal cual.
        fft.transforma()
        System.arraycopy(fft.re, 0, wRe, ow, n)
        System.arraycopy(fft.im, 0, wIm, ow, n)
    }

    /**
     * Supresor no lineal del residuo (NLP), muestra a muestra.
     *
     * POR QUE HACE FALTA. La cancelacion lineal deja siempre un residuo: la
     * sala cambia, el altavoz distorsiona, el filtro tiene longitud finita.
     * Ese residuo es poco, pero es la PROPIA VOZ del usuario repetida, y el
     * oido la reconoce aunque este 20 dB por debajo. Un AEC sin supresor
     * residual "casi" quita el eco, que para el usuario es lo mismo que no
     * quitarlo.
     *
     * QUE HACE. Compara cuanta energia de eco habia con cuanta queda. Si el
     * AEC esta cancelando mucho (ERLE alto) y ademas lo que queda es poco
     * comparado con el eco que habia, es que lo que suena ES residuo y se
     * atenua. Si el usuario esta hablando, su voz domina el error, la
     * proporcion se dispara y el supresor se abre entero.
     *
     * Esto es lo mas parecido a "coherencia con la referencia" que se puede
     * hacer sin pagar otra FFT: la proporcion eco/error por bloque es
     * justamente una medida de cuanto de lo que queda se explica por la
     * referencia.
     */
    private var gananciaNlp = 1f

    /** Ganancia de supresion que toca aplicar ahora. Suavizada. */
    fun gananciaResidual(): Float {
        if (!nlpActivo) {
            gananciaNlp = 1f
            return 1f
        }
        val sueloLin = Math.pow(10.0, nlpMaximoDb / 20.0).toFloat()

        // Cuanto de lo que queda se explica por el eco. Si el eco estimado era
        // grande y el error es pequeno, estamos en "solo eco": suprimir.
        val objetivo = if (enEco > 1e-10f && enErr > 1e-12f) {
            // err/eco pequeno -> casi todo era eco -> suprimir fuerte.
            val r = kotlin.math.sqrt((enErr / (enEco + 1e-12f)).toDouble()).toFloat()
            r.coerceIn(sueloLin, 1f)
        } else 1f

        // Suavizado asimetrico: abrir RAPIDO y cerrar despacio. Al reves se
        // comeria la primera silaba del usuario cada vez que empieza a hablar,
        // que es el defecto que todo el mundo nota en los manos libres malos.
        val c = if (objetivo > gananciaNlp) 0.3f else 0.95f
        gananciaNlp = objetivo + (gananciaNlp - objetivo) * c
        return gananciaNlp
    }
}


// ---------------------------------------------------------------------------
// 13b) CALIBRADOR DEL CAMINO DE ECO
// ---------------------------------------------------------------------------

/**
 * Mide el retardo REAL entre lo que sale por el altavoz y lo que vuelve por el
 * microfono, emitiendo una senal de prueba conocida.
 *
 * POR QUE ESTO EXISTE, Y POR QUE ES LA PIEZA QUE FALTABA.
 *
 * El [EstimadorRetardo] automatico funciona muy bien en lazo ABIERTO y no
 * funciona en lazo CERRADO, y no es un fallo suyo: en un megafono lo que sale
 * por el altavoz ES la voz que acaba de entrar por el microfono, asi que
 * referencia y microfono se parecen muchisimo a desfase casi cero. La
 * correlacion no tiene un pico en el retardo del eco; baja monotona desde
 * cero, dominada por el parecido de la voz consigo misma. Sin alineacion, el
 * eco cae fuera de la ventana del filtro adaptativo y el AEC cancela CERO.
 *
 * La calibracion rompe ese circulo cambiando las condiciones: durante 1-2
 * segundos el usuario se calla, nosotros emitimos una senal que NO es su voz,
 * y medimos con ella. Entonces la correlacion si tiene un pico limpio, porque
 * la referencia ya no se parece a nada mas de lo que hay en el microfono.
 * Es exactamente lo que hacen los sistemas profesionales de megafonia fija
 * cuando se calibran contra la sala al instalarlos.
 *
 * En un megafono portatil esto es MUY efectivo porque la geometria es casi
 * constante: lo que se mide una vez sigue valiendo. En megafonia de sala
 * habria que recalibrar cada poco y no valdria la pena.
 *
 * QUE SENAL SE EMITE, Y POR QUE ESA. Una rafaga corta de ruido paso banda
 * (300-3000 Hz, la banda de la voz) modulada por una ventana suave:
 *
 *  - RUIDO y no un tono: un tono puro no sirve para medir retardo, porque
 *    todas sus repeticiones son iguales y la correlacion tiene picos cada
 *    periodo. El ruido tiene una autocorrelacion que es una AGUJA, que es
 *    justo lo que hace falta para medir retardo con precision.
 *  - PASO BANDA en la banda de voz: fuera de ahi el altavoz no radia y el
 *    microfono no recoge, asi que la energia que se meta ahi solo sirve para
 *    molestar al que pasa por la calle.
 *  - VENTANA SUAVE: sin ella la rafaga empieza y acaba con un chasquido, que
 *    ademas de sonar mal excita el altavoz de forma no lineal.
 *
 * COMO SE MIDE. Correlacion cruzada por FFT entre lo emitido y lo captado, con
 * las FORMAS DE ONDA (no las envolventes): aqui si se puede, porque sabemos
 * exactamente que hemos emitido y sabemos que no hay voz encima. La forma de
 * onda da una resolucion de UNA muestra (0,02 ms), muchisimo mejor que los
 * 2,7 ms de la envolvente.
 *
 * SE USA ASI, desde el hilo de audio:
 *
 *   cal.arranca()
 *   ... en cada muestra:  salida = cal.siguienteMuestra(microfono)
 *   ... cuando cal.terminado:  aec.fijaRetardo(cal.retardoMuestras)
 *
 * Mientras dura, [siguienteMuestra] devuelve la senal de prueba y el resto de
 * la cadena no debe sonar: el usuario ve un aviso de "no hables".
 */
class CalibradorEco(
    private val fs: Int,
    /** Retardo maximo que se va a buscar, en ms. El A2DP no pasa de ~400. */
    msMaximo: Float = 500f
) {
    /**
     * Duracion de la rafaga de prueba. 250 ms de ruido dan energia de sobra
     * para que el pico de correlacion destaque sobre el ruido de la calle, y
     * son lo bastante cortos como para que nadie se asuste.
     */
    private val muestrasRafaga = (0.25f * fs).toInt()

    /**
     * Cuanto se escucha DESPUES de acabar la rafaga. Tiene que cubrir el
     * retardo maximo mas la cola del camino, o el eco llegaria cuando ya hemos
     * dejado de grabar.
     */
    private val muestrasEscucha = ((msMaximo + 100f) / 1000f * fs).toInt()

    /** Total de muestras que dura la calibracion. */
    private val total = muestrasRafaga + muestrasEscucha

    /**
     * Tamano de la FFT: tiene que ser al menos el doble del tramo analizado
     * para que la correlacion circular no se enrolle sobre si misma.
     */
    private val n = siguientePotenciaDe2(total * 2)

    private val fft = Fft(n)

    /** Lo emitido y lo captado, guardados enteros para correlarlos al final. */
    private val emitido = FloatArray(total)
    private val captado = FloatArray(total)

    // Espacio de trabajo para la correlacion, preasignado.
    private val aRe = FloatArray(n)
    private val aIm = FloatArray(n)

    // Filtros que dan forma a la rafaga de ruido.
    private val hp = Biquad()
    private val lp = Biquad()
    private val r = java.util.Random(20260914L)

    private var i = 0

    /** Amplitud de la rafaga. 0,25 se oye claro sin molestar ni saturar. */
    @Volatile var amplitud = 0.25f

    /** true cuando ya hay resultado. */
    @Volatile var terminado = false; private set

    /** Retardo medido, en muestras. -1 si no se pudo medir. */
    @Volatile var retardoMuestras = -1; private set

    /** Retardo medido en ms, para la pantalla. -1 si no se pudo. */
    @Volatile var retardoMs = -1f; private set

    /**
     * Lo claro que salio el pico, 0..1. Por debajo de ~0,3 la medida no es de
     * fiar: normalmente significa que el altavoz estaba mudo, que el volumen
     * estaba al minimo o que habia un ruido enorme alrededor.
     */
    @Volatile var calidad = 0f; private set

    /** Cuanto queda, 0..1, para pintar una barra de progreso. */
    val progreso: Float get() = (i.toFloat() / total).coerceIn(0f, 1f)

    fun arranca() {
        java.util.Arrays.fill(emitido, 0f)
        java.util.Arrays.fill(captado, 0f)
        i = 0
        terminado = false
        retardoMuestras = -1
        retardoMs = -1f
        calidad = 0f
        hp.pasoAlto(300f, 0.707f, fs)
        lp.pasoBajoRbj(3000f, 0.707f, fs)
        hp.reiniciar()
        lp.reiniciar()
    }

    /**
     * Una muestra. Se le da lo que entra por el microfono y devuelve lo que
     * hay que mandar al altavoz.
     *
     * Cuando [terminado] se pone a true ya no hay que llamar mas: devuelve
     * silencio y no toca nada.
     */
    fun siguienteMuestra(microfono: Float): Float {
        if (terminado) return 0f

        var salida = 0f
        if (i < muestrasRafaga) {
            // Ruido blanco filtrado a la banda de voz, con ventana suave en
            // los dos extremos para que no haya chasquido.
            val blanco = (r.nextFloat() * 2f - 1f)
            var v = lp.procesa(hp.procesa(blanco))
            // Ventana de Hann sobre la rafaga entera.
            val w = 0.5f - 0.5f * cos(2.0 * Math.PI * i / (muestrasRafaga - 1)).toFloat()
            v *= w * amplitud
            salida = v
        }
        emitido[i] = salida
        captado[i] = microfono
        i++

        if (i >= total) {
            calcula()
            terminado = true
        }
        return salida
    }

    /**
     * Correlacion cruzada por FFT entre emitido y captado.
     *
     * corr = IFFT( conj(FFT(emitido)) * FFT(captado) ). El pico cae en el
     * desfase k tal que captado[t] se parece a emitido[t-k], que es
     * exactamente el retardo de ida y vuelta que buscamos.
     */
    private fun calcula() {
        // FFT de lo emitido.
        java.util.Arrays.fill(fft.re, 0f)
        java.util.Arrays.fill(fft.im, 0f)
        System.arraycopy(emitido, 0, fft.re, 0, total)
        fft.transforma()
        System.arraycopy(fft.re, 0, aRe, 0, n)
        System.arraycopy(fft.im, 0, aIm, 0, n)

        // FFT de lo captado.
        java.util.Arrays.fill(fft.re, 0f)
        java.util.Arrays.fill(fft.im, 0f)
        System.arraycopy(captado, 0, fft.re, 0, total)
        fft.transforma()

        // conj(EMITIDO) * CAPTADO.
        for (k in 0 until n) {
            val ar = aRe[k]
            val ai = -aIm[k]
            val br = fft.re[k]
            val bi = fft.im[k]
            fft.re[k] = ar * br - ai * bi
            fft.im[k] = ar * bi + ai * br
        }
        // IFFT (conjugar, transformar, quedarse con la parte real).
        for (k in 0 until n) fft.im[k] = -fft.im[k]
        fft.transforma()

        // El eco no puede llegar antes de salir: solo desfases positivos. Y no
        // mas alla de la ventana de escucha, porque a partir de ahi la
        // referencia se sale del tramo emitido y la correlacion no significa
        // nada.
        var mejor = -1
        var mejorV = 0f
        for (k in 0 until muestrasEscucha) {
            val v = fft.re[k] / n
            if (v > mejorV) { mejorV = v; mejor = k }
        }
        if (mejor < 0 || mejorV <= 0f) {
            calidad = 0f
            retardoMuestras = -1
            retardoMs = -1f
            return
        }

        // Cuanto destaca el pico sobre el suelo de la correlacion. Se mide
        // LEJOS del pico, que es donde de verdad se ve el ruido de fondo; la
        // zona pegada al pico es la falda del propio pico y no es suelo.
        // Un margen de 2 ms a cada lado basta: la autocorrelacion de un ruido
        // paso banda de 300-3000 Hz se apaga en mucho menos que eso.
        val margen = (0.002f * fs).toInt().coerceAtLeast(8)
        var suma = 0f
        var cuantos = 0
        for (k in 0 until muestrasEscucha) {
            if (k < mejor - margen || k > mejor + margen) {
                val v = fft.re[k] / n
                suma += if (v < 0f) -v else v
                cuantos++
            }
        }
        val suelo = if (cuantos > 0) suma / cuantos else 0f
        val destaca = if (suelo > 1e-20f) mejorV / suelo else 0f
        // Un pico 10 veces el suelo ya es una deteccion redonda. Con la senal
        // de prueba (ruido, autocorrelacion en aguja) y sin voz encima, lo
        // normal es pasar de eso con holgura; si no se llega, es que el
        // altavoz no estaba sonando.
        calidad = (destaca / 10f).coerceIn(0f, 1f)

        // Solo se acepta si destaca de verdad. Mas vale no dar retardo (y que
        // el AEC se quede como estaba) que dar uno inventado, que desalinea el
        // filtro y lo deja cancelando cero.
        if (destaca >= 4f) {
            retardoMuestras = mejor
            retardoMs = mejor * 1000f / fs
        } else {
            retardoMuestras = -1
            retardoMs = -1f
        }
    }

    private fun siguientePotenciaDe2(v: Int): Int {
        var x = 1
        while (x < v) x = x shl 1
        return x
    }
}
// ---------------------------------------------------------------------------
// 14) FILTRO DE VOZ POR ESTRUCTURA ARMONICA
// ---------------------------------------------------------------------------

/**
 * Deja pasar la voz y atenua lo demas.
 *
 * SEAMOS HONESTOS CON EL NOMBRE. Esto NO es una red neuronal, aunque en la
 * pantalla se llame "filtro de voz" y la gente lo llame "filtro IA". Una red
 * de verdad tipo RNNoise son 85 kB de pesos entrenados sobre cientos de horas
 * de audio, escrita en C y ejecutada con NEON; reimplementarla en Kotlin puro
 * sin JNI no es viable ni tendria sentido. Lo que hay aqui es DETECCION DE
 * ESTRUCTURA ARMONICA mas supresion espectral, que es lo que hacian los
 * supresores buenos antes de que existieran las redes.
 *
 * Y POR QUE EN ESTE CASO CONCRETO PUEDE FUNCIONAR IGUAL O MEJOR. Una red
 * generica esta entrenada para ser robusta a todo: microfonos malos, senal
 * lejana, relaciones senal-ruido horribles. Aqui el caso es el contrario: un
 * lavalier a menos de 10 cm de la boca. La voz llega 25-30 dB por encima de
 * cualquier cosa de la sala. Con esa relacion senal-ruido, distinguir voz de
 * no-voz es facil y no hace falta una red: basta con mirar si hay estructura
 * armonica, porque la voz sonora la tiene SIEMPRE y el ruido de sala no la
 * tiene NUNCA.
 *
 * LAS DOS PIEZAS:
 *
 *  1. DETECTOR DE VOZ por producto espectral armonico (HPS, Harmonic Product
 *     Spectrum, de Noll 1969) mas relacion armonico/ruido (HNR). La voz sonora
 *     tiene un tono fundamental y armonicos en 2f, 3f, 4f... Multiplicando el
 *     espectro por versiones de si mismo comprimidas 2, 3 y 4 veces, todos los
 *     armonicos caen encima del fundamental y este se dispara; el ruido, que
 *     no tiene esa estructura, se aplana. El pico del HPS dice el tono y lo
 *     alto que es dice cuanta voz hay.
 *
 *  2. SUPRESION ESPECTRAL tipo Wiener con estimacion de ruido por minimos
 *     (la idea de Martin y de MCRA: el ruido es lo mas bajo que suena en cada
 *     banda a lo largo del tiempo, aunque haya voz por encima casi siempre).
 *     La ganancia de cada banda sale de su relacion senal-ruido, y el detector
 *     de voz manda por encima: sin voz, se atenua todo mucho mas.
 *
 * Se trabaja en BANDAS, no en bins sueltos. Dos razones: el coste baja mucho,
 * y sobre todo las bandas evitan el "ruido musical", que es el artefacto
 * clasico de la sustraccion espectral (bins sueltos que sobreviven al azar y
 * suenan como campanitas). Agrupando en bandas anchas, un bin que sobrevive
 * arrastra a sus vecinos y no se oye como un tintineo.
 *
 * Trabaja por bloques con solapamiento del 50% y ventana de raiz de Hann, que
 * es la que permite reconstruir sin rizado al sumar (analisis y sintesis con
 * la misma ventana: raizHann^2 sumada con salto de mitad da constante).
 */
class FiltroVoz(private val fs: Int, private val nFft: Int = 512) {

    private val bins = nFft / 2
    private val salto = nFft / 2

    private val fft = Fft(nFft)

    /** Ventana de raiz de Hann, para analisis Y sintesis. */
    private val ventana = FloatArray(nFft)

    /** Buffer de entrada acumulada. */
    private val entrada = FloatArray(nFft)
    private var entradaLlenas = 0
    /** Cola de salida solapada. */
    private val solape = FloatArray(nFft)
    /** Bloque de salida listo para entregar. */
    private val salida = FloatArray(salto)
    private var salidaLeidas = 0

    // --- Bandas --------------------------------------------------------------
    //
    // 20 bandas repartidas de forma aproximadamente logaritmica entre 80 Hz y
    // 8 kHz, que es donde vive la voz. Logaritmica y no lineal porque el oido
    // (y los formantes) se reparten asi: hacen falta bandas finas en los
    // graves, donde estan el fundamental y el primer formante, y anchas en los
    // agudos, donde solo hay fricativas.
    private val nBandas = 20
    private val bandaIni = IntArray(nBandas)
    private val bandaFin = IntArray(nBandas)

    private val potBanda = FloatArray(nBandas)
    private val ruidoBanda = FloatArray(nBandas)
    private val gananciaBanda = FloatArray(nBandas) { 1f }
    private val magBin = FloatArray(bins)

    // --- Analisis APARTE para el tono, sobre la senal decimada -------------
    //
    // Ver el comentario de [estructuraDeVoz]: con la FFT del filtrado (512
    // puntos a 48 kHz) cada bin mide 93,75 Hz y el fundamental de la voz no se
    // puede ni localizar. Decimando a fs/4 la misma FFT da 23,4 Hz por bin,
    // que ya sirve. Es analisis puro: no esta en el camino del audio, solo
    // mira.
    private val decimadorTono = Decimador(fs, factor = 4)
    private val bufTono = FloatArray(nFft)
    private var tonoLlenas = 0
    private val magTono = FloatArray(bins)
    private val fftTono = Fft(nFft)

    // --- Ajustes -------------------------------------------------------------
    /**
     * Cuanto se atenua como mucho cuando NO hay voz, en dB (negativo).
     *
     * -20 dB, no menos. Atenuar a silencio absoluto suena peor, no mejor: la
     * sala desaparece de golpe entre frase y frase y el resultado parece una
     * radio rota. Con -20 dB el ruido de fondo pasa a ser inaudible bajo la
     * voz pero la continuidad se mantiene.
     */
    @Volatile var atenuacionMaximaDb = -20f

    /**
     * Umbral de "esto es voz", sobre el HPS normalizado. Por encima se abre.
     * 0,35 es el punto donde en las pruebas el tono puro y el ruido blanco
     * caen fuera y la voz sintetica con armonicos entra holgada.
     */
    @Volatile var umbralVoz = 0.35f

    /** Banda util de la voz. Fuera de aqui se corta sin contemplaciones. */
    @Volatile var hzMinimo = 80f
    @Volatile var hzMaximo = 8000f

    // --- Metricas ------------------------------------------------------------
    /** 0..1, cuanta estructura de voz se esta viendo. */
    @Volatile var probabilidadVoz = 0f; private set
    /** Tono fundamental detectado en Hz, o -1. */
    @Volatile var tonoHz = -1f; private set

    /** Suavizado temporal de la decision de voz. */
    private var vozSuave = 0f

    init {
        // Raiz de Hann. La de Hann normal elevada al cuadrado por el solape
        // daria Hann^2, que NO suma constante con salto de mitad; la raiz si.
        for (i in 0 until nFft) {
            val h = 0.5 - 0.5 * cos(2.0 * Math.PI * i / nFft)
            ventana[i] = kotlin.math.sqrt(h).toFloat()
        }

        // Reparto logaritmico de las bandas entre 80 Hz y 8 kHz.
        val kMin = (80f * nFft / fs).toInt().coerceAtLeast(1)
        val kMax = (8000f * nFft / fs).toInt().coerceAtMost(bins - 1)
        val lnMin = ln(kMin.toDouble())
        val lnMax = ln(kMax.toDouble().coerceAtLeast(kMin + 1.0))
        for (bd in 0 until nBandas) {
            val f0 = exp(lnMin + (lnMax - lnMin) * bd / nBandas)
            val f1 = exp(lnMin + (lnMax - lnMin) * (bd + 1) / nBandas)
            bandaIni[bd] = f0.toInt().coerceIn(1, bins - 1)
            bandaFin[bd] = f1.toInt().coerceIn(bandaIni[bd] + 1, bins)
        }
        java.util.Arrays.fill(ruidoBanda, 1e-8f)
    }

    fun reiniciar() {
        java.util.Arrays.fill(entrada, 0f)
        java.util.Arrays.fill(solape, 0f)
        java.util.Arrays.fill(salida, 0f)
        java.util.Arrays.fill(ruidoBanda, 1e-8f)
        java.util.Arrays.fill(gananciaBanda, 1f)
        java.util.Arrays.fill(bufTono, 0f)
        java.util.Arrays.fill(magTono, 0f)
        tonoLlenas = 0
        decimadorTono.reiniciar()
        entradaLlenas = 0
        salidaLeidas = 0
        vozSuave = 0f
        probabilidadVoz = 0f
        tonoHz = -1f
    }

    /**
     * Retardo que introduce, en muestras.
     *
     * Es la VENTANA entera, no el salto. Se acumula media ventana antes de
     * poder analizar nada, y la entrega va otro medio bloque por detras; en
     * total nFft muestras (512 = 10,7 ms a 48 kHz). Medido buscando la
     * alineacion que minimiza el error de reconstruccion: con retardo 512 el
     * error es de -137 dB y la ganancia exactamente 1,0000; declarar 256
     * (que es lo que ponia antes) descuadraba la latencia que se le ensena al
     * usuario y, peor, descuadraria cualquier cosa que intente alinear esta
     * senal con otra.
     */
    fun retardoMuestras(): Int = nFft

    /**
     * Una muestra. Devuelve la muestra filtrada, retrasada [retardoMuestras].
     */
    fun procesa(x: Float): Float {
        // ORDEN: primero se ENTREGA lo que ya estaba hecho y luego se acumula
        // la muestra nueva. Al reves (acumular, y si se completa el bloque
        // analizar y entregar la muestra 0 de ese mismo bloque) la salida se
        // adelanta media ventana a su sitio y la reconstruccion se estropea:
        // medido, el error de rehacer la senal salia POSITIVO (+4 dB, o sea
        // el "error" era mayor que la propia senal) y la mejor alineacion
        // caia en salto/2 en vez de en salto, que es la firma exacta de este
        // desfase.
        val y = if (salidaLeidas < salto) salida[salidaLeidas++] else 0f

        // Analisis del tono, en paralelo y sobre la senal DECIMADA. No toca el
        // audio: solo alimenta al detector de estructura armonica.
        if (decimadorTono.empuja(x)) {
            bufTono[tonoLlenas] = decimadorTono.salida
            tonoLlenas++
            if (tonoLlenas >= nFft) {
                analizaTono()
                // Solapamiento del 50%, igual que el analisis principal.
                System.arraycopy(bufTono, nFft / 2, bufTono, 0, nFft / 2)
                tonoLlenas = nFft / 2
            }
        }

        // Acumular en la mitad nueva del buffer de analisis.
        entrada[salto + entradaLlenas] = x
        entradaLlenas++
        if (entradaLlenas >= salto) {
            entradaLlenas = 0
            analizaYSintetiza()
            // Correr la ventana: la mitad nueva pasa a ser la vieja.
            System.arraycopy(entrada, salto, entrada, 0, salto)
            salidaLeidas = 0
        }
        return y
    }

    private fun analizaYSintetiza() {
        // --- 1) Al dominio de la frecuencia ---------------------------------
        for (i in 0 until nFft) {
            fft.re[i] = entrada[i] * ventana[i]
            fft.im[i] = 0f
        }
        fft.transforma()

        for (k in 0 until bins) {
            magBin[k] = fft.re[k] * fft.re[k] + fft.im[k] * fft.im[k]
        }

        // --- 2) Potencia y ruido por banda ----------------------------------
        //
        // El ruido se sigue por MINIMOS: sube muy despacio y baja rapido. Como
        // el ruido de fondo es lo mas bajo que suena en cada banda, el minimo
        // movil converge a el aunque haya voz por encima el 90% del tiempo.
        // Es la idea de Martin (IEEE TSAP 2001) en version barata.
        for (bd in 0 until nBandas) {
            var s = 0f
            for (k in bandaIni[bd] until bandaFin[bd]) s += magBin[k]
            val pot = s / (bandaFin[bd] - bandaIni[bd])
            potBanda[bd] = pot
            ruidoBanda[bd] = if (pot < ruidoBanda[bd]) {
                // Baja rapido hacia el nuevo minimo: el ruido de sala puede
                // parar de golpe (se apaga el aire acondicionado) y hay que
                // enterarse enseguida.
                0.7f * ruidoBanda[bd] + 0.3f * pot
            } else if (vozSuave > umbralVoz) {
                // CON VOZ ENCIMA, EL SUELO NO SUBE. NADA.
                //
                // Esto es lo que arregla que una vocal sostenida se fuera
                // apagando. Con la subida lenta de siempre (0,002 por trama),
                // una "aaaah" de dos segundos son ~180 tramas y el suelo de
                // ruido acababa trepando hasta el nivel de la propia voz; a
                // partir de ahi la relacion senal-ruido de esa banda daba
                // cero, el Wiener cerraba y la voz se atenuaba sola. Medido:
                // la voz sostenida salia 10-14 dB por debajo mientras que la
                // voz con silabas (que deja huecos donde el suelo se recoloca)
                // salia bien.
                //
                // Congelar el aprendizaje mientras hay voz es exactamente lo
                // que hacen los estimadores tipo MCRA, y por esta razon.
                ruidoBanda[bd]
            } else {
                // Sin voz, sube despacio hacia el nivel que haya.
                0.998f * ruidoBanda[bd] + 0.002f * pot
            }
            if (ruidoBanda[bd] < 1e-12f) ruidoBanda[bd] = 1e-12f
        }

        // --- 3) Detector de voz por estructura armonica ---------------------
        val pVoz = estructuraDeVoz()
        // Suavizado asimetrico: abre rapido (para no comerse el ataque de la
        // silaba) y cierra despacio (para no cortar entre silabas).
        vozSuave = if (pVoz > vozSuave) {
            0.4f * vozSuave + 0.6f * pVoz
        } else {
            0.85f * vozSuave + 0.15f * pVoz
        }
        probabilidadVoz = vozSuave

        // --- 4) Ganancia por banda -------------------------------------------
        val suelo = Math.pow(10.0, atenuacionMaximaDb / 20.0).toFloat()
        // Cuanto manda el detector de voz sobre la ganancia final. Con voz
        // clara (vozSuave alto) se deja pasar segun la relacion senal-ruido de
        // cada banda; sin voz, se cierra hacia el suelo entero.
        val mando = ((vozSuave - umbralVoz) / (1f - umbralVoz)).coerceIn(0f, 1f)

        val kBajo = (hzMinimo * nFft / fs).toInt()
        val kAlto = (hzMaximo * nFft / fs).toInt()

        for (bd in 0 until nBandas) {
            // Wiener: G = SNR / (1 + SNR), con SNR a posteriori de la banda.
            // Es la ganancia que minimiza el error cuadratico si la senal y el
            // ruido no estan correlados, que para voz contra ruido de sala es
            // una aproximacion razonable.
            val snr = (potBanda[bd] / ruidoBanda[bd] - 1f).coerceAtLeast(0f)
            val wiener = snr / (1f + snr)
            // La ganancia final mezcla el Wiener con el mando del detector:
            // aunque una banda tenga buena relacion senal-ruido, si no hay
            // estructura de voz en ninguna parte se cierra igual. Eso es lo
            // que distingue esto de un supresor de ruido normal: un ventilador
            // constante tiene buena SNR en su banda cuando arranca, y un
            // supresor normal lo dejaria pasar; aqui no, porque no es voz.
            var g = suelo + (1f - suelo) * wiener * mando
            if (g > 1f) g = 1f
            // Suavizado temporal de la ganancia de cada banda, para que no
            // parpadee entre bloques (el otro origen del ruido musical).
            gananciaBanda[bd] = 0.5f * gananciaBanda[bd] + 0.5f * g
        }

        // --- 5) Aplicar la ganancia y volver al tiempo -----------------------
        // Los bins de cada banda llevan su ganancia; fuera de la banda util se
        // corta al suelo directamente.
        for (k in 0 until bins) {
            var g = suelo
            if (k in kBajo..kAlto) {
                // Que banda le toca a este bin.
                var bd = -1
                for (j in 0 until nBandas) {
                    if (k >= bandaIni[j] && k < bandaFin[j]) { bd = j; break }
                }
                g = if (bd >= 0) gananciaBanda[bd] else suelo
            }
            fft.re[k] *= g
            fft.im[k] *= g
            // Simetria hermitica: el bin espejo lleva la misma ganancia. Sin
            // esto la senal reconstruida sale compleja y con la mitad de
            // amplitud.
            if (k > 0 && k < bins) {
                fft.re[nFft - k] *= g
                fft.im[nFft - k] *= g
            }
        }

        // IFFT (conjugar, transformar, conjugar, dividir por n).
        for (k in 0 until nFft) fft.im[k] = -fft.im[k]
        fft.transforma()
        val inv = 1f / nFft

        // Solapamiento y suma con la ventana de sintesis.
        for (i in 0 until nFft) {
            val v = fft.re[i] * inv * ventana[i]
            solape[i] += v
        }
        // La primera mitad ya esta completa: sale.
        System.arraycopy(solape, 0, salida, 0, salto)
        // Y se corre el solape.
        System.arraycopy(solape, salto, solape, 0, salto)
        java.util.Arrays.fill(solape, salto, nFft, 0f)
    }

    /**
     * Producto espectral armonico (HPS) y relacion armonico/ruido.
     *
     * Devuelve 0..1: cuanta estructura de voz sonora hay.
     *
     * El HPS multiplica el espectro por si mismo comprimido 2, 3 y 4 veces.
     * Donde hay un fundamental con sus armonicos, los cuatro terminos son
     * grandes a la vez y el producto se dispara; donde solo hay ruido, alguno
     * de los cuatro es pequeno y el producto se hunde. Es muy selectivo
     * justamente porque es un PRODUCTO y no una suma: basta con que falle un
     * armonico para que el candidato se caiga.
     *
     * Importante: un TONO PURO tiene el fundamental pero NO tiene 2f, 3f ni
     * 4f, asi que su HPS es bajo. Eso es lo que hace que esto distinga una
     * vocal de un pitido, que es justo lo que hace falta aqui.
     */
    /**
     * Cuenta cuantos ARMONICOS DE VERDAD hay, y con eso decide si es voz.
     *
     * POR QUE NO VALE EL HNR A SECAS, que fue el primer intento. La idea de
     * "cuanta energia esta en el fundamental y sus armonicos frente al total"
     * suena bien y NO funciona, medido: un tono puro de 440 Hz daba HNR 1,36 y
     * uno de 1000 Hz daba 0,99, mientras que la voz con armonicos daba 0,52 a
     * 0,78. O sea justo al reves de lo que hace falta. La razon es obvia
     * mirandolo: un tono puro tiene TODA su energia en un sitio, asi que
     * cualquier medida de "concentracion" le sale inmejorable. La
     * concentracion no distingue voz de pitido; lo que distingue es que la
     * energia este repartida en una SERIE de multiplos exactos.
     *
     * LO QUE SE MIDE AQUI. Para cada candidato a fundamental se mira cuantos
     * de sus armonicos (2f, 3f, 4f, 5f) asoman de verdad sobre el fondo del
     * espectro. Una vocal tiene 4 de 4; un tono puro tiene 0 de 4, porque por
     * definicion no tiene armonicos; el ruido tiene los que le salgan por azar
     * y encima en un fundamental que cambia en cada trama. Eso SI separa.
     *
     * Y POR QUE SE ANALIZA APARTE Y NO CON LA MISMA FFT DEL FILTRADO. Porque
     * no da la resolucion. A 48 kHz una FFT de 512 reparte 93,75 Hz por bin:
     * el rango entero del fundamental de una voz (70 a 400 Hz) cabe en CUATRO
     * bins. Con eso no se puede buscar un tono: el primer intento acababa
     * mirando los bins 0 a 4 y contestaba "187,5 Hz" a todo, incluido el ruido
     * blanco. El analisis del tono se hace sobre la senal decimada a fs/4, que
     * con la misma FFT da 23,4 Hz por bin y deja 14 bins utiles para el
     * fundamental: ya es un rango donde buscar tiene sentido.
     */
    private fun estructuraDeVoz(): Float {
        // Rango del fundamental de una voz humana, en bins del analisis
        // DECIMADO: de voz grave de hombre (70 Hz) a voz aguda (400 Hz).
        val fsDec = fs / 4
        val kMin = (70f * nFft / fsDec).toInt().coerceAtLeast(2)
        val kMax = (400f * nFft / fsDec).toInt().coerceAtMost(bins / 6)
        if (kMax <= kMin) { tonoHz = -1f; return 0f }

        var total = 0f
        for (k in 1 until bins) total += magTono[k]
        if (total < 1e-10f) { tonoHz = -1f; return 0f }
        val medio = total / bins

        // Un armonico "cuenta" si sobresale del fondo medio del espectro. El
        // factor 3 (unos 5 dB) es bajo a proposito: los armonicos altos de la
        // voz son flojos y no hay que exigirles mucho, lo que importa es que
        // ESTEN.
        val umbralArmonico = medio * 3f

        var mejorPuntos = 0
        var mejorEnergia = 0f
        var mejorK = -1

        for (k in kMin..kMax) {
            val f0 = maxLocalT(k)
            // El propio fundamental tiene que destacar, si no no es candidato.
            if (f0 < umbralArmonico) continue

            var puntos = 0
            var energia = f0
            var h = 2
            while (h <= 5) {
                val kh = k * h
                if (kh >= bins - 1) break
                val v = maxLocalT(kh)
                if (v > umbralArmonico) puntos++
                energia += v
                h++
            }
            // Gana el que tenga mas armonicos; a igualdad, el mas fuerte.
            if (puntos > mejorPuntos || (puntos == mejorPuntos && energia > mejorEnergia)) {
                mejorPuntos = puntos
                mejorEnergia = energia
                mejorK = k
            }
        }

        if (mejorK < 0) { tonoHz = -1f; return 0f }
        tonoHz = mejorK.toFloat() * fsDec / nFft

        // De 0 a 4 armonicos -> de 0 a 1. Con 2 armonicos ya se pasa del
        // umbral de 0,35, que es lo que se quiere: la voz real pierde
        // armonicos constantemente (segun la vocal, segun el microfono) y
        // exigirle los cuatro seria cortarla a cada rato.
        return (mejorPuntos / 4f).coerceIn(0f, 1f)
    }

    /**
     * Espectro de la senal decimada, que es sobre el que se busca el tono.
     * Se enventana con la misma raiz de Hann; aqui no hay que reconstruir
     * nada, pero enventanar evita que los bordes del bloque ensucien el
     * espectro con derrame y aparezcan armonicos que no existen.
     */
    private fun analizaTono() {
        for (i in 0 until nFft) {
            fftTono.re[i] = bufTono[i] * ventana[i]
            fftTono.im[i] = 0f
        }
        fftTono.transforma()
        for (k in 0 until bins) {
            magTono[k] = fftTono.re[k] * fftTono.re[k] + fftTono.im[k] * fftTono.im[k]
        }
    }

    /** Maximo del bin y sus dos vecinos en el espectro del analisis de tono. */
    private fun maxLocalT(k: Int): Float {
        var v = magTono[k]
        if (k > 0 && magTono[k - 1] > v) v = magTono[k - 1]
        if (k < bins - 1 && magTono[k + 1] > v) v = magTono[k + 1]
        return v
    }
}
