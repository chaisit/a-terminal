package dev.aterminal.core

/**
 * ticket tmux/03: OSC 4 query ต้องตอบสี "ตัวที่ renderer วาดจริง" — lookup เดียวกับที่ StyleResolver
 * (webview ส่ง scheme ผ่าน colorSchemeJson ให้ xterm.js เอง; native ใช้ [paletteColor] ตรงนี้)
 *
 * index ≡ xterm/WT palette: 0-7 normal, 8-15 bright — ห้ามสลับ (vim/tmux theme เพี้ยนเงียบถ้าผิด)
 * คืน hex เดิมของ Scheme (`#rrggbb`) ให้ TerminalReports ไปขยายเป็น rgb: triplet ต่อ
 */
fun Scheme.paletteColor(index: Int): String? = when (index) {
    0 -> black
    1 -> red
    2 -> green
    3 -> yellow
    4 -> blue
    5 -> purple
    6 -> cyan
    7 -> white
    8 -> brightBlack
    9 -> brightRed
    10 -> brightGreen
    11 -> brightYellow
    12 -> brightBlue
    13 -> brightPurple
    14 -> brightCyan
    15 -> brightWhite
    else -> null
}
