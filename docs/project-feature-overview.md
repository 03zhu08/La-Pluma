# La-Pluma 工程功能概述

本文面向后续功能开发，用于让新的开发者或 ChatGPT 快速理解 La-Pluma 的现有工程结构、核心功能、数据格式和扩展入口。语法细节可配合 `docs/dialog-chat-syntax.md` 阅读。

## 1. 项目定位

La-Pluma 是一个面向 Minecraft 的交互式文字演绎 Mod，当前工程主要是 Forge 1.12.2 客户端实现。它把资源包中的剧情脚本、聊天脚本、图片、音频和视频资源组织起来，在游戏内呈现视觉小说/AVG 风格剧情、即时通讯风格聊天界面，以及全屏视频播放。

项目核心能力包括：

- `.journal` 视觉小说剧情播放：文本、选项、背景、立绘、实体展示、音乐、音效、屏幕特效、剧情跳转。
- `.chat` 即时通讯式对话：联系人/群聊、多会话、分支回复、消息逐条揭示、输入中状态。
- 服务端联动：通过 Forge 自定义包通道接收服务端指令，打开剧情、播放视频、下发远程 journal、推送聊天数据。
- 视频播放：通过 JavaCV/FFmpeg 解码本地或网络视频，并在 Minecraft GUI 中播放。
- 资源包驱动：剧情、聊天、背景、立绘、声音、视频等都以 `assets/lapluma/...` 路径加载。

## 2. 技术栈与构建

- Minecraft / Forge：`1.12.2-14.23.5.2768`
- ForgeGradle：`2.3-SNAPSHOT`
- MCP mappings：`stable_39`
- Java：建议完整 JDK 8
- 视频依赖：JavaCV `1.5.9`、FFmpeg `6.0-1.5.9`
- Lombok：`1.18.24`

构建入口在 `build.gradle`。项目支持按平台打包 FFmpeg native：

```bash
./gradlew clean build -PtargetPlatform=windows-x86_64
./gradlew clean build -PtargetPlatform=linux-x86_64
./gradlew clean build -PtargetPlatform=macosx-x86_64
./gradlew clean build -PtargetPlatform=macosx-arm64
```

默认版本号在 `build.gradle` 中为 `1.4.1-SNAPSHOT`，Mod 注解中的 `LaPluma.VERSION` 当前仍是 `1.0-SNAPSHOT`，如果后续做版本展示或发布流程，需要统一这两个来源。

## 3. 目录结构

主要源码包：

- `cn.earthsky.dev.project.lapluma.LaPluma`：Mod 生命周期、命令注册、声音注册、资源预加载、tick 事件。
- `common/text`：`.journal` 剧情脚本解析和运行时结构。
- `common/text/prompts`：剧情节点类型，包括文字、选择、函数。
- `common`：函数执行、参数解析、资源命名空间、工具解析器。
- `common/commands`：客户端命令 `/playJournal`、`/playVideo`。
- `common/network`：客户端与服务端插件通信。
- `client/gui`：AVG 对话 GUI、视频 GUI、按钮、日志、跳过菜单。
- `client/gui/fx`：屏幕特效。
- `client/gui/chat`：即时通讯界面渲染。
- `client/gui/chat/data`：聊天数据模型、脚本解析和状态管理。
- `client/audio`：动态音乐与视频音频播放。
- `client/video`：FFmpeg native 释放辅助。

主要资源路径：

- `assets/lapluma/journals/*.journal`：AVG 剧情脚本。
- `assets/lapluma/chat/*.chat`：聊天脚本。
- `assets/lapluma/avg/*.png`：背景和立绘。
- `assets/lapluma/chat/skin/*.png`：聊天头像/皮肤资源。
- `assets/lapluma/sounds.json` 与 `assets/lapluma/sounds/*.ogg`：音效。
- `assets/lapluma/shaders/*`：聊天界面模糊 shader。
- `assets/lapluma/videos/*`：可选的视频资源路径，运行时也会查找 `.minecraft/lapluma/videos/`。

## 4. 启动与生命周期

`LaPluma` 是 Forge Mod 主类。

