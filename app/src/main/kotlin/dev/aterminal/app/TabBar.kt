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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** model เล็กสำหรับ render — MainActivity สกัดจาก TerminalSession แล้วส่งเข้า Compose */
data class TabInfo(val id: Long, val title: String, val state: SessionController.State, val error: Boolean)

private val BgTab = Color(0xFF0C0C0C)
private val BgActive = Color(0xFF1F1F1F)
private val BgIdle = Color(0xFF141414)
private val TextActive = Color(0xFFFFFFFF)
private val TextIdle = Color(0xFF9A9A9A)

/**
 * Compose tab strip (round 4) — ใช้ Compose จุดเดียวของ app; terminal host ยังเป็น plain Views
 *  - tab = ชื่อ (Profile.tabTitle ?: name) + จุดสีสถานะ; ตัว active มี "×" (ปิด = ปิด session)
 *  - ห้าม focusableFromTouch: กัน strip ฉก IME focus จาก WebView (focus recipe เรียกหลัง switch เสมอ)
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ATerminalTabBar(
    tabs: List<TabInfo>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onClose: (Long) -> Unit,
    onMenu: (Long) -> Unit,
) {
    // ปุ่ม "+" ต้องอยู่นอก horizontalScroll: pill 3+ ตัวจะทำให้ "+" เลื่อนหลุดจอ (เจอจริงบน device)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(BgTab)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val sel = t.id == activeId
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) BgActive else BgIdle)
                    // long-press = เมนู splitPane/closeOtherPanes (round 5) — combinedClickable
                    // การันตี long-press แล้วไม่ fire onClick พร้อมกัน
                    .combinedClickable(
                        onClick = { if (!sel) onSelect(t.id) },
                        onLongClick = { onMenu(t.id) },
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Spacer(Modifier.width(6.dp))
                Text(
                    t.title,
                    color = if (sel) TextActive else TextIdle,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp),
                )
                if (sel) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "×",
                        color = TextIdle,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxHeight()
                            .clickable { onClose(t.id) },
                    )
                }
            }
        }
    }
        Text(
            " + ",
            color = TextIdle,
            fontSize = 16.sp,
            modifier = Modifier
                .fillMaxHeight()
                .clickable { onNew() },
        )
    }
}
