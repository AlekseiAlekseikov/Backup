/* Stage T1: дословный порт ядра телеметрии Capy (tel-core.js).
   Файловые проверки + инварианты формул, вычисленные по семантике Dart. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
let pass = 0, fail = 0;
const chk = (n, c, e) => { if (c) { pass++; console.log('чек', n + ':', 'OK'); } else { fail++; console.log('чек', n + ':', 'FAIL', e !== undefined ? JSON.stringify(e) : ''); } };
const ROOT = path.resolve(__dirname, '../..');
const close = (a, b, eps) => Math.abs(a - b) <= (eps || 1e-9);

(async () => {
  /* ══ Файловые ══ */
  const core = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/ui/tel-core.js'), 'utf8');
  const html = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/ui/index.html'), 'utf8');
  chk('Ф1. tel-core.js существует и подключён в index.html до основного скрипта',
    html.indexOf('tel-core.js') >= 0 && html.indexOf('tel-core.js') < html.indexOf('window.__nativeEvent'));
  chk('Ф2. license-происхождение указано (Apache 2.0, capy)', core.includes('Apache License 2.0') && core.includes('Timoteohss'));

  /* ══ Инварианты формул (браузер) ══ */
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await new Promise(r => setTimeout(r, 1200));

  // Design tokens — точные литералы automotive_colors.dart
  let r = await p.evaluate(() => ({
    d: window.CapyDS.dark, l: window.CapyDS.light,
    sp: window.CapyDS.spacing, ty: window.CapyDS.typography
  }));
  chk('1. тёмная палитра = _AutomotiveDarkColors (выборочно)', r.d.surface === '#131313' && r.d.secondary === '#4AE183' && r.d.tertiary === '#92CCFF' && r.d.kinetic === '#3498DB' && r.d.technicalPanel === '#1A1A1A', r.d);
  chk('2. светлая палитра = AutomotiveLightColors (выборочно)', r.l.surface === '#FCF9F8' && r.l.secondary === '#006D37' && r.l.tertiary === '#006493' && r.l.technicalBorder === '#C4C7C7', r.l);
  chk('3. spacing/radii = automotive_spacing.dart', r.sp.unit === 8 && r.sp.railWidth === 96 && r.sp.marginScreen === 32 && r.sp.x1_5 === 12 && r.sp.x3 === 24);
  chk('4. типографика: mono 64/700 metricDisplay, labelCaps 12/700 ls .6', r.ty.metricDisplay.size === 64 && r.ty.metricDisplay.font === 'JetBrains Mono' && r.ty.labelCaps.letterSpacing === 0.6 && r.ty.unitLabel.size === 14);

  // telemetry_format: formatDuration — часы/минуты/секунды как в Dart
  r = await p.evaluate(() => ({
    a: telFmt.formatDuration(59 * 1000),
    b: telFmt.formatDuration(60 * 1000),
    c: telFmt.formatDuration(3661 * 1000),
    d: telFmt.formatDuration(null),
    e: telFmt.formatDuration(9 * 3600 * 1000 + 5 * 60 * 1000)
  }));
  chk('5. formatDuration: 00:59 / 01:00 / 01:01:01 / -- / 09:05:00', r.a === '00:59' && r.b === '01:00' && r.c === '01:01:01' && r.d === '--' && r.e === '09:05:00', r);

  r = await p.evaluate(() => ({
    dist: telFmt.distanceLabelFor(12.34),
    eff: telFmt.efficiencyLabel(152.6),
    rpe: telFmt.rangePerEnergyLabel(6.789),
    en: telFmt.energyLabel(1.2345),
    soc: telFmt.socRangeLabel(46.25, 80),
    socD: telFmt.socDeltaLabel(46.25, 80.0),
    socDn: telFmt.socDeltaLabel(80, 79.5),
    amb: telFmt.ambientTempRangeLabel(-3.5, 12.25),
    alt: telFmt.altitudeGainLabel(123.6),
    tilt: telFmt.tiltAngleLabel(-0.2),
    gear: [telFmt.gearLabelFor(0x8), telFmt.gearLabelFor(0x4), telFmt.gearLabelFor(0x2), telFmt.gearLabelFor(0x1), telFmt.gearLabelFor(0)],
    st: [telFmt.statusLabel('active'), telFmt.statusLabel('ENDED'), telFmt.statusLabel('PENDING_END'), telFmt.statusLabel('weird')],
    fallback: [telFmt.fallbackEndReason('ACTIVE'), telFmt.fallbackEndReason('PENDING_END'), telFmt.fallbackEndReason('x')],
    objD: telFmt.objectAsDouble('1,5'),
    objDn: telFmt.objectAsDouble('  '),
    leff: telFmt.liveEfficiencyWhPerKm(15.0, 60),
    lkm: telFmt.liveKmPerKwh(15.0, 60),
    leff0: telFmt.liveEfficiencyWhPerKm(15.0, 0.005)
  }));
  chk('6. метки: 12.3 км / 153 Wh/km / 6.8 km/kWh / 1.23 kWh', r.dist === '12.3 км' && r.eff === '153 Wh/km' && r.rpe === '6.8 km/kWh' && r.en === '1.23 kWh', r);
  chk('7. SoC: 46.3% -> 80.0%, дельта +33.8%/-0.5%', r.soc === '46.3% -> 80.0%' && r.socD === '+33.8%' && r.socDn === '-0.5%', r);
  chk('8. температура/высота/наклон: -3.5°C -> 12.3°C / +124 m / 0°', r.amb === '-3.5°C -> 12.3°C' && r.alt === '+124 m' && r.tilt === '0°', r);
  chk('9. передачи D/P/R/N/RAW (битовая маска)', r.gear[0] === 'D' && r.gear[1] === 'P' && r.gear[2] === 'R' && r.gear[3] === 'N' && r.gear[4] === 'RAW 0', r);
  chk('10. статусы из app_ru.arb', r.st[0] === 'АКТИВНА' && r.st[1] === 'ЗАВЕРШЕНО' && r.st[2] === 'ОЖИДАНИЕ ОКОНЧАНИЯ' && r.st[3] === 'WEIRD', r);
  chk('11. fallbackEndReason: В_ПРОЦЕССЕ / ОЖИДАНИЕ_ПРОСТОЯ / --', r.fallback[0] === 'В_ПРОЦЕССЕ' && r.fallback[1] === 'ОЖИДАНИЕ_ПРОСТОЯ' && r.fallback[2] === '--', r);
  chk('12. objectAsDouble: 1,5 → 1.5, пустая строка → null', r.objD === 1.5 && r.objDn === null, r);
  chk('13. live-эффективность: 250 Wh/km, 4 km/kWh, делитель <0.01 → null', r.leff === 250 && r.lkm === 4 && r.leff0 === null, r);

  r = await p.evaluate(() => ({
    active: telFmt.statusColorFor('ACTIVE', null, false),
    cancel: telFmt.statusColorFor('ENDED', 'USER_CANCEL', false),
    ended: telFmt.statusColorFor('ENDED', null, true),
    other: telFmt.statusColorFor('XYZ', null, false),
    pmin: telFmt.paddedMinY([{ x: 0, y: 10 }, { x: 1, y: 20 }], 0, 100),
    pmax: telFmt.paddedMaxY([{ x: 0, y: 10 }, { x: 1, y: 20 }], 0, 100),
    pminFlat: telFmt.paddedMinY([{ x: 0, y: 5 }], 0, 100),
    pminEmpty: telFmt.paddedMinY([], 3, 9),
    bear: [compassBearingLabel(214.6), compassBearingLabel(null), compassBearingLabel(359.6)]
  }));
  chk('14. statusColorFor: ACTIVE→secondary, CANCEL→warning, ENDED→tertiary, прочее→onSurfaceVariant', r.active === '#4AE183' && r.cancel === '#F39C12' && r.ended === '#006493' && r.other === '#C4C7C7', r);
  chk('15. paddedMinY/MaxY: диапазон 10..20 → 8.8 / 21.2 (паддинг 1.2)', close(r.pmin, 8.8) && close(r.pmax, 21.2), r);
  chk('16. paddedMinY на плоской серии → 5−1; пустая → floor', r.pminFlat === 4 && r.pminEmpty === 3, r);
  chk('17. compassBearingLabel: 215° / -- / 0°', r.bear[0] === '215°' && r.bear[1] === '--' && r.bear[2] === '0°', r);

  // energy_buckets: прогрессивные ширины как в Dart
  r = await p.evaluate(() => ({
    a: telBuckets.progressiveEnergyBucketMinutes(1), b: telBuckets.progressiveEnergyBucketMinutes(60),
    c: telBuckets.progressiveEnergyBucketMinutes(61), d: telBuckets.progressiveEnergyBucketMinutes(125),
    e: telBuckets.progressiveEnergyBucketMinutes(180), f: telBuckets.progressiveEnergyBucketMinutes(181),
    w1: telBuckets.chooseEnergyBucketWidth(30 * 60000, 24),
    w2: telBuckets.chooseEnergyBucketWidth(600 * 60000, 24),
    w3: telBuckets.chooseEnergyBucketWidth(3000 * 60000, 24),
    cap: telBuckets.energyChartBarCapacity(1000, 40, 8),
    next: telBuckets.nextEnergyBucketWidth(60000)
  }));
  chk('18. progressive: 1/60 без округления, 61→65, 125→125 (уже кратно 5), 180→180, 181→195', r.a === 1 && r.b === 60 && r.c === 65 && r.d === 125 && r.e === 180 && r.f === 195, r);
  chk('19. chooseEnergyBucketWidth: 30мин/24 → 2мин, 600мин/24 → 25мин, 3000мин/24 → 125мин', r.w1 === 2 * 60000 && r.w2 === 25 * 60000 && r.w3 === 125 * 60000, r);
  chk('20. energyChartBarCapacity(1000,40,8) = 25; next(1мин) = 2мин', r.cap === 25 && r.next === 2 * 60000, r);

  // reduceEnergyBuckets: суммирование Wh и align по origin
  r = await p.evaluate(() => {
    const mk = t => new telBuckets.EnergyBucket({ start: t, width: 60000, tractionWh: 100, regeneratedWh: 20, auxiliaryWh: 30, integratedSeconds: 60, deliveredWh: 0, speedDistanceKm: 1, climateWh: 5, climateIntegratedSeconds: 60, startSoc: 10, endSoc: 12 });
    const minutes = [mk(0), mk(60000), mk(120000)];
    const reduced = telBuckets.reduceEnergyBuckets(minutes, 180000, 0);
    const reducedClock = telBuckets.reduceEnergyBuckets(minutes, 120000, null);
    return {
      n: reduced.length, w: reduced[0] && reduced[0].width,
      tr: reduced[0] && reduced[0].tractionWh, rg: reduced[0] && reduced[0].regeneratedWh,
      soc: reduced[0] && [reduced[0].startSoc, reduced[0].endSoc],
      clockN: reducedClock.length, legacy: telBuckets.EnergyBucket.fromMap({ t: 1000, traction: 7, regen: 2, aux: 1, sec: 60, km: 0.5, climate: 4, climateSec: 60, soc0: 50, soc1: 51 })
    };
  });
  chk('21. reduceEnergyBuckets: 3 минуты → 1 бакет 3 мин, Wh суммируются (300/60), SoC первый→последний',
    r.n === 1 && r.w === 180000 && r.tr === 300 && r.rg === 60 && r.soc[0] === 10 && r.soc[1] === 12, r);
  chk('22. align по часам (origin=null) раскладывает 3 минуты в 2-минутные бакеты', r.clockN === 2, r);
  chk('23. EnergyBucket.fromMap понимает legacy-имена TEL (t/traction/regen/soc0)', r.legacy.tractionWh === 7 && r.legacy.regeneratedWh === 2 && r.legacy.startSoc === 50 && r.legacy.endSoc === 51 && r.legacy.start === 1000, r);

  // charge_graph_data: лог-кривая и сборка
  r = await p.evaluate(() => ({
    z: telChargeGraph.chargePowerPlotValue(0),
    top: telChargeGraph.chargePowerPlotValue(80),
    one: telChargeGraph.chargePowerPlotValue(1),
    half: telChargeGraph.chargePowerPlotValue(40),
    ceil80: telChargeGraph.chargePowerPlotValue(120, 80),
    idleConst: telChargeGraph.CHARGE_IDLE_POWER_KW, maxKw: telChargeGraph.CHARGE_GRAPH_MAXIMUM_POWER_KW
  }));
  chk('24. chargePowerPlotValue: 0→0, 80→100, монотонность, кламп в потолок', r.z === 0 && r.top === 100 && r.one > 5 && r.half > r.one && r.half < 100 && r.ceil80 === 100, r);
  chk('25. константы: idle 0.05 кВт, общий потолок 80 кВт', r.idleConst === 0.05 && r.maxKw === 80, r);

  r = await p.evaluate(() => {
    // 30 минут: заряд 0..600с по 60 кВт, SOC 20→26; затем idle на плаге до 3600с; лимит достигнут на 600с
    const soc = [], pw = [];
    for (let s = 0; s <= 600; s += 60) { pw.push({ x: s, y: 60 }); soc.push({ x: s, y: 20 + s / 100 }); }
    return telChargeGraph.buildChargeGraphData(
      { socSeries: soc, powerSeries: pw, targetReachedSeconds: [600], session: { startSoc: 20 } },
      3600 * 1000, 24);
  });
  chk('26. buildChargeGraphData: idle-хвост обрезан (окно ≤ 600·1.35=810с)', r.buckets.length > 0 && (r.buckets.length * r.intervalSeconds) <= 810 + r.intervalSeconds, { n: r.buckets.length, iv: r.intervalSeconds });
  chk('27. фазы: после лимита — holding, power=0, SoC переносится', r.buckets[r.buckets.length - 1].phase === 'holding' && r.buckets[r.buckets.length - 1].powerKw === 0 && r.buckets[r.buckets.length - 1].socPercent != null, r.buckets[r.buckets.length - 1]);
  chk('28. ось осталась 80 кВт (пик 60), тики [80,30,10,0], targetReached отфильтрован', r.axisMaximumKw === 80 && JSON.stringify(r.powerTicksKw) === '[80,30,10,0]' && r.targetReachedSeconds.length === 1, r);

  r = await p.evaluate(() => {
    const pw = [{ x: 0, y: 85 }, { x: 60, y: 85 }];
    return telChargeGraph.buildChargeGraphData({ socSeries: [{ x: 0, y: 10 }], powerSeries: pw, targetReachedSeconds: [], session: { startSoc: 10 } }, null, 24);
  });
  chk('29. пик выше 80 → потолок поднят до 90 (не кламп)', r.axisMaximumKw === 90 && r.powerTicksKw[0] === 90, r);

  r = await p.evaluate(() => telChargeGraph.buildChargeGraphData({ socSeries: [], powerSeries: [], targetReachedSeconds: [], session: {} }, null, 24));
  chk('30. пустая сессия → isEmpty, интервал 60с, потолок 80', r.isEmpty === true && r.intervalSeconds === 60 && r.axisMaximumKw === 80, r);

  chk('31. страница без ошибок JS (tel-core.js не конфликтует)', errs.length === 0, errs);
  await b.close();
  console.log(`\ntestt1: OK=${pass} FAIL=${fail}`);
  process.exit(fail > 0 ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
