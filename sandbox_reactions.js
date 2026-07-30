// Песочница (CLAUDE.md §18, шаг 2): проверка безопасного фикса «реакции мигают».
//
// Модель соответствует реальной механике ATRUM:
//   - reactions.txt — NIP-78 replaceable-событие, ПО ОДНОМУ СЛОТУ НА pubkey (у каждого
//     участника свой слот, подписанный его ключом).
//   - Старое чтение latestFile(): берёт ОДИН слот с максимальным created_at → слот
//     «последнего писателя» затирает реакции остальных (см. NostrTransport.latestFile,
//     комментарий про lost update / мерцание).
//   - Запись toggleReaction(): read-modify-write ПОЛНОГО набора в свой слот.
//
// Проверяемый фикс (без смены формата файла, §17):
//   - Новое чтение = UNION всех слотов, где КАЖДЫЙ пользователь авторитетен только за свои
//     реакции (строку msgId|emoji|userId принимаем из слота, лишь если слот подписан pubkey
//     этого userId). Это же чинит подделку чужих реакций (§Безопасность).
//   - Реконсиляция сравнивает НОРМАЛИЗОВАННЫЙ набор (а не зашифрованные байты) → нет лишних
//     перерисовок из-за рандомного nonce/перепубликаций (анти-мигание).
//   - Новые клиенты ПРОДОЛЖАЮТ писать полный набор (RMW) → старые клиенты (latestFile)
//     видят всё как раньше (обратная совместимость).

let CLOCK = 1000;
const tick = () => ++CLOCK;

// ── «Сеть»: реле хранит по одному replaceable-слоту на pubkey для d=reactions.txt ──
class Relays {
  constructor() { this.slots = new Map(); } // pubkey -> { created_at, lines:Set<string> }
  publish(pubkey, created_at, lines) {
    const cur = this.slots.get(pubkey);
    if (!cur || created_at >= cur.created_at) {
      this.slots.set(pubkey, { created_at, lines: new Set(lines) });
    }
  }
  // Полный снимок всех слотов (как union-read увидел бы канал).
  allSlots() { return [...this.slots.entries()].map(([pk, s]) => ({ pk, ...s })); }
  // Устаревшее чтение: имитируем реле, отдающее СТАРУЮ версию слота для pubkey `stalePk`.
  allSlotsStale(stalePk, staleLines, staleCreated) {
    return this.allSlots().map(s =>
      s.pk === stalePk ? { pk: s.pk, created_at: staleCreated, lines: new Set(staleLines) } : s
    );
  }
}

// pubkeyForUserId — как в реальном коде (members/profiles). Строка реакции: msgId|emoji|userId.
const PK = { alice: 'pk_A', bob: 'pk_B', carol: 'pk_C' };
const pubkeyForUserId = (uid) => PK[uid] || null;

const lineOf = (m, e, u) => `${m}|${e}|${u}`;
const parse = (lines) => [...lines].map(l => l.split('|')).filter(p => p.length === 3);

// ── СТАРОЕ чтение: один слот с макс created_at ─────────────────────────────────
function readLatestFile(slots) {
  let best = null;
  for (const s of slots) if (!best || s.created_at > best.created_at) best = s;
  return best ? new Set(best.lines) : new Set();
}

// ── НОВОЕ чтение: union per-user-authoritative ────────────────────────────────
function readUnion(slots) {
  const out = new Set();
  // 1) авторитетный проход: строку берём из слота, ТОЛЬКО если слот подписан pubkey автора.
  const resolvedUsers = new Set();
  for (const s of slots) {
    for (const [m, e, u] of parse(s.lines)) {
      const pk = pubkeyForUserId(u);
      if (pk && pk === s.pk) { out.add(lineOf(m, e, u)); resolvedUsers.add(u); }
    }
  }
  // 2) fallback для userId без резолва pubkey (неизвестный участник): OR-union из всех слотов.
  //    Никогда не теряем add; на remove может «залипнуть», пока не доедет свой слот — залипание
  //    несравнимо мягче мигания и сходится. Пользователи с известным pubkey сюда не попадают.
  for (const s of slots) {
    for (const [m, e, u] of parse(s.lines)) {
      if (!pubkeyForUserId(u) && !resolvedUsers.has(u)) out.add(lineOf(m, e, u));
    }
  }
  return out;
}

