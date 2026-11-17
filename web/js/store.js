// Local data layer: everything lives in memory and is mirrored to IndexedDB.
// Same data model and rules as the Android app (counters, periods, conversions, tombstones).

const STORES = ['soldiers', 'counters', 'members', 'entries', 'duties', 'templates', 'runs', 'tombstones'];
const KEYS = {
  soldiers: 'uid', counters: 'uid', members: 'key', entries: 'uid',
  duties: 'uid', templates: 'uid', runs: 'uid', tombstones: 'key',
};
const DB_NAME = 'hakshev-web';
const MAX = Number.MAX_SAFE_INTEGER;

export const data = {};
STORES.forEach((s) => { data[s] = []; });

export const uuid = () =>
  (crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxxxxxx4xxxyxxxxxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  }));

// ------------------------------------------------------------------ prefs (localStorage)
const PREF_KEY = 'hk_prefs';
const defaults = {
  commander: 'מפקד',
  squads: "כיתה א', כיתה ב', כיתה ג'",
  mySquad: '',
  theme: 'system',
  wipeDays: 0,
  lastActive: 0,
  pinSalt: '',
  pinHash: '',
};
let prefs = { ...defaults };
try { prefs = { ...defaults, ...JSON.parse(localStorage.getItem(PREF_KEY) || '{}') }; } catch (e) { /* ignore */ }
export const getPref = (k) => prefs[k];
export function setPref(k, v) {
  prefs[k] = v;
  try { localStorage.setItem(PREF_KEY, JSON.stringify(prefs)); } catch (e) { /* ignore */ }
}
export const squadList = () => String(prefs.squads).split(',').map((s) => s.trim()).filter(Boolean);

// ------------------------------------------------------------------ IndexedDB
let idb = null;
let pending = [];
let flushing = false;

export function openDb() {
  return new Promise((resolve) => {
    if (!window.indexedDB) { resolve(); return; }
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      STORES.forEach((s) => { if (!db.objectStoreNames.contains(s)) db.createObjectStore(s, { keyPath: KEYS[s] }); });
    };
    req.onsuccess = () => { idb = req.result; resolve(); };
    req.onerror = () => resolve();
  });
}

export async function load() {
  await openDb();
  if (!idb) { reindex(); return; }
  await Promise.all(STORES.map((s) => new Promise((resolve) => {
    const r = idb.transaction(s).objectStore(s).getAll();
    r.onsuccess = () => { data[s] = r.result || []; resolve(); };
    r.onerror = () => resolve();
  })));
  reindex();
}

function flush() {
  if (flushing || !idb || !pending.length) return;
  flushing = true;
  const ops = pending;
  pending = [];
  const tx = idb.transaction([...new Set(ops.map((o) => o.store))], 'readwrite');
  ops.forEach((o) => {
    const os = tx.objectStore(o.store);
    if (o.type === 'put') os.put(o.obj);
    else if (o.type === 'del') os.delete(o.key);
    else if (o.type === 'clear') os.clear();
  });
  const done = () => { flushing = false; if (pending.length) flush(); };
  tx.oncomplete = done;
  tx.onerror = done;
  tx.onabort = done;
}
function queue(op) { pending.push(op); Promise.resolve().then(flush); }
const persist = (store, obj) => queue({ type: 'put', store, obj });
const unpersist = (store, key) => queue({ type: 'del', store, key });

// ------------------------------------------------------------------ indexes + change events
export const idx = {
  soldier: new Map(), counter: new Map(), member: new Map(),
  entriesByCounter: new Map(), membersByCounter: new Map(), membersBySoldier: new Map(),
};

export function reindex() {
  idx.soldier = new Map(data.soldiers.map((s) => [s.uid, s]));
  idx.counter = new Map(data.counters.map((c) => [c.uid, c]));
  idx.member = new Map(data.members.map((m) => [m.key, m]));
  idx.entriesByCounter = new Map();
  for (const e of data.entries) {
    let l = idx.entriesByCounter.get(e.counterUid);
    if (!l) { l = []; idx.entriesByCounter.set(e.counterUid, l); }
    l.push(e);
  }
  idx.membersByCounter = new Map();
  idx.membersBySoldier = new Map();
  for (const m of data.members) {
    let l = idx.membersByCounter.get(m.counterUid);
    if (!l) { l = []; idx.membersByCounter.set(m.counterUid, l); }
    l.push(m);
    let l2 = idx.membersBySoldier.get(m.soldierUid);
    if (!l2) { l2 = []; idx.membersBySoldier.set(m.soldierUid, l2); }
    l2.push(m);
  }
}

