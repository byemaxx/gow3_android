# God of War III Remastered · Port para Android

[简体中文](README.zh-CN.md) | [English](README.md) | **Português (Brasil)**

<p align="center">
  <strong>God of War III Remastered · port para Android / ARM64</strong>
</p>

<p align="center">
  Leve a versão de PS4 de <em>God of War III Remastered</em> para dispositivos Android.
  <br />
  Desenvolvido com FEXCore, Vulkan / Turnip e um runtime específico para o jogo.
  <br />
  <strong>Em desenvolvimento.</strong>
</p>

<p align="center">
  <a href="docs/QUICKSTART.md">Início rápido</a> ·
  <a href="#requisitos">Requisitos</a> ·
  <a href="#como-funciona">Como funciona</a> ·
  <a href="docs/gow3/ROADMAP.md">Roadmap</a>
</p>

<p align="center">
  <a href="#status-do-projeto"><img src="https://img.shields.io/badge/status-work--in--progress-f0a000?style=flat-square" alt="Status: em desenvolvimento" /></a>
  <a href="#requisitos"><img src="https://img.shields.io/badge/platform-Android%20%7C%20ARM64-3ddc84?style=flat-square" alt="Plataforma: Android e ARM64" /></a>
  <a href="#como-funciona"><img src="https://img.shields.io/badge/graphics-Vulkan%20%7C%20Turnip-6f42c1?style=flat-square" alt="Gráficos: Vulkan e Turnip" /></a>
  <a href="#licença"><img src="https://img.shields.io/badge/license-GPL--2.0--or--later-blue?style=flat-square" alt="Licença: GPL 2.0 ou posterior" /></a>
</p>

<p align="center">
  <img src="docs/imgs/a.jpg" alt="Captura de tela do jogo 1" width="45%"/>
  <img src="docs/imgs/b.jpg" alt="Captura de tela do jogo 2" width="45%"/>
</p>

<p align="center">
  <img src="docs/imgs/home.jpg" alt="Tela inicial do launcher Android" width="28%" />
  <br />
  <sub>Launcher Android · Instalação e inicialização do runtime</sub>
</p>

Este documento é uma tradução em português do README do repositório.

O **port para Android / ARM64** de *God of War III Remastered* (PlayStation 4) — em
desenvolvimento, veja abaixo.

