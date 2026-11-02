#!/usr/bin/env python3
"""
patch_xterm_ime.py — patch vendored xterm.js 5.5.0 UMD (IME 229-gate ของ Android WebView)

ใช้:  python3 -I tools/patch_xterm_ime.py app/src/main/assets/xterm/xterm.js
- anchor-based string replace (ห้ามแก้ minified ด้วย editor มือเปล่า — ดู docs/xterm-umd-patch.md)
- idempotent: ตรวจว่า patch แล้วก่อน, anchor ไม่ unique = หยุดไม่แก้
- backup: เก็บสำเนาเดิมไว้ /tmp (git คือ backup หลัก — revert: git checkout -- <file>)
- หลัง patch ต้องรัน: node --check app/src/main/assets/xterm/xterm.js
"""
import sys
import shutil

PATCHES = [
    # ---- P3: handlePasteEvent — preventDefault กัน paste residue ค้างใน helper textarea ----
    (
        't.handlePasteEvent=function(e,t,i,s){e.stopPropagation(),e.clipboardData&&r(',
        't.handlePasteEvent=function(e,t,i,s){e.stopPropagation(),e.preventDefault(),e.clipboardData&&r(',
    ),
    # ---- P1: _handleAnyTextareaChanges — setTimeout(0) เดียว -> retry 5x30ms ----
    # root cause #6259: WebView ส่ง text ที่ commit แล้ว *หลัง* deferred-diff รอบเดียวของ 5.5 -> text หาย
    # (backport แนว _textareaChangeTimer ของ 6.0; baseline = current value ต่อครั้ง, retry จน value เปลี่ยน
    #  หรือครบ ~150ms; ! _isComposing gate คงเดิม)
    (
        '_handleAnyTextareaChanges(){const e=this._textarea.value;setTimeout((()=>{if(!this._isComposing){'
        'const t=this._textarea.value,i=t.replace(e,"");this._dataAlreadySent=i,'
        't.length>e.length?this._coreService.triggerDataEvent(i,!0):t.length<e.length?'
        'this._coreService.triggerDataEvent(`${a.C0.DEL}`,!0):t.length===e.length&&t!==e&&'
        'this._coreService.triggerDataEvent(t,!0)}}),0)}',
        '_handleAnyTextareaChanges(){const e=this._textarea.value;let n=0;const r=()=>{'
        'if(this._isComposing)return;const t=this._textarea.value;if(t===e){if(n++<5)'
        'this._textareaRetryTimer=setTimeout(r,30);return}const i=t.replace(e,"");'
        'this._dataAlreadySent=i,t.length>e.length?this._coreService.triggerDataEvent(i,!0):'
        't.length<e.length?this._coreService.triggerDataEvent(`${a.C0.DEL}`,!0):'
        't.length===e.length&&t!==e&&this._coreService.triggerDataEvent(t,!0)};'
        'clearTimeout(this._textareaRetryTimer),this._textareaRetryTimer=setTimeout(r,0)}',
        # stable marker: P4b ต่อท้าย triggerDataEvent(t,!0) = new ทั้งก้อนไม่ contiguous ใน bundle ที่ patch ครบ
        '_handleAnyTextareaChanges(){const e=this._textarea.value;let n=0;const r=()=>{',
    ),
    # ---- P2: _inputEvent — เปิดประตู rescue + dedup ด้วย _dataAlreadySent ----
    # gate เดิม (!e.composed||!this._keyDownSeen) ทิ้ง input ที่ตามมาหลัง commit (keyDownSeen=true จาก
    # keydown 229) -> หาย; rescue: isComposing===false && data != สิ่งที่ deferred-diff ส่งไปแล้ว = ส่งได้
    # และทุกครั้งที่ _inputEvent ส่ง จะ mark _dataAlreadySent (shim ฝั่ง terminal.html ใช้ตรวจซ้ำรอบสอง)
    (
        '_inputEvent(e){if(e.data&&"insertText"===e.inputType&&(!e.composed||!this._keyDownSeen)&&'
        '!this.optionsService.rawOptions.screenReaderMode){if(this._keyPressHandled)return!1;'
        'this._unprocessedDeadKey=!1;const t=e.data;return this.coreService.triggerDataEvent(t,!0),',
        '_inputEvent(e){if(e.data&&"insertText"===e.inputType&&(!e.composed||!this._keyDownSeen||'
        '!1===e.isComposing&&e.data!==this._dataAlreadySent)&&!this.optionsService.rawOptions.'
        'screenReaderMode){if(this._keyPressHandled)return!1;this._unprocessedDeadKey=!1;'
        'const t=e.data;return this._dataAlreadySent=t,this.coreService.triggerDataEvent(t,!0),',
    ),
    # ---- P4 (round 15-HW-3): send-then-clear — ทุกจุดที่ส่ง bytes = เคลียร์ helper textarea ทันที ----
    # root cause (Honor V6, screenshot 2026-11-01): textarea ไม่เคยถูกเคลียร์หลัง commit → Gboard มองเป็น
    # textbox จริง → แตะ predict = deleteSurroundingText(คำเก่า)+commit(คำใหม่) → P1 shrink ยิง DEL ตัวเดียว
    # (กลางคำแหว่ง) + shim เห็น residue ส่งซ้ำทั้งก้อน (หางคำซ้ำ) —เคลียร์แล้ว Gboard เห็น surroundings ว่าง
    # ≡ terminal app อื่น = predict เป็น pure insert; soft-⌫ ยังได้ \x7f ผ่าน Blink synthesized keydown (M1)
    # หมายเหตุ: P2 anchor อยู่บนคลาส CORE (this._dataAlreadySent ≡ term._core._dataAlreadySent ที่ shim อ่าน —
    # probe ผ่าน CDP แล้ว 2026-11-01); _textareaRetryTimer/_textarea/_isComposing อยู่บน CompositionHelper
    # clearTimeout ทุกจุด clear = จำเป็น: ไม่งั้น chain ที่ arm จาก keydown-229 เห็น value หดเป็น '' → ยิง DEL เก้อ
    (
        # P4a: _inputEvent (บน core) — clear ผ่าน _compositionHelper (field-proven ว่าเป็น element เดียวกัน)
        'const t=e.data;return this._dataAlreadySent=t,this.coreService.triggerDataEvent(t,!0),this.cancel(e),!0',
        'const t=e.data;return this._dataAlreadySent=t,this.coreService.triggerDataEvent(t,!0),'
        'this._compositionHelper._isComposing||(clearTimeout(this._compositionHelper._textareaRetryTimer),'
        'this._compositionHelper._textarea.value=""),this.cancel(e),!0',
    ),
    (
        # P4b: ท้าย retry chain r ของ P1 — r early-return ถ้า composing อยู่ = clear แบบ unconditional ปลอดภัย
        't.length===e.length&&t!==e&&this._coreService.triggerDataEvent(t,!0)};'
        'clearTimeout(this._textareaRetryTimer),this._textareaRetryTimer=setTimeout(r,0)}',
        't.length===e.length&&t!==e&&this._coreService.triggerDataEvent(t,!0),this._textarea.value=""};'
        'clearTimeout(this._textareaRetryTimer),this._textareaRetryTimer=setTimeout(r,0)}',
    ),
    (
        # P4c: _finalizeComposition deferred branch — timer อาจแลนตอน composition ใหม่เริ่มแล้ว = guard จำเป็น
        # (ถ้า P2 ส่ง+เคลียร์ไปแล้ว t=substring(start+=len(_dataAlreadySent)) = '' → ไม่ส่งซ้ำ โดย design)
        't.length>0&&this._coreService.triggerDataEvent(t,!0)}}),0)}',
        't.length>0&&(this._dataAlreadySent=t,this._coreService.triggerDataEvent(t,!0),'
        'this._isComposing||(clearTimeout(this._textareaRetryTimer),this._textarea.value=""))}}),0)}',
    ),
    (
        # P4d: _finalizeComposition immediate branch (keydown ไม่ 229 ตัด composition) — mark + clear
        'const e=this._textarea.value.substring(this._compositionPosition.start,'
        'this._compositionPosition.end);this._coreService.triggerDataEvent(e,!0)}',
        'const e=this._textarea.value.substring(this._compositionPosition.start,'
        'this._compositionPosition.end);this._dataAlreadySent=e,this._coreService.triggerDataEvent(e,!0),'
        'this._isComposing||(clearTimeout(this._textareaRetryTimer),this._textarea.value="")}',
    ),
    # ---- P5 (0.2.4): guard ของ P2 ต้องกันแค่ input ซ้ำของ "คีย์เดียวกัน" — keydown ใหม่ = ล้าง mark ----
    # ไม่ล้าง = ตัวซ้ำติดกันที่มี keydown ทุกตัว (ll, //, &&) ถูกมองเป็นการส่งซ้ำ ทั้ง gate และ shim จึงทิ้ง
    (
        '_keyDown(e){if(this._keyDownHandled=!1,this._keyDownSeen=!0,',
        '_keyDown(e){if(this._keyDownHandled=!1,this._keyDownSeen=!0,this._dataAlreadySent="",',
    ),
]


def main(path: str) -> int:
    src = open(path, encoding="utf-8").read()
    size_before = len(src)
    done = 0
    for i, entry in enumerate(PATCHES, 1):
        old, new = entry[0], entry[1]
        # element ที่ 3 (optional) = stable marker สำหรับตรวจ idempotency เมื่อ patch ภายหลังมาต่อท้าย new นี้
        marker = entry[2] if len(entry) > 2 else new
        if marker in src:
            print(f"P{i}: already patched — skip")
            continue
        n = src.count(old)
        if n != 1:
            print(f"P{i}: ABORT — anchor found {n} times (ต้อง re-audit anchor กับ version นี้)")
            return 1
        src = src.replace(old, new)
        done += 1
    if done:
        shutil.copyfile(path, f"/tmp/xterm.js.pre-patch.bak")
        open(path, "w", encoding="utf-8").write(src)
        print(f"patched {done} patch(es); backup: /tmp/xterm.js.pre-patch.bak")
    print(f"size {size_before} -> {len(src)} (delta {len(src)-size_before:+d})")
    print("next: node --check", path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/xterm/xterm.js"))