const listeners = new Set();
export function onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); }
/** local = true when the user changed something (triggers a sync); false for data pulled from the cloud. */
export function changed(local = true) {
  reindex();
  listeners.forEach((f) => { try { f(local); } catch (e) { console.error(e); } });
}

// ------------------------------------------------------------------ periods
export const PERIODS = [
  ['DAY', 'יום'], ['WEEK', 'שבוע (מיום ראשון)'], ['TWO_WEEKS', 'שבועיים'],
  ['MONTH', 'חודש'], ['TOTAL', 'מצטבר, בלי איפוס'],
];
export const periodLabel = (p) => {
  const short = { DAY: 'יום', WEEK: 'שבוע (מיום ראשון)', TWO_WEEKS: 'שבועיים', MONTH: 'חודש', TOTAL: 'מצטבר, בלי איפוס' };
  return short[p] || short.DAY;
};
const midnight = (d) => { d.setHours(0, 0, 0, 0); return d; };
const toSunday = (d) => { d.setDate(d.getDate() - d.getDay()); return d; };

/** {from, to) of the period containing `now`, shifted by `offset` periods (0 current, -1 previous). */
export function periodRange(period, anchor, now, offset) {
  const cal = midnight(new Date(now));
  let from; let to;
  if (period === 'WEEK') {
    toSunday(cal);
    cal.setDate(cal.getDate() + 7 * offset);
    from = +cal;
    cal.setDate(cal.getDate() + 7);
    to = +cal;
  } else if (period === 'TWO_WEEKS') {
    const a = toSunday(midnight(new Date(anchor > 0 ? anchor : now)));
    const days = Math.round((+cal - +a) / 86400000);
    const blocks = Math.floor(days / 14);
    a.setDate(a.getDate() + (blocks + offset) * 14);
    from = +a;
    a.setDate(a.getDate() + 14);
    to = +a;
  } else if (period === 'MONTH') {
    cal.setDate(1);
    cal.setMonth(cal.getMonth() + offset);
    from = +cal;
    cal.setMonth(cal.getMonth() + 1);
    to = +cal;
  } else if (period === 'TOTAL') {
    return [0, MAX];
  } else {
    cal.setDate(cal.getDate() + offset);
    from = +cal;
    cal.setDate(cal.getDate() + 1);
    to = +cal;
  }
  return [from, to];
}

export const fmtDate = (ms) => new Date(ms).toLocaleDateString('he-IL');
export const fmtDateTime = (ms) => new Date(ms).toLocaleString('he-IL', { dateStyle: 'short', timeStyle: 'short' });

export function rangeText(def, now) {
  if (def.period === 'TOTAL') return 'כל התקופות';
  const r = periodRange(def.period, def.createdAt, now, 0);
  if (def.period === 'DAY') return fmtDate(r[0]);
  return `${fmtDate(r[0])} – ${fmtDate(r[1] - 1)}`;
}

// ------------------------------------------------------------------ statuses
export const STATUSES = [
  ['PRESENT', 'נוכח'], ['SICK', 'גימלים'], ['DUTY', 'תורנות'], ['LEAVE', 'יציאה'], ['PROBLEM', 'בעייתי'],
];
export const statusLabel = (s) => (STATUSES.find((x) => x[0] === s) || STATUSES[0])[1];
export const onSite = (s) => s === 'PRESENT' || s === 'DUTY';

// ------------------------------------------------------------------ lookups
const cmpSoldier = (a, b) => (a.squad || '').localeCompare(b.squad || '', 'he') || a.fullName.localeCompare(b.fullName, 'he');
export const allSoldiers = () => [...data.soldiers].sort(cmpSoldier);
export const allCounters = () => [...data.counters].sort((a, b) => a.name.localeCompare(b.name, 'he'));
export const soldier = (uid) => idx.soldier.get(uid);
export const counter = (uid) => idx.counter.get(uid);
export const membersOf = (counterUid) =>
  (idx.membersByCounter.get(counterUid) || []).map((m) => idx.soldier.get(m.soldierUid)).filter(Boolean).sort(cmpSoldier);
export const countersOf = (soldierUid) =>
  (idx.membersBySoldier.get(soldierUid) || []).map((m) => idx.counter.get(m.counterUid)).filter(Boolean)
    .sort((a, b) => a.name.localeCompare(b.name, 'he'));
