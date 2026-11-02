# font provenance — terminal.ttf (OFL)

terminal.ttf = FiraCode Nerd Font (Regular) แบบ subset — สร้าง 2026-10-31

- ต้นทาง: https://github.com/ryanoasis/nerd-fonts/releases/download/v3.5.1/FiraCode.zip
  - ไฟล์เดิม `FiraCodeNerdFont-Regular.ttf` sha256 f70580120955ef2e898125684c6f730c234371ca68ea9fa7bb57e29a04b3bb64 (2,752,724 B)
- subset (fontTools 4.61.1, `pyftsubset --unicodes=U+0020-007E,U+00A0-00FF,U+2000-206F,U+20A0-20CF,U+2190-21FF,U+2500-257F,U+2580-259F,U+E000-F2FF`)
  - ผล: 1,090,392 B, sha256 750bdb6901698b37f19687b6ae7d52a324d87625ef29146de0e21e8464030d46
  - เจตนาตัด Thai/CJK/Devanagari: ไม่มี monospace Thai — ไทย render ผ่าน system fallback (Noto Sans Thai) ทั้ง webview และ native
- license: OFL-1.1 (ทั้ง FiraCode และ glyph ชุด Nerd) — ตัวเต็มอยู่ OFL.txt ในโฟลเดอร์นี้ (ต้องติด APK ไปกับทุกการ redistribute)
- credit ของชุด icon ในฟอนต์ (CC BY 4.0, MIT, OFL) อยู่ที่ licenses/THIRD_PARTY_NOTICES.txt — subset นี้ไม่มี Font Logos, Material Design, Octicons
- ชื่อ family ที่แท้จริงในไฟล์: `FiraCode Nerd Font` (ต้องตรงตัวกับที่อ้างใน @font-face / fontFace stack)
- ไฟล์นี้ = canonical path `fonts/terminal.ttf` — ฝั่ง JS (@font-face) และ native (BundledFont) อ้าง path นี้ ห้ามอ้างชื่อไฟล์ vendor
- way regenerate: `python3 -I -m fontTools.subset FiraCodeNerdFont-Regular.ttf --unicodes="<ranges ข้างบน>" --output-file=terminal.ttf`