// Канонизация набора для анти-мигающего сравнения (порядок/дубликаты не важны).
const canon = (set) => [...set].sort().join('\n');

// ── Клиент ─────────────────────────────────────────────────────────────────
class Client {
  constructor(userId, relays, readFn, { legacyWriter = false } = {}) {
    this.uid = userId; this.pk = PK[userId]; this.relays = relays;
    this.readFn = readFn; this.legacyWriter = legacyWriter;
    this.merged = new Set();      // мой merged-вид (localReactionsContent)
    this.displayedCanon = null;   // что реально показано в UI
    this.uiUpdates = 0;           // сколько раз дёрнули adapter.setReactions
  }
  // toggleReaction: RMW полного набора → пишем в СВОЙ слот.
  toggle(m, e) {
    const line = lineOf(m, e, this.uid);
    if (this.merged.has(line)) this.merged.delete(line); else this.merged.add(line);
    this.relays.publish(this.pk, tick(), this.merged);
    this._applyUi(this.merged); // оптимистичный показ
  }
  // reconcile из опроса (slots — что вернуло чтение канала).
  reconcile(slots) {
    const set = this.readFn(slots);
    this.merged = new Set(set); // обновляем базу для будущих RMW
    this._applyUi(set);
  }
  _applyUi(set) {
    const c = canon(set);
    if (c !== this.displayedCanon) { this.displayedCanon = c; this.uiUpdates++; }
  }
  shown() { return new Set(this.displayedCanon ? this.displayedCanon.split('\n').filter(Boolean) : []); }
}

// ── Тест-харнесс ──────────────────────────────────────────────────────────────
let PASS = 0, FAIL = 0;
function check(name, cond, extra = '') {
  if (cond) { PASS++; console.log(`  PASS  ${name}`); }
  else { FAIL++; console.log(`  FAIL  ${name}  ${extra}`); }
}
const eqSet = (a, b) => a.size === b.size && [...a].every(x => b.has(x));
const banner = (t) => console.log(`\n=== ${t} ===`);

// Сценарий 1: одновременные реакции двух людей. Старое чтение теряет одну; новое — обе.
(function scenarioConcurrent() {
  banner('1. Одновременные реакции (Alice ❤ msg1, Bob 👍 msg1)');
  // ── Старое чтение ──
  {
    const R = new Relays();
    const a = new Client('alice', R, readLatestFile);
    const b = new Client('bob', R, readLatestFile);
    a.toggle('m1', '❤');           // slot A: {m1|❤|alice}
    b.merged = readLatestFile(R.allSlots()); // Bob опросил (видит реакцию Alice)
    b.toggle('m1', '👍');          // slot B: {m1|❤|alice, m1|👍|bob} — RMW спас
    // но теперь Alice снова тогглит что-то, не увидев Bob (устаревший merged):
    a.toggle('m1', '😂');          // slot A новее: {m1|❤|alice, m1|😂|alice} — БЕЗ реакции Bob!
    const shownOld = readLatestFile(R.allSlots());
    check('старое чтение ТЕРЯЕТ реакцию Bob (демонстрация бага)',
      !shownOld.has('m1|👍|bob'), `shown=${[...shownOld]}`);
  }
  // ── Новое чтение (union) ──
  {
    const R = new Relays();
    const a = new Client('alice', R, readUnion);
    const b = new Client('bob', R, readUnion);
    a.toggle('m1', '❤');
    b.merged = readUnion(R.allSlots());
    b.toggle('m1', '👍');
    a.toggle('m1', '😂'); // Alice снова пишет свой слот без учёта Bob
    const shown = readUnion(R.allSlots());
    check('union СОХРАНЯЕТ обе (Alice ❤, Alice 😂, Bob 👍)',
      eqSet(shown, new Set(['m1|❤|alice', 'm1|😂|alice', 'm1|👍|bob'])), `shown=${[...shown]}`);
  }
})();

