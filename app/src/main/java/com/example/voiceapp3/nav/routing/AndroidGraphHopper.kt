package com.example.voiceapp3.nav.routing

import android.util.Log
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.ImportUnit
import com.graphhopper.routing.ev.MaxSpeed
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadClassLink
import com.graphhopper.routing.ev.RoadEnvironment
import com.graphhopper.routing.ev.Roundabout
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.routing.ev.VehicleSpeed
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.storage.DAType
import com.graphhopper.util.PMap

/**
 * Этап 7 (fix8d/fix8e): GraphHopper для Android, БЕЗ Janino.
 *
 * Диагноз (подтверждён по исходникам тегов 8.0/9.0/11.1 — мина во всех трёх,
 * поэтому даунгрейд бесполезен): класс CustomModelParser загружается ЛЮБЫМ
 * statiс-вызовом к нему, а он тянет org.codehaus.janino.* → javax.lang.model.*
 * → на ART «Failed resolution of: Ljavax/lang/model/SourceVersion;».
 * Вызовов к нему в нашем потоке ровно ДВА:
 *
 * 1) DefaultWeightingFactory.createWeighting — обходится фабрикой
 *    (override createWeightingFactory, см. ниже; fix8d).
 * 2) GraphHopper.prepareImport() L865 (11.1) — БЕЗУСЛОВНЫЙ вызов
 *    CustomModelParser.findVariablesForEncodedValuesString для каждого
 *    профиля при импорте графа — исправляется override'ом ниже (fix8e).
 *
 * Загрузка графа/роутинг/индексы больше к CustomModelParser не обращаются
 * (прогрепано по всем core-sources 11.1 — ни одной другой точки).
 *
 * prepareImport() переопределён СЛОВО-В-СЛОВО с GH 11.1 L860–909, с двумя
 * точечными изменениями:
 *  · выброшен блок CustomModelParser (модель нашего профиля пустая — блок был
 *    бы no-op; НО EV car_average_speed в GH 10+ приходит в EncodingManager
 *    ИМЕННО через переменные модели → добавляем car_access/car_average_speed
 *    ЯВНО, иначе их просто не было бы в графе);
 *  · поле osmParsers у GraphHopper private без сеттера, а присвоить его
 *    обязан prepareImport → единственное reflection-присвоение, с громким
 *    диагностическим падением при несовпадении с тегом 11.1 (fail-fast на
 *    первом импорте, а не молча позже). Всё остальное — только
 *    protected/public API: parseEncodedValueString (public static),
 *    buildEncodingManager/buildOSMParsers (protected), getImportRegistry/
 *    getReaderConfig (public), encodingManager (protected-поле).
 *
 * Константы конфига, влияющие на приватные ветки оригинального метода, в
 * нашей сборке держатся дефолтными (гарантия — как требует основной код):
 * init(ghConfig) не вызывается ⇒ urbanDensityThreads=0, maxSpeedCalculator=null
 * ⇒ обе private-ветки оригинала — вхолостую; turn costs выключены ⇒
 * restrictionVehicleTypesByProfile ≡ пустая карта.
 */
class AndroidGraphHopper : GraphHopper() {

    init {
        // fix9 (полный аудит цикла): RAMDataAccess (дефолт RAM_STORE) на ART
        // мёртв — статические VarHandle(dword/word).withInvokeExactBehavior
        // (Java 12+, нет в ART: fix8i). RAM_INT_STORE (int-heap) падал глубже:
        // KVStorage (nodekv_vals/edgekv_vals — атрибуты узлов/рёбер) нуждается
        // в БАЙТОВОМ доступе — «does not support byte based access», плюс все
        // прочесанные индексы (LocationIndexTree, stringIndex, turnCostStorage)
        // тоже байтовые. ЕДИНСТВЕННЫЙ чистый бэкенд 11.1 на Android — MMAP:
        //  · без VarHandle; sun.misc.Unsafe.invokeCleaner есть в libcore с
        //    API 26 (unmapped-off при ошибке — только лог, не крах логики);
        //  · байтовый доступ ✓; RAM на импорте резко ниже (BY ~350 МБ);
        //  · при load() автоматически MMAP_RO — read-only роутинг из page cache.
        // Поле private без сеттера (GraphHopper.java 11.1 L105) → тот же
        // fail-fast reflection-контракт: несовпадение с 11.1 — громко и сразу.
        setPrivateField("dataAccessDefaultType", DAType.MMAP)
    }

    // ───────────────────────── фабрика весов (fix8d) ─────────────────────────

    override fun createWeightingFactory(): WeightingFactory {
        // super-фабрика = DefaultWeightingFactory — её createWeighting при любом
        // custom-весе уходит в CustomModelParser → janino. НЕ зовём её вообще:
        // все веса в приложении создаются здесь нативно.
        return WeightingFactory { profile, _, _ ->
            if (profile.name == GraphManager.PROFILE_CAR) carFastestWeighting()
            else throw IllegalArgumentException(
                "неизвестный профиль «${profile.name}»: в Android-сборке "
                    + "поддерживается только «${GraphManager.PROFILE_CAR}»"
            )
        }
    }

