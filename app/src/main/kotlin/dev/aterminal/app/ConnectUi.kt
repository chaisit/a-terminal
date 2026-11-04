package dev.aterminal.app

import android.app.Activity
import dev.aterminal.backend.ssh.KeyCheck
import dev.aterminal.backend.ssh.SshBackend
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.aterminal.core.Profile
import dev.aterminal.core.Settings
import java.util.UUID

/**
 * Connect flow ทั้งหมด (plain-Activity, no Compose):
 *  - profile list (tap = connect / long-press = delete) + ปุ่ม Add
 *  - ฟอร์ม add SSH profile: name/host/port/user/auth(+key import ผ่าน SAF, +password prompt ตอน connect จริง)
 *  - password prompt (จำใน EncryptedSharedPreferences ได้)
 *  - TOFU host-key dialog (บล็อก MINA thread ด้วย latch — caller รับผิดชอบ timeout)
 */
class ConnectUi(private val act: MainActivity) {

    var pendingKeyAlias: String? = null   // ผล SAF import ล่าสุด (รอฟอร์ม save)
    private var importTarget: ((alias: String) -> Unit)? = null

    // ---------- profile list (task 7 round 23: slim = picker + Add เท่านั้น — action อื่นย้ายเข้า ⋮ overflow) ----------
    fun showProfileList() {
        // task 10 (round 23): ทุก dialog ในไฟล์นี้เปิดหลัง closeImeForMenus() — menu/dialog = user สั่งปิด IME
        // (ADR 0001: ห้าม IME ค้างทับ dialog และห้าม auto-reopen; pinned โดย CloseImePolicyGuardTest)
        // toast() ไม่-count = ไม่ใช่ dialog, ไม่มี IME flow
        act.closeImeForMenus()
        val profiles = visibleProfiles()
        val titles = profiles.map { p ->
            val tail = when (p.type) {
                "ssh" -> "  ${p.user ?: ""}@${p.host}:${p.port}"
                "termux" -> "  (Termux sshd 127.0.0.1:8022)"
                else -> "  (${p.type})"
            }
            (p.name ?: p.guid ?: "?") + tail
        } + listOf("➕ Add profile…")
        AlertDialog.Builder(act)
            .setTitle("New session")
            .setItems(titles.toTypedArray()) { d, which ->
                when {
                    which == profiles.size -> { d.dismiss(); showAddForm() }
                    else -> { d.dismiss(); act.connectProfile(profiles[which]) }
                }
            }
            .setNegativeButton("Close", null)
            .showGuarded(act)
    }

    /** โปรไฟล์ที่แสดงใน picker/list (filterHidden เดียวกันทุกทางเข้า — single source: extension ท้ายไฟล์) */
    internal fun visibleProfiles(): List<Profile> = act.settings.visibleProfiles()

    /**
     * ⋮ Profiles group (task 7 decision): active pane มี profile = ทำงานกับตัวนั้นตรง ๆ,
     * ไม่มี (blank tab) = list-pick fallback — dialog ทั้งหมด reuse ของเดิม (showProfileForm/showEditList)
     */
    internal fun editProfileOf(p: Profile?) {
        if (p != null) showProfileForm(p) else showEditList(visibleProfiles())
    }

    /** เหมือน editProfileOf — delete ยังผ่าน confirmDelete (มี warning เรื่อง password/key ติดไปด้วย) */
    internal fun deleteProfileOf(p: Profile?) {
        if (p != null) confirmDelete(p) else showDeleteList(visibleProfiles())
    }