// Сценарий 2: мигание из-за скачущего «победителя» слота при обычном чтении vs стабильность union.
(function scenarioFlicker() {
  banner('2. Мигание: несколько опросов подряд, слоты пишутся по очереди');
  const R = new Relays();
  const a = new Client('alice', R, readUnion);
  const b = new Client('bob', R, readUnion);
  a.toggle('m1', '❤'); b.merged = readUnion(R.allSlots()); b.reconcile(R.allSlots());
  b.toggle('m1', '👍'); a.merged = readUnion(R.allSlots());
  a.reconcile(R.allSlots()); // один реальный reconcile «устаканивает» набор (❤ → ❤+👍)
  // Серия ДАЛЬНЕЙШИХ опросов у Alice БЕЗ реальных изменений набора → UI не должен дёргаться.
  const before = a.uiUpdates;
  for (let i = 0; i < 5; i++) a.reconcile(R.allSlots());
  check('union: 5 опросов без изменений → 0 лишних перерисовок (нет мигания)',
    a.uiUpdates === before, `updates+=${a.uiUpdates - before}`);
  // Старое чтение при чередовании публикаций: показываем, что набор осциллирует.
  {
    const R2 = new Relays();
    R2.publish(PK.alice, 100, ['m1|❤|alice']);
    R2.publish(PK.bob,   101, ['m1|👍|bob']); // slot B без реакции alice (Bob не успел смёржить)
    const s1 = readLatestFile(R2.allSlots());          // победил B (101) → нет ❤ alice
    R2.publish(PK.alice, 102, ['m1|❤|alice']);         // A перезаписал (heartbeat/повтор)
    const s2 = readLatestFile(R2.allSlots());          // победил A (102) → нет 👍 bob
    check('старое чтение ОСЦИЛЛИРУЕТ (s1 без ❤, s2 без 👍) — это и есть мигание',
      !s1.has('m1|❤|alice') && !s2.has('m1|👍|bob'), `s1=${[...s1]} s2=${[...s2]}`);
    // Тот же вход через union — стабильно полный набор.
    const u1 = readUnion(R2.allSlots());
    check('union на тех же слотах → полный стабильный набор',
      eqSet(u1, new Set(['m1|❤|alice', 'm1|👍|bob'])), `u=${[...u1]}`);
  }
})();

// Сценарий 3: устаревшее чтение с реле (реле отдало старую версию слота Bob).
(function scenarioStale() {
  banner('3. Устаревшее чтение слота Bob (реле отстало)');
  const R = new Relays();
  const a = new Client('alice', R, readUnion);
  const b = new Client('bob', R, readUnion);
  a.toggle('m1', '❤');
  b.merged = readUnion(R.allSlots()); b.toggle('m1', '👍'); // slot B новый: содержит обе
  a.reconcile(R.allSlots());
  const shownFresh = a.shown();
  // Теперь опрос вернул СТАРУЮ версию слота Bob (до его 👍) — created_at меньше.
  const staleSlots = R.allSlotsStale(PK.bob, ['m1|❤|alice'], 1); // Bob-слот старый, без 👍
  const uStale = readUnion(staleSlots);
  // Реакция Bob исходит из ЕГО слота; если реле отдало старый слот Bob — 👍 отсутствует,
  // но это корректный откат к тому, что реле реально знает (не «чужой» стал автором).
  // Ключевое: реакция Alice НЕ пропадает из-за слота Bob (в отличие от latestFile).
  check('union при устаревшем слоте Bob СОХРАНЯЕТ реакцию Alice',
    uStale.has('m1|❤|alice'), `u=${[...uStale]}`);
  // Классическая демонстрация бага latestFile: новейший слот принадлежит Bob и НЕ содержит
  // реакцию Alice (Bob не успел смёржить) → latestFile берёт только его → Alice пропадает.
  const demo = [
    { pk: PK.alice, created_at: 10, lines: new Set(['m1|❤|alice']) },
    { pk: PK.bob,   created_at: 11, lines: new Set(['m1|👍|bob']) },
  ];
  check('старое чтение (новейший слот Bob без Alice) ТЕРЯЕТ реакцию Alice — баг',
    !readLatestFile(demo).has('m1|❤|alice'), `old=${[...readLatestFile(demo)]}`);
  check('union на тех же слотах сохраняет обе',
    eqSet(readUnion(demo), new Set(['m1|❤|alice', 'm1|👍|bob'])), `u=${[...readUnion(demo)]}`);
})();

