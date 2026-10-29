package app.thirdear.whitenoise

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer

/**
 * Follows Android Auto's connection (CarConnection) so incoming messages switch from "automatic"
 * to "ask" in the car: Android Auto announces them and offers to read them.
 */
object CarWatch {
    private var connection: CarConnection? = null
    private val observer = Observer<Int> { type ->
        CarState.connected = type != null && type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        Log.i("ThirdEarCar", "car connection type $type")
    }

    fun start(context: Context) {
        Handler(Looper.getMainLooper()).post {
            if (connection != null) return@post
            runCatching {
                connection = CarConnection(context.applicationContext).also { it.type.observeForever(observer) }
            }.onFailure { Log.w("ThirdEarCar", "CarConnection unavailable: ${it.message}") }
        }
    }

    fun stop() {
        Handler(Looper.getMainLooper()).post {
            connection?.type?.removeObserver(observer)
            connection = null
        }
    }
}
