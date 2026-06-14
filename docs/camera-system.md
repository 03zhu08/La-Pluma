# La-Pluma Cinematic Camera 运镜系统工程设计文档

## 1. 项目定位

La-Pluma Cinematic Camera 不是独立插件，也不是独立于现有剧情框架之外的新系统，而是 **La-Pluma Story Presentation System（剧情演出系统）内部新增的 Camera Runtime 模块**。

该模块与现有：

* `.journal` 视觉小说剧情系统
* `.chat` 即时通讯剧情系统
* 视频播放系统
* AVG 图片系统
* 服务端剧情调度系统

共同组成统一的剧情演出框架。

运镜系统的职责是在现有剧情流程中提供空间镜头表现能力，使剧情策划能够在 `.journal`、`.chat`、视频等演出形式之间自由切换，并通过镜头轨道、关键帧、事件轨道实现 Minecraft 场景内的实时过场演出。

除此之外，本系统还将引入 **虚拟摄影机（Virtual Camera）模型**，允许镜头模拟现实摄影设备的部分参数，包括：

* 光圈（Aperture）
* 快门（Shutter）
* ISO
* 焦距（Focal Length）
* 景深（Depth of Field）
* 曝光（Exposure）

从而让剧情演出能够获得更接近电影摄影语言的表现能力。

核心目标不是构建一个独立 Camera Mod，而是在现有 La-Pluma 剧情体系中增加一种新的演出资源类型：

```text
剧情演出资源
├─ .journal
├─ .chat
├─ 视频
├─ AVG 图片
└─ Camera 运镜
```

因此运镜系统必须遵循现有剧情系统的架构、资源组织方式、网络协议和生命周期管理方式。

---

## 2. 设计目标

### 2.1 核心目标

1. Forge 1.12.2 客户端支持完整运镜播放。
2. 运镜作为 La-Pluma 剧情系统内部模块实现。
3. 运镜能够直接被 `.journal` 调用。
4. 运镜能够与 `.chat`、视频系统共同参与剧情流程。
5. 服务端能够通过现有剧情通信协议控制运镜。
6. 运镜资源能够作为剧情资源包的一部分进行管理。
7. 外部工具能够生成运镜资源。
8. 运镜播放结束后能够回传剧情状态。
9. 运镜事件能够参与剧情推进。
10. 运镜结束后恢复玩家原始状态。
11. 支持虚拟摄影机参数模拟。
12. 支持电影镜头语言表达。

### 2.2 非目标

第一阶段不做：

1. 独立运镜插件体系。
2. 独立剧情框架。
3. 独立资源管理器。
4. 独立网络协议。
5. 多人同步电影拍摄系统。
6. 完整物理级摄影模拟器。
7. 完整实时路径追踪渲染器。
8. 完整视频编辑器。

---

## 3. 与现有 La-Pluma 工程的关系

### 3.1 基本原则

运镜系统属于：

```text
La-Pluma
├─ Journal
├─ Chat
├─ Video
├─ AVG
└─ Camera
```

而不是：

```text
La-Pluma
├─ Journal
├─ Chat
├─ Video

独立插件：
└─ Camera
```

### 3.2 集成原则

运镜模块必须：

1. 使用现有资源加载体系。
2. 使用现有剧情生命周期。
3. 使用现有网络协议。
4. 使用现有资源 Hash 机制。
5. 使用现有剧情函数解析器。
6. 使用现有客户端状态管理逻辑。
7. 使用现有服务端剧情调度逻辑。

### 3.3 剧情演出层统一结构

推荐统一抽象：

```text
Presentation Runtime
├─ Journal Runtime
├─ Chat Runtime
├─ Video Runtime
└─ Camera Runtime
```

所有演出资源共享：

```text
Presentation Resource Manager
Presentation Network Layer
Presentation Event Dispatcher
Presentation State Manager
```

运镜模块只是其中一个 Runtime。

---

## 4. 总体架构

调整后的架构如下：

```text
La-Pluma Story Presentation System
│
├─ Presentation Runtime
│  ├─ Journal Runtime
│  ├─ Chat Runtime
│  ├─ Video Runtime
│  └─ Camera Runtime
│
├─ Presentation Resource Manager
│  ├─ Journal Loader
│  ├─ Chat Loader
│  ├─ Video Loader
│  └─ Camera Loader
│
├─ Presentation Network Layer
│  ├─ Journal Actions
│  ├─ Chat Actions
│  ├─ Video Actions
│  └─ Camera Actions
│
├─ Presentation Event Dispatcher
│
└─ External Camera Studio
```