export const memberKey = (counterUid, soldierUid) => `${counterUid}_${soldierUid}`;

export function total(counterUid, soldierUid, from, to) {
  let sum = 0;
  for (const e of idx.entriesByCounter.get(counterUid) || []) {
    if (e.soldierUid === soldierUid && e.createdAt >= from && e.createdAt < to) sum += e.delta;
  }
  return sum;
}
export function totals(counterUid, from, to) {
  const m = new Map();
  for (const e of idx.entriesByCounter.get(counterUid) || []) {
    if (e.createdAt >= from && e.createdAt < to) m.set(e.soldierUid, (m.get(e.soldierUid) || 0) + e.delta);
  }
  return m;
}
export const entriesOf = (counterUid, soldierUid) =>
  (idx.entriesByCounter.get(counterUid) || []).filter((e) => e.soldierUid === soldierUid)
    .sort((a, b) => b.createdAt - a.createdAt).slice(0, 100);

// ------------------------------------------------------------------ value text / rows
export function valueText(def, value) {
  if (def.kind === 1) return value > 0 ? '✓ בוצע' : '✗ לא בוצע';
  const s = def.target > 0 ? `${value}/${def.target}` : String(value);
  return def.unit ? `${s} ${def.unit}` : s;
}
const unitOnlyText = (def, value) => (def.unit ? `${value} ${def.unit}` : String(value));
export const groupTotalText = (def, total_) => (def.kind === 1 ? `${total_} בוצעו` : unitOnlyText(def, total_));

function makeRow(def, s, v, prevV, titleOf) {
  const check = def.kind === 1;
  return {
    counterUid: def.uid,
    soldierUid: s.uid,
    title: titleOf,
    avatarText: titleOf,
    value: v,
    target: def.target,
    check,
    valueText: valueText(def, v),
    reached: check ? v > 0 : def.target > 0 && v >= def.target,
    sub: prevV == null ? '' : `בתקופה הקודמת: ${valueText(def, prevV)}`,
  };
}

export function memberRows(def, members, now) {
  const cur = periodRange(def.period, def.createdAt, now, 0);
  const curMap = totals(def.uid, cur[0], cur[1]);
  let prevMap = null;
  if (def.period !== 'TOTAL') {
    const prev = periodRange(def.period, def.createdAt, now, -1);
    prevMap = totals(def.uid, prev[0], prev[1]);
  }
  return members.map((s) => {
    const r = makeRow(def, s, curMap.get(s.uid) || 0, prevMap ? (prevMap.get(s.uid) || 0) : null, s.fullName);
    r.avatarText = s.fullName;
    return r;
  });
}

export function soldierRows(s, counters, now) {
  return counters.map((def) => {
    const cur = periodRange(def.period, def.createdAt, now, 0);
    const v = total(def.uid, s.uid, cur[0], cur[1]);
    let prevV = null;
    if (def.period !== 'TOTAL') {
      const prev = periodRange(def.period, def.createdAt, now, -1);
      prevV = total(def.uid, s.uid, prev[0], prev[1]);
    }
    const r = makeRow(def, s, v, prevV, `${def.name} (${periodLabel(def.period)})`);
    r.avatarText = def.name;
    return r;
  });
}

export function groupTotal(def, now) {
  const cur = periodRange(def.period, def.createdAt, now, 0);
  const map = totals(def.uid, cur[0], cur[1]);
  let sum = 0;
  for (const s of membersOf(def.uid)) {
    const v = map.get(s.uid) || 0;
    sum += def.kind === 1 ? (v > 0 ? 1 : 0) : v;
  }
  return sum;
}

// ------------------------------------------------------------------ soldiers
export function newSoldier(fields = {}) {
  return {
    uid: uuid(), fullName: '', personalNumber: '', phone: '', parentPhone: '', squad: '', status: 'PRESENT',
    shoeSize: '', uniformSize: '', allergies: '', exemptions: '', exemptionExpiry: 0, medTimes: '',
    weaponNumber: '', equipment: '', notes: '', updatedAt: 0, syncedAt: 0, ...fields,
  };
}
export function saveSoldier(s) {
  s.updatedAt = Date.now();
  const isNew = !idx.soldier.has(s.uid);
  if (isNew) data.soldiers.push(s);
  persist('soldiers', s);
  changed();
}
export function addSoldiers(list) {
  const now = Date.now();
  for (const s of list) { s.updatedAt = now; data.soldiers.push(s); persist('soldiers', s); }
  changed();
}
export function setStatusBulk(uids, status) {
  const now = Date.now();
  for (const uid of uids) {
    const s = idx.soldier.get(uid);
    if (s) { s.status = status; s.updatedAt = now; persist('soldiers', s); }
  }
  changed();
}
function addTombstone(type, uid) {
  const t = { key: `${type}_${uid}`, type, uid, deletedAt: Date.now() };
  data.tombstones = data.tombstones.filter((x) => x.key !== t.key);
  data.tombstones.push(t);
  persist('tombstones', t);
}
function dropTombstone(key) {
  if (data.tombstones.some((t) => t.key === key)) {
    data.tombstones = data.tombstones.filter((t) => t.key !== key);
    unpersist('tombstones', key);
  }
}
export function dropTombstoneKey(key) { dropTombstone(key); }