    /** notices ของ dependency/ฟอนต์ที่ติดไปกับ APK (MIT/Apache/CC BY ต้องให้ผู้รับเข้าถึงได้) */
    internal fun showLicenses() {
        act.closeImeForMenus() // task 10 menu policy
        val text = act.assets.open("licenses/THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        AlertDialog.Builder(act)
            .setTitle("Open source licenses")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .showGuarded(act)
    }

    private fun showDeleteList(profiles: List<Profile>) {
        act.closeImeForMenus() // task 10 menu policy
        if (profiles.isEmpty()) { toast("no profiles"); return }
        AlertDialog.Builder(act)
            .setTitle("Delete which profile?")
            .setItems(profiles.map { it.name ?: it.guid ?: "?" }.toTypedArray()) { _, which ->
                confirmDelete(profiles[which])
            }
            .setNegativeButton("Back", null)
            .showGuarded(act)
    }

    private fun confirmDelete(p: Profile) {
        act.closeImeForMenus() // task 10 menu policy
        AlertDialog.Builder(act)
            .setTitle("Delete profile")
            .setMessage("Delete \"${p.name}\"? Its saved password or key is deleted too.")
            .setPositiveButton("Delete") { _, _ -> act.deleteProfile(p) }
            .setNegativeButton("Cancel", null)
            .showGuarded(act)
    }

    // ---------- add / edit profile (round 6: ฟอร์มเดียว, edit = prefill + คง guid/fields อื่น) ----------
    fun showAddForm() = showProfileForm(null)

    private fun showEditList(profiles: List<Profile>) {
        act.closeImeForMenus() // task 10 menu policy
        if (profiles.isEmpty()) { toast("no profiles"); return }
        AlertDialog.Builder(act)
            .setTitle("Edit which profile?")
            .setItems(profiles.map { it.name ?: it.guid ?: "?" }.toTypedArray()) { _, which ->
                showProfileForm(profiles[which])
            }
            .setNegativeButton("Back", null)
            .showGuarded(act)
    }

    /**
     * ฟอร์ม create/edit — round 6:
     *  - existing == null → profile ใหม่ (UUID + connect หลัง save = พฤติกรรมเดิมของ showAddForm)
     *  - existing != null → prefill, **คง guid + fields ที่ไม่อยู่ในฟอร์ม** (fontSize/scheme/...) ผ่าน copy,
     *    save แล้วไม่ auto-connect (config ใหม่มีผลเฉพาะ connect ใหม่ — tab ที่รันอยู่ไม่โดนแตะ)
     *  - type spinner: termux = host/port lock ที่ 127.0.0.1:8022 (ตรงกับ startSession), echo = ซ่อน host/auth
     */
    fun showProfileForm(existing: Profile?) {
        act.closeImeForMenus() // task 10 menu policy (form เปิด IME เองได้เฉพาะเมื่อ user tap field)
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val form = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val typeNames = listOf("ssh", "termux", "echo")
        val type = Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, typeNames)
            setSelection(typeNames.indexOf(existing?.type ?: "ssh").coerceAtLeast(0))
        }
        form.addView(TextView(act).apply { text = "Type" })
        form.addView(type)
        val nameLabel = TextView(act).apply { text = "Name"; visibility = TextView.GONE }
        val name = edit(form, "Name", existing?.name ?: "my-server")
        val hostLabel = TextView(act).apply { text = "Host"; visibility = TextView.GONE }
        val host = edit(form, "Host", existing?.host ?: "")
        val portLabel = TextView(act).apply { text = "Port"; visibility = TextView.GONE }
        val port = edit(form, "Port", (existing?.port ?: 22).toString(), number = true)
        val user = edit(form, "User", existing?.user ?: "")
        val authNames = listOf("Password", "Private key", "None")
        val auth = Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, authNames)
            setSelection(when (existing?.authMethod ?: "password") { "key" -> 1; "none" -> 2; else -> 0 })
        }
        val authLabel = TextView(act).apply { text = "Authentication" }
        form.addView(authLabel)
        form.addView(auth)
        val keepAlias = existing?.keyAlias
        val keyLabel = TextView(act).apply {
            text = if (keepAlias != null) "Key: $keepAlias (current)" else "Key: (none)"
            setPadding(0, pad / 2, 0, 0)
        }
        form.addView(keyLabel)
        val importBtn = android.widget.Button(act).apply {
            text = "Import private key…"
            visibility = TextView.GONE
            // ต้องเซฟ pendingKeyAlias ด้วย ไม่ใช่แค่ label — ไม่งั้น Save ปฏิเสธเสมอ (bug เจอบน device)
            setOnClickListener { pickKey { alias -> pendingKeyAlias = alias; keyLabel.text = "Key: $alias" } }
        }
        form.addView(importBtn)

        fun applyTypeVisibility() {
            val t = typeNames[type.selectedItemPosition]
            val hideNet = t == "termux" || t == "echo"
            host.visibility = if (hideNet) TextView.GONE else TextView.VISIBLE
            port.visibility = if (hideNet) TextView.GONE else TextView.VISIBLE
            // echo = ไม่ต้องมี network + auth section เลย (Save บังคับ none อยู่แล้ว — ซ่อนกันสับสน)
            user.visibility = if (t == "echo") TextView.GONE else TextView.VISIBLE
            authLabel.visibility = if (t == "echo") TextView.GONE else TextView.VISIBLE
            auth.visibility = if (t == "echo") TextView.GONE else TextView.VISIBLE
            val showAuth = t != "echo" && auth.selectedItemPosition == 1
            importBtn.visibility = if (showAuth) TextView.VISIBLE else TextView.GONE
            // edit profile เดิมที่ auth=key อยู่แล้ว: ให้เห็น alias ค้าง (เว้นแต่ import ใหม่ที่ label เปลี่ยนเอง)
            keyLabel.visibility = if (showAuth || (t != "echo" && keepAlias != null && auth.selectedItemPosition == 1)) TextView.VISIBLE else TextView.GONE
        }
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = applyTypeVisibility()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        auth.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = applyTypeVisibility()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        applyTypeVisibility()

        AlertDialog.Builder(act)
            .setTitle(if (existing == null) "Add profile" else "Edit profile")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val t = typeNames[type.selectedItemPosition]
                val h = host.text.toString().trim()
                if (t == "ssh" && h.isEmpty() && existing?.host == null) { toast("Host required"); return@setPositiveButton }
                val alias = when {
                    t == "echo" -> null
                    auth.selectedItemPosition == 1 -> pendingKeyAlias ?: keepAlias
                    else -> null
                }
                if (t != "echo" && auth.selectedItemPosition == 1 && alias == null) {
                    toast("Import private key before saving"); return@setPositiveButton
                }
                val base = existing ?: Profile(guid = UUID.randomUUID().toString())
                val p = base.copy(
                    name = name.text.toString().ifBlank { h.ifBlank { base.name ?: "profile" } },
                    type = t,
                    host = if (t == "termux") "127.0.0.1" else h.ifBlank { base.host },
                    port = if (t == "termux") 8022 else port.text.toString().toIntOrNull() ?: base.port,
                    user = user.text.toString().ifBlank { base.user },
                    authMethod = if (t == "echo") "none"
                        else when (auth.selectedItemPosition) { 1 -> "key"; 2 -> "none"; else -> "password" },
                    keyAlias = alias,
                )
                pendingKeyAlias = null
                act.saveProfile(p)
                if (existing == null) act.connectProfile(p) else toast("saved ${'$'}{p.name}")
            }
            .setNegativeButton("Cancel", null)
            .showGuarded(act)
    }

    private fun edit(parent: LinearLayout, hint: String, prefill: String, number: Boolean = false): EditText =
        EditText(act).apply {
            this.hint = hint
            setText(prefill)
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            parent.addView(this)
        }

    // ---------- SAF key import ----------
    fun pickKey(onDone: (String) -> Unit) {
        importTarget = onDone
        act.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQ_KEY)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_KEY || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val alias = "key-" + System.currentTimeMillis()
        try {
            val bytes = act.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.size > 128 * 1024) { toast("file too large (not a key?)"); return }
            if (SshBackend.inspectKey(bytes) == KeyCheck.INVALID) {
                bytes.fill(0)
                toast("This file is not a supported private key")
                return
            }
            act.secrets.importKey(alias, bytes)
            bytes.fill(0)
            importTarget?.invoke(alias) ?: run { pendingKeyAlias = alias }
            toast("Imported as $alias")
        } catch (e: Exception) {
            toast("import failed: ${e.message}")
        }
    }

    // ---------- passphrase ของ private key (ตอน connect) ----------
    fun promptPassphrase(onOk: (passphrase: String, remember: Boolean) -> Unit) {
        act.closeImeForMenus() // task 10 menu policy (IME ของ dialog ตัวเองเกิดเมื่อ user tap field)
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val pw = EditText(act).apply {
            hint = "passphrase"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // ไม่จำ = auto-reconnect ทำไม่ได้ จึงเปิดไว้เป็นค่าเริ่มต้นเหมือน password
        val remember = CheckBox(act).apply { text = "Remember (encrypted). Needed for auto-reconnect."; isChecked = true }
        box.addView(pw); box.addView(remember)
        AlertDialog.Builder(act)
            .setTitle("Private key passphrase")
            .setView(box)
            .setPositiveButton("Connect") { _, _ -> onOk(pw.text.toString(), remember.isChecked) }
            .setNegativeButton("Cancel", null)
            .showGuarded(act)
    }

    // ---------- password prompt (ตอน connect) ----------
    fun promptPassword(p: Profile, onOk: (password: String, remember: Boolean) -> Unit) {
        act.closeImeForMenus() // task 10 menu policy (IME ของ dialog ตัวเองเกิดเมื่อ user tap field)
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val pw = EditText(act).apply {
            hint = "password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val remember = CheckBox(act).apply { text = "Remember (encrypted)"; isChecked = true }
        box.addView(pw); box.addView(remember)
        AlertDialog.Builder(act)
            .setTitle("${p.user ?: ""}@${p.host}:${p.port}")
            .setView(box)
            .setPositiveButton("Connect") { _, _ -> onOk(pw.text.toString(), remember.isChecked) }
            .setNegativeButton("Cancel", null)
            .showGuarded(act)
    }

    // ---------- TOFU host key ----------
    /**
     * เรียกบน main thread ผ่าน coroutine — คืนค่าเมื่อผู้ใช้ตัดสินใจ
     * (ห้าม latch รอบน main thread: ปุ่ม dialog ก็ต้องรันบน main looper = deadlock — บทเรียนจาก device)
     */
    suspend fun tofuDialog(host: String, port: Int, algo: String, fingerprint: String): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            act.closeImeForMenus() // task 10 menu policy — dialog ห้ามเปิดทับคีย์บอร์ด
            val dlg = AlertDialog.Builder(act)
                .setTitle("Verify host key")
                .setMessage(
                    "First connection to $host:$port\n\n" +
                        "Key type: $algo\nSHA256: ${fingerprint.removePrefix("SHA256:")}\n\n" +
                        "Check this fingerprint against your server (ssh-keygen -lf /etc/ssh/ssh_host_*key.pub).\n" +
                        "Accept pins this key. If it changes later, the connection is refused to protect you from interception."
                )
                .setCancelable(false)
                .setPositiveButton("Accept & pin") { _, _ -> if (!cont.isCompleted) cont.resumeWith(Result.success(true)) }
                .setNegativeButton("Reject") { _, _ -> if (!cont.isCompleted) cont.resumeWith(Result.success(false)) }
                .setOnCancelListener { if (!cont.isCompleted) cont.resumeWith(Result.success(false)) }
                .showGuarded(act)
            // R5: dialog ไม่ถูก show (activity ตาย) = ไม่มี callback มา resume cont เลย → connect ค้างนิรันดร์
            // fail-safe = Reject (เช่นเดียวกับ cancel)
            if (dlg == null && !cont.isCompleted) cont.resumeWith(Result.success(false))
        }

    // ---------- re-pin host key (host ที่ pin แล้ว key เปลี่ยน เช่น server ลงใหม่) ----------
    /**
     * เรียกบน main thread ผ่าน coroutine — ลายเซ็นเดียวกับ tofuDialog (fail-safe = Reject)
     * ข้อความมาจาก RepromptPolicy.warningMessage (มี test): ผู้ใช้ต้องเห็น fingerprint เก่า+ใหม่ก่อน — ห้ามเปลี่ยน pin เงียบ
     */
    suspend fun rePinDialog(
        host: String, port: Int,
        oldAlgo: String, oldFingerprint: String,
        newAlgo: String, newFingerprint: String,
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        act.closeImeForMenus() // task 10 menu policy — dialog ห้ามเปิดทับคีย์บอร์ด
        val dlg = AlertDialog.Builder(act)
            .setTitle("Host key has changed")
            .setMessage(RepromptPolicy.warningMessage(host, port, oldAlgo, oldFingerprint, newAlgo, newFingerprint))
            .setCancelable(false)
            .setPositiveButton("Accept new key") { _, _ -> if (!cont.isCompleted) cont.resumeWith(Result.success(true)) }
            .setNegativeButton("Reject") { _, _ -> if (!cont.isCompleted) cont.resumeWith(Result.success(false)) }
            .setOnCancelListener { if (!cont.isCompleted) cont.resumeWith(Result.success(false)) }
            .showGuarded(act)
        if (dlg == null && !cont.isCompleted) cont.resumeWith(Result.success(false))
    }

    fun toast(msg: String) {
        // R5: toast บน activity ตาย = noise (ไม่ crash แต่ไร้ประโยชน์) — ทิ้ง
        if (canShowDialog(act.isFinishing, act.isDestroyed)) Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
    }

    companion object { const val REQ_KEY = 0x51 }
}

/**
 * แหล่งเดียวของ filter hidden-profile (parked (n), fix wave): picker ของ ConnectUi, barSnap ของ
 * MainActivity (เมนู ∨) และ lookup ชื่อจาก ∨ picker ต้องอ่านผ่าน fun นี้ทั้งหมด —
 * การเขียน filterNot { it.hidden } ซ้ำเป็นที่สาม = picker สองอันที่ drift กันได้
 * (comment "ห้ามมี list versionที่สอง" เดิมห้ามด้วยข้อความ ตอนนี้ห้ามด้วย implementation เดียว)
 */
internal fun Settings.visibleProfiles(): List<Profile> = profiles.list.filterNot { it.hidden }
