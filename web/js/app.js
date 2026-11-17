import * as S from './store.js';
import * as Sync from './sync.js';

// ------------------------------------------------------------------ tiny DOM helpers
function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'style') el.style.cssText = v;
    else if (k === 'value' || k === 'checked' || k === 'disabled' || k === 'selected') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  const add = (kid) => {
    if (kid === null || kid === undefined || kid === false) return;
    if (Array.isArray(kid)) kid.forEach(add);
    else el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  };
  kids.forEach(add);
  return el;
}
const $ = (sel, root = document) => root.querySelector(sel);
const hashOf = (s) => { let x = 0; const t = String(s); for (let i = 0; i < t.length; i++) x = (x * 31 + t.charCodeAt(i)) >>> 0; return x; };

function avatar(text, key) {
  const t = String(text || '?').trim();
  const hue = hashOf(key || t) % 360;
  return h('span', { class: 'avatar', style: `background:hsl(${hue} 38% 36%)` }, [...t][0] || '?');
}

let toastTimer;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 3500);
}

// ------------------------------------------------------------------ modal
function modal({ title, message, body, buttons, dismiss = true }) {
  const root = $('#modal-root');
  const bg = h('div', { class: 'modal-bg' });
  const close = () => { bg.remove(); };
  const btns = (buttons || [{ label: 'סגור' }]).map((b) =>
    h('button', {
      class: `btn ${b.kind || 'text'}`,
      type: 'button',
      onclick: async () => { const r = b.onClick ? await b.onClick() : undefined; if (r !== false) close(); },
    }, b.label));
  const panel = h('div', { class: 'modal', role: 'dialog' },
    title ? h('h3', {}, title) : null,
    message ? h('p', { class: 'modal-msg' }, message) : null,
    body ? h('div', { class: 'modal-body' }, body) : null,
    h('div', { class: 'actions' }, btns));
  bg.append(panel);
  if (dismiss) bg.addEventListener('mousedown', (e) => { if (e.target === bg) close(); });
  root.append(bg);
  const first = panel.querySelector('input,textarea,select');
  if (first) setTimeout(() => first.focus(), 50);
  return { close, panel };
}
function confirmDialog(message, onYes, yesLabel = 'כן') {
  modal({ message, buttons: [{ label: 'ביטול' }, { label: yesLabel, kind: 'primary', onClick: onYes }] });
}
function choose(title, labels, onPick) {
  const m = modal({
    title,
    body: h('div', { class: 'choices' }, labels.map((l, i) =>
      h('button', { class: 'choice', type: 'button', onclick: () => { m.close(); onPick(i); } }, l))),
    buttons: [{ label: 'ביטול' }],
  });
}

// form helpers
function field(label, { value = '', type = 'text', multiline = false, inputmode, maxlength, rows = 2, placeholder } = {}) {
  const input = multiline
    ? h('textarea', { rows, value, placeholder })
    : h('input', { type, value, inputmode, maxlength, placeholder, autocomplete: 'off' });
  if (multiline) input.value = value;
  return { el: h('label', { class: 'field' }, h('span', {}, label), input), input };
}
function select(options, value) {
  const sel = h('select', {}, options.map(([v, l]) => h('option', { value: v, selected: String(v) === String(value) }, l)));
  sel.value = String(value);
  return sel;
}
const labeled = (label, el) => h('label', { class: 'field' }, h('span', {}, label), el);
const toInt = (v, d = 0) => { const n = parseInt(String(v).trim(), 10); return Number.isFinite(n) ? n : d; };

// ------------------------------------------------------------------ reports
function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text).then(() => true, () => false);
  const ta = h('textarea', { style: 'position:fixed;opacity:0' });
  ta.value = text;
  document.body.append(ta);
  ta.select();
  let ok = false;
  try { ok = document.execCommand('copy'); } catch (e) { /* ignore */ }
  ta.remove();
  return Promise.resolve(ok);
}
async function showReport(text) {
  if (await copyText(text)) toast('הדוח הועתק');
  const buttons = [{ label: 'סגור' }];
  if (navigator.share) {
    buttons.push({ label: 'שתף', onClick: () => { navigator.share({ text }).catch(() => {}); return false; } });
  }
  buttons.push({ label: 'וואטסאפ', kind: 'primary', onClick: () => { window.open(`https://wa.me/?text=${encodeURIComponent(text)}`, '_blank', 'noopener'); return false; } });
  modal({ body: h('pre', { class: 'report' }, text), buttons });
}
function chooseReport() {
  choose('העתק דוח לוואטסאפ', ['דוח מלא (סד"כ + ספירות)', 'דוח מצב סד"כ', 'דוח ספירות'], (i) => {
    const list = S.allSoldiers();
    let text;
    if (i === 0) text = S.reportFull(list);
    else if (i === 1) text = S.reportHeadcount(list);
    else text = S.reportAllCounters(Date.now());
    showReport(text || 'עדיין אין ספירות. לחץ "ספירה חדשה" ובחר הצעה או הגדר בעצמך.');
  });
}

// ------------------------------------------------------------------ entries dialogs
function mkEntry(counterUid, soldierUid, delta, note = '') {
  return { counterUid, soldierUid, delta, note, recordedBy: S.getPref('commander'), createdAt: Date.now() };
}
function saveEntries(list) {
  const msgs = S.addEntries(list);
  if (msgs.length) toast(msgs.join('\n'));
}
function askNote(title, mode, onNote) {
  if (mode === 0) { onNote(''); return; }
  const f = field(mode === 2 ? 'סיבה (חובה)' : 'הערה / סיבה', { multiline: true });
  modal({
    title,
    body: f.el,
    buttons: [{ label: 'ביטול' }, {
      label: 'שמור', kind: 'primary',
      onClick: () => {
        const text = f.input.value.trim();
        if (mode === 2 && !text) { f.input.classList.add('error'); toast('צריך לכתוב סיבה'); return false; }
        onNote(text);
        return true;
      },
    }],
  });
}
function quickDelta(def, s, delta) {
  askNote(`${def.name} — ${s.fullName}`, def.reasonMode, (note) => saveEntries([mkEntry(def.uid, s.uid, delta, note)]));
}
function addAmount(def, s) {
  if (def.kind === 1) { toggleDone(def, s); return; }
  const amount = field('כמות (אפשר שלילית)', { value: '1', type: 'number', inputmode: 'numeric' });
  const note = field(def.reasonMode === 2 ? 'סיבה (חובה)' : 'הערה / סיבה', { multiline: true });
  modal({
    title: `${def.name} — ${s.fullName}`,
    body: [amount.el, note.el],
    buttons: [{ label: 'ביטול' }, {
      label: 'שמור', kind: 'primary',
      onClick: () => {
        const a = toInt(amount.input.value, 0);
        if (a === 0) return false;
        const n = note.input.value.trim();
        if (def.reasonMode === 2 && !n) { toast('צריך לכתוב סיבה'); return false; }
        saveEntries([mkEntry(def.uid, s.uid, a, n)]);
        return true;
      },
    }],
  });
}
function toggleDone(def, s) {
  const r = S.periodRange(def.period, def.createdAt, Date.now(), 0);
  const value = S.total(def.uid, s.uid, r[0], r[1]);
  if (value <= 0) {
    askNote(`${def.name} — ${s.fullName}`, Math.max(1, def.reasonMode),
      (note) => saveEntries([mkEntry(def.uid, s.uid, 1, note)]));
  } else {
    confirmDialog(`לבטל את הסימון "בוצע" של ${s.fullName}?`,
      () => saveEntries([mkEntry(def.uid, s.uid, -value, 'הסימון בוטל')]));
  }
}
function history(def, s) {
  const entries = S.entriesOf(def.uid, s.uid);
  if (!entries.length) { toast('אין רישומים עדיין'); return; }
  const rows = entries.map((e) => h('button', {
    class: 'hist-row', type: 'button',
    onclick: () => confirmDialog('למחוק את הרישום?', () => S.deleteEntry(e.uid)),
  }, h('b', {}, `${e.delta > 0 ? '+' : ''}${e.delta}`), e.note ? `  ${e.note}` : '',
  h('small', {}, `${e.recordedBy} · ${S.fmtDateTime(e.createdAt)}`)));
  modal({
    title: `${def.name} — ${s.fullName}`,
    body: h('div', { class: 'hist' }, rows),
    buttons: [
      { label: 'סגור' },
      {
        label: 'הסר מהספירה',
        onClick: () => {
          if (def.forAll) { toast('זו ספירת ברירת מחדל: כל החיילים נכללים בה. כדי להסיר, בטל את "ברירת מחדל" בעריכת הספירה.'); return false; }
          S.removeMember(def.uid, s.uid);
          return true;
        },
      },
    ],
  });
}

