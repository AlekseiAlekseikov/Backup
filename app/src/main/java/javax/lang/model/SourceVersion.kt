package javax.lang.model

/**
 * fix8g: ART не содержит javax.lang.model.* (это API javac/JPMS, в Android его
 * никогда не поставляли), а GraphHopper 11 валидирует имена encoded-values
 * через SourceVersion.isKeyword(name) — два вызововых места в стоке 11.1:
 *  · core/routing/ev/IntEncodedValueImpl.java:240  (EncodingManager build!)
 *  · web-api/com/graphhopper/util/JsonFeature.java:98
 * Отсюда «Failed resolution of: Ljavax/lang/model/SourceVersion;» на сборке
 * EncodingManager. (Других обращений к javax.lang.model в core+web-api нет —
 * прогрепано по всем 513 исходникам тега 11.1.)
 *
 * Почему Kotlin: javac не скомпилирует класс в пакете javax.lang.model —
 * «package exists in another module: java.compiler»; kotlinc такой JPMS-
 * проверки не делает. Семантика воспроизведена по OpenJDK SourceVersion:
 * isKeyword(s) ≡ isKeyword(s, latest()), latest = RELEASE_21; isKeyword
 * включает литералы true/false/null; "_" — ключевое слово с Java 9 (JEP 302).
 * Вызовы isName/isKeyword(name, version) реализованы для полноты контракта.
 *
 * Замечу: sealed/record/var/yield — НЕ ключевые слова (restricted identifiers),
 * в KEYWORDS их нет — как в JDK.
 */
enum class SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5,
    RELEASE_6, RELEASE_7, RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11,
    RELEASE_12, RELEASE_13, RELEASE_14, RELEASE_15, RELEASE_16, RELEASE_17,
    RELEASE_18, RELEASE_19, RELEASE_20, RELEASE_21;

    companion object {
        /** Ключевые слова + литералы Java 21 (как у JDK SourceVersion для latest). */
        private val KEYWORDS: Set<String> = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while",
            // boolean/null-литералы — isKeyword возвращает true и для них
            "true", "false", "null"
        )

        @JvmStatic
        fun latest(): SourceVersion = RELEASE_21

        @JvmStatic
        fun latestSupported(): SourceVersion = latest()

        @JvmStatic
        fun isKeyword(name: CharSequence): Boolean = isKeyword(name, latest())

        @JvmStatic
        fun isKeyword(name: CharSequence, version: SourceVersion): Boolean {
            val s = name.toString()
            // "_" запрещён как имя только с Java 9
            if (s == "_") return version.ordinal >= RELEASE_9.ordinal
            return KEYWORDS.contains(s)
        }

        @JvmStatic
        fun isName(name: CharSequence): Boolean = isName(name, latest())

        @JvmStatic
        fun isName(name: CharSequence, version: SourceVersion): Boolean {
            val s = name.toString()
            if (s.isEmpty() || !Character.isJavaIdentifierStart(s[0])) return false
            for (i in 1 until s.length) {
                if (!Character.isJavaIdentifierPart(s[i])) return false
            }
            return !isKeyword(s, version)
        }
    }
}