### preinit

`preinit` 中执行：

- 初始化 `ProxyPacketHandler`，注册 Forge 网络通道和事件监听。
- 注册 `ObjectRegistryHandler` 到 MinecraftForge 事件总线。

### init

`init` 中注册客户端命令：

- `/playJournal <Journal Name>`
- `/playVideo <url|name|path> [allowSkip]`

这些命令通过 `ClientCommandHandler` 注册，主要用于客户端本地测试或单机触发。

### postinit

`postinit` 中执行：

- 预加载 `example.journal` 到 `GuiDialog.EXAMPLE_STRUCTURE`。
- 注册资源重载监听器。资源重载时会重新计算资源 hash，并重新加载聊天测试数据。
- 初次加载 `ChatDataManager.loadTestData()`。

### 客户端 tick

`ObjectRegistryHandler.onClientTick` 每 tick 调用 `ChatDataManager.tick()`，用于推进聊天输入中延迟和消息揭示。

## 5. `.journal` AVG 剧情系统

`.journal` 文件由 `ConversationLoader` 加载，核心结构是 `ConversationStructure`。加载路径固定为：

```text
assets/lapluma/journals/<name>.journal
```

### 解析流程

1. `JournalNamespace.get(name)` 查找缓存。
2. 如果未缓存，调用 `ConversationLoader.loadStructureFromResource(name)`。
3. `ConversationStructure.loadPrompts(lines)` 调用 `Eval.eval(lines)`。
4. `Eval` 将文本拆为四类结果：
   - `Prompt`：`说话人:文本`
   - `Function`：`<function(args)>`
   - `Selection`：`[选项]<function(args)>`
   - `Block`：`块名{ ... }`
5. `ConversationStructure` 把结果转成运行时 `ConversationPrompt`：
   - `WordPrompt`
   - `FunctionPrompt`
   - `SelectionPrompt`

### 分支块

`块名{ ... }` 会被注册为新的命名空间：

```text
<当前journal名>.<块名>
```

例如 `example.journal` 中的 `s1{...}` 会注册为 `example.s1`。脚本里通常用 `$this.s1` 配合 `continue()` 跳转，`$this` 会替换为当前 journal 名。

如果块名以 `!` 开头，解析结束时会追加 `reverseSnapshot()`，用于临时跳转后恢复原剧情快照。

### 播放流程

`GuiDialog` 接收一个 `ConversationStructure` 并从 `cursor = -1` 开始推进：

- `nextPrompt()` 递增 cursor，取当前 prompt。
- `WordPrompt` 调用 `GuiDialog.showText()` 更新说话人与文本。
- `FunctionPrompt` 执行函数后立即继续推进下一条。
- `SelectionPrompt` 创建多个 `GuiSelectionButton`，等待玩家点击。

GUI 支持：

- 文本打字机效果。
- 自动播放。
- 鼠标/空格跳过当前打字。
- 选项点击或数字键 `1` 到 `9` 选择。
- 日志面板。
- 跳过菜单。
- 隐藏 HUD。
- 关闭时向服务端回传剧情结束状态。

### 可用函数入口

所有 `.journal` 函数最终由 `Functions.doFunction(Parsing, GuiDialog)` 执行。新增剧情函数通常就在这里加 `else if` 分支。

已实现的主要函数：

- `clean()`：清空立绘/实体。
- `show(avg, pos, dimmed)`：显示 `assets/lapluma/avg/<avg>.png` 立绘。
- `solo(avg, pos)`：清空后只显示一个立绘。
- `entity(entity/id/type/name, pos, scale, dimmed, y, follow)`：创建并展示 Minecraft 实体。
- `worldEntity(player/displayName/uuid, pos, scale, dimmed, y, follow)`：展示当前世界已有实体或玩家。
- `bg(bg)`：设置背景 `assets/lapluma/avg/<bg>.png`。
- `resetBg()`：恢复背景 `bg`。
- `color(color)`：设置对话框分割线颜色。
- `center()` / `stopCenter()`：开启/关闭居中文本。
- `fx_fadeIn()`、`fx_fadeOut()`、`fx_shakeShort()`、`fx_shakeFor()`、`fx_shakeCustom()`：屏幕特效。
- `music(name)`：播放动态音乐。
- `sound(name, volume, pitch)`：播放注册音效。
- `continue(next)`：切换到另一个 `ConversationStructure`。
- `chat(msg)`：让玩家发送聊天消息。
- `reverseSnapshot()`：恢复前一个剧情快照。
- `info(title, abstract)`：设置跳过菜单的标题和描述。
- `video(url/file/name, skip)`：暂停当前剧情并打开视频播放器，视频结束后回到当前 `GuiDialog`。