// ------------------------------------------------------------------ counter row
function countRow(row, def, s) {
  const longPress = (el, fn) => {
    let t;
    el.addEventListener('pointerdown', () => { t = setTimeout(() => { t = null; fn(); }, 600); });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) => el.addEventListener(ev, () => { if (t) clearTimeout(t); }));
    el.addEventListener('contextmenu', (e) => { e.preventDefault(); });
  };
  const info = h('div', { class: 'row-info', role: 'button', onclick: () => addAmount(def, s) },
    h('div', { class: 'row-title' }, row.title),
    row.sub ? h('div', { class: 'row-sub' }, row.sub) : null);
  longPress(info, () => history(def, s));
  const histBtn = h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'היסטוריה', onclick: () => history(def, s) }, '⋯');
  const controls = row.check
    ? h('button', {
      class: `btn check ${row.reached ? 'done' : ''}`, type: 'button', onclick: () => toggleDone(def, s),
    }, row.valueText)
    : [
      h('button', { class: 'step', type: 'button', 'aria-label': 'הפחת', onclick: () => quickDelta(def, s, -1) }, '−'),
      h('span', { class: `value ${row.reached ? 'reached' : ''}` }, row.valueText),
      h('button', { class: 'step', type: 'button', 'aria-label': 'הוסף', onclick: () => quickDelta(def, s, 1) }, '+'),
    ];
  return h('div', { class: 'count-row' }, avatar(row.avatarText, row.avatarText), info, h('div', { class: 'controls' }, controls), histBtn);
}

// ------------------------------------------------------------------ counter form
const PRESETS = [
  'מימיות|DAY|4|מימיות', 'שעות ביציאה|TOTAL|0|שעות', 'שמירות נוספות|WEEK|0|', 'מבחן ת.א|TOTAL|0||1|1',
  'מבחנים שעברו|TOTAL|0|', 'סש"ט|TOTAL|0||0|1', 'עונשים והערות משמעת|TOTAL|0||0|2', 'נוכחות בשיעורים|WEEK|0|',
];
function newCounterFlow(onCreated) {
  const names = PRESETS.map((p) => p.split('|')[0]).concat(['ספירה אחרת (הגדרה עצמית)']);
  choose('הצעות לספירה', names, (i) => {
    const base = S.newCounter();
    if (i < PRESETS.length) {
      const f = PRESETS[i].split('|');
      base.name = f[0]; base.period = f[1]; base.target = toInt(f[2]); base.unit = f[3];
      if (f.length > 4) base.kind = toInt(f[4]);
      if (f.length > 5) base.reasonMode = toInt(f[5]);
    }
    counterForm(base, true, onCreated, null);
  });
}
function counterForm(def, isNew, onSaved, onDeleted) {
  const target = def.convertToUid ? S.counter(def.convertToUid) : null;
  const name = field('שם הספירה', { value: def.name });
  const unit = field('יחידה (למשל: שעות). לא חובה', { value: def.unit });
  const tgt = field('יעד לכל חייל בתקופה (0 = בלי יעד)', { value: def.target > 0 ? String(def.target) : '', type: 'number', inputmode: 'numeric' });
  const period = select(S.PERIODS, def.period);
  const kind = select([[0, 'ספירה (מספר לכל חייל)'], [1, 'רשימת ביצוע (בוצע / לא בוצע)']], def.kind);
  const reason = select([[0, 'בלי סיבה'], [1, 'לבקש סיבה (אפשר לדלג)'], [2, 'סיבה חובה']], def.reasonMode);
  const cAt = field('המרה: כמה בספירה הזו הופכים לעונש אחר (0 = בלי המרה)', { value: def.convertAt > 0 ? String(def.convertAt) : '', type: 'number', inputmode: 'numeric' });
  const cTo = field('שם הספירה / העונש שמקבלים בהמרה (נוצר אוטומטית אם אין)', { value: target ? target.name : '' });
  const cbDefault = h('input', { type: 'checkbox', checked: def.forAll });
  const cbAll = h('input', { type: 'checkbox' });
  const numeric = h('div', {}, unit.el, tgt.el, cAt.el, cTo.el);
  const syncKind = () => { numeric.style.display = kind.value === '1' ? 'none' : ''; };
  kind.addEventListener('change', syncKind);
  syncKind();
  const buttons = [{ label: 'ביטול' }];
  if (!isNew && onDeleted) {
    buttons.push({
      label: 'מחק', kind: 'danger',
      onClick: () => {
        confirmDialog(`למחוק את הספירה "${def.name}" ואת כל הרישומים שלה? אי אפשר לשחזר.`, () => { S.deleteCounter(def.uid); onDeleted(); });
        return true;
      },
    });
  }
  buttons.push({
    label: 'שמור', kind: 'primary',
    onClick: () => {
      const n = name.input.value.trim();
      if (!n) { toast('חובה להזין שם'); return false; }
      def.name = n;
      def.period = period.value;
      def.kind = toInt(kind.value);
      def.reasonMode = toInt(reason.value);
      def.forAll = cbDefault.checked;
      def.unit = unit.input.value.trim();
      def.target = Math.max(0, toInt(tgt.input.value));
      if (def.kind === 1) { def.target = 0; def.unit = ''; }
      let convertAt = def.kind === 1 ? 0 : Math.max(0, toInt(cAt.input.value));
      const convertTo = cTo.input.value.trim();
      def.convertAt = 0;
      def.convertToUid = '';
      if (convertAt > 0 && convertTo && convertTo !== def.name) {
        let t = S.counterByName(convertTo);
        if (!t) { t = S.newCounter({ name: convertTo, period: 'TOTAL' }); S.saveCounter(t); }
        def.convertAt = convertAt;
        def.convertToUid = t.uid;
      }
      S.saveCounter(def);
      if (isNew && cbAll.checked) S.addMembers(S.data.soldiers.map((s) => [def.uid, s.uid]));
      S.ensureDefaults();
      if (onSaved) onSaved(def.uid);
      return true;
    },
  });
  modal({
    title: isNew ? 'ספירה חדשה' : 'ערוך הגדרות',
    body: h('div', {}, name.el, labeled('כל כמה זמן הספירה מתאפסת', period), labeled('סוג', kind), labeled('סיבה', reason), numeric,
      h('label', { class: 'check-line' }, cbDefault, h('span', {}, 'ברירת מחדל לכל חייל: כל החיילים (גם חדשים) נכללים בספירה אוטומטית')),
      isNew ? h('label', { class: 'check-line' }, cbAll, h('span', {}, 'הוסף את כל הפלוגה לספירה')) : null),
    buttons,
  });
}

