# God of War III Remastered · Android Port

<p align="center">
  <strong>God of War III Remastered · Android / ARM64 port</strong>
</p>

<p align="center">
  Bring the PS4 version of <em>God of War III Remastered</em> to Android devices.
  <br />
  Powered by FEXCore, Vulkan / Turnip, and a game-specific runtime.
  <br />
  <strong>Work in progress.</strong>
</p>

<p align="center">
  <a href="docs/QUICKSTART.md">Quickstart</a> ·
  <a href="#system-requirements">System requirements</a> ·
  <a href="#how-it-works">How it works</a> ·
  <a href="docs/gow3/ROADMAP.md">Roadmap</a>
</p>

<p align="center">
  <a href="README.zh-CN.md">简体中文</a> ·
  <strong>English</strong> ·
  <a href="README.pt-BR.md">Português (Brasil)</a>
</p>

<p align="center">
  <a href="#project-status"><img src="https://img.shields.io/badge/status-work--in--progress-f0a000?style=flat-square" alt="Status: work in progress" /></a>
  <a href="#system-requirements"><img src="https://img.shields.io/badge/platform-Android%20%7C%20ARM64-3ddc84?style=flat-square" alt="Platform: Android and ARM64" /></a>
  <a href="#how-it-works"><img src="https://img.shields.io/badge/graphics-Vulkan%20%7C%20Turnip-6f42c1?style=flat-square" alt="Graphics: Vulkan and Turnip" /></a>
  <a href="#license"><img src="https://img.shields.io/badge/license-GPL--2.0--or--later-blue?style=flat-square" alt="License: GPL 2.0 or later" /></a>
</p>

<table align="center">
  <tr>
    <td align="center" width="42%">
      <img src="docs/imgs/a.jpg" alt="God of War III gameplay screenshot one" width="100%" />
      <sub>Gameplay · Vulkan rendering</sub>
    </td>
    <td align="center" width="42%">
      <img src="docs/imgs/b.jpg" alt="God of War III gameplay screenshot two" width="100%" />
      <sub>Gameplay · Android device</sub>
    </td>
  </tr>
</table>

<p align="center">
  <img src="docs/imgs/home.jpg" alt="Android launcher home screen" width="28%" />
  <br />
  <sub>Android launcher · Runtime installation and launch</sub>
</p>

The **Android / ARM64 port** of *God of War III Remastered* (PlayStation 4) is under development.