运镜不再作为独立系统存在，而是 Presentation Runtime 的组成部分。

---

## 5. 资源目录设计

运镜资源继续放在 La-Pluma 资源体系内部：

```text
assets/lapluma/
├─ journals/
├─ chat/
├─ avg/
├─ video/
├─ cameras/
├─ camera_presets/
└─ camera_thumbnails/
```

其地位与：

```text
journals
chat
video
```

完全一致。

---

## 6. 运镜资源格式

资源格式保持不变。

运镜文件仍然使用：

```text
.cam.json
```

但其资源类型定义应调整为：

```text
Presentation Resource Type: CAMERA
```

与：

```text
JOURNAL
CHAT
VIDEO
CAMERA
```

统一管理。

### 6.1 世界作用域（必需）

运镜坐标必须声明它所属的 Minecraft 世界，否则客户端应拒绝播放。Camera 资源至少需要包含 `world.dimension` 或 `world.name`：

```json
{
  "id": "intro_gate",
  "world": {
    "dimension": 0,
    "name": "world"
  }
}
```

字段含义：

| 字段 | 说明 |
|---|---|
| `world.dimension` | Minecraft 维度 ID，例如主世界为 `0` |
| `world.name` | 世界名，可选；多世界服务器建议填写 |

所有关键帧坐标都解释为该 world scope 下的世界绝对坐标。播放前应检查玩家当前世界是否匹配，避免同一坐标在不同世界或维度中被错误使用。

### 6.2 虚拟摄影机参数

新增 Camera Lens Track：

```json
{
  "lens": {
    "focalLength": 50,
    "aperture": 2.8,
    "iso": 100,
    "shutterSpeed": 0.0167
  }
}
```

单位定义：

| 参数           | 单位       |
| ------------ | -------- |
| focalLength  | mm       |
| aperture     | f-number |
| iso          | ISO      |
| shutterSpeed | 秒        |

---

## 7. Camera Runtime 详细实现设计

这一部分是整个系统最核心的内容。

### 7.1 Runtime 架构

推荐结构：

```text
PresentationManager
│
├─ JournalRuntime
├─ ChatRuntime
├─ VideoRuntime
└─ CameraRuntime
    │
    ├─ CameraPlayer
    ├─ CameraTimeline
    ├─ CameraInterpolator
    ├─ CameraViewController
    ├─ CameraLensController
    ├─ CameraEventDispatcher
    ├─ CameraStateController
    └─ CameraResourceLoader
```

### 7.2 Minecraft 1.12.2 运镜实现原理

Minecraft 本身没有真正意义上的 Cinematic Camera。

运镜实际上是：

```text
接管 RenderViewEntity
↓
创建虚拟摄像机实体
↓
每帧更新摄像机位置
↓
Minecraft 使用该实体渲染世界
```

实现流程：

```text
玩家
↓
播放运镜
↓
记录原始 RenderViewEntity
↓
创建 CameraViewEntity
↓
mc.setRenderViewEntity(CameraViewEntity)
↓
Timeline 驱动 CameraViewEntity
↓
播放结束
↓
恢复原始 RenderViewEntity
```

### 7.3 CameraViewEntity

CameraViewEntity 是客户端专用实体。

职责：

```text
保存镜头位置
保存镜头旋转
保存 Roll
保存 FOV
保存 LookAt 状态
保存 Lens 参数
```

不需要：

```text
AI
碰撞
同步
服务端实体
NBT存档
```

本质上只是一个渲染观察点。

### 7.4 运镜播放流程

```text
加载 CameraDefinition
↓
创建 Runtime Session
↓
锁定输入
↓
隐藏 HUD
↓
创建 CameraViewEntity
↓
切换 RenderViewEntity
↓
开始 Timeline
↓
更新 Lens 参数
↓
触发事件
↓
播放结束
↓
恢复状态
↓
通知 Journal
```

### 7.5 Timeline 驱动逻辑

每个运镜由 Timeline 驱动。

```text
Timeline
├─ Transform Track
├─ FOV Track
├─ Lens Track
├─ LookAt Track
├─ Shake Track
└─ Event Track
```

