# La-Pluma Camera 服务端插件改进预留文档

本文记录 Cinematic Camera 运镜系统上线后，服务端插件需要配套改进的接口、数据模型和兼容策略。目标是让服务端能够安全调度客户端运镜，并与现有 Journal、Chat、Video 剧情流程共用同一套 Presentation Session。

## 1. 资源与世界约束

Camera 资源必须带世界作用域。客户端已经要求 `.cam.json` 至少声明以下任一字段：

```json
{
  "world": {
    "dimension": 0,
    "name": "world"
  }
}
```

建议服务端插件保存和下发运镜前执行同样校验：

- `world.dimension`：Minecraft 维度 ID，推荐必填。
- `world.name`：世界名，可选；服务器多世界部署时建议填。
- 如果服务端知道玩家当前世界与 Camera 资源不匹配，应在服务端直接拒绝播放，不要等客户端回传 `ERROR`。
- Camera 坐标都解释为该 world scope 下的世界绝对坐标。

## 2. 协议预留

继续使用现有 `SkyHUDMessage`：

```text
int act
int data
string ctx
```

Camera 相关 action：

| act | 方向 | 说明 |
|---|---|---|
| `40` | 服务端 -> 客户端 | 播放运镜 |
| `41` | 服务端 -> 客户端 | 停止运镜 |
| `42` | 客户端 -> 服务端 | 运镜结束结果 |
| `44` | 客户端 -> 服务端 | 运镜事件回传 |

### 2.1 播放运镜

`act=40`，`ctx`：

```json
{
  "cameraId": "intro_gate",
  "allowSkip": true,
  "blockingToken": "quest_001_intro"
}
```

字段说明：

- `cameraId`：对应客户端资源 `assets/lapluma/cameras/<cameraId>.cam.json`。
- `allowSkip`：是否允许玩家按 ESC 跳过。
- `blockingToken`：服务端自定义 token，用于把结束回传关联到剧情会话；当前客户端不会解析此字段，服务端可先预留。

### 2.2 停止运镜

`act=41`，`ctx` 可为空。用于服务端强制终止正在播放的镜头，例如玩家离开剧情区域、死亡、切换世界、任务取消。

### 2.3 结束回传

客户端发送 `act=42`，`ctx`：

```json
{
  "cameraId": "intro_gate",
  "result": "FINISHED"
}
```

`result` 可能值：

- `FINISHED`：正常播放结束。
- `SKIPPED`：玩家跳过。
- `ERROR`：资源缺失、世界不匹配或运行时异常。
- `SERVER_STOP`：服务端或新镜头打断。

服务端插件应根据结果推进或回滚剧情状态。

### 2.4 事件回传

客户端发送 `act=44`，`ctx`：

```json
{
  "cameraId": "intro_gate",
  "event": "open_gate",
  "type": "marker"
}
```

服务端应把它接入统一 Presentation Event Bus，让 Camera 事件可以触发：

- NPC 行为。
- 方块/机关状态变化。
- 任务阶段推进。
- 后续 Journal/Chat/Video 播放。

## 3. Session 模型

建议插件新增或扩展：

```text
PresentationSession
├─ journalSession
├─ chatSession
├─ videoSession
└─ cameraSession
```

`CameraSession` 建议字段：

- `playerId`
- `cameraId`
- `world`
- `dimension`
- `blockingToken`
- `startedAt`
- `allowSkip`
- `expectedState`
- `result`

服务端在收到 `act=42` 时按 `playerId + cameraId` 或 `blockingToken` 匹配会话。

## 4. Journal 集成

服务端插件如果负责解析或调度 `.journal`，需要识别：

```text
<camera(id: intro_gate, blocking: true)>
<camera(id: intro_gate, blocking: false)>
<waitCamera()>
```

推荐行为：

- `blocking=true`：服务端等待 `act=42` 后再继续剧情。
- `blocking=false`：立即继续剧情，但记录后台 camera session。
- `waitCamera()`：如果玩家有 active camera session，等待它结束。

如果 Journal 仍完全由客户端解析，服务端只需要处理客户端回传结果和事件即可。

## 5. 资源校验

长期建议把 `MD5HASH` 改为更准确的 `PRESENTATION_HASH`。服务端插件需要把以下资源都纳入一致性检查：

- `journals/*.journal`
- `chat/*.chat`
- `avg/*.png`
- `cameras/*.cam.json`
- 未来可选：`camera_presets/*`、`camera_thumbnails/*`

目前客户端已把 `.cam.json` 纳入现有 hash 计算。

## 6. 权限与安全

建议服务端插件只允许可信剧情来源触发运镜：

- 管理员命令。
- 任务/剧情脚本。
- 服务端插件内部逻辑。

不要允许普通玩家直接构造 `act=40` 等价请求。服务端接收客户端 `act=42` / `act=44` 时也要校验玩家确实有 active camera session，避免伪造事件推进剧情。

## 7. 推荐插件命令

可预留以下调试命令：

```text
/lapluma camera play <player> <cameraId> [allowSkip]
/lapluma camera stop <player>
/lapluma camera validate <cameraId>
/lapluma camera list
```

`validate` 应检查：

- 资源是否存在。
- world scope 是否声明。
- 当前玩家世界是否匹配。
- keyframes 是否非空。
- duration 是否有效。

## 8. 与 LaPlumaCinema 编辑器的衔接

`LaPlumaCinema` 会导出 `.cam.json`。插件应直接读取同一格式，不另行定义服务端专用格式。

推荐外部工具输出路径：

```text
assets/lapluma/cameras/<cameraId>.cam.json
```

编辑器导出的关键字段包括：

- `id`
- `world`
- `durationTicks`
- `keyframes`
- `events`
- `allowSkip`
- `hideHud`
- `hideHand`
- `lockInput`
- `cinematicBars`

## 9. 后续可扩展项

后续服务端插件可逐步增加：

- Camera 资源远程下发，类似当前远程 journal transfer。
- Camera 分片传输与 HMAC 校验。
- 服务端 Marker 注册表，用于把 `lookAt.target` 映射到坐标。
- Entity/Player LookAt 目标同步。
- 剧情区域锁定，防止玩家在运镜期间被移动或受到伤害。
- 多人同时观看同一段运镜的 session group。