// ------------------------------------------------------------------ views
const view = { current: null };
const filters = { query: '', squad: null, status: 0 };
let editRoster = false;

const header = (title, back) => h('div', { class: 'page-head' },
  back ? h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'חזרה', onclick: () => history_back() }, '→') : null,
  h('h1', {}, title));
const history_back = () => { if (window.history.length > 1) window.history.back(); else location.hash = '#/'; };

// ---- roster (main screen)
function viewRoster(edit) {
  const search = h('input', { type: 'search', placeholder: 'חיפוש לפי שם או מספר אישי', class: 'search', value: filters.query, autocomplete: 'off' });
  const squadSel = h('select', { 'aria-label': 'כיתה' });
  const statusSel = select([[0, 'הכל'], ...S.STATUSES.map(([, l], i) => [i + 1, l])], filters.status);
  const count = h('div', { class: 'muted small' });
  const list = h('div', { class: 'list' });
  const greeting = h('div', { class: 'hero-line' });
  const present = h('div', { class: 'hero-sub', role: 'button', onclick: () => { location.hash = '#/overview'; } });
  const syncLine = h('div', { class: 'sync-line' });

  const rebuildSquads = (all) => {
    const squads = [...S.squadList()];
    for (const s of all) if (s.squad && !squads.includes(s.squad)) squads.push(s.squad);
    let selected = filters.squad;
    if (selected === null) selected = !edit && S.getPref('mySquad') && squads.includes(S.getPref('mySquad')) ? S.getPref('mySquad') : '';
    filters.squad = squads.includes(selected) ? selected : '';
    squadSel.replaceChildren(h('option', { value: '' }, 'הכל'), ...squads.map((q) => h('option', { value: q }, q)));
    squadSel.value = filters.squad;
  };

  const update = () => {
    S.ensureDefaults();
    const all = S.allSoldiers();
    if (!edit) {
      greeting.textContent = `שלום, ${S.getPref('commander')}`;
      present.textContent = `נוכחים ${S.headcount(all)} מתוך ${all.length} · לחץ לסקירה`;
      syncLine.textContent = syncStateLine();
    }
    rebuildSquads(all);
    const q = filters.query.toLowerCase();
    const st = toInt(filters.status);
    const shown = all.filter((s) => {
      if (q) { if (!s.fullName.toLowerCase().includes(q) && !s.personalNumber.includes(q)) return false; } else if (filters.squad && s.squad !== filters.squad) return false;
      if (st > 0 && S.STATUSES[st - 1][0] !== s.status) return false;
      return true;
    });
    view.shown = shown;
    count.textContent = all.length === 0 && !edit ? 'אין עדיין חיילים. לחץ על "הוסף חייל".' : `מוצגים ${shown.length} מתוך ${all.length}`;
    list.replaceChildren(...shown.map((s) => {
      const badges = h('span', { class: 'badges' },
        s.allergies ? h('span', { class: 'badge alert' }, 'אלרגיה') : null,
        s.exemptions ? h('span', { class: 'badge warn' }, 'פטור') : null);
      return h('div', { class: 'soldier-row' },
        h('div', { class: 'row-main', role: 'button', onclick: () => { location.hash = `#/soldier/${s.uid}${edit ? '/edit' : ''}`; } },
          avatar(s.fullName, s.squad || s.fullName),
          h('div', { class: 'row-info' },
            h('div', { class: 'row-title' }, s.fullName),
            h('div', { class: 'row-sub' }, [s.squad, s.personalNumber].filter(Boolean).join(' · ') || ' '),
            badges)),
        h('button', {
          class: `status-chip st-${s.status}`, type: 'button',
          onclick: () => choose(s.fullName, S.STATUSES.map((x) => x[1]), (i) => S.setStatusBulk([s.uid], S.STATUSES[i][0])),
        }, S.statusLabel(s.status)));
    }));
  };

  search.addEventListener('input', () => { filters.query = search.value.trim(); update(); });
  squadSel.addEventListener('change', () => { filters.squad = squadSel.value; if (!edit) S.setPref('mySquad', filters.squad); update(); });
  statusSel.addEventListener('change', () => { filters.status = statusSel.value; update(); });

  const el = h('div', { class: 'page' },
    edit ? header('עריכת נתוני חיילים', true)
      : h('div', { class: 'hero small' }, greeting, present, syncLine),
    search,
    h('div', { class: 'filters' }, labeled('כיתה', squadSel), labeled('סטטוס', statusSel)),
    count,
    list,
    h('div', { class: 'bar' },
      h('button', {
        class: 'btn tonal', type: 'button',
        onclick: () => {
          const shown = view.shown || [];
          if (!shown.length) { toast('אין חיילים ברשימה'); return; }
          confirmDialog(`לסמן ${shown.length} חיילים כנוכחים?`, () => S.setStatusBulk(shown.map((s) => s.uid), 'PRESENT'));
        },
      }, 'סמן נוכחות לכולם'),
      h('button', { class: 'btn primary', type: 'button', onclick: () => { location.hash = '#/soldier/new'; } }, '+ הוסף חייל')));
  return { el, update };
}

