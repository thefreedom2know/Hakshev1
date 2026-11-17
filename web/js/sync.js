// Sync with the shared unit, compatible with the Android app: same Firestore documents,
// same AES-256-GCM encryption, same name+password key derivation and "HK1:" join code.
// Uses the Firebase REST APIs directly (no SDK), so it also works from a plain static site.

import * as store from './store.js';
import { data, idx, uuid, memberKey, _internals } from './store.js';

// The Firebase project bundled in the Android app (assets/google-services.json).
export const DEFAULT_FB = {
  projectId: 'hakshev-26821',
  appId: '1:985499017315:android:99ef2d3ae793f9e873d856',
  apiKey: 'AIzaSyC4nHv7FbXLY8Ee_pzhs7McPOsCdhoiRl4',
};

const CFG_KEY = 'hk_sync';
const AUTH_KEY = 'hk_auth';
const enc = new TextEncoder();
const dec = new TextDecoder();

// ------------------------------------------------------------------ base64
export function toB64(bytes) {
  let s = '';
  const a = new Uint8Array(bytes);
  for (let i = 0; i < a.length; i += 0x8000) s += String.fromCharCode(...a.subarray(i, i + 0x8000));
  return btoa(s);
}
export function fromB64(str) {
  const norm = str.replace(/-/g, '+').replace(/_/g, '/');
  const padded = norm + '='.repeat((4 - (norm.length % 4)) % 4);
  const bin = atob(padded);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
const hex = (bytes) => [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');

// ------------------------------------------------------------------ config
export function loadCfg() {
  try { return JSON.parse(localStorage.getItem(CFG_KEY) || '{}'); } catch (e) { return {}; }
}
function saveCfg(cfg) { localStorage.setItem(CFG_KEY, JSON.stringify(cfg)); }
export const isJoined = () => { const c = loadCfg(); return !!(c.unitId && c.secret && c.projectId); };
export const lastSyncAt = () => loadCfg().lastSyncAt || 0;
export const lastError = () => loadCfg().lastError || '';

function patchCfg(p) { saveCfg({ ...loadCfg(), ...p }); }

/** Joins/creates a unit. A different unit than before: forget the pull position and resend everything. */
function adopt(unit) {
  const old = loadCfg();
  const next = { ...unit };
  if (old.unitId !== unit.unitId) {
    next.lastPull = '1970-01-01T00:00:00Z';
    next.repush = true;
  } else {
    next.lastPull = old.lastPull || '1970-01-01T00:00:00Z';
    next.repush = false;
  }
  next.lastSyncAt = 0;
  next.lastError = '';
  saveCfg(next);
}

export async function joinWithNamePassword(name, password) {
  const n = name.trim().toLowerCase();
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(`hakshev-unit\0${n}\0${password}`)));
  const unitId = hex(h.subarray(0, 16));
  const keyMat = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: enc.encode(`hakshev-salt\0${n}`), iterations: 120000 }, keyMat, 256);
  adopt({ ...DEFAULT_FB, unitId, secret: toB64(bits) });
}

export function createUnit() {
  const id = crypto.getRandomValues(new Uint8Array(16));
  const secret = crypto.getRandomValues(new Uint8Array(32));
  adopt({ ...DEFAULT_FB, unitId: hex(id), secret: toB64(secret) });
}

export function joinCode() {
  const c = loadCfg();
  const o = { p: c.projectId, a: c.appId, k: c.apiKey, u: c.unitId, s: c.secret };
  const b = toB64(enc.encode(JSON.stringify(o))).replace(/\+/g, '-').replace(/\//g, '_');
  return `HK1:${b}`;
}

export function joinWithCode(code) {
  code = code.trim();
  if (!code.startsWith('HK1:')) throw new Error('bad code');
  const o = JSON.parse(dec.decode(fromB64(code.slice(4))));
  const secret = fromB64(o.s);
  if (secret.length < 16 || String(o.u).length < 16) throw new Error('bad code');
  adopt({ projectId: o.p, appId: o.a, apiKey: o.k, unitId: o.u, secret: o.s });
}

export function leave() {
  localStorage.removeItem(CFG_KEY);
  localStorage.removeItem(AUTH_KEY);
}

// ------------------------------------------------------------------ crypto (AES-256-GCM, iv(12) + ciphertext + tag, base64)
async function aesKey(secretB64) {
  const raw = fromB64(secretB64);
  const k = new Uint8Array(32);
  k.set(raw.subarray(0, 32));
  return crypto.subtle.importKey('raw', k, 'AES-GCM', false, ['encrypt', 'decrypt']);
}
export async function encrypt(key, plain) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, tagLength: 128 }, key, enc.encode(plain)));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv);
  out.set(ct, 12);
  return toB64(out);
}
export async function decrypt(key, b64) {
  const blob = fromB64(b64);
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: blob.subarray(0, 12), tagLength: 128 }, key, blob.subarray(12));
  return dec.decode(pt);
}
export { aesKey };

