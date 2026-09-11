package com.amantis.megafono

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Pantalla unica: un boton grande, los medidores y los ajustes que de verdad
 * cambian algo cuando hay acople. Todo construido en codigo para no arrastrar
 * XML de layout: la app tiene una sola pantalla.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var motor: MotorAudio

    private lateinit var botonPrincipal: Button
    private lateinit var botonHablar: Button
    private lateinit var barraEntrada: View
    private lateinit var barraSalida: View
    private lateinit var etiquetaEstado: TextView
    private lateinit var etiquetaRuta: TextView
    private lateinit var listaEntradas: Spinner
    private lateinit var listaSalidas: Spinner

    // Lo que hay ahora mismo en cada desplegable.
    private var entradas: List<MotorAudio.Dispositivo> = emptyList()
    private var salidas: List<MotorAudio.Dispositivo> = emptyList()
    private lateinit var etiquetaDiagnostico: TextView
    private lateinit var avisoAcople: TextView

    private lateinit var mandoGanancia: SeekBar
    private lateinit var valorGanancia: TextView
    private lateinit var mandoPuerta: SeekBar
    private lateinit var valorPuerta: TextView

    private lateinit var interruptorAec: Switch
    private lateinit var interruptorAntiacople: Switch
    private lateinit var interruptorGraves: Switch
    private lateinit var interruptorSilencio: Switch
    private lateinit var interruptorPulsar: Switch

    private val pantalla = Handler(Looper.getMainLooper())

    private val COD_PERMISOS = 100

    // --- Colores de marca ---------------------------------------------------
    private val LIMA = Color.parseColor("#A8C11D")
    private val LIMA_OSCURO = Color.parseColor("#8BA30F")
    private val FONDO = Color.parseColor("#0A0E00")
    private val TARJETA = Color.parseColor("#151B08")
    private val BORDE = Color.parseColor("#2C3618")
    private val TEXTO = Color.parseColor("#EEF1EC")
    private val TEXTO_SUAVE = Color.parseColor("#9AA29A")
    private val TEXTO_TENUE = Color.parseColor("#6F7560")
    private val AVISO = Color.parseColor("#FFC107")
    private val PELIGRO = Color.parseColor("#F87171")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        motor = Megafono.motor ?: MotorAudio(applicationContext).also { Megafono.motor = it }

        motor.alCambiarEstado = { e -> pantalla.post { pintarEstado(e) } }
        motor.alFallar = { msg ->
            pantalla.post {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                etiquetaEstado.text = msg
                etiquetaEstado.setTextColor(PELIGRO)
                pintarBoton(false)
            }
        }

        setContentView(construirPantalla())
        pintarBotonHablar(false)
        cargarDispositivos()
        pintarBoton(motor.estaCorriendo())
        if (motor.estaCorriendo()) mostrarDiagnostico()
    }

    override fun onResume() {
        super.onResume()
        // El lavalier se puede enchufar con la app ya abierta: al volver a
        // la pantalla repasamos que hay conectado.
        if (::listaEntradas.isInitialized) cargarDispositivos()
    }

    // ------------------------------------------------------------------------

    private fun construirPantalla(): View {
        val raiz = ScrollView(this)
        raiz.setBackgroundColor(FONDO)
        raiz.isFillViewport = true

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), dp(28))
        raiz.addView(col)

        // --- Cabecera con el logo de Amantis --------------------------------
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.amantis_logo)
        logo.adjustViewBounds = true
        val paramsLogo = LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT)
        paramsLogo.gravity = Gravity.CENTER_HORIZONTAL
        logo.layoutParams = paramsLogo
        col.addView(logo)

        val titulo = TextView(this)
        titulo.text = "MEGÁFONO"
        titulo.setTextColor(LIMA)
        titulo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        titulo.letterSpacing = 0.22f
        titulo.gravity = Gravity.CENTER
        titulo.setPadding(0, dp(14), 0, 0)
        col.addView(titulo)

        val subtitulo = TextView(this)
        subtitulo.text = "Del micrófono al altavoz, en directo"
        subtitulo.setTextColor(TEXTO_TENUE)
        subtitulo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        subtitulo.gravity = Gravity.CENTER
        subtitulo.setPadding(0, dp(2), 0, dp(20))
        col.addView(subtitulo)

        // --- Botones: Empezar/Parar y Pulsar para hablar --------------------
        val filaBotones = LinearLayout(this)
        filaBotones.orientation = LinearLayout.HORIZONTAL

        botonPrincipal = Button(this)
        botonPrincipal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        botonPrincipal.isAllCaps = false
        botonPrincipal.setPadding(0, dp(20), 0, dp(20))
        botonPrincipal.setOnClickListener { alPulsarPrincipal() }
        val pB = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        pB.rightMargin = dp(6)
        filaBotones.addView(botonPrincipal, pB)

        botonHablar = Button(this)
        botonHablar.text = "Pulsar para hablar"
        botonHablar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        botonHablar.isAllCaps = false
        botonHablar.setPadding(0, dp(20), 0, dp(20))
        // Mantener pulsado = hablar. Al soltar se corta, tambien si el dedo
        // se sale del boton sin levantarlo (ACTION_CANCEL): si no, se
        // quedaria el microfono abierto sin querer.
        botonHablar.setOnTouchListener { v, evento ->
            when (evento.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    motor.hablando = true
                    pintarBotonHablar(true)
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    motor.hablando = false
                    pintarBotonHablar(false)
                    true
                }
                else -> false
            }
        }
        val pH = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        pH.leftMargin = dp(6)
        filaBotones.addView(botonHablar, pH)

        col.addView(filaBotones, anchoCompleto())

        // --- Aviso de acople ------------------------------------------------
        avisoAcople = TextView(this)
        avisoAcople.text = "⚠  Acople detectado — bajando volumen"
        avisoAcople.setTextColor(FONDO)
        avisoAcople.setBackgroundColor(AVISO)
        avisoAcople.gravity = Gravity.CENTER
        avisoAcople.setPadding(dp(12), dp(10), dp(12), dp(10))
        avisoAcople.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        avisoAcople.visibility = View.GONE
        col.addView(avisoAcople, anchoCompleto(arriba = 10))

        // --- Medidores ------------------------------------------------------
        val tarjetaNiveles = tarjeta()
        col.addView(tarjetaNiveles, anchoCompleto(arriba = 16))

        tarjetaNiveles.addView(rotulo("NIVELES"))

        tarjetaNiveles.addView(etiquetaPequena("Entrada (micrófono)"))
        barraEntrada = View(this)
        // Arranca como pista apagada; al sonar se llena de lima.
        barraEntrada.setBackgroundColor(BORDE)
        tarjetaNiveles.addView(barraEntrada, barraMedidor())

        tarjetaNiveles.addView(etiquetaPequena("Salida (altavoz)", arriba = 12))
        barraSalida = View(this)
        barraSalida.setBackgroundColor(BORDE)
        tarjetaNiveles.addView(barraSalida, barraMedidor())

        etiquetaEstado = TextView(this)
        etiquetaEstado.text = "Parado"
        etiquetaEstado.setTextColor(TEXTO_SUAVE)
        etiquetaEstado.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        etiquetaEstado.setPadding(0, dp(12), 0, 0)
        tarjetaNiveles.addView(etiquetaEstado)

        // --- Entrada y salida -----------------------------------------------
        val tarjetaRuta = tarjeta()
        col.addView(tarjetaRuta, anchoCompleto(arriba = 12))
        tarjetaRuta.addView(rotulo("ENTRADA Y SALIDA"))

        tarjetaRuta.addView(etiquetaPequena("Micrófono"))
        listaEntradas = Spinner(this)
        tarjetaRuta.addView(listaEntradas, anchoCompleto(arriba = 4))

        tarjetaRuta.addView(etiquetaPequena("Altavoz", arriba = 12))
        listaSalidas = Spinner(this)
        tarjetaRuta.addView(listaSalidas, anchoCompleto(arriba = 4))

        val botonRefrescar = Button(this)
        botonRefrescar.text = "Volver a buscar dispositivos"
        botonRefrescar.isAllCaps = false
        botonRefrescar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        botonRefrescar.setTextColor(LIMA)
        val fondoRefrescar = GradientDrawable()
        fondoRefrescar.cornerRadius = dp(10).toFloat()
        fondoRefrescar.setColor(Color.TRANSPARENT)
        fondoRefrescar.setStroke(dp(1), BORDE)
        botonRefrescar.background = fondoRefrescar
        botonRefrescar.setOnClickListener { cargarDispositivos() }
        tarjetaRuta.addView(botonRefrescar, anchoCompleto(arriba = 10))

        etiquetaRuta = TextView(this)
        etiquetaRuta.text = "Pulsa Empezar para ver por dónde va de verdad."
        etiquetaRuta.setTextColor(TEXTO_SUAVE)
        etiquetaRuta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        etiquetaRuta.setLineSpacing(dp(4).toFloat(), 1f)
        etiquetaRuta.setPadding(0, dp(12), 0, 0)
        tarjetaRuta.addView(etiquetaRuta)

        // --- Ajustes --------------------------------------------------------
        val tarjetaAjustes = tarjeta()
        col.addView(tarjetaAjustes, anchoCompleto(arriba = 12))
        tarjetaAjustes.addView(rotulo("AJUSTES"))

        // Volumen
        val filaGanancia = LinearLayout(this)
        filaGanancia.orientation = LinearLayout.HORIZONTAL
        filaGanancia.addView(etiquetaPequena("Volumen"), pesoUno())
        valorGanancia = TextView(this)
        valorGanancia.setTextColor(LIMA)
        valorGanancia.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        filaGanancia.addView(valorGanancia)
        tarjetaAjustes.addView(filaGanancia, anchoCompleto())

        mandoGanancia = SeekBar(this)
        mandoGanancia.max = 100
        // Empezamos en x1.0: con micro y altavoz cerca, subir de entrada
        // es pedir el pitido.
        mandoGanancia.progress = 25
        tenirMando(mandoGanancia)
        mandoGanancia.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                // 0..100 -> 0,2 .. 4,0
                val g = 0.2f + (p / 100f) * 3.8f
                motor.ganancia = g
                valorGanancia.text = String.format("x%.1f", g)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        tarjetaAjustes.addView(mandoGanancia, anchoCompleto())

        // Puerta de ruido
        val filaPuerta = LinearLayout(this)
        filaPuerta.orientation = LinearLayout.HORIZONTAL
        filaPuerta.addView(etiquetaPequena("Puerta de ruido", arriba = 10), pesoUno())
        valorPuerta = TextView(this)
        valorPuerta.setTextColor(LIMA)
        valorPuerta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        valorPuerta.setPadding(0, dp(10), 0, 0)
        filaPuerta.addView(valorPuerta)
        tarjetaAjustes.addView(filaPuerta, anchoCompleto())

        mandoPuerta = SeekBar(this)
        mandoPuerta.max = 100
        mandoPuerta.progress = 30
        tenirMando(mandoPuerta)
        mandoPuerta.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                // 0..100 -> 0,000 .. 0,050
                val u2 = (p / 100f) * 0.05f
                motor.umbralPuerta = u2
                valorPuerta.text = if (p == 0) "abierta" else String.format("%.0f%%", p.toFloat())
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        tarjetaAjustes.addView(mandoPuerta, anchoCompleto())

        val pistaPuerta = TextView(this)
        pistaPuerta.text = "Si sube el pitido en los silencios, sube la puerta."
        pistaPuerta.setTextColor(TEXTO_TENUE)
        pistaPuerta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tarjetaAjustes.addView(pistaPuerta)

        // Interruptores
        val filaAec = interruptor(
            "Cancelación de eco",
            "Solo con el micrófono del móvil. Con micro externo manda el micro.",
            true
        ) { activo ->
            motor.quiereAec = activo
            if (motor.estaCorriendo()) {
                // Cambiar la fuente exige reabrir el micro: no hay otra.
                reiniciarAudio()
            }
        }
        interruptorAec = filaAec.mando
        tarjetaAjustes.addView(filaAec.fila, anchoCompleto(arriba = 14))

        val filaAntiacople = interruptor(
            "Antiacople automático",
            "Baja el volumen solo si empieza a pitar.",
            true
        ) { activo -> motor.antiacople = activo }
        interruptorAntiacople = filaAntiacople.mando
        tarjetaAjustes.addView(filaAntiacople.fila, anchoCompleto(arriba = 8))

        val filaGraves = interruptor(
            "Filtro de graves",
            "Quita el retumbe. Ayuda mucho contra el acople.",
            true
        ) { activo -> motor.filtroGraves = activo }
        interruptorGraves = filaGraves.mando
        tarjetaAjustes.addView(filaGraves.fila, anchoCompleto(arriba = 8))

        val filaSilencio = interruptor(
            "Callar 1 s al acoplar",
            "Corta del todo para romper el pitido y limpiar la sala.",
            true
        ) { activo -> motor.silenciarAlAcoplar = activo }
        interruptorSilencio = filaSilencio.mando
        tarjetaAjustes.addView(filaSilencio.fila, anchoCompleto(arriba = 8))

        val filaPulsar = interruptor(
            "Modo pulsar para hablar",
            "Solo sale sonido con el botón pulsado. Lo más seguro contra el acople.",
            false
        ) { activo ->
            motor.modoPulsar = activo
            if (!activo) motor.hablando = false
            pintarBotonHablar(false)
        }
        interruptorPulsar = filaPulsar.mando
        tarjetaAjustes.addView(filaPulsar.fila, anchoCompleto(arriba = 8))

        // --- Diagnóstico ----------------------------------------------------
        val tarjetaDiag = tarjeta()
        col.addView(tarjetaDiag, anchoCompleto(arriba = 12))
        tarjetaDiag.addView(rotulo("QUÉ SOPORTA ESTE MÓVIL"))
        etiquetaDiagnostico = TextView(this)
        etiquetaDiagnostico.text = "Se comprueba al arrancar."
        etiquetaDiagnostico.setTextColor(TEXTO_SUAVE)
        etiquetaDiagnostico.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        etiquetaDiagnostico.setLineSpacing(dp(4).toFloat(), 1f)
        tarjetaDiag.addView(etiquetaDiagnostico)

        // --- Nota honesta sobre el Bluetooth --------------------------------
        val nota = TextView(this)
        nota.text = "El altavoz Bluetooth añade un retardo de 150–250 ms " +
            "que no se puede quitar: es del códec, no de la app. Para voz en " +
            "directo va mejor un altavoz por cable o USB-C.\n\n" +
            "Con el micrófono y el altavoz en la misma sala el acople es " +
            "física, no software: separa el altavoz del micrófono y apúntalo " +
            "en otra dirección."
        nota.setTextColor(TEXTO_TENUE)
        nota.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        nota.setLineSpacing(dp(3).toFloat(), 1f)
        nota.setPadding(dp(4), dp(18), dp(4), 0)
        col.addView(nota, anchoCompleto())

        // Valores iniciales de las etiquetas
        valorGanancia.text = "x1.0"
        valorPuerta.text = "30%"
        motor.ganancia = 1.0f
        motor.umbralPuerta = 0.015f

        return raiz
    }

    // ------------------------------------------------------------------------

    /**
     * Llena los dos desplegables con lo que hay enchufado ahora.
     *
     * Se vuelve a llamar al volver a la pantalla y con el boton de refrescar,
     * porque el lavalier se puede enchufar con la app ya abierta.
     */
    private fun cargarDispositivos() {
        entradas = motor.listarEntradas()
        salidas = motor.listarSalidas()

        montarLista(listaEntradas, entradas, motor.idEntradaElegida) { elegido ->
            motor.idEntradaElegida = elegido
            if (motor.estaCorriendo()) {
                // Cambiar de micro interno a externo (o al reves) cambia la
                // fuente de audio, y eso obliga a reabrir el microfono.
                if (motor.aplicarDispositivosElegidos()) {
                    reiniciarAudio()
                } else {
                    mostrarDiagnostico()
                }
            }
        }

        montarLista(listaSalidas, salidas, motor.idSalidaElegida) { elegido ->
            motor.idSalidaElegida = elegido
            if (motor.estaCorriendo()) {
                motor.aplicarDispositivosElegidos()
                mostrarDiagnostico()
            }
        }
    }

    private fun montarLista(
        lista: Spinner,
        opciones: List<MotorAudio.Dispositivo>,
        idActual: Int?,
        alElegir: (Int?) -> Unit
    ) {
        val nombres = opciones.map { it.nombre }
        val adaptador = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, nombres
        ) {
            override fun getView(pos: Int, convert: View?, padre: ViewGroup): View {
                val v = super.getView(pos, convert, padre)
                (v as? TextView)?.setTextColor(TEXTO)
                (v as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                return v
            }
        }
        adaptador.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        lista.onItemSelectedListener = null
        lista.adapter = adaptador

        val pos = opciones.indexOfFirst { d ->
            if (idActual == null) d.esAutomatico else d.id == idActual
        }
        lista.setSelection(if (pos >= 0) pos else 0)

        // Android APLAZA el aviso de setSelection() hasta el siguiente
        // pintado, asi que llega cuando el oyente ya esta puesto otra vez.
        // Quitar el oyente antes no basta: hay que ignorar ese primer aviso,
        // o al abrir la app se pisa lo que el usuario tenia elegido.
        var primerAviso = true
        lista.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                if (primerAviso) {
                    primerAviso = false
                    return
                }
                val d = opciones.getOrNull(i) ?: return
                alElegir(if (d.esAutomatico) null else d.id)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun alPulsarPrincipal() {
        if (motor.estaCorriendo()) {
            pararTodo()
        } else {
            if (!tengoPermisos()) {
                pedirPermisos()
                return
            }
            arrancarTodo()
        }
    }

    private fun arrancarTodo() {
        // El servicio primero: si el micro se abre sin el, Android puede
        // cortarlo en cuanto la pantalla se apague.
        MegafonoService.arrancar(this)
        if (motor.arrancar()) {
            pintarBoton(true)
            mostrarDiagnostico()
        } else {
            MegafonoService.detener(this)
            pintarBoton(false)
        }
    }

    private fun pararTodo() {
        motor.parar()
        MegafonoService.detener(this)
        pintarBoton(false)
        avisoAcople.visibility = View.GONE
        etiquetaEstado.text = "Parado"
        etiquetaEstado.setTextColor(TEXTO_SUAVE)
        anchoBarra(barraEntrada, 0f)
        anchoBarra(barraSalida, 0f)
    }

    private fun reiniciarAudio() {
        motor.parar()
        if (!motor.arrancar()) {
            MegafonoService.detener(this)
            pintarBoton(false)
            return
        }
        mostrarDiagnostico()
    }

    private fun mostrarDiagnostico() {
        val c = motor.capacidades

        etiquetaRuta.text = "Entra por:  " + c.nombreEntrada +
            "\nSale por:  " + c.nombreSalida

        val lineas = StringBuilder()
        lineas.append(marca(c.aecDisponible))
            .append("  Cancelador de eco: ")
            .append(
                when {
                    !c.aecDisponible -> "no lo tiene este móvil"
                    c.aecActivo -> "activo"
                    else -> "disponible, pero apagado"
                }
            )
        lineas.append("\n").append(marca(c.supresorDisponible))
            .append("  Supresor de ruido: ")
            .append(
                when {
                    !c.supresorDisponible -> "no disponible"
                    c.supresorActivo -> "activo"
                    else -> "disponible, sin activar"
                }
            )
        lineas.append("\n").append(marca(true))
            .append("  Frecuencia: ").append(c.frecuencia).append(" Hz")

        if (c.entradaNoRespetada) {
            lineas.append("\n\n⚠  Has elegido un micrófono, pero el móvil está ")
            lineas.append("cogiendo el sonido de otro. Es cosa del fabricante: ")
            lineas.append("prueba a desenchufar y volver a enchufar el micro, ")
            lineas.append("o a parar y arrancar otra vez.")
        }

        if (c.aecCedidoPorMicro) {
            lineas.append("\n\nCon micrófono externo se apaga el cancelador de ")
            lineas.append("eco del sistema: es la única forma de que entre el ")
            lineas.append("sonido por ese micro. Del acople se encargan la ")
            lineas.append("puerta de ruido y el antiacople.")
        } else if (!c.aecDisponible) {
            lineas.append("\n\nSin cancelador de eco del sistema, quien hace el ")
            lineas.append("trabajo es la puerta de ruido y el antiacople.")
        }

        etiquetaDiagnostico.text = lineas.toString()
    }

    private fun marca(bien: Boolean) = if (bien) "✓" else "✕"

    private fun pintarEstado(e: MotorAudio.Estado) {
        anchoBarra(barraEntrada, e.nivelEntrada)
        anchoBarra(barraSalida, e.nivelSalida)

        if (e.enSilencioPorAcople) {
            avisoAcople.text = "🔇  Callado " + e.msSilencioRestante + " ms para cortar el acople"
            avisoAcople.visibility = View.VISIBLE
        } else if (e.acoplando) {
            avisoAcople.text = "⚠  Acople detectado — bajando volumen"
            avisoAcople.visibility = View.VISIBLE
        } else {
            avisoAcople.visibility = View.GONE
        }

        if (!motor.estaCorriendo()) return

        etiquetaEstado.text = when {
            e.enSilencioPorAcople -> "Callado un momento para romper el acople"
            motor.modoPulsar && !motor.hablando -> "Listo — mantén pulsado para hablar"
            e.acoplando -> String.format(
                "Conteniendo acople — volumen al %.0f%%", e.reduccionAcople * 100f
            )
            e.puertaAbierta -> "Sonando"
            else -> "En silencio (puerta cerrada)"
        }
        etiquetaEstado.setTextColor(
            when {
                e.enSilencioPorAcople -> AVISO
                e.acoplando -> AVISO
                e.puertaAbierta -> LIMA
                else -> TEXTO_TENUE
            }
        )
    }

    private fun pintarBoton(enMarcha: Boolean) {
        botonPrincipal.text = if (enMarcha) "Parar" else "Empezar"
        val fondo = GradientDrawable()
        fondo.cornerRadius = dp(14).toFloat()
        if (enMarcha) {
            fondo.setColor(Color.parseColor("#2A1212"))
            fondo.setStroke(dp(2), PELIGRO)
            botonPrincipal.setTextColor(PELIGRO)
        } else {
            fondo.setColor(LIMA)
            botonPrincipal.setTextColor(FONDO)
        }
        botonPrincipal.background = fondo
    }

    /**
     * El boton de hablar se enciende mientras se tiene el dedo encima.
     * Apagado cuando el modo no esta activo, para que se vea que no hace nada.
     */
    private fun pintarBotonHablar(pulsado: Boolean) {
        val activo = motor.modoPulsar
        val fondo = GradientDrawable()
        fondo.cornerRadius = dp(14).toFloat()

        when {
            !activo -> {
                fondo.setColor(Color.TRANSPARENT)
                fondo.setStroke(dp(1), BORDE)
                botonHablar.setTextColor(TEXTO_TENUE)
            }
            pulsado -> {
                fondo.setColor(LIMA)
                botonHablar.setTextColor(FONDO)
            }
            else -> {
                fondo.setColor(Color.TRANSPARENT)
                fondo.setStroke(dp(2), LIMA)
                botonHablar.setTextColor(LIMA)
            }
        }
        botonHablar.background = fondo
        botonHablar.isEnabled = activo
    }

    private fun anchoBarra(barra: View, nivel: Float) {
        val padre = barra.parent as? ViewGroup ?: return
        // Antes del primer pintado el padre mide 0: sin esto las barras se
        // quedarian invisibles hasta que el usuario tocara algo.
        val disponible = padre.width - padre.paddingLeft - padre.paddingRight
        if (disponible <= 0) return

        val n = nivel.coerceIn(0f, 1f)
        val params = barra.layoutParams
        if (n <= 0f) {
            // Sin senal dejamos la pista entera apagada, para que se vea
            // donde esta el medidor aunque no suene nada.
            params.width = disponible
            barra.layoutParams = params
            barra.setBackgroundColor(BORDE)
            return
        }
        params.width = (disponible * n).toInt().coerceAtLeast(dp(3))
        barra.layoutParams = params
        // Rojo cuando roza el techo: avisa de que hay que bajar el volumen.
        barra.setBackgroundColor(if (n > 0.93f) PELIGRO else LIMA)
    }

    // --- Permisos -----------------------------------------------------------

    private fun tengoPermisos(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pedirPermisos() {
        val lista = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lista.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lista.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, lista.toTypedArray(), COD_PERMISOS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != COD_PERMISOS) return

        if (tengoPermisos()) {
            arrancarTodo()
        } else {
            Toast.makeText(
                this,
                "Sin permiso de micrófono la app no puede hacer nada.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // --- Ayudas de maquetación ---------------------------------------------

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun anchoCompleto(arriba: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(arriba)
        return p
    }

    private fun pesoUno(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun barraMedidor(): LinearLayout.LayoutParams {
        // Empieza a ancho completo, no a cero: con cero las barras no se ven
        // hasta que llega el primer nivel, y parece que la app esta rota.
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        p.topMargin = dp(6)
        return p
    }

    private fun tarjeta(): LinearLayout {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.setPadding(dp(16), dp(16), dp(16), dp(16))
        val fondo = GradientDrawable()
        fondo.cornerRadius = dp(14).toFloat()
        fondo.setColor(TARJETA)
        fondo.setStroke(dp(1), BORDE)
        t.background = fondo
        return t
    }

    private fun rotulo(texto: String): TextView {
        val t = TextView(this)
        t.text = texto
        t.setTextColor(LIMA_OSCURO)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        t.letterSpacing = 0.14f
        t.setPadding(0, 0, 0, dp(10))
        return t
    }

    private fun etiquetaPequena(texto: String, arriba: Int = 0): TextView {
        val t = TextView(this)
        t.text = texto
        t.setTextColor(TEXTO_SUAVE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        t.setPadding(0, dp(arriba), 0, 0)
        return t
    }

    private fun tenirMando(barra: SeekBar) {
        // mutate() para no tenir el drawable que comparten todos los SeekBar
        // del sistema.
        barra.progressDrawable?.mutate()?.setTint(LIMA)
        barra.thumb?.mutate()?.setTint(LIMA)
    }

    /** Una fila de ajuste: el interruptor y la fila entera que lo contiene. */
    private class FilaInterruptor(val fila: View, val mando: Switch)

    /** Fila con titulo, explicacion y el interruptor a la derecha. */
    private fun interruptor(
        titulo: String,
        explicacion: String,
        inicial: Boolean,
        alCambiar: (Boolean) -> Unit
    ): FilaInterruptor {
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL

        val textos = LinearLayout(this)
        textos.orientation = LinearLayout.VERTICAL

        val t1 = TextView(this)
        t1.text = titulo
        t1.setTextColor(TEXTO)
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        textos.addView(t1)

        val t2 = TextView(this)
        t2.text = explicacion
        t2.setTextColor(TEXTO_TENUE)
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        textos.addView(t2)

        fila.addView(textos, pesoUno())

        val sw = Switch(this)
        sw.isChecked = inicial
        sw.setOnCheckedChangeListener { _, activo -> alCambiar(activo) }
        fila.addView(sw)

        return FilaInterruptor(fila, sw)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ojo: NO paramos el motor aqui. Si el usuario sale de la pantalla
        // con el megafono en marcha, tiene que seguir sonando; para eso esta
        // el servicio en primer plano y su boton "Parar".
        motor.alCambiarEstado = null
        motor.alFallar = null
    }
}