// ---- soldier (view = counters, edit = form)
function viewSoldier(uid, edit) {
  const s = uid === 'new' ? null : S.soldier(uid);
  if (uid !== 'new' && !s) { location.hash = '#/'; return { el: h('div') }; }
  return edit || !s ? soldierEdit(s) : soldierCounters(s);
}
function soldierCounters(s) {
  const banner = h('div', { class: 'banner alert' });
  const head = h('div', { class: 'soldier-head' });
  const list = h('div', { class: 'list' });
  const empty = h('div', { class: 'muted pad' }, 'החייל עדיין לא משתתף באף ספירה.');
  const update = () => {
    const cur = S.soldier(s.uid);
    if (!cur) { location.hash = '#/'; return; }
    S.ensureDefaults();
    head.replaceChildren(avatar(cur.fullName, cur.squad || cur.fullName), h('div', {},
      h('h1', {}, cur.fullName), h('div', { class: 'muted' }, [cur.squad, S.statusLabel(cur.status)].filter(Boolean).join(' · '))));
    banner.style.display = cur.allergies ? '' : 'none';
    banner.textContent = `⚠ אלרגיה: ${cur.allergies}`;
    const defs = S.countersOf(cur.uid);
    const rows = S.soldierRows(cur, defs, Date.now());
    list.replaceChildren(...rows.map((r) => countRow(r, S.counter(r.counterUid), cur)));
    empty.style.display = rows.length ? 'none' : '';
  };
  const el = h('div', { class: 'page' },
    h('div', { class: 'page-head' }, h('button', { class: 'icon-btn', type: 'button', onclick: history_back, 'aria-label': 'חזרה' }, '→'), head),
    banner, h('div', { class: 'section' }, 'ספירות של החייל'), list, empty,
    h('div', { class: 'bar' },
      h('button', { class: 'btn tonal', type: 'button', onclick: () => { location.hash = `#/soldier/${s.uid}/edit`; } }, 'עריכת פרטים'),
      h('button', {
        class: 'btn primary', type: 'button',
        onclick: () => {
          const mine = new Set(S.countersOf(s.uid).map((c) => c.uid));
          const avail = S.allCounters().filter((c) => !mine.has(c.uid));
          choose('הוסף ספירה לחייל', [...avail.map((c) => `${c.name} (${S.periodLabel(c.period)})`), 'ספירה חדשה'], (i) => {
            if (i < avail.length) S.addMembers([[avail[i].uid, s.uid]]);
            else newCounterFlow((cuid) => S.addMembers([[cuid, s.uid]]));
          });
        },
      }, 'הוסף ספירה לחייל')));
  return { el, update };
}
function soldierEdit(existing) {
  const isNew = !existing;
  const s = existing ? { ...existing } : S.newSoldier();
  const name = field('שם מלא', { value: s.fullName });
  const num = field('מספר אישי', { value: s.personalNumber, inputmode: 'numeric', maxlength: 7 });
  const phone = field('טלפון', { value: s.phone, type: 'tel' });
  const pphone = field('טלפון הורים / איש קשר לחירום', { value: s.parentPhone, type: 'tel' });
  const shoe = field('מידת נעליים', { value: s.shoeSize });
  const uni = field('מידת מדים', { value: s.uniformSize });
  const all = field('אלרגיות (תיאור קצר בלבד)', { value: s.allergies, multiline: true });
  const ex = field('פטורים (למשל: פטור עמידה)', { value: s.exemptions, multiline: true });
  const med = field('זמני נטילת תרופות', { value: s.medTimes });
  const wpn = field('מספר אישי של הנשק', { value: s.weaponNumber });
  const eq = field('ציוד חתום (משקפת, אמר"ל, ציוד מיוחד)', { value: s.equipment, multiline: true });
  const notes = field('הערות הערכה', { value: s.notes, multiline: true });
  const squads = [...S.squadList()];
  if (s.squad && !squads.includes(s.squad)) squads.push(s.squad);
  const squad = select(squads.map((q) => [q, q]), s.squad || squads[0] || '');
  const status = select(S.STATUSES, s.status);
  const exDate = h('input', { type: 'date' });
  if (s.exemptionExpiry) { const d = new Date(s.exemptionExpiry); exDate.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; }
  const save = () => {
    const n = name.input.value.trim();
    if (!n) { name.input.classList.add('error'); toast('חובה להזין שם'); return; }
    const p = num.input.value.trim();
    if (p && !/^\d{7}$/.test(p)) { num.input.classList.add('error'); toast('מספר אישי חייב להיות בדיוק 7 ספרות'); return; }
    Object.assign(s, {
      fullName: n, personalNumber: p, phone: phone.input.value.trim(), parentPhone: pphone.input.value.trim(),
      shoeSize: shoe.input.value.trim(), uniformSize: uni.input.value.trim(), allergies: all.input.value.trim(),
      exemptions: ex.input.value.trim(), medTimes: med.input.value.trim(), weaponNumber: wpn.input.value.trim(),
      equipment: eq.input.value.trim(), notes: notes.input.value.trim(), squad: squad.value, status: status.value,
    });
    if (exDate.value) { const [y, m, d] = exDate.value.split('-').map(Number); s.exemptionExpiry = new Date(y, m - 1, d, 23, 59, 0).getTime(); } else s.exemptionExpiry = 0;
    if (existing) Object.assign(existing, s);
    S.saveSoldier(existing || s);
    S.ensureDefaults();
    history_back();
  };
  const el = h('div', { class: 'page' },
    header(isNew ? 'חייל חדש' : s.fullName, true),
    h('div', { class: 'section' }, 'פרטים אישיים'), name.el, num.el, phone.el, pphone.el,
    labeled('כיתה', squad), labeled('סטטוס', status), shoe.el, uni.el,
    h('div', { class: 'section' }, 'רפואה ופטורים'), all.el, ex.el,
    labeled('תאריך תפוגה לפטור (לא חובה)', exDate), med.el,
    h('div', { class: 'section' }, 'ציוד וצל"ם'), wpn.el, eq.el, notes.el,
    h('p', { class: 'muted small' }, 'אל תזינו מידע מסווג (מיקומים מדויקים, תכניות אימון, מספרי ציוד מסווג). תיאורים קצרים בלבד, לא אבחנות ולא מספרי זהות.'),
    h('div', { class: 'bar' },
      !isNew ? h('button', {
        class: 'btn danger', type: 'button',
        onclick: () => confirmDialog(`למחוק את ${existing.fullName} ואת כל הרישומים שלו? אי אפשר לשחזר.`, () => { S.deleteSoldier(existing.uid); location.hash = '#/edit-roster'; }),
      }, 'מחק חייל') : null,
      h('button', { class: 'btn primary', type: 'button', onclick: save }, 'שמור')));
  if (isNew) setTimeout(() => name.input.focus(), 80);
  return { el };
}

// ---- counters
function viewCounters() {
  const list = h('div', { class: 'list' });
  const empty = h('div', { class: 'muted pad' }, 'עדיין אין ספירות. לחץ "ספירה חדשה" ובחר הצעה או הגדר בעצמך.');
  const update = () => {
    const now = Date.now();
    const defs = S.allCounters();
    list.replaceChildren(...defs.map((c) => h('div', {
      class: 'card counter-card', role: 'button', onclick: () => { location.hash = `#/counter/${c.uid}`; },
    },
    h('div', { class: 'card-title' }, c.name, h('span', { class: 'pill' }, S.periodLabel(c.period))),
    h('div', { class: 'muted small' }, [`${S.membersOf(c.uid).length} משתתפים`,
      c.kind === 1 ? 'סימון בוצע / לא בוצע' : (c.target > 0 ? `יעד לחייל: ${c.unit ? `${c.target} ${c.unit}` : c.target}` : '')].filter(Boolean).join(' · ')),
    h('div', { class: 'card-total' }, 'סה"כ בתקופה: ', h('b', {}, S.groupTotalText(c, S.groupTotal(c, now)))))));
    empty.style.display = defs.length ? 'none' : '';
  };
  const el = h('div', { class: 'page' }, header('ספירות'),
    h('p', { class: 'muted small' }, 'ספירה היא כל דבר שרוצים לספור: מימיות, שעות ביציאה, מבחנים שעברו ועוד. כל ספירה נקבעת לפי תקופה, ולמי שמשתתף בה.'),
    list, empty,
    h('div', { class: 'bar' }, h('button', {
      class: 'btn primary', type: 'button', onclick: () => newCounterFlow((uid) => { location.hash = `#/counter/${uid}`; }),
    }, '+ ספירה חדשה')));
  return { el, update };
}

