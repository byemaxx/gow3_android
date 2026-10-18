package com.gow3.android

import com.gow3.android.data.Gow3Data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gow3DataTest {
    private fun tempDir(): File = Files.createTempDirectory("gow3data-test").toFile()

    @Test
    fun `prepare migrates old user config dll and logs`() {
        val files = tempDir()
        val old = File(files, "gow3port").apply { mkdirs() }
        File(old, "user/savedata/1/CUSA01623/AUTOSAVE").mkdirs()
        File(old, "user/savedata/1/CUSA01623/AUTOSAVE/data.bin").writeText("save")
        File(old, "gow3.ini").writeText("fps_limit=60\n")
        File(old, "Lossless.dll").writeBytes(byteArrayOf(1, 2, 3))
        File(old, "logs").mkdirs()
        File(old, "logs/android-game.log").writeText("log")
        val defaults = File(files, "default.ini").apply { writeText("fps_limit=30\n") }

        Gow3Data.prepare(files, defaults)

        assertEquals("save", File(files, "gow3data/user/savedata/1/CUSA01623/AUTOSAVE/data.bin").readText())
        assertEquals("fps_limit=60\n", File(files, "gow3data/gow3.ini").readText())
        assertTrue(File(files, "gow3data/Lossless.dll").isFile)
        assertEquals("log", File(files, "gow3data/logs/android-game.log").readText())
        assertTrue(File(files, "gow3data/.data-schema").isFile)
    }

    @Test
    fun `prepare does not overwrite existing user data`() {
        val files = tempDir()
        File(files, "gow3port").mkdirs()
        File(files, "gow3port/gow3.ini").writeText("old=1\n")
        File(files, "gow3data").mkdirs()
        File(files, "gow3data/gow3.ini").writeText("new=1\n")
        val defaults = File(files, "default.ini").apply { writeText("default=1\n") }

        Gow3Data.prepare(files, defaults)

        assertEquals("new=1\n", File(files, "gow3data/gow3.ini").readText())
    }

    @Test
    fun `prepare creates default config when no legacy config exists`() {
        val files = tempDir()
        val defaults = File(files, "default.ini").apply { writeText("fps_limit=30\n") }

        Gow3Data.prepare(files, defaults)

        assertEquals("fps_limit=30\n", Gow3Data.config(files).readText())
        assertTrue(Gow3Data.user(files).isDirectory)
        assertTrue(Gow3Data.logs(files).isDirectory)
    }

    // ---- 引擎 FPS 显示退役：prepare 把残留的 show_fps=1 归零（change design D7）----

    @Test
    fun `prepare rewrites legacy show_fps=1 to 0 keeping other lines`() {
        val files = tempDir()
        // 模拟旧版默认模板落盘的 gow3.ini（show_fps=1 + 其它键 + 注释）
        val legacy = File(files, "gow3port").apply { mkdirs() }
        File(legacy, "gow3.ini").writeText(
            "# base config\nfps_limit=60\nshow_fps=1 ; old default\nupscaler=fsr3\n"
        )

        Gow3Data.prepare(files, null)

        val text = Gow3Data.config(files).readText()
        assertTrue(text.contains("show_fps=0"))
        assertFalse(text.contains("show_fps=1"))
        assertTrue("其它键保留", text.contains("fps_limit=60\n") && text.contains("upscaler=fsr3\n"))
        assertTrue("注释保留", text.contains("# base config\n") && text.contains("old default"))
    }

    @Test
    fun `prepare keeps config untouched when show_fps already off or absent`() {
        val files = tempDir()
        val ini = File(files, "gow3port").apply { mkdirs() }.resolve("gow3.ini")
        // 已为 0：不触碰文件（幂等）
        ini.writeText("fps_limit=60\nshow_fps=0\n")
        val before = ini.readText()
        Gow3Data.prepare(files, null)
        assertEquals(before, File(files, "gow3data/gow3.ini").readText())

        // 键缺失：保持缺省（运行时默认即关），不新增行
        val files2 = tempDir()
        val ini2 = File(files2, "gow3port").apply { mkdirs() }.resolve("gow3.ini")
        ini2.writeText("fps_limit=30\n")
        Gow3Data.prepare(files2, null)
        assertEquals("fps_limit=30\n", File(files2, "gow3data/gow3.ini").readText())
    }

    @Test
    fun `prepare does not confuse show_fps with keys sharing the prefix`() {
        val files = tempDir()
        val legacy = File(files, "gow3port").apply { mkdirs() }
        File(legacy, "gow3.ini").writeText("show_fps_note=keep\nshow_fps=1\n")

        Gow3Data.prepare(files, null)

        val text = Gow3Data.config(files).readText()
        assertTrue("前缀相似键不受影响", text.contains("show_fps_note=keep\n"))
        assertTrue(text.contains("show_fps=0\n"))
    }
}
