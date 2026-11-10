package android.car
import android.content.Context
class Car { fun getCarManager(name: String): Any? = null; fun isConnected(): Boolean = true; fun disconnect() {}
    companion object { fun createCar(c: Context): Car? = null } }