    private fun carFastestWeighting(): Weighting {
        val avgSpeed: DecimalEncodedValue = encodingManager
            .getDecimalEncodedValue(VehicleSpeed.key(GraphManager.PROFILE_CAR))
            ?: throw IllegalStateException(
                "EV ${VehicleSpeed.key(GraphManager.PROFILE_CAR)} отсутствует — "
                    + "граф собран без профиля car; удалите граф и пересоберите"
            )
        // Сигнатуры — по CustomWeighting.java тега 11.1 (L90/L187/L164–174):
        // Parameters(speed, maxSpeed, priority, maxPriority, turnPenalty,
        //            distanceInfluence, headingPenaltySeconds)
        val params = CustomWeighting.Parameters(
            CustomWeighting.EdgeToDoubleMapping { edge, reverse ->
                if (reverse) edge.getReverse(avgSpeed) else edge.get(avgSpeed)
            },
            CustomWeighting.MaxCalc { MAX_CAR_SPEED_KMH },
            CustomWeighting.EdgeToDoubleMapping { _, _ -> 1.0 },
            CustomWeighting.MaxCalc { 1.0 },
            CustomWeighting.TurnPenaltyMapping { _, _, _, _, _ -> 0.0 },
            0.0, // distance influence — чистый «самый быстрый»
            0.0  // heading penalty
        )
        return CustomWeighting(TurnCostProvider.NO_TURN_COST_PROVIDER, params)
    }

    // ───────────────────────── импорт (fix8e) ─────────────────────────

    override fun prepareImport() {
        // Тело — реплика GH 11.1 prepareImport() (GraphHopper.java L860–909),
        // минус CustomModelParser (см. описание класса).
        val encodedValuesWithProps: MutableMap<String, PMap> =
            GraphHopper.parseEncodedValueString(encodedValuesString)

        // Стоковые дефолты 11.1:
        encodedValuesWithProps.putIfAbsent(RoadClass.KEY, PMap())
        encodedValuesWithProps.putIfAbsent(RoadEnvironment.KEY, PMap())
        encodedValuesWithProps.putIfAbsent(Roundabout.KEY, PMap())
        encodedValuesWithProps.putIfAbsent(RoadClassLink.KEY, PMap())
        encodedValuesWithProps.putIfAbsent(MaxSpeed.KEY, PMap())
        // Для авто-профиля: access сток добавляет сам (L881), speed — только из
        // переменных custom-модели (у нас её нет) → оба явно:
        encodedValuesWithProps.putIfAbsent(
            VehicleAccess.key(GraphManager.PROFILE_CAR), PMap()
        )
        encodedValuesWithProps.putIfAbsent(
            VehicleSpeed.key(GraphManager.PROFILE_CAR), PMap()
        )

        // restrictionVehicleTypesByProfile ≡ ∅: turn costs в наших профилях
        // выключены — это в точности результат private getRestrictionVehicleTypesByProfile
        val restrictionVehicleTypesByProfile: Map<String, List<String>> = emptyMap()

        // Резолв ImportUnit'ов — как L898–907
        val registry = importRegistry // public getImportRegistry()
        val activeImportUnits = LinkedHashMap<String, ImportUnit>()
        val deque = ArrayDeque(encodedValuesWithProps.keys)
        while (deque.isNotEmpty()) {
            val ev = deque.removeFirst()
            val importUnit = registry.createImportUnit(ev)
                ?: throw IllegalArgumentException("Unknown encoded value: $ev")
            if (activeImportUnits.put(ev, importUnit) == null)
                deque.addAll(importUnit.requiredImportUnits)
        }

        // protected-поля/методы суперкласса — штатный контракт расширения GH
        encodingManager = buildEncodingManager(
            encodedValuesWithProps, activeImportUnits, restrictionVehicleTypesByProfile
        )
        val parsers = buildOSMParsers(
            encodedValuesWithProps, activeImportUnits,
            restrictionVehicleTypesByProfile, readerConfig.ignoredHighways
        )
        setPrivateField("osmParsers", parsers)
        Log.i(TAG, "prepareImport: EV=${encodedValuesWithProps.keys}, " +
            "units=${activeImportUnits.keys} — janino не задействован")
    }

    /**
     * Единственные два шва с private-реализацией GH: osmParsers (L102) и
     * dataAccessDefaultType (L105) — без сеттеров, а обязан присвоить именно
     * наследник. Fail-fast: любое расхождение с 11.1 — громкая ошибка сразу,
     * а не тихий битый граф позже.
     */
    private fun setPrivateField(name: String, value: Any?) {
        try {
            val field = GraphHopper::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(this, value)
        } catch (t: Throwable) {
            Log.e(TAG, "не удалось присвоить GraphHopper.$name (сверено с 11.1)", t)
            throw IllegalStateException(
                "AndroidGraphHopper: private-поле «$name» не найдено/не записано — " +
                    "библиотека GraphHopper не той версии, что сверялась (нужен тег 11.1)", t
            )
        }
    }

    companion object {
        private const val TAG = "AndroidGraphHopper"

        /** Верхняя граница для эвристики A* (км/ч); выше любой speed-разметки BY. */
        private const val MAX_CAR_SPEED_KMH = 150.0
    }
}