function rmSoldierLocal(uid) {
  data.soldiers = data.soldiers.filter((s) => s.uid !== uid);
  unpersist('soldiers', uid);
  data.entries = data.entries.filter((e) => { if (e.soldierUid === uid) { unpersist('entries', e.uid); return false; } return true; });
  data.members = data.members.filter((m) => { if (m.soldierUid === uid) { unpersist('members', m.key); return false; } return true; });
  data.duties = data.duties.filter((d) => { if (d.soldierUid === uid) { unpersist('duties', d.uid); return false; } return true; });
}
function rmCounterLocal(uid) {
  data.counters = data.counters.filter((c) => c.uid !== uid);
  unpersist('counters', uid);
  data.entries = data.entries.filter((e) => { if (e.counterUid === uid) { unpersist('entries', e.uid); return false; } return true; });
  data.members = data.members.filter((m) => { if (m.counterUid === uid) { unpersist('members', m.key); return false; } return true; });
}
export function deleteSoldier(uid) {
  addTombstone('soldier', uid);
  rmSoldierLocal(uid);
  changed();
}

// ------------------------------------------------------------------ counters
export function newCounter(fields = {}) {
  return {
    uid: uuid(), name: '', period: 'DAY', target: 0, unit: '', createdAt: 0, convertAt: 0, convertToUid: '',
    kind: 0, reasonMode: 0, forAll: false, updatedAt: 0, syncedAt: 0, ...fields,
  };
}
export function saveCounter(c) {
  c.updatedAt = Date.now();
  if (!c.createdAt) c.createdAt = c.updatedAt;
  if (!idx.counter.has(c.uid)) data.counters.push(c);
  persist('counters', c);
  changed();
}
export function counterByName(name) { return data.counters.find((c) => c.name === name); }
export function deleteCounter(uid) {
  addTombstone('counter', uid);
  rmCounterLocal(uid);
  changed();
}
export function addMembers(pairs) {
  const now = Date.now();
  for (const [counterUid, soldierUid] of pairs) {
    const key = memberKey(counterUid, soldierUid);
    if (idx.member.has(key) || data.members.some((m) => m.key === key)) continue;
    const m = { key, counterUid, soldierUid, updatedAt: now, syncedAt: 0 };
    data.members.push(m);
    persist('members', m);
    dropTombstone(`member_${key}`);
  }
  changed();
}
export function removeMember(counterUid, soldierUid) {
  const key = memberKey(counterUid, soldierUid);
  addTombstone('member', key);
  data.members = data.members.filter((m) => m.key !== key);
  unpersist('members', key);
  changed();
}
/** Puts every soldier into the counters marked "default for everyone". Returns true if anything changed. */
export function ensureDefaults() {
  const missing = [];
  for (const def of data.counters) {
    if (!def.forAll) continue;
    const have = new Set((idx.membersByCounter.get(def.uid) || []).map((m) => m.soldierUid));
    for (const s of data.soldiers) if (!have.has(s.uid)) missing.push([def.uid, s.uid]);
  }
  if (missing.length) addMembers(missing);
  return missing.length > 0;
}

// ------------------------------------------------------------------ entries + conversion
function insertEntry(e) {
  e.uid = e.uid || uuid();
  e.updatedAt = Date.now();
  e.syncedAt = 0;
  data.entries.push(e);
  persist('entries', e);
  let l = idx.entriesByCounter.get(e.counterUid);
  if (!l) { l = []; idx.entriesByCounter.set(e.counterUid, l); }
  l.push(e);
}

