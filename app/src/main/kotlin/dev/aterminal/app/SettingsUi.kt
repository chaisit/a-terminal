package dev.aterminal.app

import android.app.Activity
import android.content.Intent
import dev.aterminal.core.SettingsLoader

/**
 * settings.json เข้า-ออกเครื่อง (round 6) — ผู้ใช้ copy ไฟล์ไป WT/เครื่องอื่น/backup ได้ = แนว WT ที่ไฟล์คือ source of truth
 *  - Export: ACTION_CREATE_DOCUMENT → เขียน SettingsLoader.render(current) ที่เลือก
 *  - Import: ACTION_OPEN_DOCUMENT → validate ด้วย SettingsLoader.parse "ก่อนแตะของเดิม" —
 *    พัง = toast + คงสภาพ (ห้าม semi-apply), ผ่าน = แทนที่ + persist (normalize) + refreshTabs
 * SAF pattern เดียวกับ ConnectUi.pickKey (REQ_KEY 0x51) — codes ต่อจากนั้น
 */
class SettingsUi(private val act: MainActivity) {

    companion object {
        const val REQ_SETTINGS_IN = 0x52
        const val REQ_SETTINGS_OUT = 0x53
    }

    fun export() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "settings.json")
        }
        act.startActivityForResult(intent, REQ_SETTINGS_OUT)
    }

    fun import() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        act.startActivityForResult(intent, REQ_SETTINGS_IN)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK) return false
        val uri = data?.data ?: return false
        return when (requestCode) {
            REQ_SETTINGS_OUT -> {
                runCatching {
                    act.contentResolver.openOutputStream(uri)
                        ?.use { it.write(SettingsLoader.render(act.settings).toByteArray(Charsets.UTF_8)) }
                }.onFailure { act.connectUi.toast("export failed: ${it.message}") }
                    .onSuccess { act.connectUi.toast("exported settings.json") }
                true
            }
            REQ_SETTINGS_IN -> {
                val text = runCatching {
                    act.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (text == null) { act.connectUi.toast("Could not read the file"); return true }
                act.applyImportedSettings(text)
                true
            }
            else -> false
        }
    }
}
