# ChickenDupe

ChickenDupe 是一个 Minecraft 服务器插件（Paper 1.21），它允许玩家将手中物品绑定到鸡身上。插件支持两种模式：

- **KILL 模式（默认）**：杀死绑定的鸡时掉落物品
- **EGG 模式**：鸡会定时自动下蛋（掉落物品）

> 刷物品方式：默认为**杀鸡掉落**，可在 `config.yml` 中切换为**下蛋**模式。

![image](https://user-images.githubusercontent.com/98635300/235320824-f37109cc-6d2b-4747-9e2d-6ae7f2581cbe.png)

## 安装

1. 前往 [Releases](https://github.com/ZhouyiStudio/ChickenDupe/releases) 页面下载最新版本的插件。
2. 将 `ChickenDupe.jar` 放入服务器 `plugins/` 目录。
3. 重启服务器或使用 `/reload confirm`，插件将自动加载。

## 使用

1. 玩家**手持任意物品**（不能为空手），**右键点击已成年的鸡**，物品即绑定到该鸡身上，鸡头顶会显示物品名称。
2. **KILL 模式（默认）**：杀死绑定的鸡，物品会掉落在地。**EGG 模式**：插件会以设定好的间隔（默认 60 秒）周期性地扫描已绑定物品的鸡，自动掉落物品。
3. 如果鸡死亡，其绑定的物品数据会自动清除。
4. 玩家输入 `/dupe` 可查看自己的等级、今日已用次数和上限。

## 权限等级

| 等级 | 说明 | 每日次数上限 |
|------|------|-------------|
| 管理员（EXE） | 由控制台添加白名单 | ∞（无限） |
| VIP | 由控制台按玩家单独配置 | 可自定义（-1 为无限） |
| 普通玩家 | 所有未在上述列表中的玩家 | 由 `DailyLimit` 配置（默认 50） |

## 命令

| 命令 | 说明 | 执行者 |
|------|------|--------|
| `/dupe` | 查看个人信息（等级、使用次数） | 玩家 |
| `/dupe exeadd <玩家名>` | 将玩家添加为高级用户（无限次） | 控制台 |
| `/dupe vipadd <玩家名> <每天次数>` | 添加 VIP 玩家并设置每日次数（-1 = 无限） | 控制台 |
| `/dupe vipdel <玩家名>` | 从 VIP 列表中移除玩家 | 控制台 |

## 配置

编辑 `plugins/ChickenDupe/config.yml`：

```yaml
# 刷物品间隔（单位：秒）
# 太高会导致服务器卡顿
SpawnInterval: 60

# 每次刷出的物品数量
# 太高会导致服务器卡顿
SpawnNumber: 1

# 普通玩家每天可使用的次数上限
# -1 = 无限，0 = 不允许使用
DailyLimit: 50
```

## 数据存储

插件在 `plugins/ChickenDupe/data.yml` 中持久化保存：
- 鸡与物品的绑定关系
- 高级用户（EXE）白名单
- VIP 列表及每日次数
- 所有玩家的每日使用记录（按 UUID 索引）

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/ChickenDupe-<version>.jar`。

## 支持

如有问题或建议，请提交 [Issues](https://github.com/ZhouyiStudio/ChickenDupe/issues)。

## 版权和许可证

该插件基于 MIT License 开源，您可以自由使用、分发和修改本插件的代码。
