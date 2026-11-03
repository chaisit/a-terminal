package dev.aterminal.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** เขียนไฟล์แบบ all-or-nothing: แอปหรือเครื่องดับกลางคัน ไฟล์เดิมยังอยู่ครบ */
object AtomicText {
    fun write(target: File, text: String) = write(target, text.toByteArray())

    fun write(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(data)
                out.fd.sync() // ต้องลงดิสก์ก่อน rename ไม่งั้นไฟดับแล้วได้ไฟล์ว่าง
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }
}