函数参数由 `Parsing` 解析，格式大致为：

```text
functionName(key: value, key2: "quoted value")
```

参数别名通过 `Selector.searchNonNull()` 做兼容，数值和布尔值通过 `Parsers` 解析。

## 6. `.chat` 聊天系统

聊天系统由 `ChatDataManager` 管理全局状态，由 `GuiChatScreen`、`GuiContactList`、`GuiMessagePanel`、`ChatBubbleRenderer` 渲染。

### 数据模型

`ChatContact` 表示联系人：

- `id`
- `name`
- `skin`
- `faction`
- `lastMessage`
- `lastTimestamp`
- `unreadCount`
- `isGroup`
- `members`
- `memberNames`
- `memberSkins`

`ChatMessage` 表示消息：

- `id`
- `senderId`
- `senderName`
- `senderSkin`
- `content`
- `timestamp`
- `type`：`TEXT`、`IMAGE`、`SYSTEM`、`TYPING`
- `replyOptions`
- `replyBranches`
- `replyReturnsToMain`
- `replyTokens`
- `revealTime`

`ChatDataManager.Conversation` 是一个联系人下的一段会话，负责保存全部消息、当前显示区间、已揭示消息索引、分支映射、主线返回点和完成状态。

### 本地 `.chat` 加载

`ChatDataManager.loadTestData()` 当前硬编码加载以下脚本：

- `npc_001_main`
- `npc_001_quest`
- `group_001_plan`
- `npc_002_gate`
- `npc_002_trade`
- `npc_003_shop`

每个脚本从这里读取：

```text
assets/lapluma/chat/<fileId>.chat
```

解析器是 `ChatJournalParser`。它支持：

- `#conv`、`#contact`、`#title`、`#status`
- `#skin`、`#faction`、`#group`、`#members`、`#name`
- `@senderId|senderName` 消息头
- 多行消息正文
- `+ option -> branch |main` 回复选项
- `=branch` 分支
- `=main` 主线续接点

### 聊天推进

聊天不会一次性显示所有消息。`advanceConversation(contactId)` 会按当前会话状态推进：

- NPC/系统消息先进入输入中状态，根据正文长度产生 20 到 60 ticks 的延迟。
- 玩家消息如果没有回复选项，会立即揭示。
- 玩家消息如果有回复选项，会停止推进，等待玩家选择。
- 选择回复后调用 `onReplySelected(contactId, branchId, returnsToMain)`，切到对应分支。
- 如果分支带 `|main`，分支结束后跳到 `=main` 继续。
- 会话结束时调用 `markConversationFinished()`，状态改为 `completed`，并向服务端发送 `act=28`。

### 服务端 JSON 推送

服务端可以不依赖本地 `.chat` 文件，直接通过网络包推送 JSON：

- `parseContactsJson(json)`：联系人列表。
- `parseMessagesJson(json)`：会话和消息列表。
- `parseNewMessageJson(json)`：新增消息。
- `parseUpdateContactJson(json)`：单个联系人更新。

`parseMessagesJson` 支持服务端传入：

- `contactId`
- `conversationId`
- `title`
- `status`
- `messages`
- `branchIndexMap`
- `branchStarts`
- `mainContinuationIndex`
- `sequenceStart`
- `revealedIndices`

如果要做服务端持久化聊天进度，优先扩展这些字段，而不是只改客户端本地 `.chat` 解析。

## 7. 视频播放系统

视频入口有三个：

