package com.google.gson
class JsonArray { fun add(v: Any?) {} fun size(): Int = 0 }
class JsonObject { fun add(k: String, v: Any?) {} fun addProperty(k: String, v: Any?) {} fun get(k: String): JsonObject? = null
    val asString: String get() = ""; val asJsonObject: JsonObject get() = this; val asJsonArray: JsonArray get() = JsonArray() }
class Gson { fun toJson(o: Any?): String = ""; fun fromJson(s: String, c: Class<*>): Any? = null }