The game's original executable is x86-64 (the PS4 uses the same architecture). On an Android
device, the game code is dynamically translated by **FEXCore** and runs on ARM64; a runtime
written specifically for this game replaces the PS4 system libraries, and the graphics are
translated to Vulkan through the Turnip driver for Adreno GPUs. This is not a generic emulator:
the whole project serves this one game. The project forks the
[R-V-L port](https://github.com/R-V-L/gow3_pc), which is based on
[deadinside28's bloodborne_pc](https://github.com/deadinside28/bloodborne_pc), and includes
God of War III fixes from [cuesta4's shadPS4 fork](https://github.com/cuesta4/shadPS4)
(see [Acknowledgments](#acknowledgments)).
The direct foundation of this project is `gow3_pc`; for the Android adaptation, it also draws
from parts of the implementation of [ducvd89's Bloodborne-Android](https://github.com/ducvd89/Bloodborne-Android),
including the runtime, system services, input, logging, and launcher. Bloodborne-Android is an
important Android reference project, but it is not this project's direct parent project.
The project also uses parts of the Android / FEX-related implementation from
[fexdroid](https://github.com/cobrabm12/fexdroid).

> [!IMPORTANT]
> **This project contains no game files.** You need game files dumped from your own PS4
> (currently tested against CUSA01740, version 01.02). This project is not affiliated with
> Sony Interactive Entertainment or Santa Monica Studio. God of War is a trademark of Sony
> Interactive Entertainment.

## Project status

| Area | Current status |
| --- | --- |
| Android / ARM64 runtime chain | 🚧 In development; ongoing real-device validation |
| Vulkan / Turnip graphics | ✅ Integrated |
| Kotlin / Compose launcher | ✅ Integrated |
| Stable performance numbers | ⏳ Not available yet |

> This is a game-specific port, not a general-purpose PS4 emulator.

## Quickstart

The full tutorial from getting the project to running debug (Android port, macOS Apple Silicon
host) is in **[docs/QUICKSTART.md](docs/QUICKSTART.md)**.

## Performance

The Android side is still being validated on real devices; no stable performance numbers are
available yet.

## Implemented features

- **Runtime chain**: x86-64 game code runs on ARM64 through FEXCore (`cpu/`, `libgow3cpu.so`),
  while the ARM64 shadPS4 graphics core (`libgow3gpu.so`) translates graphics to Vulkan and
  connects to Adreno GPUs through Turnip.
- **Kotlin / Compose launcher** (`android-launcher/`): installs the runtime with SHA-256
  validation and manages the Surface, input, audio, settings, and process lifecycle.
- The Android port reuses the desktop build's game runtime, PS4 system library reimplementation,
  graphics core, and patch logic. Resolution, FPS, and cheat patches are still awaiting byte-level
  validation on the Android build.

## System requirements

- **ARM64 (arm64-v8a) Android device**, no root required.
- A Vulkan-capable GPU with a reasonably recent driver (Turnip; Adreno devices).
- Your own game copy: **CUSA01740 with the 01.02 update applied** (a folder containing
  `eboot.bin`, `sce_module`, and `sce_sys`). No game files are included in the downloads.

Building the Android side requires a host machine (the current flow is based on macOS Apple
Silicon); dependencies and steps are in [docs/QUICKSTART.md](docs/QUICKSTART.md).

## How it works

| Module | Location | Purpose |
| ------ | -------- | ------- |
| Prepare | `scripts/` | Reads `eboot.bin`, applies relocations, and links the game's bundled `libc` and `libSceFios2` into a single image |
| Loader | `src/probe.c` | Loads the image, installs validated hooks (cheats, multipliers, health bars), and starts the game's original code |
| Runtime | `src/runtime_*.c` | Reimplements PS4 system functions: memory, threads, files, audio, video, gamepad, saves, and services |
| GPU | `gpu/shadps4/` | Translates PS4 GPU instructions to Vulkan and includes performance optimizations such as background shader caching and deferred readback |
| In-game menu | `gpu/shim/gow3_overlay.cpp` | Dear ImGui menu and performance overlay; settings live in `gpu/shim/gow3_settings.cpp` |
| Patches | `patches/God_of_War_III_Remastered.xml`, `scripts/patches.py` | Applied in memory at startup; game files are never modified |
| Launch | `android-launcher/` | The Android launcher prepares the image and starts `gow3-probe` |

More docs: [Mods](docs/MODS.md), [Upscaler & frame generation](docs/upscaler.md),
[Parallel GPU work](docs/parallel_gpu.md), and the [Roadmap](docs/gow3/ROADMAP.md).

## Acknowledgments

This project forks R-V-L's Linux port, which in turn derives from FelipePrado0's original
project:

- **deadinside28**: [bloodborne_pc](https://github.com/deadinside28/bloodborne_pc), the original native port.
- **gow3_pc** (R-V-L): [bloodborne_pc for Windows](https://github.com/R-V-L/gow3_pc), the branch this project is based on.
- **cuesta4**: [shadPS4 fork focused on God of War III Remastered](https://github.com/cuesta4/shadPS4) (branch `eltutz`). This port uses its GNM hybrid rewrite, depth/stencil state, and image alias fixes.
- **shadPS4 team**: [shadPS4](https://github.com/shadps4-emu/shadPS4) renderer.
- **fexdroid**: [fexdroid](https://github.com/cobrabm12/fexdroid).
- **ducvd89**: [Bloodborne-Android](https://github.com/ducvd89/Bloodborne-Android), whose Android runtime, system services, input, logging, and launcher implementations were referenced and partially ported here.

Patches: kvicken and cuesta4 (resolution, texture fixes, 120 FPS, and video skipping, based on
illusion0001's PS3 patches), published in shadPS4's
[shadps4-emu/ps4_cheats#145](https://github.com/shadps4-emu/ps4_cheats/pull/145) and adapted
for this executable. The 240 FPS patch was adapted from the 120 FPS patch by Felipe Prado. The
cheats are adapted from Celogamez's [GoldHEN cheats](https://github.com/GoldHEN/GoldHEN_Cheat_Repository).

Also used: [LibAtrac9](https://github.com/Thealexbarney/LibAtrac9), [FFmpeg](https://ffmpeg.org),
[SDL3](https://github.com/libsdl-org/SDL), [Dear ImGui](https://github.com/ocornut/imgui),
[sirit](https://github.com/shadps4-emu/sirit), [magic_enum](https://github.com/Neargye/magic_enum),
[miniz](https://github.com/richgel999/miniz), [xbyak](https://github.com/herumi/xbyak),
[Vulkan Memory Allocator](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator),
[FSR-Vulkan](https://github.com/FireBurn/FSR-Vulkan) and the AMD FidelityFX SDK,
[LLVM](https://llvm.org), and [Pillow](https://python-pillow.org).

## License

GNU GPL v2 or later (see [LICENSE](LICENSE)). Third-party components retain their own licenses.