function viewCounter(uid) {
  if (!S.counter(uid)) { location.hash = '#/counters'; return { el: h('div') }; }
  let query = '';
  const title = h('h1', {});
  const range = h('div', { class: 'muted small pre' });
  const list = h('div', { class: 'list' });
  const empty = h('div', { class: 'muted pad' }, 'אין חיילים בספירה. לחץ "הוסף חיילים".');
  const search = h('input', { type: 'search', class: 'search', placeholder: 'חיפוש לפי שם או מספר אישי', autocomplete: 'off' });
  let rows = [];
  const draw = () => {
    const def = S.counter(uid);
    const shown = rows.filter((r) => {
      if (!query) return true;
      const s = S.soldier(r.soldierUid);
      return s && (s.fullName.toLowerCase().includes(query) || s.personalNumber.includes(query));
    });
    list.replaceChildren(...shown.map((r) => countRow(r, def, S.soldier(r.soldierUid))));
    empty.style.display = rows.length ? 'none' : '';
  };
  const update = () => {
    const def = S.counter(uid);
    if (!def) { location.hash = '#/counters'; return; }
    S.ensureDefaults();
    const now = Date.now();
    title.textContent = `${def.name} (${S.periodLabel(def.period)})`;
    const target = def.convertToUid ? S.counter(def.convertToUid) : null;
    range.textContent = `התקופה הנוכחית: ${S.rangeText(def, now)}${target && def.convertAt > 0 ? `\nהמרה: ${def.convertAt} כאן הופכים ל-1 ב"${target.name}"` : ''}`;
    rows = S.memberRows(def, S.membersOf(uid), now);
    draw();
  };
  search.addEventListener('input', () => { query = search.value.trim().toLowerCase(); draw(); });
  const el = h('div', { class: 'page' },
    h('div', { class: 'page-head' }, h('button', { class: 'icon-btn', type: 'button', onclick: () => { location.hash = '#/counters'; }, 'aria-label': 'חזרה' }, '→'), title),
    range, search, list, empty,
    h('div', { class: 'bar wrap' },
      h('button', {
        class: 'btn tonal', type: 'button',
        onclick: () => {
          const def = S.counter(uid);
          const members = S.membersOf(uid);
          if (!members.length) { toast('אין חיילים בספירה. לחץ "הוסף חיילים".'); return; }
          confirmDialog(`להוסיף 1 לכל ${members.length} החיילים בספירה?`, () => {
            const now = Date.now();
            const todo = members.filter((s) => {
              if (def.kind !== 1) return true;
              const r = S.periodRange(def.period, def.createdAt, now, 0);
              return S.total(def.uid, s.uid, r[0], r[1]) <= 0;
            });
            saveEntries(todo.map((s) => mkEntry(uid, s.uid, 1)));
          });
        },
      }, '+1 לכולם'),
      h('button', { class: 'btn tonal', type: 'button', onclick: () => addMembersFlow(uid) }, 'הוסף חיילים'),
      h('button', { class: 'btn tonal', type: 'button', onclick: () => showReport(S.reportCounter(S.counter(uid), Date.now())) }, 'העתק דוח ספירה'),
      h('button', { class: 'btn text', type: 'button', onclick: () => counterForm(S.counter(uid), false, null, () => { location.hash = '#/counters'; }) }, 'ערוך הגדרות')));
  return { el, update };
}
function addMembersFlow(counterUid) {
  const inside = new Set(S.membersOf(counterUid).map((s) => s.uid));
  const candidates = S.allSoldiers().filter((s) => !inside.has(s.uid));
  if (!candidates.length) { toast('כל החיילים כבר בספירה'); return; }
  const bySquad = new Map();
  for (const s of candidates) { if (!s.squad) continue; if (!bySquad.has(s.squad)) bySquad.set(s.squad, []); bySquad.get(s.squad).push(s); }
  const groups = [candidates, ...bySquad.values()];
  const labels = [`כל הפלוגה (מי שעוד לא בספירה: ${candidates.length})`, ...[...bySquad].map(([k, v]) => `כל ${k} (${v.length})`), 'בחירה ידנית…'];
  choose('הוסף חיילים', labels, (i) => {
    if (i < groups.length) { S.addMembers(groups[i].map((s) => [counterUid, s.uid])); return; }
    const checks = candidates.map((s) => h('label', { class: 'check-line' }, h('input', { type: 'checkbox', value: s.uid }), h('span', {}, s.fullName)));
    modal({
      title: 'הוסף חיילים', body: h('div', { class: 'checks' }, checks),
      buttons: [{ label: 'ביטול' }, {
        label: 'שמור', kind: 'primary',
        onClick: () => { S.addMembers(checks.map((c) => c.querySelector('input')).filter((x) => x.checked).map((x) => [counterUid, x.value])); },
      }],
    });
  });
}

// ---- duties
const REST_MS = 6 * 3600 * 1000;
function viewDuties() {
  const fair = h('div', { class: 'card muted small pre' });
  const list = h('div', { class: 'list' });
  const update = () => {
    const now = Date.now();
    const soldiers = S.allSoldiers();
    const week = S.data.duties.filter((d) => d.startTime >= now - 7 * 86400000);
    const counts = new Map();
    for (const d of week) counts.set(d.soldierUid, (counts.get(d.soldierUid) || 0) + 1);
    const sorted = [...soldiers].sort((a, b) => (counts.get(a.uid) || 0) - (counts.get(b.uid) || 0));
    fair.textContent = `הוגנות (משמרות ב-7 ימים אחרונים, מהמעט למרובה).\n${sorted.map((s) => `${s.fullName}: ${counts.get(s.uid) || 0}`).join('   ')}`;
    const duties = S.data.duties.filter((d) => d.endTime >= now - 12 * 3600 * 1000).sort((a, b) => a.startTime - b.startTime);
    list.replaceChildren(...(duties.length ? duties.map((d) => {
      const s = S.soldier(d.soldierUid);
      return h('div', { class: 'card duty' },
        h('div', {}, h('b', {}, `${d.name} — ${s ? s.fullName : '?'}`), h('div', { class: 'muted small' }, `${S.fmtDateTime(d.startTime)} ← ${S.fmtDateTime(d.endTime)}`)),
        h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'מחק', onclick: () => confirmDialog('למחוק את השיבוץ?', () => S.deleteDuty(d.uid)) }, '✕'));
    }) : [h('div', { class: 'muted pad' }, 'אין שיבוצים קרובים')]));
  };
  const add = () => {
    const soldiers = S.allSoldiers();
    if (!soldiers.length) { toast('אין חיילים ברשימה'); return; }
    const who = select(soldiers.map((s) => [s.uid, s.fullName]), soldiers[0].uid);
    const names = ['ש"ג', 'פטרול', 'שמירת מאהל', 'מטבח', 'רס"ר'];
    const what = select(names.map((n) => [n, n]), names[0]);
    const nextHour = (new Date().getHours() + 1) % 24;
    const time = h('input', { type: 'time', value: `${String(nextHour).padStart(2, '0')}:00` });
    const tomorrow = h('input', { type: 'checkbox' });
    const hours = field('משך המשמרת (שעות)', { value: '4', type: 'number', inputmode: 'numeric' });
    modal({
      title: 'שבץ שמירה / תורנות',
      body: [labeled('חייל', who), labeled('משמרת', what), labeled('שעת התחלה', time),
        h('label', { class: 'check-line' }, tomorrow, h('span', {}, 'מחר')), hours.el],
      buttons: [{ label: 'ביטול' }, {
        label: 'שמור', kind: 'primary',
        onClick: () => {
          const [hh, mm] = (time.value || '00:00').split(':').map(Number);
          const start = new Date();
          start.setHours(hh, mm, 0, 0);
          if (tomorrow.checked) start.setDate(start.getDate() + 1);
          const duty = { soldierUid: who.value, name: what.value, startTime: +start, endTime: +start + Math.max(1, toInt(hours.input.value, 4)) * 3600000 };
          const conflict = S.data.duties.some((o) => o.soldierUid === duty.soldierUid && duty.startTime < o.endTime + REST_MS && o.startTime < duty.endTime + REST_MS);
          if (!conflict) { S.addDuty(duty); return true; }
          const s = S.soldier(duty.soldierUid);
          modal({
            title: 'בעיית מנוחה',
            message: `ל-${s.fullName} יש משמרת סמוכה, והמנוחה בין המשמרות קצרה מ-6 שעות רצופות. לשבץ בכל זאת?`,
            buttons: [{ label: 'ביטול' }, { label: 'שבץ בכל זאת', kind: 'primary', onClick: () => { S.addDuty(duty); } }],
          });
          return true;
        },
      }],
    });
  };
  const el = h('div', { class: 'page' }, header('שמירות ותורנויות'), fair, list,
    h('div', { class: 'bar' }, h('button', { class: 'btn primary', type: 'button', onclick: add }, '+ שבץ שמירה / תורנות')));
  return { el, update };
}

