/* Stage NAV (бесплатный стек OSM): маршрут OSRM, ЭЗС Overpass, геокодер
   Nominatim, карта Leaflet, батарея/запас хода, подсказки голосом, объезды.
   Живой путь гоняется через мок XMLHttpRequest (те же URL, что настоящий
   стек) и мок L (Leaflet); офлайн-путь — сеть отвечает ошибкой. */
const { chromium } = require('playwright-core');
const sleep = ms => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const chk = (n, c, e) => { if (c) { pass++; console.log('чек', n + ':', 'OK'); } else { fail++; console.log('чек', n + ':', 'FAIL', e !== undefined ? JSON.stringify(e) : ''); } };

(async () => {
  const b = await chromium.launch();
  const errs = [];
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p.on('pageerror', e => errs.push(e.message));
  await p.addInitScript(() => {
    window.__emits = [];
    window.GeelyNative = { onEvent: (n, d) => { try { window.__emits.push([n, JSON.parse(d)]); } catch (e) {} } };
  });
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1500);

  /* ── Моки: XHR (OSRM/Nominatim/Overpass) + L (Leaflet) ──
        Маршрут «Гомель → Минск»: 4 точки, 5 шагов, 1 альтернатива;
        две ЭЗС: Маланка (~62 км, в запасе) и STRIMelectro (~222 км, вне). ── */
  await p.evaluate(() => {
    const P = [[52.431, 30.944], [52.60, 30.30], [53.05, 29.401], [53.50, 28.40], [53.90, 27.56]];
    const km = (a, b) => navHavKm(a, b);
    const L01 = Math.round(km(P[0], P[1]) * 1000), L12 = Math.round(km(P[1], P[2]) * 1000),
          L23 = Math.round(km(P[2], P[3]) * 1000), L34 = Math.round(km(P[3], P[4]) * 1000);
    const TOTAL = L01 + L12 + L23 + L34;
    window.__mock = { maps: 0, polylines: 0, markers: 0, xhrs: [] };
    const osrmRoute = (coords, alt) => ({
      geometry: { coordinates: coords.map(c => [c[1], c[0]]) },
      distance: alt ? TOTAL + 20000 : TOTAL, duration: alt ? 13800 : 12600,
      legs: [{
        steps: [
          { maneuver: { type: 'depart', modifier: 'left' }, name: '', distance: 100 },
          { maneuver: { type: 'turn', modifier: 'right' }, name: 'М5', distance: L01 },
          { maneuver: { type: 'turn', modifier: 'left' }, name: 'ул. Ленина', distance: L12 },
          { maneuver: { type: 'turn', modifier: 'right' }, name: 'просп. Победы', distance: L23 + L34 },
          { maneuver: { type: 'arrive', modifier: 'straight' }, name: '', distance: 200 },
        ],
      }],
    });
    window.XMLHttpRequest = function () {
      const self = this;
      self.open = (m, u) => { self.__url = u; self.__method = m; };
      self.send = (body) => {
        const u = self.__url || '';
        window.__mock.xhrs.push(u.slice(0, 60));
        const reply = (txt) => setTimeout(() => {
          self.status = 200; self.responseText = txt;
          self.onload && self.onload();
        }, 25);
        if (u.indexOf('27.56,53.90;27.57,53.91') >= 0) { reply('{"code":"Ok","routes":[]}'); return; }
        if (u.indexOf('nominatim') >= 0) {
          if (encodeURIComponent('Маланка') && u.indexOf(encodeURIComponent('Маланка')) >= 0) {
            reply('[{"lat":"52.60","lon":"30.30","display_name":"Маланка, трасса М5"}]');
          } else {
            reply('[{"lat":"53.90","lon":"27.56","display_name":"Минск, Беларусь"}]');
          }
          return;
        }
        if (u.indexOf('router.project-osrm.org/route') >= 0) {
          reply(JSON.stringify({ code: 'Ok', routes: [osrmRoute(P, false), osrmRoute([P[0], P[4]], true)] }));
          return;
        }
        if (u.indexOf('interpreter') >= 0) {
          reply(JSON.stringify({ elements: [
            { type: 'node', id: 1, lat: 52.605, lon: 30.295, tags: { brand: 'Malanka', 'brand:ru': 'Маланка', operator: 'Белоруснефть', capacity: '2' } },
            { type: 'node', id: 2, lat: 53.495, lon: 28.405, tags: { brand: 'STRIMelectro' } },
          ] }));
          return;
        }
        setTimeout(() => self.onerror && self.onerror(), 25);
      };
    };
    window.L = {
      map: function () { window.__mock.maps++; return { removeLayer() {}, fitBounds() {}, invalidateSize() {}, addTo() {} }; },
      tileLayer: () => ({ addTo() { return this; } }),
      polyline: () => { window.__mock.polylines++; return { addTo() { return this; }, getBounds: () => ({ pad() { return this; } }) }; },
      circleMarker: () => { window.__mock.markers++; return { addTo() { return this; }, bindPopup() { return this; } }; },
    };
  });

  /* Реальная телеметрия: SoC 80%, ёмкость 40 кВт·ч, расход 4,5 км/кВт·ч */
  await p.evaluate(() => window.__nativeEvent('telemetry.data', {
    range: { soc: 80, effKmPerKwh: 4.5, capacityKwh: 40, carKm: 130 },
    gauges: { speedKmh: 96, outsideTemp: 14, lat: 52.431, lon: 30.944, eff: 4.5 },
    settings: { packWh: 39600 }
  }));
  await sleep(200);
  await p.evaluate(() => select('navi'));
  await sleep(400);

  let r = await p.evaluate(() => ({
    batAll: Array.from(document.querySelectorAll('.navBat b')).map(x => x.textContent),
  }));
  chk('1. батарея: SoC 80% из телеметрии', r.batAll[0] === '80%', r);
  chk('2. реальный запас хода: 80%×40кВт·ч×4,5 = 144 км', r.batAll[1] === '144 км', r);
  chk('3. температура +14° (наружная, реальный сигнал)', r.batAll[2] === '+14°', r);

  r = await p.evaluate(() => ({ sum: document.querySelector('.navSum').textContent }));
  chk('4. до построения: демо-итоги схемы (302 км)', /302/.test(r.sum), r);

  // Поехали → живой маршрут через OSRM
  await p.evaluate(() => navBuildRoute('Минск'));
  await sleep(900);
  r = await p.evaluate(() => ({
    sum: document.querySelector('.navSum').textContent,
    total: NAV.totalKm, dur: NAV.durationH, alts: NAV.alts,
    stations: Array.from(document.querySelectorAll('[data-lst]')).map(x => x.textContent),
    badges: Array.from(document.querySelectorAll('.navBadge')).map(x => x.textContent),
    maps: window.__mock.maps,
    src: document.body.textContent.indexOf('OpenStreetMap') >= 0,
    altLine: document.body.textContent.indexOf('объездов: 1') >= 0,
  }));
  chk('5. маршрут построен: живое расстояние из OSRM (' + r.total + ' км, ' + r.dur + ' ч)', r.sum.indexOf(r.total + ' км') >= 0, r);
  chk('6. живые ЭЗС из Overpass: карточка «Маланка»', r.stations.some(t => /Маланка/.test(t)), r);
  chk('7. достижимость: Маланка в запасе хода, STRIMelectro вне', r.badges.includes('в запасе хода') && r.badges.includes('вне запаса'), r);
  chk('8. карта вкладки инициализирована (Leaflet)', r.maps >= 1, r);
  chk('9. альтернатива-объезд от OSRM показана (объездов: 1)', r.alts === 1 && r.altLine === true, r);
  chk('10. источники честно подписаны (OpenStreetMap)', r.src === true, r);

  // Полноэкранный навигатор
  await p.evaluate(() => openNavigator());
  await sleep(2600);
  r = await p.evaluate(() => ({
    man: (document.getElementById('naviManT') || {}).textContent,
    manD: (document.getElementById('naviManD') || {}).textContent,
    eta: (document.getElementById('naviEta') || {}).textContent,
    maps: window.__mock.maps,
  }));
  chk('11. манёвр из шагов OSRM (не стартовые «800 м»)', r.manD && r.manD !== '800 м' && /М5/.test(r.man || ''), r);
  chk('12. полноэкранная карта инициализирована', r.maps >= 2, r);

  // Подсказка: позиция прямо перед манёвром «просп. Победы»
  await p.evaluate(() => { TEL.gauges.lat = 53.045; TEL.gauges.lon = 29.405; });
  await sleep(2600);
  r = await p.evaluate(() => ({
    spoke: window.__emits.filter(e => e[0] === 'nav.speak').map(e => e[1].text),
    manD: (document.getElementById('naviManD') || {}).textContent,
  }));
  chk('13. голосовая подсказка ушла в натив (nav.speak)', r.spoke.length >= 1, r);
  chk('14. подсказка содержит манёвр (направо/улица)', r.spoke.some(t => /направо|Победы/.test(t)), r);
  const firstCount = r.spoke.length;
  await sleep(2500);
  r = await p.evaluate(() => ({ spoke: window.__emits.filter(e => e[0] === 'nav.speak').length }));
  chk('15. подсказка не повторяется (один раз на манёвр)', r.spoke === firstCount, { firstCount, now: r.spoke });

  // Звук выключен — подсказок нет
  await p.evaluate(() => { TEL.gauges.lat = 53.49; TEL.gauges.lon = 28.41; NAV.spokeSeg = -1; });
  await p.evaluate(() => document.getElementById('navSnd').click());
  await sleep(2600);
  r = await p.evaluate(() => ({ spoke: window.__emits.filter(e => e[0] === 'nav.speak').length, off: document.getElementById('navSnd').classList.contains('off') }));
  chk('16. звук выкл — подсказка не произносится', r.spoke === firstCount && r.off, r);
  await p.evaluate(() => document.getElementById('navX').click());

  // Ключей нет — стек полностью бесплатный
  r = await p.evaluate(() => ({ yk: typeof YANDEX_KEY !== 'undefined', osrm: typeof OSRM_BASE !== 'undefined' }));
  chk('17. ключей API нет вообще (YANDEX_KEY отсутствует, OSRM_BASE есть)', r.yk === false && r.osrm === true, r);

  // «Навигация» к живой ЭЗС — маршрут на станцию
  await p.evaluate(() => { const el = document.querySelector('[data-lnavto]'); if (el) el.click(); });
  await sleep(800);
  r = await p.evaluate(() => ({ dest: NAV.dest, route: !!NAV.coords.length, total: NAV.totalKm }));
  chk('18. «Навигация» к живой ЭЗС: маршрут перестроен на «Маланку»', r.route === true && /Маланка/.test(r.dest || ''), r);

  /* ── Офлайн-мир: сеть отвечает ошибкой ── */
  const p2 = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p2.on('pageerror', e => errs.push(e.message));
  await p2.addInitScript(() => {
    window.__emits = [];
    window.GeelyNative = { onEvent: (n, d) => { try { window.__emits.push([n, JSON.parse(d)]); } catch (e) {} } };
  });
  await p2.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1200);
  await p2.evaluate(() => {
    window.XMLHttpRequest = function () {
      const self = this;
      self.open = () => {}; self.send = () => setTimeout(() => self.onerror && self.onerror(), 15);
    };
  });
  await p2.evaluate(() => select('navi'));
  await sleep(300);
  await p2.evaluate(() => navBuildRoute('Минск'));
  await sleep(600);
  r = await p2.evaluate(() => ({
    svg: !!document.querySelector('#navMapBox svg'),
    toast: document.querySelector('#toast').textContent,
    sum: document.querySelector('.navSum').textContent,
    state: NAV.state,
  }));
  chk('19. офлайн: стилизованная схема на месте', r.svg === true, r);
  chk('20. офлайн: «Поехали» честно сообщает «Нет сети»', /Нет сети/.test(r.toast), r);
  chk('21. офлайн: демо-итоги не ломаются (302 км)', /302/.test(r.sum), r);

  console.log('ИТОГО:', pass, 'OK,', fail, 'FAIL | errors:', errs.length ? errs : 'none');
  await b.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