// Сценарий 4: подделка — Bob кладёт в свой слот реакцию от имени Alice.
(function scenarioForge() {
  banner('4. Подделка: Bob пишет строку с userId=alice в своём слоте');
  const R = new Relays();
  R.publish(PK.bob, tick(), ['m1|❤|alice', 'm1|👍|bob']); // Bob подделал ❤ от Alice
  const u = readUnion(R.allSlots());
  check('union ОТВЕРГАЕТ поддельную ❤|alice из слота Bob',
    !u.has('m1|❤|alice') && u.has('m1|👍|bob'), `u=${[...u]}`);
  check('старое чтение ПРИНЯЛО БЫ подделку (демонстрация уязвимости)',
    readLatestFile(R.allSlots()).has('m1|❤|alice'), '');
})();

// Сценарий 5: un-react (снятие реакции) сходится.
(function scenarioRemove() {
  banner('5. Снятие своей реакции сходится');
  const R = new Relays();
  const a = new Client('alice', R, readUnion);
  a.toggle('m1', '❤'); check('поставлена', readUnion(R.allSlots()).has('m1|❤|alice'));
  a.merged = readUnion(R.allSlots());
  a.toggle('m1', '❤'); // снятие
  check('union: реакция снята (свой авторитетный слот пуст по этой строке)',
    !readUnion(R.allSlots()).has('m1|❤|alice'), `u=${[...readUnion(R.allSlots())]}`);
})();

// Сценарий 6: обратная совместимость — старый клиент (пишет полный набор, читает latestFile)
// и новый (пишет полный набор, читает union) вместе.
(function scenarioBackcompat() {
  banner('6. Обратная совместимость: старый latestFile-клиент + новый union-клиент');
  const R = new Relays();
  const legacy = new Client('alice', R, readLatestFile, { legacyWriter: true });
  const modern = new Client('bob', R, readUnion);
  legacy.toggle('m1', '❤');                    // старый пишет полный набор в свой слот
  modern.merged = readUnion(R.allSlots());
  modern.toggle('m1', '👍');                   // новый пишет полный набор (RMW) в свой слот
  legacy.reconcile(R.allSlots());              // старый читает latestFile → новейший слот (Bob, полный) → видит обе
  modern.reconcile(R.allSlots());              // новый читает union → обе
  check('старый клиент видит ОБЕ реакции (новый пишет полный набор → latestFile ок)',
    eqSet(legacy.shown(), new Set(['m1|❤|alice', 'm1|👍|bob'])), `legacy=${[...legacy.shown()]}`);
  check('новый клиент видит ОБЕ реакции',
    eqSet(modern.shown(), new Set(['m1|❤|alice', 'm1|👍|bob'])), `modern=${[...modern.shown()]}`);
})();

// Сценарий 7: нормализованное сравнение — разный порядок строк / «переупаковка» не дёргает UI.
(function scenarioCanon() {
  banner('7. Нормализованное сравнение (анти-мигание при том же наборе)');
  const a = new Client('alice', new Relays(), readUnion);
  a.reconcile([{ pk: PK.alice, created_at: 1, lines: new Set(['m1|❤|alice', 'm2|👍|alice']) }]);
  const n1 = a.uiUpdates;
  // Тот же логический набор, но другой порядок и дубликат (как после переупаковки/повторной публикации).
  a.reconcile([{ pk: PK.alice, created_at: 2, lines: new Set(['m2|👍|alice', 'm1|❤|alice', 'm1|❤|alice']) }]);
  check('одинаковый набор в другом порядке → UI НЕ обновляется', a.uiUpdates === n1,
    `updates+=${a.uiUpdates - n1}`);
  a.reconcile([{ pk: PK.alice, created_at: 3, lines: new Set(['m1|❤|alice']) }]); // реально изменилось
  check('реальное изменение набора → UI обновляется', a.uiUpdates === n1 + 1);
})();