O executável original do jogo é x86-64 (o PS4 usa a mesma arquitetura). Num dispositivo Android,
o código do jogo é traduzido dinamicamente pelo **FEXCore** e roda em ARM64; um runtime escrito
para este jogo substitui as bibliotecas de sistema do PS4, e os gráficos são traduzidos para
Vulkan (ligados à GPU Adreno pelo driver Turnip). Não é um emulador genérico: o projeto atende um
único jogo. Este projeto é um fork do [port do R-V-L](https://github.com/R-V-L/gow3_pc), que se
baseia no [bloodborne_pc de deadinside28](https://github.com/deadinside28/bloodborne_pc) e traz as
correções de God of War III do [fork do shadPS4 de cuesta4](https://github.com/cuesta4/shadPS4)
(veja os [Créditos](#créditos)).
O fundamento direto deste projeto é o `gow3_pc`; para a adaptação Android, também foram usados como
referência e portados alguns componentes do [Bloodborne-Android de ducvd89](https://github.com/ducvd89/Bloodborne-Android),
incluindo runtime, serviços do sistema, entrada, logs e launcher. O Bloodborne-Android é uma referência
importante para a parte Android, mas não é o projeto pai direto deste port.
O projeto também usa partes da implementação relacionada a Android / FEX do
[fexdroid](https://github.com/cobrabm12/fexdroid).

> **Nenhum arquivo do jogo está incluído.** Você precisa de arquivos do jogo extraídos do seu
> próprio PS4 (testados atualmente com CUSA01740, versão 01.02). Este projeto não tem relação com
> a Sony Interactive Entertainment nem com o Santa Monica Studio. God of War é marca registrada da
> Sony Interactive Entertainment.

## Status do projeto

| Área | Status atual |
| --- | --- |
| Runtime Android / ARM64 | 🚧 Em desenvolvimento; validação contínua em aparelhos reais |
| Gráficos Vulkan / Turnip | ✅ Integrado |
| Launcher Kotlin / Compose | ✅ Integrado |
| Números estáveis de desempenho | ⏳ Ainda não disponíveis |

> Este é um port específico para um jogo, não um emulador geral de PS4.

## Início rápido

O tutorial completo, do zero até rodar o debug (port Android, host macOS Apple Silicon), está em
**[docs/QUICKSTART.md](docs/QUICKSTART.md)**.

## Desempenho

O lado Android ainda está em validação em aparelhos reais; ainda não há números estáveis de
desempenho.

## O que funciona

- **Cadeia de execução**: o código x86-64 do jogo roda em ARM64 pelo FEXCore (`cpu/`,
  `libgow3cpu.so`), e os gráficos são traduzidos para Vulkan pela compilação ARM64 do núcleo
  gráfico do shadPS4 (`libgow3gpu.so`), ligado à GPU Adreno via Turnip.
- **Launcher em Kotlin/Compose** (`android-launcher/`): instala o runtime (validação por
  SHA-256), gerencia a Surface, entrada, áudio, configurações e o ciclo de vida do processo.

- Reutiliza o mesmo runtime do jogo, a reimplementação das bibliotecas de sistema do PS4, o
  núcleo gráfico e a lógica de patches da versão para desktop; o lado Android está em validação
  em aparelhos reais, e os patches de resolução, FPS e trapaças ainda não passaram pela validação
  byte a byte.

## Requisitos

- **Dispositivo Android ARM64 (arm64-v8a)**, sem necessidade de root.
- GPU compatível com Vulkan com driver razoavelmente recente (Turnip; dispositivos Adreno).
- Sua própria cópia do jogo: **CUSA01740 com a atualização 01.02 aplicada** (uma pasta com
  `eboot.bin`, `sce_module`, `sce_sys`). Nenhum arquivo do jogo vem nos downloads.

Compilar o lado Android exige um host (o fluxo atual é baseado em macOS Apple Silicon); as
dependências e os passos estão em [docs/QUICKSTART.md](docs/QUICKSTART.md).

## Como funciona

| Parte | Onde | O que faz |
|---|---|---|
| Preparação | `scripts/` | Lê o `eboot.bin`, aplica as realocações e junta a `libc` e a `libSceFios2` do próprio jogo numa imagem |
| Loader | `src/probe.c` | Carrega a imagem, instala os ganchos validados (trapaças, multiplicadores, barra de vida) e inicia o código original do jogo |
| Runtime | `src/runtime_*.c` | Reimplementa as funções de sistema do PS4: memória, threads, arquivos, áudio, vídeo, controle, saves e serviços |
| GPU | `gpu/shadps4/` | O núcleo de vídeo do shadPS4, que traduz os comandos da GPU do PS4 para Vulkan, com as otimizações deste port (cache de shaders em segundo plano, leituras adiadas) |
| Menu do jogo | `gpu/shim/gow3_overlay.cpp` | Menu e contador de desempenho em Dear ImGui; configurações em `gpu/shim/gow3_settings.cpp` |
| Patches | `patches/God_of_War_III_Remastered.xml`, `scripts/patches.py` | Aplicados na memória ao iniciar, sem alterar os arquivos do jogo |
| Inicialização | `android-launcher/` | No Android o launcher prepara a imagem e inicia o `gow3-probe` |

Mais documentação: [mods](docs/MODS.md), [mapa do quadro e upscaler](docs/upscaler.md),
[trabalho paralelo da GPU](docs/parallel_gpu.md) e o [roadmap](docs/gow3/ROADMAP.md).

## Créditos

Este projeto é um fork do port Linux do R-V-L, que por sua vez deriva do projeto original de
FelipePrado0:

- **deadinside28**: [bloodborne_pc](https://github.com/deadinside28/bloodborne_pc), o port nativo
  original.
- **gow3_pc** (R-V-L): [bloodborne_pc para Windows](https://github.com/R-V-L/gow3_pc), a branch
  da qual este projeto parte.
- **cuesta4**: [fork do shadPS4 focado em God of War III Remastered](https://github.com/cuesta4/shadPS4)
  (branch `eltutz`). Este port usa dele a regravação de blends da GNM, o estado de profundidade e
  stencil e as correções de imagens que compartilham memória.
- **Equipe do shadPS4**: o renderizador do [shadPS4](https://github.com/shadps4-emu/shadPS4).
- **fexdroid**: [fexdroid](https://github.com/cobrabm12/fexdroid).
- **ducvd89**: [Bloodborne-Android](https://github.com/ducvd89/Bloodborne-Android), cujas implementações de runtime Android,
  serviços do sistema, entrada, logs e launcher foram usadas como referência e parcialmente portadas para este projeto.

Patches: kvicken e cuesta4 (resolução, correção de texturas, 120 FPS e pular vídeos, a partir dos
patches de PS3 de illusion0001), publicados para o shadPS4 em
[shadps4-emu/ps4_cheats#145](https://github.com/shadps4-emu/ps4_cheats/pull/145) e adaptados a
este executável. O patch de 240 FPS foi adaptado por Felipe Prado a partir do de 120 FPS.
Trapaças: adaptadas dos [cheats GoldHEN](https://github.com/GoldHEN/GoldHEN_Cheat_Repository) de
Celogamez.

Também usados: [LibAtrac9](https://github.com/Thealexbarney/LibAtrac9),
[FFmpeg](https://ffmpeg.org), [SDL3](https://github.com/libsdl-org/SDL),
[Dear ImGui](https://github.com/ocornut/imgui), [sirit](https://github.com/shadps4-emu/sirit),
[magic_enum](https://github.com/Neargye/magic_enum), [miniz](https://github.com/richgel999/miniz),
[xbyak](https://github.com/herumi/xbyak),
[Vulkan Memory Allocator](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator),
[FSR-Vulkan](https://github.com/FireBurn/FSR-Vulkan) e o AMD FidelityFX SDK,
[LLVM](https://llvm.org) e [Pillow](https://python-pillow.org).

## Licença

GNU GPL v2 ou posterior ([LICENSE](LICENSE)). Componentes de terceiros mantêm suas licenças.
