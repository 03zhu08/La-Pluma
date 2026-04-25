# La Pluma 羽毛笔
*为Minecraft所开发的用于呈现交互式文字小说内容的演绎工具*

![La Pluma Logo](src/main/resources/logo.png)

客户端方面基于Forge平台所开发, 主要开发面向版本为Minecraft 1.12.2, 并且Minecraft 1.16.5也在移植中并同步进行更新. 

服务端方面兼容Spigot, 以插件形式运行, 提供较多API与客户端Mod交互。Sponge版本开发中. 

该项目基于 GPL 3.0 协议开源 - 由 SkyPixel Dev 维护更新

## 使用
Mod作为一种演绎工具, 其故事剧本由 **.journal** 格式的文件所描述。该格式包含Prompt、Selection、Function以满足不同功能. 
具体格式规范见: [La Pluma Journal格式规范](https://shimo.im/docs/m8AZVBJnXoToOJAb)

自 1.4.1 起，新增 **聊天系统**（Chat System），模拟即时通讯界面风格，支持：
- 多联系人 / 群聊
- 每联系人可有多段独立对话，通过左侧下拉切换
- 基于 `.chat` 格式的分支对话脚本——选择不同回复可跳转至不同后续内容
- 消息气泡淡入、滚动边缘渐变、切换联系人闪屏等过渡动画
- 打字机效果，可点击跳过
- 玩家头像自动使用当前皮肤头部正面

### `.chat` 格式规范

```
#conv <conversation_id>          对话 ID（必须首行）
#contact <contact_id>            所属联系人
#title <标题>                    下拉框显示名
#status <unread|completed>       状态

@<sender_id>|<sender_name>      消息发送者（$player = 玩家，SYSTEM = 居中系统消息）
<message content>                消息正文（可多行）
+ <option_text> -> <branch_id>  回复选项，-> 后为跳转分支 ID
+ <option_text> -> <branch_id> |main  选项后追加 |main 表示分支结束后返回主线

=<branch_id>                    分支起始标记，后续 @ 消息属于此分支
=main                           主线延续标记，|main 选项的分支结束后回到此处
```

**选项分支规则：**
- `+ 选项 -> branch_id` — 选择后跳转到 `=branch_id` 分支，分支结束后对话结束
- `+ 选项 -> branch_id |main` — 选择后跳转到分支，分支结束后返回 `=main` 处继续
- 分支内可嵌套更多选项，同样支持 `|main`

**示例：**
```
#conv conv_demo
#contact npc_001
#title 演示对话
#status unread

@npc_001|艾琳
我这里有些新发现，你想先了解哪方面？
+ 关于古籍的研究 -> research |main
+ 关于炼金材料 -> alchemy |main
+ 直接开始任务 -> quest

=research
@npc_001|艾琳
古籍记载了一种古老的炼金术，需要三种稀有材料才能施展。

=alchemy
@npc_001|艾琳
我已经从古籍中整理出了一份材料清单，包括月光草、火蜥蜴鳞片和纯净之水。

=main
@npc_001|艾琳
总之，如果你准备好了，我们可以立即开始收集材料。
+ 好的，出发吧 -> quest
+ 我再想想 -> later

=quest
@npc_001|艾琳
很好！记得带上你的装备，路上可能会遇到危险。

=later
@npc_001|艾琳
没关系，等你准备好了随时来找我。
```

`.chat` 格式示例见 `assets/lapluma/chat/` 目录下的文件。

所有故事与聊天剧本的资源文件应以资源包(Resourcepack)形式引入
```
assets/
    lapluma/
        avg/        立绘中CG、背景、人物立绘等资源引入
        chat/       .chat 聊天剧本文件
        icon/       页面UI的图标自定义
        journals/   .journal文件故事剧本
        musics/     音乐资源引入
        sounds/     页面交互音效自定义
```

目前, 在游戏中可以通过指令调出对应剧本进行播放演绎, 服务端也可以通过指令使装有对应Mod与资源包的玩家播放剧情.

## 构建
项目现在支持按平台分别打包视频解码 native，以减小单个 Mod JAR 体积。
由于项目仍基于 ForgeGradle 2.3 / Minecraft 1.12.2，构建时应使用完整的 JDK 8。
`build-platform-jars.sh` 会优先自动选择本机可用的 JDK 8。

单平台构建:
```bash
./gradlew clean build -PtargetPlatform=windows-x86_64
./gradlew clean build -PtargetPlatform=linux-x86_64
./gradlew clean build -PtargetPlatform=macosx-x86_64
./gradlew clean build -PtargetPlatform=macosx-arm64
```

一键构建全部平台:
```bash
bash ./build-platform-jars.sh
```

只构建指定平台:
```bash
bash ./build-platform-jars.sh windows-x86_64 macosx-arm64
```

构建产物位于 `build/libs/`，文件名会带对应平台后缀。

## 开发计划
施工中

## 特别感谢
施工中