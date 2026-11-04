package dev.aterminal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** model เล็กสำหรับ render — MainActivity สกัดจาก TerminalSession แล้วส่งเข้า Compose */
data class TabInfo(
    val id: Long,
    val title: String,
    val state: SessionController.State,
    val error: Boolean,
    // task 7: ขนาด grid ของ pane focused — ใช้ใน header line ของ long-press menu "name (146×36)"
    val cols: Int = 0,
    val rows: Int = 0,
)

/**
 * ⋮ overflow ids (round 23 task 7) — ทุก id ถูก dispatch เข้า handler เดิมของ MainActivity
 * (ห้ามมี behaviour ใหม่ใน enum/menu: เมนูเป็นทางเข้าใหม่ ไม่ใช่ระบบที่สอง)
 */
enum class OverflowItemId {
    keepCpuAwake, renderer, fontMinus, fontPlus,
    copyConnectionLog, copyImeLog, exportSettings, importSettings, licenses,
    checkUpdates, updateChecksToggle, // round 23 amendment (Update Check) — dispatch เดียวผ่าน onOverflow
}

/**
 * Top bar v2 (round 23) — แถวเดียวจบ แทน tab strip + status strip สองแถวเดิม
 *  - multi-tab = pills (compact: inactive = dot + `…`title ≤8 ตัว, active = title ≤96.dp + ×)
 *  - single tab = plain title + status dot ไม่มี pill (WT `showTerminalTitleInTitlebar` semantics)
 *  - ∨ = DropdownMenu โปรไฟล์+Add (ปุ่ม `+` เดิม tap = เมนูเดียวกัน — ไม่มีระบบที่สอง)
 *  - ⋮ = DropdownMenu: Profiles group (Edit/Delete ของ active) + SettingsGroup items — dispatch ผ่าน onOverflow
 *  - status line (find counter / reconnect countdown / error) ยังเห็น: single tab = เนื้อหาของ title strip,
 *    multi-tab = text area ท้าย pills (สี = สถานะเดิมทั้งชุดของ renderActiveStatus)
 *  - ห้าม focusableFromTouch: กัน strip ฉก IME focus จาก WebView (focus recipe เรียกหลัง switch เสมอ)
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ATerminalTabBar(
    tabs: List<TabInfo>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onNewFromProfile: (String) -> Unit,   // ชื่อ profile จาก ∨ picker → MainActivity.connectProfile
    onAddProfile: () -> Unit,
    onOverflow: (OverflowItemId) -> Unit,
    chrome: ChromeColors,
    profiles: List<String>,
    activeProfileName: String?,
    onProfileEdit: () -> Unit,            // ⋮ Profiles group (list-pick fallback อยู่ฝั่ง ConnectUi)
    onProfileDelete: () -> Unit,
    onStatusTap: () -> Unit,              // ≡ tap ของ status strip เดิม (retry / picker)
    onStatusLongPress: () -> Unit,        // ≡ long-press เดิม (ปิด auto-reconnect ของ pane นี้)
    keepCpuAwake: Boolean,
    engine: String,
    updateChecks: Boolean,  // round 23 amendment: label สวิตช์ "Update checks" ใน ⋮ (อ่านจาก barSnap)
    appVersion: String,     // versionName ดิบ (ไม่มี v) — บรรทัดท้ายเมนู ⋮
    statusText: String,
    statusColor: Color,
    onClose: (Long) -> Unit,
    onMenu: (Long) -> Unit,
    // task 10: policy "menu = ปิด IME ก่อน" อยู่ฝั่ง Activity (composable ไม่มี Activity ref) —
    // เรียกก่อนเปิดทุก DropdownMenu (∨ และ ⋮) ห้ามมีเมนูที่ข้าม
    onBeforeMenu: () -> Unit,
) {
    var newMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(chrome.barBg)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tabs.size <= 1) {
            // single tab = title strip เรียบ ๆ (no pill chrome)
            //  - tap = gesture เดิมของ status strip (retry-on-error / picker)
            //  - long-press = onMenu (review fix): single tab คือ default state — pane menu (Split/Find/
            //    MARK MODE/Close tab) เป็น touch entry เดียว ถ้าไม่วางตรงนี้ = เข้าถึงไม่ได้เลย
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(
                        onClick = onStatusTap,
                        onLongClick = { tabs.firstOrNull()?.let { onMenu(it.id) } },
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.firstOrNull()?.let { StatusDot(it) }
                Spacer(Modifier.width(6.dp))
                Text(
                    statusText,
                    color = statusColor,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // task 7b (device FAIL 411dp): status เดิมเป็น weighted 1f → sibling น้ำหนักสองตัวเบียดกันแบบ
            // hard cap ต่อตัว (RowColumnMeasurePolicy: weighted ได้ mainAxisMax = ส่วนแบ่งน้ำหนักของตัวเอง
            // เท่านั้น = ~50%) → pill = active ~188dp ถูกตัดเหลือ sliver — ruling: status เปลี่ยนเป็น
            // non-weighted + widthIn(max) (policy วัด non-weighted ก่อน และ reserve แค่ที่มันวัดได้จริง)
            // เพื่อให้ pill row (weighted ตัวเดียวของแถว) ได้พื้นที่ที่เหลือทั้งหมด
            // ปุ่ม ∨/⋮ ต้องอยู่นอก horizontalScroll: pill 3+ ตัวจะทำให้ปุ่มเลื่อนหลุดจอ (เจอจริงบน device)
            // weight(fill=false) คงไว้ (R3 pin — I2 เดิม): ขาด weight = Boxes ที่วัดทีหลังได้ maxWidth 0
            // ตอน pill กว้าง; fill=false = pill row รับแค่ที่ content ต้องการ
            val pillScroll = rememberScrollState()
            // R1: viewport ต้องชี้มาที่ pill active — offset เป็น content-relative (positionInParent
            // ของ pill วัดจาก content row ใน ScrollableModifier ซึ่ง shift scroll ตอน *place* เท่านั้น)
            var activePillLeft by remember { mutableIntStateOf(0) }
            LaunchedEffect(activeId, tabs.size, activePillLeft) {
                // key activePillLeft เพิ่มจาก ruling (activeId, tabs.size): effect รันก่อน layout pass
                // ของเฟรมแรก → offset ค่านับได้จาก onGloballyPositioned เท่านั้น
                pillScroll.animateScrollTo(activePillLeft.coerceAtLeast(0))
            }
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(pillScroll)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { t ->
                    val sel = t.id == activeId
                    Row(
                        modifier = Modifier
                            .then(
                                if (sel) Modifier.onGloballyPositioned {
                                    activePillLeft = it.positionInParent().x.roundToInt()
                                } else Modifier
                            )
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) chrome.pillActiveBg else chrome.pillIdleBg)
                            // long-press = เมนู splitPane/closeOtherPanes (round 5) — combinedClickable
                            // การันตี long-press แล้วไม่ fire onClick พร้อมกัน
                            .combinedClickable(
                                onClick = { if (!sel) onSelect(t.id) },
                                onLongClick = { onMenu(t.id) },
                            )
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(t)
                        Spacer(Modifier.width(6.dp))
                        // inactive = compact (task 7): `…` + title ≤8 ตัว — กัน pills เบียด status/ปุ่มขวา
                        val label = if (sel) t.title
                            else if (t.title.length > 8) "…" + t.title.take(8) else t.title
                        Text(
                            label,
                            color = if (sel) chrome.textActive else chrome.textIdle,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // task 7b: widthIn(cap) แทนความกว้าง fix 120dp เดิม — active title ellipsize ที่ cap
                            // (pill active ≤ ~151dp: 96 + dot + × + padding) เพื่อให้ 2 tabs พอดี rest-space
                            modifier = if (sel) Modifier.widthIn(max = 96.dp) else Modifier,
                        )
                        if (sel) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "×",
                                color = chrome.textIdle,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .clickable { onClose(t.id) },
                            )
                        }
                    }
                }
            }
            // multi-tab: status line still needs room — find counter / reconnect countdown / error (เดิมคือ strip)
            // task 7b (R4): widthIn(cap) แทน weighted 1f เดิม — status yield ที่ให้ pills (ดู comment บน),
            // ข้อความยาว ellipsize ที่ cap และห้ามดัน ∨/⋮ หลุดจอ, tap/long-press gesture เดิมคงอยู่
            Text(
                statusText,
                color = statusColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 112.dp)
                    .fillMaxHeight()
                    .combinedClickable(onClick = onStatusTap, onLongClick = onStatusLongPress)
                    .padding(horizontal = 6.dp),
            )
        }
        // ∨ = new-tab picker (`+` เดิมนำเข้าเมนูเดียวกัน — single behavior, no second system)
        // task 7b (R2/R3): touch container = Box widthIn(min=44) — glyph เปลือยวัดได้ ~14–28dp
        // ต่ำกว่าขั้นต่ำ 44dp ของโปรเจกต์ และเคยมี device bug tap ตรงกลาง glyph แล้วร่วงไปโดน status
        // clickable อยู่บน Box = กล่อง ≥44dp ต่างหากที่เป็น touch target; glyph center ด้วย contentAlignment
        Box(
            modifier = Modifier
                .widthIn(min = 44.dp)
                .fillMaxHeight()
                .clickable { onBeforeMenu(); newMenu = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "∨",
                color = chrome.textIdle,
                fontSize = 15.sp,
            )
            DropdownMenu(
                expanded = newMenu,
                onDismissRequest = { newMenu = false },
                containerColor = chrome.pillActiveBg,
            ) {
                profiles.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, color = chrome.textActive, fontSize = 13.sp) },
                        onClick = { newMenu = false; onNewFromProfile(name) },
                    )
                }
                HorizontalDivider(color = chrome.pillIdleBg)
                DropdownMenuItem(
                    text = { Text("➕ Add profile…", color = chrome.textActive, fontSize = 13.sp) },
                    onClick = { newMenu = false; onAddProfile() },
                )
            }
        }
        // ⋮ = overflow ของทุก action ที่เคยอยู่บน status strip (+ Profiles group บนสุด ตาม decision round 23)
        // task 7b (R2): touch container เดียวกันกับ ∨ — Box ≥44dp, ดู comment เหนือ ∨
        Box(
            modifier = Modifier
                .widthIn(min = 44.dp)
                .fillMaxHeight()
                .clickable { onBeforeMenu(); overflowMenu = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "⋮",
                color = chrome.textIdle,
                fontSize = 15.sp,
            )
            DropdownMenu(
                expanded = overflowMenu,
                onDismissRequest = { overflowMenu = false },
                containerColor = chrome.pillActiveBg,
            ) {
                Text(
                    "Profiles: ${activeProfileName ?: "—"}",
                    color = chrome.textIdle,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                OverflowEntry("✏️ Edit profile", chrome) { overflowMenu = false; onProfileEdit() }
                OverflowEntry("🗑 Delete profile", chrome) { overflowMenu = false; onProfileDelete() }
                HorizontalDivider(color = chrome.pillIdleBg)
                OverflowEntry("🔋 Keep CPU awake: " + if (keepCpuAwake) "On" else "Off", chrome) {
                    overflowMenu = false; onOverflow(OverflowItemId.keepCpuAwake)
                }
                OverflowEntry(
                    "🖥 Renderer: " + if (engine == "native") "Native (tap → WebView)" else "WebView (tap → Native)",
                    chrome,
                ) { overflowMenu = false; onOverflow(OverflowItemId.renderer) }
                OverflowEntry("A− Font size −", chrome) { overflowMenu = false; onOverflow(OverflowItemId.fontMinus) }
                OverflowEntry("A+ Font size +", chrome) { overflowMenu = false; onOverflow(OverflowItemId.fontPlus) }
                OverflowEntry("🔍 Copy connection log", chrome) { overflowMenu = false; onOverflow(OverflowItemId.copyConnectionLog) }
                OverflowEntry("Copy IME log", chrome) { overflowMenu = false; onOverflow(OverflowItemId.copyImeLog) }
                OverflowEntry("⬇ Export settings.json", chrome) { overflowMenu = false; onOverflow(OverflowItemId.exportSettings) }
                OverflowEntry("⬆ Import settings.json…", chrome) { overflowMenu = false; onOverflow(OverflowItemId.importSettings) }
                OverflowEntry("📄 Open source licenses", chrome) { overflowMenu = false; onOverflow(OverflowItemId.licenses) }
                HorizontalDivider(color = chrome.pillIdleBg)
                OverflowEntry("🔄 Check for updates", chrome) { overflowMenu = false; onOverflow(OverflowItemId.checkUpdates) }
                OverflowEntry("🌐 Update checks: " + if (updateChecks) "On" else "Off", chrome) {
                    overflowMenu = false; onOverflow(OverflowItemId.updateChecksToggle)
                }
                Text(
                    "A-Terminal v$appVersion",
                    color = chrome.textIdle,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun OverflowEntry(label: String, chrome: ChromeColors, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = chrome.textActive, fontSize = 13.sp) },
        onClick = onClick,
    )
}

/** จุดสีสถานะ — same mapping ทั้ง pill และ single-tab title strip */
@Composable
private fun StatusDot(t: TabInfo) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                when {
                    t.error || t.state == SessionController.State.DISCONNECTED -> Color(0xFFFF6B6B)
                    t.state == SessionController.State.CONNECTED -> Color(0xFF6ADF7A)
                    t.state == SessionController.State.CONNECTING -> Color(0xFFFFD76A)
                    else -> Color(0xFF6A6A6A)
                }
            )
    )
}
