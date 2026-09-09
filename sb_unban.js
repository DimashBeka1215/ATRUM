// Песочница (CLAUDE.md §18): «разбан/размут невозможны» в мультиподписи членства
// + проверка предлагаемого лечения (метка времени решения, побеждает последнее).
//
// Модель ровно по коду:
//   • каждое устройство держит Room = слитое состояние (MembersSync.applyIncoming);
//   • каждый ПУБЛИКУЮЩИЙ (главный / делегат с MODERATE / верифиц-дев) публикует свой слот
//     из ПОЛНОГО снимка своей Room (PublishScheduler.publishMembers);
//   • слияние (MembersSync.mergeSlots): banned = OR, mutedUntil = MAX.

// ── старое слияние ────────────────────────────────────────────────────────────
function mergeOld(primary, contributions) {
  let banned = primary.banned, mutedUntil = primary.mutedUntil;
  for (const c of contributions) {
    if (c.banned) banned = true;
    if (c.mutedUntil > mutedUntil) mutedUntil = c.mutedUntil;
  }
  return { banned, mutedUntil, modTs: 0 };
}

// ── новое слияние: побеждает решение с наибольшей меткой; равные метки (в т.ч. все
//    нулевые = старые клиенты) сливаются по-старому, поэтому поведение без меток
//    БАЙТ-В-БАЙТ прежнее ───────────────────────────────────────────────────────
function mergeNew(primary, contributions) {
  const all = [primary, ...contributions];
  const best = Math.max(...all.map(c => c.modTs || 0));
  const winners = all.filter(c => (c.modTs || 0) === best);
  const r = mergeOld(winners[0], winners.slice(1));
  r.modTs = best;
  return r;
}

function run(merge, label, keepFresherLocal) {
  // applyIncoming: в новой версии локальное решение свежее пришедшего не затираем
  // (пара «состояние+метка» едет вместе). В старой — затирается всегда, как сейчас.
  const apply = (dev, merged) => {
    if (keepFresherLocal && (dev.room.modTs || 0) > (merged.modTs || 0)) return;
    dev.room = { ...merged, modTs: keepFresherLocal ? merged.modTs : dev.room.modTs };
  };
  const devices = {
    main: { room: { banned: false, mutedUntil: 0, modTs: 0 } },
    dev:  { room: { banned: false, mutedUntil: 0, modTs: 0 } },
  };
  devices.main.slot = { ...devices.main.room };
  devices.dev.slot = { ...devices.dev.room };

  const tick = () => {
    const merged = merge(devices.main.slot, [devices.dev.slot]);
    apply(devices.main, merged); apply(devices.dev, merged);
    devices.main.slot = { ...devices.main.room };
    devices.dev.slot = { ...devices.dev.room };
    return merged;
  };
  const act = (who, patch, ts) => {
    devices[who].room = { ...devices[who].room, ...patch, modTs: ts };
    devices[who].slot = { ...devices[who].room };
  };

  act('dev', { banned: true }, 100); tick(); tick();
  act('dev', { banned: false }, 200);
  const r1 = tick(); tick();

  act('dev', { banned: true }, 300); tick(); tick();
  act('main', { banned: false }, 400);
  const r2 = tick(); tick();

  act('dev', { mutedUntil: 5000 }, 500); tick(); tick();
  act('dev', { mutedUntil: 0 }, 600);
  const r3 = tick(); tick();

  console.log(label);
  console.log('  разбан делегатом:  ', r1.banned === false ? 'ОК' : 'НЕ РАБОТАЕТ');
  console.log('  разбан главным:    ', r2.banned === false ? 'ОК' : 'НЕ РАБОТАЕТ');
  console.log('  досрочный размут:  ', r3.mutedUntil === 0 ? 'ОК' : 'НЕ РАБОТАЕТ');
}

run(mergeOld, 'СЕЙЧАС (OR/MAX):', false);
run(mergeNew, 'ПОСЛЕ ПРАВКИ (последнее решение):', true);

// ── регрессия: старые клиенты (меток нет вообще) ведут себя как раньше ─────────
const legacyCases = [
  [{ banned: false, mutedUntil: 0, modTs: 0 }, [{ banned: true, mutedUntil: 0, modTs: 0 }]],
  [{ banned: true, mutedUntil: 10, modTs: 0 }, [{ banned: false, mutedUntil: 99, modTs: 0 }]],
  [{ banned: false, mutedUntil: 7, modTs: 0 }, []],
];
const same = legacyCases.every(([p, c]) => {
  const a = mergeOld(p, c), b = mergeNew(p, c);
  return a.banned === b.banned && a.mutedUntil === b.mutedUntil;
});
console.log('\nбез меток поведение прежнее:', same ? 'ОК' : 'РАСХОЖДЕНИЕ');
