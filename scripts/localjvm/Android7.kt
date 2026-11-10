package android.car.hardware.property
import android.car.hardware.CarPropertyValue
class CarPropertyManager { fun getProperty(cl: Class<*>, id: Int, area: Int): Any? = null
    fun setProperty(cl: Class<*>, id: Int, area: Int, v: Any?): Unit {}
    fun getIntProperty(id: Int, area: Int): Int = 0
    fun setIntProperty(id: Int, area: Int, v: Int): Unit {}
    fun getFloatProperty(id: Int, area: Int): Float = 0f
    fun setFloatProperty(id: Int, area: Int, v: Float): Unit {}
    fun registerCallback(cb: Any?, id: Int, rate: Float) {}
    fun unregisterCallback(cb: Any?) {}
    interface CarPropertyEventCallback { fun onChangeEvent(v: CarPropertyValue<*>) ; fun onErrorEvent(id: Int, code: Int) } }
