package com.amantis.megafono

/**
 * El motor de audio es uno solo y lo comparten la pantalla y el servicio.
 *
 * Se guarda aqui a proposito: si cada uno creara el suyo, se pelearian por
 * el microfono y la app se quedaria muda sin decir por que.
 */
object Megafono {
    @Volatile var motor: MotorAudio? = null
}