// ------------------------------------------------------------------ Firebase REST
async function jsonFetch(url, opts) {
  const res = await fetch(url, opts);
  let body = null;
  try { body = await res.json(); } catch (e) { /* empty */ }
  if (!res.ok) {
    const msg = (body && (body.error && (body.error.message || body.error))) || `HTTP ${res.status}`;
    throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
  }
  return body;
}

async function idToken(cfg) {
  let auth = null;
  try { auth = JSON.parse(localStorage.getItem(AUTH_KEY) || 'null'); } catch (e) { /* ignore */ }
  if (auth && auth.apiKey === cfg.apiKey) {
    if (auth.exp > Date.now() + 60000) return auth.idToken;
    try {
      const r = await jsonFetch(`https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(cfg.apiKey)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `grant_type=refresh_token&refresh_token=${encodeURIComponent(auth.refreshToken)}`,
      });
      auth = { apiKey: cfg.apiKey, idToken: r.id_token, refreshToken: r.refresh_token, exp: Date.now() + Number(r.expires_in) * 1000 };
      localStorage.setItem(AUTH_KEY, JSON.stringify(auth));
      return auth.idToken;
    } catch (e) { /* fall through to a new anonymous sign-in */ }
  }
  const r = await jsonFetch(`https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${encodeURIComponent(cfg.apiKey)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ returnSecureToken: true }),
  });
  auth = { apiKey: cfg.apiKey, idToken: r.idToken, refreshToken: r.refreshToken, exp: Date.now() + Number(r.expiresIn) * 1000 };
  localStorage.setItem(AUTH_KEY, JSON.stringify(auth));
  return auth.idToken;
}

const dbRoot = (cfg) => `projects/${cfg.projectId}/databases/(default)/documents`;
const apiRoot = (cfg) => `https://firestore.googleapis.com/v1/${dbRoot(cfg)}`;

// ------------------------------------------------------------------ push
function opOf(cfg, type, uid, updatedAt, deleted, payload) {
  return {
    docId: `${type}_${uid}`,
    fields: {
      t: { stringValue: type },
      u: { integerValue: String(updatedAt) },
      d: { booleanValue: deleted },
      p: { stringValue: payload },
    },
  };
}