- 命令：`/playVideo <url|name|path> [allowSkip]`
- `.journal` 函数：`<video(url: ...)>` 或 `<video(file: ...)>`
- 服务端包：`act=4`

`PlayVideoCommand` 和 `Functions.video()` 都会调用 `GuiVideoPlayer.openVideo(...)`。

### 视频来源解析

`Functions.resolveLocalVideo(name)` 的本地搜索顺序：

1. `.minecraft/lapluma/videos/<name>`，支持带扩展名或自动补常见扩展。
2. 资源包路径 `assets/lapluma/videos/<name>`，找到后提取到 `.minecraft/lapluma/cache/`。

支持扩展名包括：

```text
.mp4 .webm .avi .mkv .flv .mov .wmv .ogg
```

网络 URL 支持 `http://`、`https://`、`rtmp://`。`GuiVideoPlayer` 会处理重定向、简单 HTML 嵌入跳转、缓存下载、解码预缓冲、音频播放、跳过按钮和结束回调。

### 平台 native

`GuiVideoPlayer` 启动解码线程前调用 `NativeExtractor.ensureExtracted()`。构建时依赖按 `targetPlatform` shade 到 jar 中，所以发布时要给不同平台提供对应 jar。

## 8. 网络通信

网络通信集中在 `ProxyPacketHandler`，通道名：

```text
SkyHUDMessage
```

包格式是：

```text
int act
int data
string ctx
```

主要 act：

- `-1`：客户端向服务端发送资源 hash。
- `0`：服务端要求客户端打开指定 journal。
- `1`：客户端回传 journal 已打开或 hash 校验相关状态。
- `2`：服务端要求当前 `GuiDialog` 执行函数；客户端关闭剧情时也会用 `act=2` 回传。
- `3`：客户端选择 `.journal` 选项。
- `4`：服务端要求播放视频。
- `5`：客户端通知视频播放结束。
- `9`：Placeholder/PAPI 请求与响应。
- `10`：服务端向客户端传输远程 journal，支持单包和分片。
- `11`：客户端确认远程 journal 接收完成。
- `12`：服务端下发 HMAC 签名密钥。
- `20`：打开聊天界面，可带初始联系人 ID。
- `21`：推送联系人列表 JSON。
- `22`：推送历史消息/会话 JSON。
- `24`：推送新消息 JSON。
- `25`：客户端选择聊天回复。
- `26`：输入中状态。
- `27`：更新单个联系人。
- `28`：客户端通知会话已读完毕。
- `23`：KeepAlive，`GuiDialog` 每约 21 ticks 发送一次。

远程 journal 传输使用 `HmacSHA256` 校验：

- `act=12` 先下发 hex 签名密钥。
- `act=10,data=0` 表示单包：`journalName|signature|content`。
- `act=10,data>0` 表示分片：`journalName|chunkIndex|chunkSig|chunkContent`。
- `act=10,data=-1` 表示完整性验证：`journalName|fullSignature|totalChunks`。

校验通过后，客户端调用 `ConversationLoader.loadStructureFromString()` 生成 `ConversationStructure`，并放入 `JournalNamespace.put()`。断开服务器时会清理远程 journal、签名 key、分片缓存和聊天状态。

## 9. 资源预加载与 hash

`LaPluma.preloadResources()` 会遍历 Mod jar、已启用资源包和服务器资源包中的：

- `.journal`
- `avg/*.png`
- `icon/dialog_bubble.png`

它把 `.journal` 和 AVG 图片内容写入 buffer 后生成 SHA-256 字符串，保存到 `LaPluma.MD5HASH`。变量名叫 `MD5HASH`，实际使用的是 SHA-256。多人服务器中，客户端会把该 hash 发给服务端，用于资源一致性或内容校验。

## 10. UI 与交互重点

### `GuiDialog`

`GuiDialog` 是 AVG 剧情的核心 GUI。重要状态包括：