// ---- inspections
const insp = { running: false, current: null, soldiers: [], failures: new Map() };
function viewInspections() {
  const list = h('div', { class: 'list' });
  const tplSel = h('select', {});
  const hint = h('div', { class: 'muted small' });
  const idle = h('div', { class: 'bar wrap' });
  const run = h('div', { class: 'bar wrap' });
  const draw = () => {
    const tpls = S.templates();
    const keep = tplSel.value;
    tplSel.replaceChildren(...tpls.map((t) => h('option', { value: t.uid }, t.name)));
    if (tpls.some((t) => t.uid === keep)) tplSel.value = keep;
    tplSel.disabled = insp.running;
    idle.style.display = insp.running ? 'none' : '';
    run.style.display = insp.running ? '' : 'none';
    if (insp.running) {
      hint.textContent = 'כולם תקינים כברירת מחדל. לחיצה על חייל: סימון חריגים. "תקין" מחזיר אותו למצב תקין.';
      list.replaceChildren(...insp.soldiers.map((s) => {
        const f = insp.failures.get(s.uid);
        return h('div', { class: 'card insp-row' },
          h('div', { class: 'row-info', role: 'button', onclick: () => editFailures(s, draw) },
            h('b', {}, s.fullName), h('div', { class: f ? 'bad' : 'good' }, f ? `✗ ${f}` : '✓ תקין')),
          f ? h('button', { class: 'btn text', type: 'button', onclick: () => { insp.failures.delete(s.uid); draw(); } }, 'תקין') : null);
      }));
    } else {
      hint.textContent = 'מסדרים אחרונים';
      const runs = S.recentRuns();
      list.replaceChildren(...(runs.length ? runs.map((r) => h('div', { class: 'card pre' },
        h('b', {}, `${r.templateName} — ${S.fmtDateTime(r.runAt)}`), `\nתקינים: ${r.okCount} מתוך ${r.total}`, r.details ? `\n${r.details}` : ''))
        : [h('div', { class: 'muted pad' }, 'עדיין לא בוצעו מסדרים')]));
    }
  };
  idle.append(
    h('button', { class: 'btn primary', type: 'button', onclick: () => startRun(tplSel.value, draw) }, 'התחל מסדר'),
    h('button', { class: 'btn tonal', type: 'button', onclick: () => editTemplate(null) }, 'תבנית חדשה'),
    h('button', { class: 'btn tonal', type: 'button', onclick: () => editTemplate(S.templates().find((t) => t.uid === tplSel.value)) }, 'שכפל תבנית'));
  run.append(
    h('button', { class: 'btn tonal', type: 'button', onclick: () => { insp.failures.clear(); draw(); } }, 'תקין לכולם'),
    h('button', { class: 'btn primary', type: 'button', onclick: () => finishRun(draw) }, 'סיים ושמור'));
  const el = h('div', { class: 'page' }, header('מסדרים ורשימות בדיקה'), labeled('תבנית', tplSel), hint, list, idle, run);
  return { el, update: () => { S.ensureSeed(); draw(); } };
}
function startRun(tplUid, redraw) {
  const t = S.templates().find((x) => x.uid === tplUid);
  if (!t) return;
  insp.current = t;
  insp.soldiers = S.allSoldiers().filter((s) => s.status !== 'LEAVE');
  if (!insp.soldiers.length) { toast('אין חיילים ברשימה'); return; }
  insp.failures = new Map();
  insp.running = true;
  redraw();
}
function editFailures(s, redraw) {
  const items = S.templateItems(insp.current);
  const existing = insp.failures.get(s.uid) || '';
  const checks = items.map((it) => h('label', { class: 'check-line' },
    h('input', { type: 'checkbox', value: it, checked: existing.includes(it) }), h('span', {}, it)));
  modal({
    title: s.fullName, body: h('div', { class: 'checks' }, checks),
    buttons: [{ label: 'ביטול' }, {
      label: 'שמור', kind: 'primary',
      onClick: () => {
        const picked = checks.map((c) => c.querySelector('input')).filter((x) => x.checked).map((x) => x.value);
        if (picked.length) insp.failures.set(s.uid, picked.join(', ')); else insp.failures.delete(s.uid);
        redraw();
      },
    }],
  });
}
function finishRun(redraw) {
  const details = insp.soldiers.filter((s) => insp.failures.has(s.uid)).map((s) => `• ${s.fullName}: ${insp.failures.get(s.uid)}`).join('\n');
  S.addRun({ templateName: insp.current.name, runAt: Date.now(), total: insp.soldiers.length, okCount: insp.soldiers.length - insp.failures.size, details });
  insp.running = false;
  insp.failures = new Map();
  redraw();
}
function editTemplate(base) {
  const name = field('שם התבנית', { value: base ? `${base.name} (עותק)` : '' });
  const items = field('פריטי בדיקה (כל פריט בשורה נפרדת)', { value: base ? base.items : '', multiline: true, rows: 6 });
  modal({
    title: base ? 'שכפל תבנית' : 'תבנית חדשה', body: [name.el, items.el],
    buttons: [{ label: 'ביטול' }, {
      label: 'שמור', kind: 'primary',
      onClick: () => { const n = name.input.value.trim(); if (!n) return false; S.addTemplate(n, items.input.value.trim()); return true; },
    }],
  });
}

// ---- overview (dashboard)
function viewOverview() {
  const hero = h('div', { class: 'hero' });
  const alertsBox = h('div', { class: 'card pre' });
  const countersBox = h('div', { class: 'card pre' });
  const update = () => {
    const soldiers = S.allSoldiers();
    const defs = S.allCounters();
    const present = S.headcount(soldiers);
    const al = S.alerts(soldiers);
    const now = Date.now();
    hero.replaceChildren(
      h('div', { class: 'hero-line' }, `שלום, ${S.getPref('commander')}`),
      h('div', { class: 'hero-sub' }, new Date().toLocaleDateString('he-IL', { dateStyle: 'full' })),
      h('div', { class: 'hero-num' }, h('span', {}, `${present}/${soldiers.length}`), ' נוכחים'),
      h('div', { class: 'progress' }, h('div', { style: `width:${soldiers.length ? Math.round(100 * present / soldiers.length) : 0}%` })),
      h('div', { class: 'hero-sub' }, `${defs.length} ספירות · ${al.length} חריגים`));
    alertsBox.textContent = al.length ? al.map((a) => `• ${a}`).join('\n') : 'אין חריגים כרגע';
    countersBox.textContent = defs.length
      ? defs.map((c) => `• ${c.name} (${S.periodLabel(c.period)}): ${S.groupTotalText(c, S.groupTotal(c, now))} · ${S.membersOf(c.uid).length} חיילים`).join('\n')
      : 'עדיין אין ספירות. לחץ "ספירה חדשה" ובחר הצעה או הגדר בעצמך.';
  };
  const tile = (label, fn) => h('button', { class: 'tile', type: 'button', onclick: fn }, label);
  const el = h('div', { class: 'page' },
    h('div', { class: 'page-head' }, h('button', { class: 'icon-btn', type: 'button', onclick: () => { location.hash = '#/'; }, 'aria-label': 'חזרה' }, '→'), h('h1', {}, 'תמונת מצב')),
    hero,
    h('div', { class: 'section' }, 'חריגים'), alertsBox,
    h('div', { class: 'section' }, 'ספירות'), countersBox,
    h('div', { class: 'section' }, 'פעולות מהירות'),
    h('div', { class: 'tiles' },
      tile('דוח לוואטסאפ', chooseReport),
      tile('מסדר חדש', () => { location.hash = '#/inspections'; }),
      tile('ספירות', () => { location.hash = '#/counters'; }),
      tile('חיילים', () => { location.hash = '#/'; }),
      tile('שמירות', () => { location.hash = '#/duties'; }),
      tile('הגדרות', () => { location.hash = '#/settings'; })));
  return { el, update };
}