async function push(cfg, token, key) {
  const ops = [];
  const j = (o) => JSON.stringify(o);
  for (const s of data.soldiers) {
    if (s.updatedAt > s.syncedAt) {
      const at = s.updatedAt;
      const op = opOf(cfg, 'soldier', s.uid, at, false, await encrypt(key, j({
        name: s.fullName, num: s.personalNumber, phone: s.phone, pphone: s.parentPhone, squad: s.squad,
        status: s.status, shoe: s.shoeSize, uni: s.uniformSize, all: s.allergies, ex: s.exemptions,
        exExp: s.exemptionExpiry, med: s.medTimes, wpn: s.weaponNumber, eq: s.equipment, notes: s.notes,
      })));
      op.after = () => { s.syncedAt = at; _internals.persist('soldiers', s); };
      ops.push(op);
    }
  }
  for (const c of data.counters) {
    if (c.updatedAt > c.syncedAt) {
      const at = c.updatedAt;
      const target = c.convertToUid ? idx.counter.get(c.convertToUid) : null;
      const op = opOf(cfg, 'counter', c.uid, at, false, await encrypt(key, j({
        name: c.name, period: c.period, target: c.target, unit: c.unit, created: c.createdAt, at: c.convertAt,
        to: target ? target.uid : '', kind: c.kind, rm: c.reasonMode, fa: !!c.forAll,
      })));
      op.after = () => { c.syncedAt = at; _internals.persist('counters', c); };
      ops.push(op);
    }
  }
  for (const m of data.members) {
    if (m.updatedAt > m.syncedAt) {
      const at = m.updatedAt;
      if (!idx.counter.has(m.counterUid) || !idx.soldier.has(m.soldierUid)) continue;
      const op = opOf(cfg, 'member', m.key, at, false, await encrypt(key, j({ c: m.counterUid, s: m.soldierUid })));
      op.after = () => { m.syncedAt = at; _internals.persist('members', m); };
      ops.push(op);
    }
  }
  for (const e of data.entries) {
    if (e.updatedAt > e.syncedAt) {
      const at = e.updatedAt;
      if (!idx.counter.has(e.counterUid) || !idx.soldier.has(e.soldierUid)) continue;
      const op = opOf(cfg, 'entry', e.uid, at, false, await encrypt(key, j({
        c: e.counterUid, s: e.soldierUid, delta: e.delta, note: e.note, by: e.recordedBy, at: e.createdAt,
      })));
      op.after = () => { e.syncedAt = at; _internals.persist('entries', e); };
      ops.push(op);
    }
  }
  for (const d of data.duties) {
    if (d.updatedAt > d.syncedAt) {
      const at = d.updatedAt;
      if (!idx.soldier.has(d.soldierUid)) continue;
      const op = opOf(cfg, 'duty', d.uid, at, false, await encrypt(key, j({
        s: d.soldierUid, name: d.name, start: d.startTime, end: d.endTime,
      })));
      op.after = () => { d.syncedAt = at; _internals.persist('duties', d); };
      ops.push(op);
    }
  }
  for (const t of data.tombstones) {
    const op = opOf(cfg, t.type, t.uid, t.deletedAt, true, '');
    op.after = () => store.dropTombstoneKey(t.key);
    ops.push(op);
  }
  for (let i = 0; i < ops.length; i += 400) {
    const chunk = ops.slice(i, i + 400);
    const writes = chunk.map((o) => ({
      update: { name: `${dbRoot(cfg)}/units/${cfg.unitId}/records/${o.docId}`, fields: o.fields },
      updateTransforms: [{ fieldPath: 'ts', setToServerValue: 'REQUEST_TIME' }],
    }));
    await jsonFetch(`${apiRoot(cfg)}:commit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ writes }),
    });
    chunk.forEach((o) => o.after());
  }
  return ops.length;
}

// ------------------------------------------------------------------ pull
function tsKey(s) {
  // RFC3339 -> [seconds, nanos] so timestamps with different fraction lengths compare correctly
  const m = /^(.*?)(?:\.(\d+))?Z$/.exec(s);
  return [Math.floor(Date.parse(`${m[1]}Z`) / 1000), m[2] ? Number(m[2].padEnd(9, '0').slice(0, 9)) : 0];
}
const tsGreater = (a, b) => { const x = tsKey(a); const y = tsKey(b); return x[0] > y[0] || (x[0] === y[0] && x[1] > y[1]); };
const str = (f, k) => (f[k] && f[k].stringValue !== undefined ? f[k].stringValue : undefined);

class WrongCode extends Error {}

async function pull(cfg, token, key) {
  const last = cfg.lastPull || '1970-01-01T00:00:00Z';
  const res = await jsonFetch(`${apiRoot(cfg)}/units/${cfg.unitId}:runQuery`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: 'records' }],
        where: { fieldFilter: { field: { fieldPath: 'ts' }, op: 'GREATER_THAN', value: { timestampValue: last } } },
        orderBy: [{ field: { fieldPath: 'ts' }, direction: 'ASCENDING' }],
      },
    }),
  });
  const remotes = [];
  let max = last;
  for (const row of res || []) {
    const doc = row.document;
    if (!doc) continue;
    const f = doc.fields || {};
    const ts = f.ts && f.ts.timestampValue;
    const type = str(f, 't');
    const u = f.u && f.u.integerValue;
    if (!ts || !type || u === undefined) continue;
    if (tsGreater(ts, max)) max = ts;
    const id = doc.name.split('/').pop();
    const r = {
      type,
      uid: id.startsWith(`${type}_`) ? id.slice(type.length + 1) : id,
      updatedAt: Number(u),
      deleted: !!(f.d && f.d.booleanValue),
      json: null,
    };
    if (!r.deleted) {
      try { r.json = JSON.parse(await decrypt(key, str(f, 'p') || '')); } catch (e) { throw new WrongCode(); }
    }
    remotes.push(r);
  }
  if (!remotes.length) return 0;
  const applied = apply(remotes);
  patchCfg({ lastPull: max });
  return applied;
}

function blockedByTombstone(type, uid, remoteUpdatedAt) {
  const k = `${type}_${uid}`;
  const t = data.tombstones.find((x) => x.key === k);
  if (!t) return false;
  if (t.deletedAt >= remoteUpdatedAt) return true;
  store.dropTombstoneKey(k);
  return false;
}

const ORDER = ['soldier', 'counter', 'member', 'entry', 'duty'];

function apply(remotes) {
  let n = 0;
  const convertLinks = [];
  const { persist } = _internals;
  store.reindex();
  for (const type of ORDER) {
    for (const r of remotes) {
      if (r.deleted || r.type !== type) continue;
      if (blockedByTombstone(type, r.uid, r.updatedAt)) continue;
      const j = r.json;
      if (type === 'soldier') {
        const cur = idx.soldier.get(r.uid);
        if (cur && cur.updatedAt >= r.updatedAt) continue;
        const s = cur || store.newSoldier({ uid: r.uid });
        Object.assign(s, {
          fullName: j.name || '', personalNumber: j.num || '', phone: j.phone || '', parentPhone: j.pphone || '',
          squad: j.squad || '', status: j.status || 'PRESENT', shoeSize: j.shoe || '', uniformSize: j.uni || '',
          allergies: j.all || '', exemptions: j.ex || '', exemptionExpiry: j.exExp || 0, medTimes: j.med || '',
          weaponNumber: j.wpn || '', equipment: j.eq || '', notes: j.notes || '',
          updatedAt: r.updatedAt, syncedAt: r.updatedAt,
        });
        if (!cur) { data.soldiers.push(s); idx.soldier.set(s.uid, s); }
        persist('soldiers', s);
        n++;
      } else if (type === 'counter') {
        const cur = idx.counter.get(r.uid);
        if (cur && cur.updatedAt >= r.updatedAt) continue;
        const c = cur || store.newCounter({ uid: r.uid });
        Object.assign(c, {
          name: j.name || '', period: j.period || 'DAY', target: j.target || 0, unit: j.unit || '',
          createdAt: j.created || 0, convertAt: j.at || 0, kind: j.kind || 0, reasonMode: j.rm || 0, forAll: !!j.fa,
          updatedAt: r.updatedAt, syncedAt: r.updatedAt,
        });
        if (!cur) { data.counters.push(c); idx.counter.set(c.uid, c); }
        persist('counters', c);
        convertLinks.push([c, j.to || '']);
        n++;
      } else if (type === 'member') {
        if (!idx.counter.has(j.c) || !idx.soldier.has(j.s)) continue;
        const key = memberKey(j.c, j.s);
        const cur = idx.member.get(key) || data.members.find((m) => m.key === key);
        if (!cur) {
          const m = { key, counterUid: j.c, soldierUid: j.s, updatedAt: r.updatedAt, syncedAt: r.updatedAt };
          data.members.push(m);
          idx.member.set(key, m);
          persist('members', m);
          n++;
        } else if (cur.updatedAt < r.updatedAt) {
          cur.syncedAt = r.updatedAt;
          persist('members', cur);
        }
      } else if (type === 'entry') {
        if (data.entries.some((e) => e.uid === r.uid)) continue;
        if (!idx.counter.has(j.c) || !idx.soldier.has(j.s)) continue;
        const e = {
          uid: r.uid, counterUid: j.c, soldierUid: j.s, delta: j.delta || 0, note: j.note || '',
          recordedBy: j.by || '', createdAt: j.at || r.updatedAt, updatedAt: r.updatedAt, syncedAt: r.updatedAt,
        };
        data.entries.push(e);
        persist('entries', e);
        n++;
      } else if (type === 'duty') {
        if (data.duties.some((d) => d.uid === r.uid)) continue;
        if (!idx.soldier.has(j.s)) continue;
        const d = {
          uid: r.uid, soldierUid: j.s, name: j.name || '', startTime: j.start || 0, endTime: j.end || 0,
          updatedAt: r.updatedAt, syncedAt: r.updatedAt,
        };
        data.duties.push(d);
        persist('duties', d);
        n++;
      }
    }
  }
  // Conversion targets can point at a counter that arrived later in the same batch.
  for (const [c, toUid] of convertLinks) {
    c.convertToUid = toUid && idx.counter.has(toUid) ? toUid : '';
    persist('counters', c);
  }
  // Deletions last.
  for (const r of remotes) {
    if (!r.deleted) continue;
    if (r.type === 'soldier') {
      const cur = idx.soldier.get(r.uid);
      if (!cur) continue;
      if (cur.updatedAt <= r.updatedAt) { _internals.rmSoldierLocal(r.uid); store.reindex(); n++; } else { cur.syncedAt = 0; persist('soldiers', cur); }
    } else if (r.type === 'counter') {
      const cur = idx.counter.get(r.uid);
      if (!cur) continue;
      if (cur.updatedAt <= r.updatedAt) { _internals.rmCounterLocal(r.uid); store.reindex(); n++; } else { cur.syncedAt = 0; persist('counters', cur); }
    } else if (r.type === 'entry') {
      if (data.entries.some((e) => e.uid === r.uid)) {
        data.entries = data.entries.filter((e) => e.uid !== r.uid);
        _internals.unpersist('entries', r.uid);
        n++;
      }
    } else if (r.type === 'duty') {
      if (data.duties.some((d) => d.uid === r.uid)) {
        data.duties = data.duties.filter((d) => d.uid !== r.uid);
        _internals.unpersist('duties', r.uid);
        n++;
      }
    } else if (r.type === 'member') {
      const i = r.uid.indexOf('_');
      if (i < 0) continue;
      const key = r.uid;
      const cur = data.members.find((m) => m.key === key);
      if (cur && cur.updatedAt <= r.updatedAt) {
        data.members = data.members.filter((m) => m.key !== key);
        _internals.unpersist('members', key);
        n++;
      }
    }
  }
  return n;
}

function repushAll() {
  const now = Date.now();
  const lists = [data.soldiers, data.counters, data.members, data.entries, data.duties];
  const names = ['soldiers', 'counters', 'members', 'entries', 'duties'];
  lists.forEach((l, i) => l.forEach((o) => {
    o.syncedAt = 0;
    if (!o.updatedAt) o.updatedAt = now;
    _internals.persist(names[i], o);
  }));
}

// ------------------------------------------------------------------ run
let running = false;
let again = false;

/** Returns {ok, message, pushed, pulled}. */
export async function syncNow() {
  if (!isJoined()) return { ok: false, message: 'לא מחובר ליחידה משותפת' };
  if (running) { again = true; return { ok: true, message: 'סנכרון כבר רץ…' }; }
  running = true;
  let result;
  try {
    let cfg = loadCfg();
    const key = await aesKey(cfg.secret);
    const token = await idToken(cfg);
    if (cfg.repush) { repushAll(); patchCfg({ repush: false }); cfg = loadCfg(); }
    const pushed = await push(cfg, token, key);
    const pulled = await pull(cfg, token, key);
    patchCfg({ lastSyncAt: Date.now(), lastError: '' });
    if (pulled > 0) store.changed(false);
    result = { ok: true, message: `הסנכרון הסתיים: נשלחו ${pushed}, התקבלו ${pulled}`, pushed, pulled };
  } catch (e) {
    const message = e instanceof WrongCode
      ? 'לא ניתן לפענח את הנתונים – ייתכן שהקוד שגוי.'
      : `הסנכרון נכשל: ${e && e.message ? e.message : e}`;
    patchCfg({ lastError: message });
    result = { ok: false, message };
  } finally {
    running = false;
  }
  if (again && result.ok) { again = false; syncNow(); }
  again = false;
  return result;
}

// debounce after local changes + periodic while the app is visible
let debounce = null;
export function requestSync() {
  if (!isJoined()) return;
  clearTimeout(debounce);
  debounce = setTimeout(() => syncNow(), 3000);
}
let poll = null;
export function startPolling(onResult) {
  clearInterval(poll);
  poll = setInterval(async () => {
    if (document.visibilityState !== 'visible' || !isJoined()) return;
    const r = await syncNow();
    if (onResult) onResult(r);
  }, 15000);
}
export { uuid };
