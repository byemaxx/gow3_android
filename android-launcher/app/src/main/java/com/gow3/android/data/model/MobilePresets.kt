package com.gow3.android.data.model

import com.gow3.android.data.PatchCatalog


import com.gow3.android.R

internal data class MobilePreset(val resolution: String, val labelRes: Int) {
    // JSON 提供分辨率、60 帧目标和补丁组合；其余图形项采用启动器的移动端默认值。
    // vibration 与 display_stretch 是个人偏好（振动强度、显示端拉伸），不属于预设
    // 管理的渲染项：预设不写它们，匹配时也不要求它们等于默认值。
    val settings: Map<String, String> get() = LaunchParams.all
        .filter { it.key != "vibration" && it.key != "display_stretch" }
        .associate { it.key to it.defaultValue } + ("render_resolution" to resolution)
}


internal object MobilePresets {
    val all = listOf(
        MobilePreset("480p", R.string.mobile_preset_480p),
        MobilePreset("540p", R.string.mobile_preset_light),
        MobilePreset("720p", R.string.mobile_preset_balanced),
        MobilePreset("900p", R.string.mobile_preset_clear),
        MobilePreset("1080p", R.string.mobile_preset_hd),
    )
    // 预设只挂钩分辨率档位（经 render_resolution 联动选择分辨率补丁）与六项画面
    // 特效关闭补丁；视频跳过类补丁（Skip Intro、按 X 跳过视频）是玩家个人选择，
    // 不被预设连带开启，也不参与预设匹配判定（跳过开场动画由首页专用开关管理）。
    private val patchNames = setOf(
        "Disable SSAO", "Disable Motion Blur", "Disable Depth of Field", "Disable Bloom",
        "Disable Soft Particles", "Disable Dynamic Shadows",
    )

    fun available(catalog: PatchCatalog?): List<MobilePreset> {
        if (catalog == null || !catalog.patches.map { it.name }.containsAll(patchNames)) return emptyList()
        val params = LaunchParams.forCatalog(catalog).associateBy { it.key }
        return all.filter { preset ->
            preset.settings.all { (key, value) ->
                params[key]?.let { param -> param.enabled && param.options.any { it.value == value } } == true
            }
        }
    }

    /** 仅更新预设管理的图形项及补丁，保留黑屏修复、其他游戏的选择和手柄等个人设置。 */
    fun updates(preset: MobilePreset, catalog: PatchCatalog, ini: Map<String, String>): Map<String, String> {
        require(preset in available(catalog))
        val selected = catalog.selected(ini + preset.settings) + patchNames
        return preset.settings + mapOf(
            catalog.selectionKey to catalog.patches.filter { it.name in selected }.joinToString(";") { it.name },
            "restart_unconfirmed" to "0",
        )
    }

    /** 从实际配置计算展示值，单项修改和补丁页修改都不会留下过期的预设标签。 */
    fun matching(catalog: PatchCatalog?, ini: Map<String, String>): MobilePreset? {
        if (catalog == null) return null
        val selected = catalog.selected(ini)
        if (!selected.containsAll(patchNames)) return null
        return available(catalog).firstOrNull { preset ->
            preset.settings.all { (key, value) -> ini[key] == value }
        }
    }
}