- `structure`：当前剧情结构。
- `cursor`：当前 prompt 下标。
- `speaker`、`text`、`fullText`：当前显示文本。
- `avgCharacters`：当前立绘或实体。
- `bgName`：当前背景。
- `selectionButtonList`：当前选项按钮。
- `fx`：当前屏幕特效。
- `snapshot`：`continue()` 跳转前的快照，用于 `reverseSnapshot()`。
- `hideHUD`、`showSkipMenu`、`showLog`、`centerText`：界面状态。
- `autoPlay`：自动播放开关。

如果新增剧情功能涉及显示层，通常需要同时考虑：

- 是否应在 `Functions.doFunction()` 里新增脚本函数。
- 是否需要给 `GuiDialog` 增加状态字段。
- 是否需要在 `drawScreen()`、`updateScreen()`、`onGuiClosed()` 中处理生命周期。
- 是否需要服务端 `act=2` 支持远程触发。

### `GuiChatScreen`

聊天界面由两栏组成：

- 左侧 `GuiContactList`：联系人/会话选择。
- 右侧 `GuiMessagePanel`：消息气泡、回复按钮、滚动。

聊天数据不要直接存在 GUI 中，应通过 `ChatDataManager` 更新，这样服务端推送、本地脚本和 UI 都能共用同一状态。

## 11. 后续开发常见切入点

### 新增 `.journal` 函数

优先修改：

- `Functions.doFunction()`
- 必要时修改 `GuiDialog`
- 必要时更新 `docs/dialog-chat-syntax.md`

注意：

- `FunctionPrompt` 会立即推进下一条 prompt，所以长动画/异步行为如果要阻塞剧情，需要使用 `GuiDialog` 的 FX 队列或新增类似机制。
- 函数参数解析器对字符集和标点有限制，复杂 JSON 不适合直接塞进函数参数。

### 新增聊天字段或消息类型

优先修改：

- `ChatMessage` 或 `ChatContact`
- `ChatJournalParser`
- `ChatDataManager.parseMessagesJson()`
- `GuiMessagePanel` / `ChatBubbleRenderer`

注意：

- 服务端 JSON 字段名使用 Gson `FieldNamingPolicy.IDENTITY`，字段名必须和 Java 字段一致。
- `ChatMessage.MessageType` 已有 `IMAGE`，但具体渲染能力需要检查 `ChatBubbleRenderer` 是否完整支持。

### 新增服务端协议

优先修改：

- `ProxyPacketHandler.onClientPacket()`
- `ProxyPacketHandler.sendPacket()`
- 文件末尾协议注释
- 服务端插件对应实现

注意：

- `ctx` 是直接从 ByteBuf 剩余内容按 UTF-8 转字符串，没有长度字段；不要在协议里依赖 NUL 字符。
- 大内容建议仿照 `act=10` 做分片和签名。

### 新增资源类型

优先修改：

- 资源加载路径对应的 GUI 或 Manager。
- `LaPluma.preloadResources()`，如果该资源要参与 hash 校验。
- README 或 docs 中的资源包结构说明。

## 12. 当前工程注意事项

- 该工程是 Minecraft 1.12.2 / ForgeGradle 2.3，很多 API 和构建习惯都比较旧，避免直接套用现代 Forge/Fabric 写法。
- `README.md` 提到 1.16.5 移植中，但当前源码明显是 Forge 1.12.2。
- `LaPluma.MD5HASH` 命名不准确，实际计算 SHA-256。
- `ChatDataManager.loadTestData()` 目前硬编码示例 `.chat` 文件列表；如果要让资源包自动发现聊天脚本，需要新增扫描/索引机制。
- `.journal` 的函数解析基于正则，参数内容复杂时容易被逗号、冒号、引号影响；新增复杂配置时建议改用资源文件或 JSON 下发。
- `JournalNamespace.clearRemote()` 只清理通过 `put()` 标记的远程/子结构 key；资源文件懒加载缓存仍保留。
- `GuiVideoPlayer` 使用独立解码线程和音频播放器，新增退出/暂停/覆盖播放功能时要确保调用 `stopCurrentPlayback()` 或正确释放资源。
- 当前工作区可能包含未提交改动，后续开发前应先看 `git status`，避免覆盖已有修改。