每帧执行：

```text
当前时间
↓
计算关键帧区间
↓
插值
↓
生成 CameraState
↓
应用到 CameraViewEntity
```

### 7.6 Transform Track

Transform 是最核心轨道。

包含：

```text
Position
Rotation
Roll
```

例如：

```json
{
  "time": 0,
  "position": [0,64,0],
  "rotation": [10,90,0]
}
```

运行时：

```text
Keyframe A
↓
Keyframe B
↓
插值
↓
Camera Position
```

### 7.7 Lens Track（新增）

Lens Track 用于模拟摄影机镜头参数。

包含：

```text
Focal Length
Aperture
ISO
Shutter Speed
```

例如：

```json
{
  "time": 0,
  "focalLength": 35,
  "aperture": 2.8,
  "iso": 100,
  "shutterSpeed": 0.0167
}
```

### 7.8 焦距（Focal Length）

焦距用于决定镜头透视感。

推荐映射：

| 焦距    | 类型   |
| ----- | ---- |
| 18mm  | 超广角  |
| 24mm  | 广角   |
| 35mm  | 标准广角 |
| 50mm  | 标准镜头 |
| 85mm  | 中长焦  |
| 135mm | 长焦   |

运行时转换为 Minecraft FOV：

```text
焦距
↓
等效视角
↓
FOV
```

这样策划无需直接操作 FOV。

### 7.9 光圈（Aperture）

光圈用于模拟：

```text
景深强度
背景虚化
曝光量
```

例如：

```text
f/1.4
↓
极浅景深

f/16
↓
深景深
```

实现方式：

```text
Shader景深
+
焦点距离
+
模糊半径
```

若客户端未启用景深效果，则仅作为元数据保存。

### 7.10 ISO

ISO 用于模拟感光度。

作用：

```text
曝光补偿
画面颗粒感
```

例如：

```text
ISO100
↓
干净画面

ISO3200
↓
明显噪点
```

实现方式：

```text
曝光增益
+
Film Grain Shader
```

### 7.11 快门（Shutter Speed）

快门用于模拟运动模糊。

例如：

```text
1/30
↓
明显拖影

1/250
↓
清晰冻结
```

实现方式：

```text
Motion Blur Shader
```

第一阶段允许仅保存参数，不强制实现真实运动模糊。

### 7.12 曝光系统

新增自动曝光模型：

```text
ISO
+
Aperture
+
Shutter
↓
Exposure Value
↓
最终亮度
```

支持：

```text
AUTO
MANUAL
```

两种模式。

### 7.13 景深系统

支持：

```text
Manual Focus
LookAt Focus
Marker Focus
Entity Focus
```

例如：

```json
{
  "focusMode":"LOOK_AT"
}
```

镜头始终聚焦当前目标。

### 7.14 LookAt 系统

LookAt 是运镜中最重要的高级功能之一。

支持：

```text
POINT
ENTITY
PLAYER
MARKER
```

例如：

```json
{
  "target":"gate_center"
}
```

运行时：

```text
获取目标坐标
↓
计算方向向量
↓
转换 Pitch/Yaw
↓
覆盖 Rotation
```

这样镜头始终看向目标。

### 7.15 Camera Shake

震动轨道用于：

```text
爆炸
Boss落地
地震
机关启动
```

实现：

```text
基础镜头
+
Perlin Noise
+
随机偏移
=
最终镜头
```

### 7.16 FOV 动画

实现推镜效果：

```text
70°
↓
55°
```

视觉上会产生：

```text
镜头推进
电影长焦
压缩空间
```

效果。

当启用焦距模式时：

```text
Focal Length
↓
自动计算FOV
```

FOV Track 自动失效或作为偏移量使用。

### 7.17 输入锁定

播放期间：

```text
WASD
鼠标
攻击
使用物品
切换物品栏
```

全部锁定。

保留：

```text
ESC
F2
```

用于退出和截图。

### 7.18 HUD 控制

支持：

```text
隐藏HUD
隐藏准星
隐藏手部
电影黑边
字幕区域
跳过提示
```

### 7.19 异常恢复机制

必须保证：

```text
任何情况下
玩家都能恢复控制权
```

包括：

```text
断线
切维度
资源重载
崩溃恢复
GUI关闭
```

统一入口：

```text
CameraRuntime.forceStop()
```

---

