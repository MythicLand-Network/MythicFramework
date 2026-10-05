# 架构

## 目录

- [模块职责](#模块职责)
- [玩家存储时序](#玩家存储时序)
- [实现约束](#实现约束)
- [开发示例](#开发示例)
- [机器人](#机器人)

## 模块职责

- core：不依赖 Bukkit/NMS 的算法、状态、组件与运行时工具。
- bukkit：平台调度、配置、菜单、物品编辑和泛型玩家存储。
- database：连接池与数据库迁移。
- world：地图快照与世界保存恢复。

Framework 禁止依赖 MythicThePit 或其他玩法插件的模型、规则和文案。通用实现通过已有适配接口或宿主回调接收业务行为, 配置策略和持久化业务格式由宿主负责。

| 通用实现 | 所属模块与宿主边界 |
|---|---|
| Cooldowns、RegistryKeyNormalizer | core：到期计时和基础名称转换；宿主负责玩家事件清理及玩法别名 |
| GzipCodec | core：压缩、解压大小回调和 SHA-256；宿主负责版本、字段、大小限制与错误语义 |
| AbstractYamlConfig、ConfigBinder、YamlConfigSynchronizer | bukkit.config：文件加载、字段绑定、默认值补全与未知字段清理；宿主实现 checkConfig 与 additionalConfigPaths |
| ItemEditor、ItemEditors | bukkit.item：平台物品编辑；宿主负责标签、扣命、交易和掉落 |
| StoredPlayerSession、StoredPlayerCache、SimpleStoredPlayerLoadService、SimpleStoredPlayerSaveService | bukkit.store：会话、缓存与保存调度；宿主提供资料、仓库和库存适配 |

## 玩家存储时序

加载在 IO 执行器读取仓库, 回到主线程后核对当前加载归属, 再安装会话并应用库存。失败处理同样回到主线程, 由宿主的 loadFailureHandler 执行。

保存先在主线程捕获库存, 通过 StoredPlayerInventory.mergeCaptured 合并宿主维护的独立库存, 再构造带版本的刷盘请求。退出会话进入待保存集合, 重进可恢复同一会话；保存失败、保存期间新写入和交易回滚仍沿用会话版本与提交边界。关服等待保存完成, shutdownAnnouncement 只在宿主要求公告时执行。

## 实现约束

复用现有模块, 不新增框架体系。优先具体实现和共用方法；仅在真实能力或适配边界使用接口。类型跨类加载器时保留必要身份, 生命周期与线程约束由现有运行机制维护。

涉及下沉代码时同时验证 Framework 与使用方的行为及打包, 不将算法测试结果当成线上性能证明。

## 开发示例

宿主的配置类继承 AbstractYamlConfig, 字段使用 ConfigProperty, 玩法校验放在 checkConfig。通用物品修改可以组合 ItemEditors.name 与 ItemEditors.lore；业务标签由宿主随后处理。接入玩家存储时实现 StoredPlayerRepository、StoredPlayerProfile 和 StoredPlayerInventory, 仅当宿主有独立库存时覆写 mergeCaptured。

check 检查所有模块禁止依赖 Pit, 并检查 core 的源码不直接依赖 Bukkit/NMS；通用配置、冷却、物品和压缩行为由对应模块测试验证。

## 机器人

Framework core 的 RoundRobinBudget、StaggeredDeadline、CyclicPathCursor 和 NavigationProgress 管理更新预算、到期检查、循环路线与导航进度；bukkit 的 GridPathfinder 提供通行约束与搜索预算。bukkit.bot.v1_8_R3.BotEntity 提供不由原生跟踪器展示的玩家实体、物理移动和状态 tick, 宿主实现死亡回调；BotVisibility 通过 BotVisibilitySettings 和已格式化的可选名称管理玩家可见性、皮肤档案、名字隐藏、悬浮字及变化包。所有实体和显示方法在主线程调用, 每 tick 先调用 activity, 实体移除前调用 destroy, 卸载时清理所有实体后调用 clear。

独立宿主可以直接继承 Framework 的实体并提供独立 UUID 的 GameProfile、死亡处理和显示策略, 在子类初始化回调和显示属性后调用 spawn(), 无需依赖 Pit 玩家资料或奖励服务。ThePit 的接入见[玩法架构](../../MythicThePit/docs/architecture.md#机器人)。

core 的 PathLoadBalancer 提供循环路线的错峰起点、沿路线弧长的间距均衡及移动目标预留。宿主通过 begin、observe、end 刷新成员位置与实际前进速度, crossing 仅在分布不均时选择到达时位于另一侧的空位, destination 预测固定速度下的交会位置；已有横穿持续到达, 到达后通过 nextWaypoint 接回外圈前进方向。均匀后停止新增横穿, finish 释放到达或中断目标, remove 清理死亡及移除成员。计算使用 fastutil 8.5.13 原生 long 索引和可复用数组, Bukkit 通行检查及死亡处理仍由宿主管理。
