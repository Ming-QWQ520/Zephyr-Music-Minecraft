# Zephyr Music · Minecraft

> 在 Minecraft 中播放网易云音乐 · Forge 1.20.1 客户端模组

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](./LICENSE)
[![Forge](https://img.shields.io/badge/Forge-47.4.20-orange.svg)](https://files.minecraftforge.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-17-red.svg)](https://adoptium.net/)

---

## 简介

**Zephyr Music** 是一个 Minecraft Forge 模组，让你在游戏中聆听网易云音乐。支持扫码/手机验证码/邮箱登录、歌单浏览、歌曲搜索、逐字歌词（yrc）、游戏内 HUD 显示、完整的播放控制以及与网易云一致的听歌打卡机制。

灵感来源于 [Zephyr Music 桌面播放器](https://github.com/)（Tauri 2 + Vue 3），将其核心功能移植到 Minecraft 客户端。

---

## v0.1.0 · 播放引擎修复与体验优化

- **修复无法正常播放** — 音频输出引擎重构为泵线程（pump）架构：`write()` 阻塞式写入有界队列形成实时背压，解码速度被播放速度锁住；此前每首歌几秒就跳下一首的问题已彻底修复（该 bug 源于旧的异步 write 竞态 + 误判 naturalEnd）。
- **修复音频数据竞争** — 写入前立即拷贝 PCM（JLayer 的 SampleBuffer 会复用内部数组），消除乱序/爆音。
- **HUD 默认尺寸缩小** — 悬浮面板 220×94 → 160×84，封面 44 → 32，进度区改为从面板顶部定位，隐藏控制键时不再重叠。
- **默认音质改为标准** — `audio_quality` 默认 `standard`（128k），设置内音质选项显示中文（标准/较高/极高/无损/Hi-Res）。
- **登录态跨版本保留** — 登录 cookie 改存独立文件 `config/zephyrmusic-login.json`，不再放在 Forge 配置里：模组版本更新 / 配置修正不再清除登录态；旧配置中的 cookie 会在首次运行时自动迁移。
- **移除设置中的 API 端点项** — API 地址改为内部配置，不再暴露在设置界面。
- **版本信息** — 版本号 0.1.0，作者 Ming。

## v2.0.0 · 毛玻璃 UI / UX 重构（历史）

- **毛玻璃质感界面** — 所有面板采用 SDF（有符号距离场）shader 绘制的抗锯齿圆角矩形，配合 `RenderTarget` 一次性背景模糊：界面打开时抓取一帧世界画面并做降采样高斯模糊，面板静态显示，不逐帧模糊，零卡顿。
- **音乐 HUD 悬浮面板模式** — 全新玻璃悬浮卡片：封面、歌名、可点击进度条、迷你控制键（上一首/播放暂停/下一首/随机/循环）、当前歌词行。HUD 上的按钮**真实可点击**（通过输入事件拦截实现），按 **F8** 进入编辑模式拖拽调整位置。
- **OpenAL 音频输出** — 播放引擎从 JavaSound 全面迁移到 OpenAL（Minecraft 自带的 LWJGL OpenAL Soft）：独立 ALC 设备/上下文、流式缓冲队列、精确样本级播放进度。
- **洞穴混响** — 设置 → 播放 → 洞穴混响（OpenAL EFX Reverb，默认关闭），一键进入洞穴听感。
- **播放控制补全** — 随机播放 / 单曲循环 / 顺序播放三模式切换、拖拽音量滑条、可拖拽进度条、玻璃质感控制栏。
- **开源协议** — 由 AGPL-3.0 变更为 **GPL-3.0**。

---

## 功能特性

### 登录方式
- **扫码登录** — 显示二维码，用网易云 App 扫码即可（自动轮询状态）
- **手机验证码登录** — 支持国家区号选择 + 60 秒冷却
- **邮箱登录** — 使用网易云邮箱密码登录
- **登录态持久化** — Cookie 自动保存到 Forge 配置，重启免登录
- **登录后自动跳转** — 登录成功自动进入歌单浏览器

### 歌单与播放
- **浏览个人歌单** — 自动拉取用户所有歌单
- **歌单全部歌曲** — 点击歌单查看完整歌曲列表
- **搜索歌曲** — 关键词搜索，单击播放，双击/右键加入队列
- **播放队列** — 完整队列管理，自动播放下一首
- **循环模式** — 单曲循环 / 顺序播放
- **音量控制** — 游戏内独立音量调节
- **多音质** — standard / higher / exhigh / lossless / hires

### 歌词显示
- **逐字歌词（yrc）** — 支持网易云新版 JSON 格式和旧版 `[ms,ms](ms,ms,0)字` 格式
- **卡拉 OK 模式** — 已唱字 / 当前字 / 未唱字三色渐变
- **居中显示** — 当前行始终居中，上下行渐变透明
- **异步加载** — 歌词加载不阻塞播放

### 游戏内 HUD
- **专辑封面** — 异步下载并缓存，显示在 HUD 左侧
- **AllMusic 风格** — 青色 (#00FFFF) 强调色，进度条带滑块指示器
- **完全可定制** — 无背景 / 无边框 / 自定义大小 / 自定义位置
- **锚点定位** — 支持四角锚点 + 偏移

### 听歌打卡（与 Zephyr Music 桌面版一致）
- **最近播放打卡** — `/scrobble`（非加密 eapi），新歌开始播放 2.5 秒后触发
- **听歌时长打卡** — `/scrobble/v1`（NCBL 加密 clientlog PLV/PLD），切歌/播放完毕时上报
- **精确时长跟踪** — 按实际播放位置增量累计，seek 不增加额外时长
- **场景覆盖** — 手动切歌 / 自动播放完 / 暂停后切歌 / 刚切歌就切下一首

### UI 界面
- **PlayerScreen** — 主播放器，大图歌词 + 封面 + 进度条
- **PlaylistBrowserScreen** — 歌单浏览器，双栏列表
- **SearchScreen** — 搜索界面，结果列表 + 队列操作
- **LoginScreen** — 三种登录方式切换
- **SettingsScreen** — 完整设置面板（F11 打开）

---

## 安装

### 环境要求
- Minecraft 1.20.1
- Forge 47.4.20（或兼容版本）
- Java 17

### 步骤
1. 安装 [Forge 1.20.1-47.4.20](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) 客户端
2. 下载 `zephyrmusic-1.0.0.jar`（[Releases](../../releases)）
3. 将 jar 文件放入 `.minecraft/mods/` 目录
4. 启动游戏

---

## 操作说明

### 按键绑定（可在控制设置中修改）

| 按键 | 功能 |
|------|------|
| `F6` | 打开播放器 |
| `F7` | 打开歌单浏览器 |
| `F8` | 播放 / 暂停 |
| `F9` | 下一首 |
| `F10` | 打开搜索界面 |
| `F11` | 打开设置界面 |

### 使用流程

1. **首次使用**：按 `F7` 打开歌单浏览器
2. **登录**：点击右上角 [登录]，选择扫码 / 手机 / 邮箱方式
3. **浏览歌单**：登录后自动跳转，点击歌单查看歌曲
4. **播放歌曲**：单击播放，双击 / 右键加入队列
5. **搜索歌曲**：按 `F10`，输入关键词搜索
6. **查看歌词**：按 `F6` 打开播放器，歌词居中显示
7. **自定义 UI**：按 `F11` 打开设置，调整 HUD / 歌词 / 主题

---

## 配置

配置文件位于 `.minecraft/config/zephyrmusic-client.toml`，所有选项均可在游戏内 `F11` 设置界面修改。

### HUD 设置
| 选项 | 默认值 | 说明 |
|------|--------|------|
| `hud_enabled` | `true` | 启用 HUD |
| `hud_anchor` | `top_left` | 锚点（top_left/top_right/bottom_left/bottom_right） |
| `hud_x` / `hud_y` | `12` / `12` | 偏移量 |
| `hud_width` | `160` | 面板宽度（120-480） |
| `hud_cover_size` | `40` | 封面大小（16-256，经典 HUD） |
| `hud_bg_opacity` | `0.0` | 背景透明度（0=透明，1=不透明） |
| `hud_show_border` | `false` | 显示边框 |
| `hud_show_cover` | `true` | 显示封面 |
| `hud_show_progress_bar` | `true` | 显示进度条 |
| `hud_show_lyrics` | `true` | 显示歌词 |
| `hud_lyrics_lines` | `3` | 歌词行数（1-12） |
| `hud_volume` | `0.6` | 音量（0.0-1.0） |

### 歌词设置
| 选项 | 默认值 | 说明 |
|------|--------|------|
| `lyric_mode` | `yrc` | 歌词模式（yrc/lrc/off） |
| `lyric_karaoke` | `true` | 卡拉 OK 逐字染色 |
| `lyric_active_color` | `0xFF00FFFF` | 当前行颜色（青色） |
| `lyric_other_color` | `0xFFCCCCCC` | 其他行颜色 |
| `lyric_word_played_color` | `0xFF00FFFF` | 已唱字颜色 |
| `lyric_word_current_color` | `0xFF80FFFF` | 当前字颜色 |
| `lyric_word_unplayed_color` | `0xFF888888` | 未唱字颜色 |

### 主题色
| 选项 | 默认值 | 说明 |
|------|--------|------|
| `theme_primary` | `#00FFFF` | 主题色（青色） |
| `theme_accent` | `#00FFFF` | 强调色 |
| `theme_bg` | `#2A1F12` | 背景色（木质棕） |

### 通用
| 选项 | 默认值 | 说明 |
|------|--------|------|
| `api_base` | `https://musicapi.mingqwq.top` | 网易云 API 地址（内部，设置界面已移除该项） |
| `audio_quality` | `standard` | 默认音质（standard=128k / higher=192k / exhigh=320k / lossless / hires） |
| `scrobble_enabled` | `true` | 听歌打卡开关 |

### 登录态

登录 cookie 存储在独立文件 `config/zephyrmusic-login.json`，**不受 Forge 配置修正与模组版本更新影响**。旧版本存储在 `zephyrmusic-client.toml` 的 `auth.cookie` 中的登录态会在首次运行 v0.1.0+ 时自动迁移。

---

## 技术栈

| 层 | 技术 |
|---|---|
| 平台 | Forge 1.20.1-47.4.20 |
| 语言 | Java 17 |
| HTTP | Java 11+ HttpClient（异步） |
| JSON | Gson（Minecraft 内置） |
| 音频解码 | JLayer 1.0.1 (LGPL) |
| 音频输出 | Java Sound API (SourceDataLine) |
| API | [musicapi.mingqwq.top](https://musicapi.mingqwq.top/docs/) |

---

## 项目结构

```
src/main/java/com/zephyr/music/
├── ZephyrMusic.java                 # 模组主类
├── api/                             # 网易云 API 封装
│   ├── NeteaseApi.java              # API 接口
│   ├── NeteaseSession.java          # 会话管理
│   ├── NeteaseUser.java             # 用户数据
│   ├── NeteaseSong.java             # 歌曲数据
│   ├── NeteasePlaylist.java         # 歌单数据
│   ├── LyricLine.java               # 歌词行
│   └── LyricWord.java               # 逐字歌词
├── client/
│   ├── ClientEventHandler.java     # 按键 & 事件
│   ├── audio/
│   │   ├── MusicPlayer.java         # 音频播放引擎
│   │   ├── ScrobbleManager.java     # 听歌打卡管理
│   │   └── CoverTextureManager.java # 封面纹理管理
│   ├── gui/
│   │   ├── ModernUI.java            # 现代化 UI 工具
│   │   └── screen/
│   │       ├── PlayerScreen.java
│   │       ├── PlaylistBrowserScreen.java
│   │       ├── SearchScreen.java
│   │       ├── LoginScreen.java
│   │       └── SettingsScreen.java
│   └── hud/
│       └── MusicHudOverlay.java     # 游戏内 HUD
├── config/
│   └── ZephyrConfig.java            # 配置定义
└── net/
    └── NeteaseHttpClient.java       # HTTP 客户端

src/main/java/javazoom/jl/           # JLayer 源码 (LGPL)
src/main/resources/javazoom/jl/decoder/
├── sfd.ser                          # JLayer MDCT 系数表
├── au2lin.ser
├── l3reorder.ser
└── lin2au.ser
```

---

## 从源码构建

### 环境要求
- JDK 17
- 网络连接（首次构建需下载 Minecraft mappings）

### 步骤
```bash
# 克隆仓库
git clone https://github.com/Ming-QWQ520/Zephyr-Music-Minecraft.git
cd Zephyr-Music-Minecraft

# 编译并打包
./gradlew build

# 生成的 jar 在 build/libs/zephyrmusic-1.0.0.jar
```

---

## 致谢

- [Zephyr Music 桌面播放器](https://github.com/) — 灵感来源与打卡机制参考
- [AllMusic](https://github.com/Coloryr/AllMusic) — UI 设计参考（青色主题风格）
- [JLayer](http://www.javazoom.net/javalayer/javalayer.html) — MP3 解码库 (LGPL)
- [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi) — API 文档
- [musicapi.mingqwq.top](https://musicapi.mingqwq.top/docs/) — API 服务

---

## 许可证

本项目基于 [GNU Affero General Public License v3.0](./LICENSE) 开源。

JLayer 库基于 LGPL 许可，源码包含在 `javazoom.jl` 包中。

---

## 免责声明

本项目仅供个人学习使用，下载后请于 24 小时内删除。使用者需自行承担使用本模组产生的一切法律责任。本项目与网易云音乐官方无任何关联。
