// A-Terminal keybindings dispatcher + markMode (round 7)
//  - grammar NOT parsed here — ตาราง normalize แล้วมาจาก Kotlin (KeybindingResolver.toJsJson, D1)
//  - hook = attachCustomKeyEventHandler (มีใน xterm 5.5 core — zero patch); คืน false เฉพาะ chord ที่ match
//  - guard 229/isComposing ก่อน match ทุกครั้ง = Thai composition ไม่เห็น chord logic (D2, P2-safe)
//  - markMode ตาม WT spec #4993: เข้า = selection ที่ cursor, arrows เลื่อน/ขยาย, ENTER = copy+exit,
//    ESC = clear+exit, key อื่น = exit แล้วส่ง byte ปกติ (unmatched exits)
(function () {
  'use strict';
  let term = null;
  let table = [];
  let mark = null; // {anchor:{row,col}, cur:{row,col}} — row = absolute buffer line

  // ---------- canonicalize KeyboardEvent → token เดียวกับ ChordParser ----------
  const CODE = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
    Escape: 'escape', Enter: 'enter', Tab: 'tab', Space: 'space', Backspace: 'backspace',
    Delete: 'delete', Insert: 'insert', Home: 'home', End: 'end', PageUp: 'pageup', PageDown: 'pagedown',
    Minus: 'minus', Equal: 'equal', NumpadAdd: 'plus', NumpadSubtract: 'minus', Numpad0: '0',
  };
  function keyName(e) {
    let k = CODE[e.code] || (e.key ? e.key.toLowerCase() : '');
    if (k === 'equal') k = e.shiftKey ? 'plus' : 'minus'; // "ctrl+plus" ของจริง = shift+ctrl+=
    if (k === '+' || k === '=' && e.shiftKey) k = 'plus';
    if (k === '-') k = 'minus';
    if (k === '+' ) k = 'plus';
    if (k.length > 1 && !(k in {up:1,down:1,left:1,right:1,escape:1,enter:1,tab:1,space:1,backspace:1,delete:1,insert:1,home:1,end:1,pageup:1,pagedown:1,plus:1,minus:1})) {
      // "F5" → f5 อนุญาตตาม grammar; อื่น ๆ ที่ยาวเกิน (Dead, Unidentified) = ไม่ match
      if (!/^f([1-9]|1[0-9]|2[0-4])$/.test(k)) return '';
    }
    return k;
  }
  function lookup(e) {
    const k = keyName(e);
    if (!k) return null;
    return table.find(t => t.key === k && !!t.ctrl === !!e.ctrlKey &&
      !!t.alt === !!e.altKey && !!t.shift === !!e.shiftKey) || null;
  }

  // ---------- execute ฝั่ง terminal (state อยู่ที่ xterm) ----------
  function doCopy(a) {
    let s = term.getSelection();
    if (!s) return true;
    if (String(a.singleLine) === 'true') s = s.replace(/\r?\n\s*/g, ' '); // WT CopyAction.singleLine
    if (window.Droid) Droid.copyToClipboard(s);
    return true;
  }
  function clearBuffer(mode) {
    if (mode === 'screen') term.clear();
    else term.reset(); // all/scrollback: xterm 5.5 ไม่มี public "wipe scrollback" — reset = ใกล้เคียงสุด (บันทึกใน report)
  }
  function execTerminal(a) {
    switch (a.action) {
      case 'scrollUp': term.scrollLines(-1); return true;
      case 'scrollDown': term.scrollLines(1); return true;
      case 'scrollUpPage': term.scrollPages(-1); return true;
      case 'scrollDownPage': term.scrollPages(1); return true;
      case 'scrollToTop': term.scrollLines(-term.buffer.active.viewportY); return true;
      case 'scrollToBottom': term.scrollToBottom(); return true;
      case 'selectAll': term.selectAll(); return true;
      case 'copy': return doCopy(a);
      case 'find': window.AtermFind && AtermFind.open({}); return true;
      case 'findMatch':
        if (!window.AtermFind || !AtermFind.isOpen()) window.AtermFind && AtermFind.open({});
        else (a.direction === 'next' ? AtermFind.next() : AtermFind.prev());
        return true;
      case 'markMode': toggleMark(); return true;
      case 'sendInput': if (a.input) term.input(String(a.input).replace(/\\u001b/gi, '\x1b')); return true;
      case 'clearBuffer': clearBuffer(a.clear || 'all'); return true;
      default: return false;
    }
  }
  function exec(entry) {
    const a = entry.cmd || {};
    if (entry.target === 'terminal') return execTerminal(a);
    // app + unsupported → Kotlin (unsupported = toast "ยังไม่รองรับ" ฝั่งนั้น — fail-soft ที่เดียว)
    if (window.Droid && Droid.onAction) { Droid.onAction(JSON.stringify(a)); return true; }
    return false;
  }

  // ---------- markMode (WT spec #4993) ----------
  function reportMark(on) { if (window.Droid && Droid.onMarkMode) Droid.onMarkMode(on); }
  function posAbs() { // cursor = absolute line (verify กับ bundle แล้ว: IBuffer getters = cursorX/cursorY, ไม่มี x/y ใน 5.5)
    const b = term.buffer.active;
    return { row: b.baseY + b.cursorY, col: Math.min(b.cursorX, term.cols - 1) };
  }
  function paintMark() {
    const cols = term.cols, b = term.buffer.active;
    let a = mark.anchor.row * cols + mark.anchor.col, c = mark.cur.row * cols + mark.cur.col;
    let sRow = a, eLin = c;
    if (c < a) { sRow = c; eLin = a; }
    window.__atermSilentSelect = true;
    term.select(sRow % cols, Math.floor(sRow / cols), eLin - sRow + 1);
    // reveal ให้ cur อยู่ใน view
    if (mark.cur.row < b.viewportY || mark.cur.row >= b.viewportY + term.rows) {
      term.scrollLines(mark.cur.row - (b.viewportY + Math.floor(term.rows / 2)));
    }
    window.__atermSilentSelect = false;
  }
  function enterMark() {
    if (mark || !term) return;
    const p = posAbs();
    mark = { anchor: p, cur: { row: p.row, col: p.col } };
    paintMark();
    reportMark(true);
  }
  function exitMark(clearSel) {
    if (!mark) return;
    mark = null;
    if (clearSel) term.clearSelection();
    reportMark(false);
  }
  function toggleMark() { mark ? exitMark(true) : enterMark(); }
  function markMove(name) { // ชื่อ key ตามตาราง ChordParser (up/down/left/right/pageup/pagedown/home/end)
    if (!mark) return;
    const cols = term.cols, last = term.buffer.active.length - 1;
    const cur = mark.cur;
    if (name === 'left') cur.col -= 1;
    else if (name === 'right') cur.col += 1;
    else if (name === 'up') cur.row -= 1;
    else if (name === 'down') cur.row += 1;
    else if (name === 'pageup') cur.row -= term.rows;
    else if (name === 'pagedown') cur.row += term.rows;
    else if (name === 'home') { cur.row = 0; cur.col = 0; }
    else if (name === 'end') { cur.row = last; cur.col = cols - 1; }
    if (cur.col < 0) { cur.col += cols; cur.row -= 1; }          // col wrap แบบ linear buffer
    else if (cur.col >= cols) { cur.col -= cols; cur.row += 1; }
    cur.row = Math.max(0, Math.min(last, cur.row));
    cur.col = Math.max(0, Math.min(cols - 1, cur.col));
    paintMark();
  }
  function markEnterCopy() { // ENTER = copy + clear + exit (WT)
    const s = term.getSelection();
    if (s && window.Droid) Droid.copyToClipboard(s);
    exitMark(true);
  }
  const KEY_BYTES = { up: '\x1b[A', down: '\x1b[B', right: '\x1b[C', left: '\x1b[D', enter: '\r', esc: '\x1b' };
  function markKey(data) { // ทางที่ 2: ExtraKeysBar bytes (bypass JS) — Kotlin divert มาเมื่อ mode on (D6)
    if (!mark) { if (window.Droid) Droid.onInput(data); return; }
    if (!consumeMarkData(data)) { exitMark(true); if (window.Droid) Droid.onInput(data); } // unmatched = exit + ส่งจริง
  }
  /** คืน true = consume แล้ว (อย่าส่งเข้า PTY) */
  function consumeMarkData(data) {
    if (!mark) return false;
    if (data === '\x1b') { exitMark(true); return true; }
    if (data === '\r' || data === '\n') { markEnterCopy(); return true; }
    if (data === '\x1b[A') { markMove('up'); return true; }
    if (data === '\x1b[B') { markMove('down'); return true; }
    if (data === '\x1b[C') { markMove('right'); return true; }
    if (data === '\x1b[D') { markMove('left'); return true; }
    return false;
  }

  // ---------- hardware chord path ----------
  function onKey(e) {
    if (e.type !== 'keydown') return true;
    if (e.isComposing || e.keyCode === 229) return true;   // D2: composition = ปล่อยให้ xterm/patch
    if (mark) { // ใน mark mode ทุก key belongs to it ก่อน (WT)
      const k = keyName(e);
      if (k === 'enter') { markEnterCopy(); return false; }
      if (k === 'escape') { exitMark(true); return false; }
      if (['up', 'down', 'left', 'right', 'pageup', 'pagedown', 'home', 'end'].indexOf(k) >= 0) {
        markMove(k); return false;
      }
      exitMark(true); return true; // unmatched = exit แล้วปล่อย key ปกติ
    }
    const entry = lookup(e);
    if (!entry) return true;
    return !exec(entry); // match = xterm ต้อง NOT handle (false) — IME path ไม่โดน (D2)
  }

  window.AtermKeys = {
    init: function (t) { term = t; term.attachCustomKeyEventHandler(onKey); },
    setTable: function (json) { try { table = JSON.parse(json) || []; } catch (e) { table = []; } },
    toggle: toggleMark,
    markKey: markKey,
    consumeIfMarkMode: function (data) { return consumeMarkData(data); }, // terminal.html onData gate
    isMark: function () { return !!mark; },
  };
})();
