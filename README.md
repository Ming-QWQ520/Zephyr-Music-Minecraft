# Zephyr Music · Minecraft

> 在 Minecraft 中播放网易云音乐 · Forge 1.20.1 客户端模组

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](./LICENSE)
[![Forge](https://img.shields.io/badge/Forge-47.4.20-orange.svg)](https://files.minecraftforge.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-17-red.svg)](https://adoptium.net/)

---

## 简介

**Zephyr Music** 是一个 Minecraft Forge 模组，让你在游戏中聆听网易云音乐。支持扫码/手机验证码/邮箱登录、歌单浏览、歌曲搜索、逐字歌词（yrc）、游戏内 HUD 显示、完整的播放控制以及与网易云一致的听歌打卡机制。

灵感来源于 [Zephyr Music 桌面播放器](https://github.com/)（Tauri 2 + Vue 3），将其核心功能移植到 Minecraft 客户端。

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
| `hud_panel_width` | `220` | 面板宽度（100-600） |
| `hud_cover_size` | `64` | 封面大小（16-256） |
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
| `api_base` | `https://musicapi.mingqwq.top` | 网易云 API 地址 |
| `default_quality` | `exhigh` | 默认音质 |
| `scrobble_enabled` | `true` | 听歌打卡开关 |

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
