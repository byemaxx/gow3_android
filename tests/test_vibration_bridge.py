import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNTIME_PAD = ROOT / "src/runtime_pad.c"
PAD_BRIDGE = ROOT / "android-launcher/app/src/main/java/com/gow3/android/game/PadBridge.java"
GAME_ACTIVITY = ROOT / "android-launcher/app/src/main/java/com/gow3/android/game/GameActivity.java"
RUN_BIONIC = ROOT / "tools/android/device/run-bionic.sh"
SETTINGS_CPP = ROOT / "gpu/shim/gow3_settings.cpp"
SETTINGS_H = ROOT / "gpu/shim/gow3_settings.h"
GOW3_GPU = ROOT / "gpu/shim/gow3gpu.cpp"


class VibrationSettingsContractTest(unittest.TestCase):
    """上游 9d0f51c 震动设置链：解析、裁剪、env 覆盖与缩放（full/reduced/disabled 场景）。"""

    def test_settings_parse_clamps_and_defaults(self):
        """full=100 默认；reduced 按值；disabled=0；越界裁剪到 0..100。"""
        source = SETTINGS_CPP.read_text()
        self.assertIn('key == "vibration"', source)
        self.assertIn("value.empty() ? 100 : std::clamp(i, 0, 100)", source)
        header = SETTINGS_H.read_text()
        self.assertIn("std::atomic<int> vibration{100};", header)
        self.assertIn("0 turns it off", header)

    def test_env_override_is_registered(self):
        """GOW3_VIBRATION env 覆盖注册存在（missing env 时回落 ini/默认）。"""
        self.assertIn('{"GOW3_VIBRATION", "vibration"}', SETTINGS_CPP.read_text())

    def test_scaling_applies_percent_and_clamps(self):
        """scePadSetVibration 参数按百分比缩放，percent>100 裁剪。"""
        source = RUNTIME_PAD.read_text()
        self.assertIn("strength * 257u * percent / 100u", source)
        self.assertIn("if (percent > 100) percent = 100;", source)

    def test_vibration_percent_bridge_symbol_exists(self):
        """GPU shim 导出 gow3gpu_vibration_percent（设置 -> pad 运行时的桥）。"""
        self.assertIn("gow3gpu_vibration_percent(void)", GOW3_GPU.read_text())


class RumbleBridgeContractTest(unittest.TestCase):
    def test_single_android_output_channel(self):
        source = GAME_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn('vibration=new VibrationBridge(this,new File(BASE,"android-pad.state"))', source)
        self.assertIn("vibration.pause()", source)
        self.assertIn("vibration.resume()", source)
        self.assertNotIn("startRumble(", source)
        self.assertNotIn("GOW3_PAD_RUMBLE_FILE", RUN_BIONIC.read_text(encoding="utf-8"))

    def test_native_snapshot_round_trip(self):
        import shutil
        import subprocess
        import tempfile
        compiler = shutil.which("cc")
        if not compiler:
            self.skipTest("host C compiler is required")
        with tempfile.TemporaryDirectory(prefix="gow3-rumble-") as directory:
            executable = str(Path(directory) / "vibration-ipc")
            subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-I.",
                            "tests/test_android_vibration.c", "-o", executable], cwd=ROOT, check=True)
            subprocess.run([executable], check=True)


if __name__ == "__main__":
    unittest.main()