// ---- settings
let deferredInstall = null;
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferredInstall = e; });

function syncStateLine() {
  if (!Sync.isJoined()) return 'סנכרון: כבוי';
  if (Sync.lastError()) return 'סנכרון: נכשל, העבודה נשמרת במכשיר';
  const last = Sync.lastSyncAt();
  return last ? `סנכרון: עודכן ב-${new Date(last).toLocaleTimeString('he-IL', { hour: '2-digit', minute: '2-digit' })}` : 'סנכרון: ממתין';
}

function viewSettings() {
  const commander = field('שם המפקד (נרשם על כל רישום בספירות)', { value: S.getPref('commander') });
  const squads = field('כיתות (מופרדות בפסיק)', { value: S.squadList().join(', ') });
  const wipe = field('מחיקה אוטומטית אחרי כמה ימי אי-שימוש (0 = כבוי)', { value: String(S.getPref('wipeDays') || 0), type: 'number', inputmode: 'numeric' });
  const pinState = h('div', { class: 'muted' });
  const syncBox = h('div', {});
  const file = h('input', { type: 'file', accept: '.csv,text/csv,text/plain', style: 'display:none' });
  file.addEventListener('change', async () => {
    const f = file.files[0];
    if (!f) return;
    try {
      const parsed = S.parseCsv(await f.text());
      S.addSoldiers(parsed);
      toast(`יובאו ${parsed.length} חיילים`);
    } catch (e) { toast('הייבוא נכשל'); }
    file.value = '';
  });
  const updatePin = () => { pinState.textContent = S.hasPin() ? 'נעילת קוד PIN פעילה' : 'אין נעילת קוד PIN'; };

  const drawSync = () => {
    const joined = Sync.isJoined();
    const last = Sync.lastSyncAt();
    const err = Sync.lastError();
    const info = joined
      ? `הנתונים נשמרים ב-Firebase ומוצפנים לפני שהם יוצאים מהמכשיר. מפקדים אחרים מצטרפים עם אותם שם יחידה וסיסמה, או עם קוד הצטרפות.\nסנכרון אחרון: ${last ? S.fmtDateTime(last) : 'עדיין לא'}${err ? `\n${err}` : ''}`
      : 'לא מחובר ליחידה משותפת. הנתונים נשארים רק במכשיר הזה. כדי לעבוד עם האפליקציה באנדרואיד ובאייפון על אותם נתונים, הצטרפו לאותה יחידה בכל המכשירים.';
    const busy = (btn, fn) => async () => { btn.disabled = true; try { await fn(); } finally { btn.disabled = false; } };
    const joinBtn = h('button', { class: 'btn primary', type: 'button', onclick: () => joinDialog(drawSync) }, joined ? 'הצטרף ליחידה אחרת' : 'הצטרף ליחידה (שם + סיסמה)');
    const codeBtn = h('button', { class: 'btn tonal', type: 'button', onclick: () => codeDialog(drawSync) }, 'יש לי קוד הצטרפות');
    const nowBtn = h('button', { class: 'btn tonal', type: 'button' }, 'סנכרן עכשיו');
    nowBtn.onclick = busy(nowBtn, async () => { toast('מסנכרן…'); const r = await Sync.syncNow(); toast(r.message); drawSync(); });
    syncBox.replaceChildren(
      h('p', { class: 'muted pre' }, info),
      h('div', { class: 'bar wrap static' }, joinBtn, codeBtn,
        joined ? nowBtn : null,
        joined ? h('button', { class: 'btn tonal', type: 'button', onclick: showCode }, 'הצג קוד') : null,
        !joined ? h('button', {
          class: 'btn tonal', type: 'button',
          onclick: () => { Sync.createUnit(); drawSync(); Sync.syncNow().then((r) => { toast(r.message); drawSync(); }); showCode(); },
        }, 'יחידה חדשה פרטית') : null,
        joined ? h('button', {
          class: 'btn danger', type: 'button',
          onclick: () => confirmDialog('לעזוב את היחידה המשותפת? הנתונים שבמכשיר נשארים, אבל הם יפסיקו להתעדכן.', () => { Sync.leave(); toast('עזבת את היחידה המשותפת'); drawSync(); }),
        }, 'עזוב את היחידה המשותפת') : null));
  };

  const themeRadios = [['system', 'לפי הגדרות המכשיר'], ['day', 'יום'], ['night', 'כהה'], ['red', 'לילה אדום (שומר על ראיית לילה)']]
    .map(([v, l]) => h('label', { class: 'check-line' },
      h('input', { type: 'radio', name: 'theme', value: v, checked: S.getPref('theme') === v, onchange: () => { S.setPref('theme', v); applyTheme(); } }), h('span', {}, l)));

  const ua = navigator.userAgent;
  const isIos = /iPhone|iPad|iPod/.test(ua);
  const installHint = isIos
    ? 'באייפון: פתחו בספארי ← כפתור השיתוף ← "הוסף למסך הבית".'
    : 'באנדרואיד: בכרום ← תפריט ⋮ ← "התקן אפליקציה" (או "הוסף למסך הבית").';
  const installBtn = h('button', { class: 'btn primary', type: 'button', style: deferredInstall ? '' : 'display:none', onclick: async () => { if (deferredInstall) { deferredInstall.prompt(); deferredInstall = null; installBtn.style.display = 'none'; } } }, 'התקן כאפליקציה');

  const el = h('div', { class: 'page' }, header('הגדרות'),
    h('div', { class: 'section' }, 'מבנה היחידה'), commander.el, squads.el,
    h('div', { class: 'bar wrap static' },
      h('button', {
        class: 'btn primary', type: 'button',
        onclick: () => { S.setPref('commander', commander.input.value.trim() || 'מפקד'); S.setPref('squads', squads.input.value.replace(/،/g, ',')); toast('נשמר'); },
      }, 'שמור'),
      h('button', { class: 'btn tonal', type: 'button', onclick: () => { location.hash = '#/edit-roster'; } }, 'עריכת נתוני חיילים'),
      h('button', { class: 'btn tonal', type: 'button', onclick: () => file.click() }, 'ייבוא חיילים מקובץ CSV')),
    file,
    h('p', { class: 'muted small' }, 'עמודות: שם מלא, מספר אישי, טלפון, כיתה. מאקסל: שמור בשם ← CSV UTF-8.'),
    h('div', { class: 'section' }, 'סנכרון בין מפקדים ומכשירים'), syncBox,
    h('div', { class: 'section' }, 'תצוגה'), h('div', { class: 'radios' }, themeRadios),
    h('div', { class: 'section' }, 'אבטחה'), pinState,
    h('div', { class: 'bar wrap static' },
      h('button', {
        class: 'btn tonal', type: 'button',
        onclick: () => {
          const pin = field('קוד PIN (לפחות 4 ספרות)', { type: 'password', inputmode: 'numeric' });
          modal({
            title: 'קבע קוד PIN', body: pin.el,
            buttons: [{ label: 'ביטול' }, {
              label: 'שמור', kind: 'primary',
              onClick: async () => {
                const v = pin.input.value.trim();
                if (v.length < 4) { toast('הקוד קצר מדי'); return false; }
                await S.setPin(v); lock.unlocked = true; updatePin(); return true;
              },
            }],
          });
        },
      }, 'קבע קוד PIN'),
      h('button', { class: 'btn tonal', type: 'button', onclick: () => { S.clearPin(); updatePin(); } }, 'בטל קוד PIN')),
    h('p', { class: 'muted small' }, 'האפליקציה תינעל אחרי 30 שניות ברקע. הנתונים נשמרים בדפדפן של המכשיר הזה.'),
    wipe.el,
    h('div', { class: 'bar wrap static' },
      h('button', { class: 'btn tonal', type: 'button', onclick: () => { S.setPref('wipeDays', Math.max(0, toInt(wipe.input.value))); toast('נשמר'); } }, 'שמור'),
      h('button', {
        class: 'btn danger', type: 'button',
        onclick: () => confirmDialog('למחוק את כל החיילים והרישומים מהמכשיר? אי אפשר לשחזר.', () => { S.wipeAll(); Sync.leave(); toast('הנתונים נמחקו'); drawSync(); }),
      }, 'מחק את כל הנתונים')),
    h('div', { class: 'section' }, 'התקנה'), h('p', { class: 'muted' }, installHint), installBtn);
  updatePin();
  drawSync();
  return { el, update: null };
}
function showCode() {
  if (!Sync.isJoined()) return;
  const code = Sync.joinCode();
  const ta = h('textarea', { rows: 5, readonly: true, class: 'mono' });
  ta.value = code;
  modal({
    title: 'קוד הצטרפות',
    message: 'שלחו את הקוד רק למפקדים של היחידה. מי שמחזיק בו יכול לקרוא ולשנות את כל הנתונים.',
    body: ta,
    buttons: [{ label: 'סגור' }, { label: 'העתק', kind: 'primary', onClick: async () => { if (await copyText(code)) toast('הקוד הועתק'); return false; } }],
  });
  setTimeout(() => ta.select(), 80);
}
function joinDialog(redraw) {
  const name = field('שם היחידה');
  const pass = field('סיסמה (לפחות 6 תווים)', { type: 'password' });
  modal({
    title: 'הצטרף ליחידה',
    message: 'כל המפקדים מזינים את אותו שם יחידה ואותה סיסמה, ורואים את אותם נתונים — באנדרואיד, באייפון ובאתר. הסיסמה מצפינה את הנתונים: בחרו סיסמה שקשה לנחש ושמרו אותה.',
    body: [name.el, pass.el],
    buttons: [{ label: 'ביטול' }, {
      label: 'הצטרף', kind: 'primary',
      onClick: async () => {
        if (!name.input.value.trim()) { toast('חובה להזין שם'); return false; }
        if (pass.input.value.length < 6) { toast('הסיסמה חייבת להיות לפחות 6 תווים'); return false; }
        try {
          await Sync.joinWithNamePassword(name.input.value, pass.input.value);
          redraw();
          toast('מסנכרן…');
          Sync.syncNow().then((r) => { toast(r.message); redraw(); });
          return true;
        } catch (e) { toast('ההצטרפות נכשלה'); return false; }
      },
    }],
  });
}
function codeDialog(redraw) {
  const ta = h('textarea', { rows: 4, class: 'mono', placeholder: 'HK1:…' });
  modal({
    title: 'הצטרף עם קוד', message: 'הדביקו כאן את קוד ההצטרפות שקיבלתם ממפקד אחר.', body: ta,
    buttons: [{ label: 'ביטול' }, {
      label: 'שמור', kind: 'primary',
      onClick: () => {
        try { Sync.joinWithCode(ta.value); } catch (e) { toast('הקוד לא תקין.'); return false; }
        redraw();
        toast('מסנכרן…');
        Sync.syncNow().then((r) => { toast(r.message); redraw(); });
        return true;
      },
    }],
  });
}

