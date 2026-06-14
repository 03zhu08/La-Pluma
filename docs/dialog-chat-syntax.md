# La-Pluma 对话与聊天语法参考

本模组提供两套独立的对话系统：**聊天系统**（`.chat` 文件，即时通讯风格）和 **对话框系统**（`.journal` 文件，视觉小说/AVG 风格）。

---

## 目录

- [1. 聊天系统（.chat）](#1-聊天系统chat)
  - [1.1 文件头指令](#11-文件头指令)
  - [1.2 消息行](#12-消息行)
  - [1.3 回复选项](#13-回复选项)
  - [1.4 分支标签](#14-分支标签)
  - [1.5 完整示例](#15-完整示例)
- [2. 对话框系统（.journal）](#2-对话框系统journal)
  - [2.1 提示行](#21-提示行)
  - [2.2 函数调用](#22-函数调用)
  - [2.3 选择项](#23-选择项)
  - [2.4 代码块（分支）](#24-代码块分支)
  - [2.5 函数参考](#25-函数参考)
  - [2.6 完整示例](#26-完整示例)
- [3. 变量与特殊语法](#3-变量与特殊语法)

---

## 1. 聊天系统（.chat）

文件存放于 `assets/lapluma/chat/`。每条消息以 `@发件人ID|显示名称` 开头，支持分支对话和回复选项。

### 1.1 文件头指令

文件必须以一组 `#` 头指令开头：

| 指令 | 必需 | 说明 |
|---|---|---|
| `#conv <id>` | 是 | 对话唯一标识，如 `conv_npc_001_main` |
| `#contact <id>` | 是 | 联系人 ID，如 `npc_001` |
| `#title <文本>` | 否 | 对话标题，在联系人列表中显示 |
| `#status <值>` | 否 | `unread`（默认，有未读标记）或 `completed`（所有消息立即可见，显示绿色勾） |
| `#skin <路径>` | 否 | 联系人头像纹理路径，如 `chat/skin/snow_maple` |
| `#faction <标签>` | 否 | 阵营标签，自动加载 `chat/skin/faction_<标签>.png` 作为阵营图标 |
| `#group <bool>` | 否 | `true` 表示群聊，`false` 表示私聊 |
| `#members <列表>` | 群聊时 | 逗号分隔的成员 ID 列表，如 `npc_001,npc_002,npc_003` |
| `#name <名称>` | 群聊时 | 群聊显示名称，如 `远征小队` |

示例：

```
#conv conv_npc_001_main
#contact npc_001
#title 古籍翻译
#status completed
#skin chat/skin/snow_maple
#faction scholar
#group false
```

### 1.2 消息行

格式：`@<发件人ID>|<显示名称>`

紧接着的行（直到下一个 `@`、`+` 或 `=`）为消息正文，支持多行。

| 发件人 ID | 说明 |
|---|---|
| 自定义 ID | 如 `npc_001`，NPC 消息，左对齐灰色气泡 |
| `$player` | 玩家消息，右对齐蓝色气泡 |
| `SYSTEM` 或 `$system` | 系统消息，居中灰色文字，无气泡 |

```
@npc_001|艾琳·霜叶
你好，冒险者。我是学院的研究员艾琳。

@$player|
你好，有什么需要帮忙的吗？

@SYSTEM|
—— 第二天 ——
```

### 1.3 回复选项

格式：`+ <显示文本> -> <分支ID> |main`

紧跟在 NPC 消息之后，每个 `+` 行生成一个可点击按钮。

| 部分 | 说明 |
|---|---|
| `<显示文本>` | 玩家看到的选项文字 |
| `-> <分支ID>` | 可选，点击后跳转到的分支标签 |
| `\|main` | 可选，分支结束后返回主线（跳转到 `=main` 标签） |

选项中可嵌入特殊指令（不影响显示文本）：
- `<console>` — 执行控制台命令
- `<player_command>` — 以玩家身份执行聊天命令

```
@npc_001|艾琳·霜叶
那份古籍的翻译已经完成了，你来看看？
+ 好的，我马上过去 ✓
+ 月光草在哪里能找到？ -> ask_moon |main
+ 有什么报酬吗？ -> ask_reward |main
```

- 选项 `好的，我马上过去 ✓` 没有 `->`，不跳转到分支（继续下一条消息）
- 选项 `月光草在哪里能找到？` 跳转到 `=ask_moon` 分支，回答完后返回主线

### 1.4 分支标签

格式：`=分支ID`

`=main` 是一个特殊标签，标记分支出返回后的主线续接位置。

```
=ask_moon
@npc_001|艾琳·霜叶
月光草生长在月光林地的深处，只在满月之夜会发出银白色的光芒。

=ask_reward
@npc_001|艾琳·霜叶
古籍里记载的这种药剂据说可以永久增强精神力，我会分你一半作为报酬。

=main
@npc_001|艾琳·霜叶
火蜥蜴的鳞片在北境火山可以狩猎获得。纯净之水需要去莉娜·银币的店里购买。
```

### 1.5 完整示例

**私聊（带分支）：**

```
#conv conv_npc_001_quest
#contact npc_001
#title 炼金术任务
#status unread
#skin chat/skin/contact_default
#faction scholar
#group false

@npc_001|艾琳·霜叶
根据古籍记载，要制作炼金药剂需要三样稀有材料。
+ 我帮你收集全部 -> collect_all
+ 月光草在哪里能找到？ -> ask_moon |main
+ 有什么报酬吗？ -> ask_reward |main

=ask_moon
@npc_001|艾琳·霜叶
月光草生长在月光林地的深处，采集的时候记得带银制工具。

=ask_reward
@npc_001|艾琳·霜叶
我会分你一半作为报酬。

=main
@npc_001|艾琳·霜叶
怎么样，可以出发了吗？
+ 交给我吧 -> collect_all
+ 让我再考虑一下 -> think_quest

=collect_all
@npc_001|艾琳·霜叶
太好了！收集齐了之后拿来我的实验室，我在图书馆二楼等你。

=think_quest
@npc_001|艾琳·霜叶
没关系，不着急。等你准备好了随时来图书馆找我。
```

**群聊：**

```
#conv conv_group_001_plan
#contact group_001
#title 远征计划
#status completed
#skin chat/skin/group_default
#faction
#group true
#members npc_001,npc_002,npc_003
#name 远征小队

@npc_002|卡尔·铁锤
各位，明天的远征计划有变动。

@npc_001|艾琳·霜叶
怎么了？遇到什么问题了吗？

@npc_002|卡尔·铁锤
城门口发现了可疑的脚印，出发时间推迟到确认安全之后。

@npc_003|莉娜·银币
我这边补给已经准备好了，随时可以出发。

@npc_002|卡尔·铁锤
集合时间改到明天黎明。
+ 收到 ✓
+ 需要我去侦察一下吗？

@$player|
收到
```

---

## 2. 对话框系统（.journal）

文件存放于 `assets/lapluma/journals/`。通过 `/playJournal <名称>` 命令启动。使用视觉小说风格的画面呈现，支持背景、立绘/实体、特效和分支选择。

### 2.1 提示行

格式：`说话人:对话内容`

| 写法 | 说明 |
|---|---|
| `说话人:文本` | 正常对话，显示说话人名称和文本 |
| ` :文本` | 开头空格+冒号，延续上一个说话人（不重复显示名称） |

文本中的 `&` 会被转换为 Minecraft 格式化代码 `§`。

```
艾琳:你好，冒险者。
 :欢迎来到学院。
卡尔:好久不见。
```

### 2.2 函数调用

格式：`<函数名(参数键: 值, ...)>`

参数键支持多种别名，详见 [2.5 函数参考](#25-函数参考)。

```
<bg(b: empty)>              设置背景
<show(avg:person_a, pos: 25)>   显示立绘
<entity(e: minecraft:zombie, pos: 30, scale: 2.0)>   显示实体
<fx_fadeIn()>               淡入效果
```

### 2.3 选择项

格式：`[显示文本]<函数1(参数)><函数2(参数)>`

创建可点击的选项按钮，点击后依次执行行内的所有函数。通常配合 `<continue(n: ...)>` 实现跳转。

```
[我觉得烤面筋要用95号汽油]<continue(n: $this.s1)><chat(m: 我要吃95号汽油)>
[我觉得烤面筋要用97号汽油]<continue(n: $this.s2)><chat(m: 我要吃97号汽油)>
[我觉得烤面筋要用柴油]<continue(n: $this.s3)><chat(m: 我要吃柴油)>
```

`$this` 会被替换为当前 journal 文件名。例如在 `example.journal` 中，`$this.s1` 等于 `example.s1`。

### 2.4 代码块（分支）

格式：

```
块名{
 :对话内容
 :更多内容
<reverseSnapshot()>
}

!自动返回块{
 :对话内容（无需手动 reverseSnapshot）
}
```

- `name{ ... }` — 普通块，手动控制返回
- `!name{ ... }` — 自动返回块，执行完毕后自动调用 `<reverseSnapshot()>`

块会被注册为 `<父文件名>.<块名>`，通过 `<continue(n: ...)>` 跳入。

```
 :这是选择演示
[选项A]<continue(n: $this.s1)>
[选项B]<continue(n: $this.s2)>

!s1{
 :你选择了A（自动返回）
}

s2{
 :你选择了B
<reverseSnapshot()>
}
```

以 `#` 开头的行为注释，空行和纯空格行会被忽略。

### 2.5 函数参考

#### 背景与画面

| 函数 | 参数 | 说明 |
|---|---|---|
| `<bg(b: 名称)>` | `bg`/`b`/`background`/`val`/`v` | 设置背景图 `assets/lapluma/avg/<名称>.png` |
| `<resetBg()>` | 无 | 恢复默认背景 `bg` |
| `<center()>` | 无 | 启用全屏居中文字模式 |
| `<stopCenter()>` | 无 | 退出居中模式，回到底部对话框 |

#### 角色与立绘

| 函数 | 参数 | 说明 |
|---|---|---|
| `<show(avg: id, pos: 50, dim: yes)>` | `avg`/`a`/`actor`/`act`/`id`/`path`/`val`, `pos`/`p`/`loc`/`location`/`position` (默认 50), `dimmed`/`dim`/`dark`/`d` | 在屏幕指定位置显示 2D 立绘，位置为屏幕宽度的百分比 |
| `<solo(avg: id, pos: 50)>` | 同上 | 清除所有角色后单独显示此立绘 |
| `<clean()>` | 无 | 清除所有角色/实体 |

#### 实体渲染

| 函数 | 参数 | 说明 |
|---|---|---|
| `<entity(e: id, pos: 30, scale: 2.0, y: -20, follow: yes)>` | `entity`/`e`/`id`/`type`/`t`/`name`/`n`, `pos`/`p`/`loc` (默认 50), `scale`/`s`/`size`/`sz` (默认 1.0), `dimmed`/`dim`/`dark`/`d`, `y`/`yoffset`/`yo`/`offset` (默认 0), `follow`/`mouse`/`cursor`/`fm` | 按注册名创建并渲染 Minecraft 实体 |
| `<worldEntity(player: $self, pos: 50, scale: 1.5)>` | `player`/`pl`/`p`, `displayname`/`display`/`dn`/`name`/`n`, `uuid`/`u`/`uid`, 同上 pos/scale/dimmed/yoffset/follow 参数 | 从游戏世界获取实体渲染。`$self` 表示当前玩家 |

#### 颜色

| 函数 | 参数 | 说明 |
|---|---|---|
| `<color(c: 0xFFFFFFFF)>` | `color`/`colour`/`c`/`val`/`v` | 设置说话人名称下方分割线颜色（格式 `0xRRGGBBAA`） |

#### 特效

| 函数 | 参数 | 说明 |
|---|---|---|
| `<fx_fadeIn()>` | 无 | 画面淡入 |
| `<fx_fadeOut()>` | 无 | 画面淡出（淡出后自动关闭对话框） |
| `<fx_shakeShort()>` | 无 | 短暂屏幕震动（振幅 2.5，周期 4，持续 22 tick 后自动停止） |
| `<fx_shakeFor(t: 22)>` | `t`/`time`/`d`/`duration` (默认 22) | 震动指定 tick 数 |
| `<fx_shakeCustom(a: 2.5, c: 4, t: 22)>` | `amp`/`amplitude`/`a`, `cycle`/`c`/`period`/`p`, `t`/`time`/`d`/`duration` | 自定义震动参数 |

#### 音频

| 函数 | 参数 | 说明 |
|---|---|---|
| `<sound(s: 名称, volume: 1.0, pitch: 1.0)>` | `s`/`n`/`sound`/`name`, `volume`/`v` (默认 1.0), `pitch`/`p` (默认 1.0) | 播放 Minecraft 声音事件 |
| `<music(m: 名称或URL)>` | `m`/`music`/`audio`/`a`/`sound`/`s`/`name`/`n`/`val`/`value`/`v` | 播放动态背景音乐 |

#### 流程控制

| 函数 | 参数 | 说明 |
|---|---|---|
| `<continue(n: 目标)>` | `next`/`n`/`v`/`value`/`val`/`journal`/`j`/`c`/`d`/`destination` | 跳转到另一个 journal 结构（用于选择分支跳转） |
| `<reverseSnapshot()>` | 无 | 返回上一个快照（从子块返回） |
| `<delay(v: tick数)>` | `val`/`value`/`v`/`t`/`tick` | 暂停指定 tick 数再继续下一条 |

#### 其他

| 函数 | 参数 | 说明 |
|---|---|---|
| `<chat(m: 文本)>` | `m`/`msg`/`chat`/`c`/`ctx`/`content`/`context`/`value`/`val`/`v` | 让玩家在聊天栏发送消息 |
| `<info(title: 标题, abstract: 描述)>` | `title`/`t`, `abstract`/`a`/`abs`/`description`/`desc`/`d` | 设置跳过菜单的标题与描述 |
| `<video(url: URL, file: 路径, skip: yes)>` | `url`/`u`/`src`/`source`/`link`/`l`, `file`/`f`/`path`/`p`/`name`/`n`, `skip`/`skippable`/`canSkip`/`allowSkip`/`allow`/`esc` | 播放视频。URL 源或本地文件（从 `.minecraft/lapluma/videos/` 或资源包 `assets/lapluma/videos/` 查找），`skip` 控制是否可跳过 |

### 2.6 完整示例

```
<bg(b: empty)>
 :这是一个对话过程的演示
 :你好, %player_name%!
<show(avg:person_a, pos: 25)>
<show(avg:person_b, pos: 100, dim: yes)>
A: 这是A在说话
<clean()>
<show(avg:person_a, pos: 25, dim: yes)>
<show(avg:person_b, pos: 100)>
<color(c: 0xFFFFFF55)>
B: 这是B在说话
<color(c: 0xFFFFFFFF)>
<clean()>

 :这是实体渲染的演示
<entity(e: minecraft:zombie, pos: 30, scale: 2.0)>
<entity(e: minecraft:skeleton, pos: 70, scale: 1.5, dim: yes)>
 :左边是僵尸，右边是骷髅
<clean()>

<worldEntity(player: $self, pos: 50, scale: 1.5, follow: yes)>
 :这是你自己！会跟随鼠标移动
<clean()>

 :这是一个选择过程的演示
[选项A — 95号汽油]<continue(n: $this.s1)><chat(m: 我要吃95号汽油)>
[选项B — 97号汽油]<continue(n: $this.s2)><chat(m: 我要吃97号汽油)>
[选项C — 柴油]<continue(n: $this.s3)><chat(m: 我要吃柴油)>

!s1{
 :你选择了95号汽油
}
```

---

## 3. 变量与特殊语法

| 语法 | 位置 | 说明 |
|---|---|---|
| `%player_name%` | .journal 文本 | 替换为当前玩家名称 |
| `$this` | .journal 选择项 | 替换为当前 journal 文件名，`$this.s1` 等于 `<当前文件>.s1` |
| `$self` | worldEntity player 参数 | 引用当前玩家 |
| `&` | .journal 文本 | 自动转换为 `§`（Minecraft 格式化代码），如 `&a` → `§a`（绿色） |
| `$player` | .chat 发件人 ID | 玩家身份 |
| `SYSTEM` / `$system` | .chat 发件人 ID | 系统消息 |
