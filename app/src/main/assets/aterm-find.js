// A-Terminal find bar (round 7) — action `find`/`findMatch` ตาม WT ($defs.FindMatchAction direction next|prev)
// เลือก in-page overlay (ไม่ใช่ native dialog): กัน IME-focus ยกล็อตของ round 3/C13 + state ต้องอยู่ JS อยู่แล้ว (D5)
// ใช้ buffer API + term.select เท่านั้น — ไม่แตะ xterm.js (zero patch)
(function () {
  'use strict';
  let term = null;
  let bar = null, input = null, countEl = null;
  let query = '', caseSensitive = false;
  let matches = [];          // [{row(abs), col, len}] — recompute ทุกครั้งที่ query/ buffer เปลี่ยน (scan 9001 บรรทัด = เร็ว)
  let idx = -1;              // ตัวที่แสดงอยู่
  let savedSilent = false;

  const css = '#aterm-find{position:fixed;left:8px;right:8px;top:4px;z-index:9;display:flex;gap:6px;' +
    'align-items:center;background:#1f1f1f;border:1px solid #3f3f46;padding:4px 6px;' +
    'font:13px sans-serif;color:#ccc;box-shadow:0 2px 8px rgba(0,0,0,.6)}' +
    '#aterm-find input{flex:1;min-width:0;background:#0c0c0c;color:#e6e6e6;border:1px solid #3f3f46;' +
    'padding:6px;font:14px monospace;border-radius:2px;-webkit-user-select:text;user-select:text}' +
    '#aterm-find button{background:#333;color:#ddd;border:0;border-radius:2px;padding:6px 10px;font-size:14px}' +
    '#aterm-find .af-on{background:#0037da;color:#fff}' +
    '#aterm-find #aterm-find-count{min-width:56px;text-align:right;color:#9a9a9a}';

  function ensureBar() {
    if (bar) return;
    const st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
    bar = document.createElement('div'); bar.id = 'aterm-find';
    input = document.createElement('input');
    input.placeholder = 'find…'; input.setAttribute('type', 'text');
    input.addEventListener('input', () => { query = input.value; recompute(true); });
    input.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); e.shiftKey ? prev() : next(); }
      else if (e.key === 'Escape') { e.preventDefault(); close(); }
    });
    const mk = (label, title, fn) => {
      const b = document.createElement('button');
      b.textContent = label; b.title = title;
      b.addEventListener('click', fn); bar.appendChild(b); return b;
    };
    countEl = document.createElement('span'); countEl.id = 'aterm-find-count';
    bar.appendChild(input);
    const caseBtn = mk('Aa', 'case sensitive', () => { caseSensitive = !caseSensitive; caseBtn.classList.toggle('af-on', caseSensitive); recompute(true); });
    mk('▲', 'findPrev (findMatch prev)', () => prev());
    mk('▼', 'findNext (findMatch next)', () => next());
    mk('✕', 'close (Esc)', () => close());
    bar.appendChild(countEl);
    document.body.appendChild(bar);
  }

  // กัน copyOnSelect/copy storm ตอน find วาด selection ซ้ำ ๆ (gate เดียวกับ markMode)
  function silent(on) { window.__atermSilentSelect = on; }

  function recompute(resetIdx) {
    matches = []; idx = -1;
    if (query) {
      const b = term.buffer.active, need = caseSensitive ? query : query.toLowerCase();
      for (let r = 0; r < b.length; r++) {
        const line = b.getLine(r); if (!line) continue;
        let s = line.translateToString(true);
        if (!caseSensitive) s = s.toLowerCase();
        let c = s.indexOf(need);
        while (c >= 0) { matches.push({ row: r, col: c, len: query.length }); c = s.indexOf(need, c + need.length); }
      }
    }
    if (resetIdx && matches.length) { // ตัวแรก "ถัดจาก" กระจกที่มองเห็น = เริ่มจาก viewportTop
      const top = term.buffer.active.viewportY;
      idx = matches.findIndex(m => m.row >= top);
      if (idx < 0) idx = 0;
      show();
    } else show0();
  }

  function show0() { report(); }
  function report() {
    if (countEl) countEl.textContent = query ? (matches.length ? (idx + 1) + '/' + matches.length : '0/0') : '';
    if (window.Droid && Droid.findStatus) {
      Droid.findStatus(query ? JSON.stringify({ q: query, i: idx + 1, n: matches.length }) : '');
    }
  }

  function show() {
    if (!matches.length) { term.clearSelection(); report(); return; }
    const m = matches[(idx + matches.length) % matches.length]; idx = (idx + matches.length) % matches.length;
    silent(true);
    term.select(m.col, m.row, m.len);
    // reveal: ให้แถว match อยู่กลาง ๆ view (scrollLines = relative ต่อ viewportY)
    const b = term.buffer.active;
    if (m.row < b.viewportY || m.row >= b.viewportY + term.rows) {
      term.scrollLines(m.row - (b.viewportY + Math.floor(term.rows / 2)));
    }
    silent(false);
    report();
  }

  function next() { if (!matches.length) recompute(false); idx = idx < 0 ? 0 : idx + 1; show(); }
  function prev() { if (!matches.length) recompute(false); idx = idx < 0 ? matches.length - 1 : idx - 1; show(); }

  function open(opts) {
    if (!term) return;
    ensureBar();
    savedSilent = !!window.__atermSilentSelect; silent(true);
    bar.style.display = 'flex';
    // dock = fit() กัน top ออก (terminal.html) — highlight แถวบนสุดต้องไม่โดน bar บัง
    window.__atermInsetTop = bar.offsetHeight + 12;
    window.dispatchEvent(new Event('resize'));
    if (opts && typeof opts.ignoreCase === 'boolean') caseSensitive = !opts.ignoreCase;
    input.focus(); input.select();
    if (query) recompute(true);
  }
  function close() {
    if (!bar) return;
    bar.style.display = 'none';
    window.__atermInsetTop = 0;
    window.dispatchEvent(new Event('resize'));
    silent(savedSilent);
    if (window.Droid && Droid.findStatus) Droid.findStatus('');
    term.focus(); // คืน IME ให้ helper textarea (focusTerminal ฝั่ง Kotlin จะตามมาอีกชั้นถ้า user tap)
  }
  function isOpen() { return bar && bar.style.display !== 'none'; }

  window.AtermFind = {
    init: function (t) { term = t; },
    open: open, close: close, next: next, prev: prev, isOpen: isOpen,
  };
})();