// ------------------------------------------------------------------ theme + lock
function applyTheme() {
  document.documentElement.dataset.theme = S.getPref('theme');
  const meta = $('meta[name=theme-color]');
  const t = S.getPref('theme');
  const dark = t === 'night' || t === 'red' || (t === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
  meta.content = t === 'red' ? '#000000' : dark ? '#101412' : '#F3F4F1';
}
const lock = { unlocked: false, hiddenAt: 0 };
function showLock() {
  if (!S.hasPin()) return;
  const root = $('#lock');
  if (!root.hidden) return;
  const pin = h('input', { type: 'password', inputmode: 'numeric', class: 'pin', placeholder: '••••', autocomplete: 'off' });
  const err = h('div', { class: 'bad' });
  const go = async () => {
    if (await S.checkPin(pin.value.trim())) { lock.unlocked = true; root.hidden = true; root.replaceChildren(); } else { err.textContent = 'קוד שגוי'; pin.value = ''; }
  };
  pin.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
  root.replaceChildren(h('div', { class: 'lock-box' }, h('h2', {}, 'הקשב'), h('p', {}, 'הזן קוד PIN'), pin, err, h('button', { class: 'btn primary', type: 'button', onclick: go }, 'פתח')));
  root.hidden = false;
  setTimeout(() => pin.focus(), 50);
}

// ------------------------------------------------------------------ router
const TAB_OF = { '': 'roster', overview: 'roster', roster: 'roster', counters: 'counters', counter: 'counters', soldier: 'counters', duties: 'duties', inspections: 'inspections', settings: 'settings', 'edit-roster': 'settings' };
function route() {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  const [name = '', a, b] = parts;
  let v;
  editRoster = false;
  switch (name) {
    case '': case 'roster': v = viewRoster(false); break;
    case 'edit-roster': editRoster = true; v = viewRoster(true); break;
    case 'overview': v = viewOverview(); break;
    case 'counters': v = viewCounters(); break;
    case 'counter': v = viewCounter(a); break;
    case 'soldier': v = viewSoldier(a, b === 'edit'); break;
    case 'duties': v = viewDuties(); break;
    case 'inspections': v = viewInspections(); break;
    case 'settings': v = viewSettings(); break;
    default: v = viewRoster(false);
  }
  view.current = v;
  $('#view').replaceChildren(v.el);
  window.scrollTo(0, 0);
  if (v.update) v.update();
  const tab = TAB_OF[name] || 'roster';
  document.querySelectorAll('#nav a').forEach((x) => x.classList.toggle('active', x.dataset.tab === tab));
}

// ------------------------------------------------------------------ boot
async function boot() {
  applyTheme();
  await S.load();
  if (S.housekeeping()) { S.wipeAll(); Sync.leave(); }
  S.touchActive();
  S.ensureSeed();
  S.ensureDefaults();
  S.onChange((local) => {
    if (local) Sync.requestSync();
    if (view.current && view.current.update) view.current.update();
  });
  window.addEventListener('hashchange', route);
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') { lock.hiddenAt = Date.now(); } else {
      S.touchActive();
      if (S.hasPin() && lock.hiddenAt && Date.now() - lock.hiddenAt > 30000) { lock.unlocked = false; showLock(); }
      Sync.syncNow().then(() => { if (view.current && view.current.update) view.current.update(); });
    }
  });
  Sync.startPolling(() => { if (view.current && view.current.update) view.current.update(); });
  route();
  if (S.hasPin()) showLock();
  if (Sync.isJoined()) Sync.syncNow().then(() => { if (view.current && view.current.update) view.current.update(); });
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
}
boot();