/** Saves an entry and applies the counter's conversion rule. Returns a message when a conversion happened. */
export function addEntry(e) {
  insertEntry(e);
  let msg = null;
  if (e.delta > 0) msg = convert(e);
  changed();
  return msg;
}
function convert(e) {
  const def = idx.counter.get(e.counterUid);
  if (!def || def.convertAt <= 0 || !def.convertToUid || def.convertToUid === def.uid) return null;
  const target = idx.counter.get(def.convertToUid);
  if (!target) return null;
  const r = periodRange(def.period, def.createdAt, e.createdAt, 0);
  let tot = total(def.uid, e.soldierUid, r[0], r[1]);
  let conversions = 0;
  while (tot >= def.convertAt) {
    insertEntry({
      counterUid: def.uid, soldierUid: e.soldierUid, delta: -def.convertAt,
      note: `הומר ל"${target.name}"`, recordedBy: e.recordedBy, createdAt: e.createdAt,
    });
    insertEntry({
      counterUid: target.uid, soldierUid: e.soldierUid, delta: 1,
      note: `הומר מ-${def.convertAt} ב"${def.name}"`, recordedBy: e.recordedBy, createdAt: e.createdAt,
    });
    tot -= def.convertAt;
    conversions++;
  }
  if (!conversions) return null;
  const key = memberKey(target.uid, e.soldierUid);
  if (!data.members.some((m) => m.key === key)) {
    const m = { key, counterUid: target.uid, soldierUid: e.soldierUid, updatedAt: Date.now(), syncedAt: 0 };
    data.members.push(m);
    persist('members', m);
    dropTombstone(`member_${key}`);
  }
  const s = idx.soldier.get(e.soldierUid);
  return `${s ? s.fullName : ''}: ${def.convertAt * conversions} ב"${def.name}" הומרו ל-${conversions} ב"${target.name}"`;
}
export function addEntries(list) {
  const msgs = [];
  for (const e of list) {
    insertEntry(e);
    if (e.delta > 0) { const m = convert(e); if (m) msgs.push(m); }
  }
  changed();
  return msgs;
}
export function deleteEntry(uid) {
  addTombstone('entry', uid);
  data.entries = data.entries.filter((e) => e.uid !== uid);
  unpersist('entries', uid);
  changed();
}

// ------------------------------------------------------------------ duties
export function addDuty(d) {
  d.uid = uuid();
  d.updatedAt = Date.now();
  d.syncedAt = 0;
  data.duties.push(d);
  persist('duties', d);
  changed();
}
export function deleteDuty(uid) {
  addTombstone('duty', uid);
  data.duties = data.duties.filter((d) => d.uid !== uid);
  unpersist('duties', uid);
  changed();
}

// ------------------------------------------------------------------ checklists (local only, like the Android app)
const DEFAULT_TEMPLATES = [
  ['מסדר בוקר', 'גילוח\nמדים נקיים\nמיטה מסודרת\nמימייה מלאה'],
  ['מסדר נשק', 'נשק נקי\nנשק פרוק\nמחסנית ריקה\nרצועה תקינה'],
  ['בדיקת ציוד ויציאה', 'צל"ם חתום\nמשקפת\nתחבושת אישית\nמימייה\nכובע'],
  ['מסדר רפואי', 'פטורים מעודכנים\nתרופות קבועות\nנעליים תקינות\nכוויות שמש'],
];
export function ensureSeed() {
  if (data.templates.length) return;
  for (const [name, items] of DEFAULT_TEMPLATES) {
    const t = { uid: uuid(), name, items };
    data.templates.push(t);
    persist('templates', t);
  }
}
export const templateItems = (t) => t.items.split('\n').map((x) => x.trim()).filter(Boolean);
export function addTemplate(name, items) {
  const t = { uid: uuid(), name, items };
  data.templates.push(t);
  persist('templates', t);
  changed(false);
}
export const templates = () => [...data.templates].sort((a, b) => a.name.localeCompare(b.name, 'he'));
export function addRun(run) {
  run.uid = uuid();
  data.runs.push(run);
  persist('runs', run);
  changed(false);
}
export const recentRuns = () => [...data.runs].sort((a, b) => b.runAt - a.runAt).slice(0, 30);

// ------------------------------------------------------------------ alerts + reports
export function alerts(soldiers) {
  const tomorrow = Date.now() + 24 * 3600 * 1000;
  return soldiers.filter((s) => s.exemptionExpiry > 0 && s.exemptionExpiry <= tomorrow)
    .map((s) => `פטור של ${s.fullName} מסתיים עד ${fmtDate(s.exemptionExpiry)}`);
}
export const headcount = (soldiers) => soldiers.filter((s) => onSite(s.status)).length;