// Сценарий 8: отказ реле (пустой снимок слотов) НЕ гасит показанные реакции (Nostr-гард).
// Мод��лирует Kotlin-ветку: `if (reactionSlotsSigned.isNotEmpty())` — при пустых слотах для
// Nostr НИЧЕГО не трогаем, чтобы реакции не мигали в 0 при временном отказе реле.
(function scenarioRelayDown() {
  banner('8. Отказ реле: пустой снимок слотов не гасит реакции (Nostr-гард)');
  const R = new Relays();
  const a = new Client('alice', R, readUnion);
  a.toggle('m1', '❤');
  a.reconcile(R.allSlots());
  const before = canon(a.shown());
  const updatesBefore = a.uiUpdates;
  // Реле не ответили → снимок слотов пуст. Гард: для Nostr пропускаем reconcile.
  const relaySlots = [];
  const isNostr = true;
  if (relaySlots.length > 0 || !isNostr) a.reconcile(relaySlots); // else — не трогаем
  check('реакция Alice осталась после пустого снимка реле',
    a.shown().has('m1|❤|alice'), `shown=${[...a.shown()]}`);
  check('набор не изменился и UI не дёрнулся',
    canon(a.shown()) === before && a.uiUpdates === updatesBefore);
})();

// Сценарий 9: флаки-чтение пропускает слот → липкий кэш слотов не роняет реакции (нет мигания).
// Моделирует NostrTransport.stickyReactionSlots: помним новейший слот на pubkey между тиками.
(function scenarioStickySlots() {
  banner('9. Флаки-чтение: пропуск слота не роняет реакции (липкий кэш слотов)');
  const sticky = new Map();
  const stickyMerge = (tickSlots) => {
    for (const s of tickSlots) {
      const cur = sticky.get(s.pk);
      if (!cur || s.created_at >= cur.created_at)
        sticky.set(s.pk, { pk: s.pk, created_at: s.created_at, lines: new Set(s.lines) });
    }
    return [...sticky.values()];
  };
  const R = new Relays();
  R.publish(PK.alice, 10, ['m1|❤|alice']);
  R.publish(PK.bob,   11, ['m1|👍|bob']);
  const full = R.allSlots();
  const s1 = readUnion(stickyMerge(full));
  check('оба слота прочитаны → обе реакции', eqSet(s1, new Set(['m1|❤|alice', 'm1|👍|bob'])), `s=${[...s1]}`);
  // Флаки-тик: реле вернуло ТОЛЬКО слот Alice (слот Bob пропал).
  const flaky = full.filter(s => s.pk === PK.alice);
  const s2 = readUnion(stickyMerge(flaky));
  check('флаки-тик без слота Bob: реакция Bob НЕ пропала (липкость)', s2.has('m1|👍|bob'), `s=${[...s2]}`);
  check('набор не изменился → нет мигания', canon(s2) === canon(s1));
  check('БЕЗ липкости тот же флаки-тик РОНЯЕТ Bob (старое поведение = мигание)',
    !readUnion(flaky).has('m1|👍|bob'), `s=${[...readUnion(flaky)]}`);
  // Реальное снятие: Bob публикует новый ПУСТОЙ слот с бо́льшим created_at → липкость заменяет.
  const s3 = readUnion(stickyMerge([{ pk: PK.bob, created_at: 20, lines: new Set() }]));
  check('снятие реакции (пустой слот новее) применяется через липкость', !s3.has('m1|👍|bob'), `s=${[...s3]}`);
})();

console.log(`\n──────────────\nИТОГ: ${PASS} PASS, ${FAIL} FAIL`);
process.exit(FAIL === 0 ? 0 : 1);