## 8. `.journal` 深度集成

### 8.1 核心定位

运镜最主要的入口应当是：

```text
.journal
```

而不是：

```text
服务端命令
```

服务端触发只是辅助能力。

### 8.2 推荐使用方式

```text
<camera(id: "intro_gate", blocking: true)>
```

```text
<camera(id: "tower_reveal", blocking: false)>
```

```text
<waitCamera()>
```

未来支持：

```text
<cameraLens(aperture: 1.8, focalLength: 85)>
```

用于动态调整镜头语言。

### 8.3 剧情状态统一

运镜结束后：

```text
Camera Runtime
↓
Presentation Runtime
↓
Journal Runtime
↓
继续剧情
```

### 8.4 Blocking 模式实现

```text
Journal执行
↓
遇到camera()
↓
暂停Prompt推进
↓
等待Camera结束
↓
恢复Prompt推进
```

### 8.5 Non-Blocking 模式

```text
Journal执行
↓
启动Camera
↓
继续执行Prompt
↓
Camera后台播放
```

---

## 9. 网络协议

### 9.1 保持现有协议

继续使用：

```text
SkyHUDMessage
```

继续使用：

```text
int act
int data
string ctx
```

### 9.2 Camera Action

```text
0~19   Journal
20~29  Chat
30~39  Video
40~59  Camera
```

### 9.3 Camera 播放协议

服务端：

```json
{
  "cameraId":"intro_gate",
  "allowSkip":true,
  "blockingToken":"quest_001"
}
```

客户端：

```text
加载资源
↓
播放
↓
结束
↓
回传结果
```

### 9.4 Camera 回传协议

```json
{
  "cameraId":"intro_gate",
  "result":"FINISHED"
}
```

支持：

```text
FINISHED
SKIPPED
ERROR
SERVER_STOP
```

---

## 10. 服务端设计调整

### 10.1 不建议独立插件

推荐：

```text
LaPlumaStoryPlugin
├─ Journal
├─ Chat
├─ Video
└─ Camera
```

### 10.2 服务端结构

```text
StoryService
├─ JournalService
├─ ChatService
├─ VideoService
└─ CameraService
```

### 10.3 Session

```text
PresentationSession
├─ JournalSession
├─ ChatSession
├─ VideoSession
└─ CameraSession
```

---

## 11. 运镜事件与剧情事件统一

推荐统一事件总线：

```text
Presentation Event Bus
```

例如：

```json
{
  "event":"open_gate"
}
```

既可以来自：

```text
Journal
Camera
Chat
Video
```

也可以被：

```text
Quest
NPC
Server Logic
```

监听。

---

## 12. 资源 Hash

建议统一：

```java
PRESENTATION_HASH
```

内部包含：

```text
journal
chat
video
camera
```

长期目标是统一剧情资源校验体系。

---

## 13. 运镜编辑工具完整设计

### 13.1 技术选型

相比 Java 桌面程序，更推荐：

```text
Electron
+
TypeScript
+
React
+
Three.js
```

原因：

1. UI开发效率远高于 Swing。
2. 时间轴编辑器生态成熟。
3. Three.js 非常适合实现地图预览。
4. Electron 更容易实现复杂编辑器。
5. 后续可扩展 Journal 编辑器和剧情工具链。

推荐架构：

```text
LaPluma Camera Studio
│
├─ Electron Main
├─ React UI
├─ Three.js Viewport
├─ Timeline Editor
├─ Camera Generator
├─ Lens Simulator
├─ World Importer
├─ Schematic Importer
├─ Exporter
└─ Validator
```

---

### 13.2 编辑器模块

```text
Camera Studio
├─ Project Explorer
├─ Scene View
├─ Timeline
├─ Inspector
├─ Lens Inspector
├─ Asset Browser
├─ Marker Manager
├─ Preset Generator
├─ Preview Player
└─ Export Wizard
```

---

### 13.3 世界导入器

支持：

```text
Minecraft World
WorldEdit Schematic
Sponge Schem
```

读取：

```text
region/*.mca
level.dat
schematic
schem
```

导入后生成：

```text
Scene
├─ Chunks
├─ Blocks
├─ Markers
└─ Entities
```

---

### 13.4 Three.js 场景系统

Three.js 负责：

```text
地图显示
镜头预览
轨迹显示
关键帧显示
Marker显示
景深
```