function stamp() {
  return new Date().toLocaleString('he-IL', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false });
}
export function reportHeadcount(soldiers) {
  let sb = `*דוח מצב סד"כ*\n${stamp()}\n\nנוכחים: ${headcount(soldiers)} מתוך ${soldiers.length}\n\n`;
  const bySquad = new Map();
  for (const s of soldiers) {
    const k = s.squad || 'ללא כיתה';
    const v = bySquad.get(k) || [0, 0];
    v[1]++;
    if (onSite(s.status)) v[0]++;
    bySquad.set(k, v);
  }
  for (const [k, v] of bySquad) sb += `- ${k}: ${v[0]}/${v[1]}\n`;
  const away = soldiers.filter((s) => !onSite(s.status)).map((s) => `- ${s.fullName} (${statusLabel(s.status)})\n`).join('');
  if (away) sb += `\n*לא נוכחים*\n${away}`;
  return sb.trim();
}
export function reportCounter(def, now) {
  const rows = memberRows(def, membersOf(def.uid), now);
  let sb = `*${def.name}* (${periodLabel(def.period)})\n${rangeText(def, now)}\n`;
  if (!rows.length) sb += 'אין חיילים בספירה. לחץ "הוסף חיילים".';
  for (const r of rows) sb += `- ${r.title}: ${r.valueText}\n`;
  return sb.trim();
}
export const reportAllCounters = (now) => allCounters().map((c) => reportCounter(c, now)).join('\n\n').trim();
export function reportFull(soldiers) {
  let sb = reportHeadcount(soldiers);
  const counters = reportAllCounters(Date.now());
  if (counters) sb += `\n\n${counters}`;
  const al = alerts(soldiers);
  if (al.length) sb += `\n\n*חריגים*\n${al.map((a) => `- ${a}\n`).join('')}`;
  return sb.trim();
}

// ------------------------------------------------------------------ CSV import
export function parseCsv(text) {
  const out = [];
  const lines = text.split(/\r?\n/);
  let first = true;
  const col = (cols, i) => {
    if (i >= cols.length) return '';
    let v = cols[i].trim();
    if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1);
    return v;
  };
  const header = new Set(['שם', 'שם מלא', 'שם פרטי', 'name', 'full name']);
  for (let line of lines) {
    if (first && line.charCodeAt(0) === 0xFEFF) line = line.slice(1);
    if (!line.trim()) { first = false; continue; }
    const cols = line.split(/[,;\t]/);
    const isHeader = first && header.has(col(cols, 0).trim().toLowerCase());
    first = false;
    if (isHeader) continue;
    const name = col(cols, 0);
    if (!name) continue;
    const num = col(cols, 1);
    out.push(newSoldier({
      fullName: name, personalNumber: /^\d{7}$/.test(num) ? num : '', phone: col(cols, 2), squad: col(cols, 3),
    }));
  }
  return out;
}

// ------------------------------------------------------------------ wipe
export function wipeAll() {
  STORES.forEach((s) => { data[s] = []; });
  if (idb) STORES.forEach((s) => queue({ type: 'clear', store: s }));
  changed(false);
}

// ------------------------------------------------------------------ housekeeping
export function touchActive() { setPref('lastActive', Date.now()); }
export function housekeeping() {
  const days = Number(getPref('wipeDays')) || 0;
  const last = Number(getPref('lastActive')) || 0;
  if (days > 0 && last > 0 && Date.now() - last > days * 86400000) return true; // caller wipes + leaves sync
  return false;
}

// ------------------------------------------------------------------ PIN
const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
async function pinHash(salt, pin) {
  const bytes = new TextEncoder().encode(salt + pin);
  return b64(await crypto.subtle.digest('SHA-256', bytes));
}
export const hasPin = () => !!getPref('pinHash');
export async function setPin(pin) {
  const salt = b64(crypto.getRandomValues(new Uint8Array(16)));
  setPref('pinSalt', salt);
  setPref('pinHash', await pinHash(salt, pin));
}
export function clearPin() { setPref('pinSalt', ''); setPref('pinHash', ''); }
export async function checkPin(pin) { return !!getPref('pinHash') && (await pinHash(getPref('pinSalt'), pin)) === getPref('pinHash'); }

// ------------------------------------------------------------------ used by sync
export const _internals = {
  persist, unpersist, addTombstone, rmSoldierLocal, rmCounterLocal,
};
