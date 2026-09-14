# 世界地图保存与启动重置

`mythic-framework-world` 是可选的 1.8.8 地形快照模块，不依赖 ThePit。它不需要 Java Agent，也不修改服务端字节码。业务插件分别创建自己的世界管理实例和存储目录，因此 ThePit 与 Lobby 可以在各自的服务端部署中复用。

## 构建

```powershell
.\gradlew.bat test assemble
```

产物为 `world/build/libs/MythicFramework-World.jar`。构建环境的本地 Maven 仓库需提供 `org.spigotmc:spigot:1.8.8-R0.1-SNAPSHOT`，因为区域文件清理使用 1.8.8 NMS 的 NBT 读写实现。

## 宿主接入

1. 在插件 `onLoad`、Bukkit 加载世界之前创建 `WorldManager(plugin, storageDirectory, resetEnabled)` 并调用 `prepare()`。重置开启时，此步骤按已有基准的文件名替换对应世界的 `region` 目录。
2. 在 `onEnable` 中调用 `loadAdditionalWorlds()`，确保基准中的额外世界先于位置、NPC 和玩法组件加载。
3. 管理员在主线程调用 `save(worldName)`。保存会先刷新区块写入并取得区域文件副本，再异步清除实体、压缩及原子替换基准；Future 完成后才表示保存成功。
4. 关服时调用 `close()`，停止接收新保存并等待已取得的快照写完。关服本身不会更新基准。

世界环境保存在地形归档的 ZIP 注释中。NORMAL 使用 `<世界>/region`，NETHER 使用 `<世界>/DIM-1/region`，THE_END 使用 `<世界>/DIM1/region`。

## ThePit 配置

ThePit 使用 `plugins/MythicThePit/world.yml`：

```yaml
world-reset: false
```

先在服务器运行期间执行保存，再停止服务器并将 `world-reset` 改为 `true`。重置开启后，启动时会按所有已保存基准的世界名恢复地图；没有任何基准时会中止启动并提示先保存。重置关闭时不会替换或额外加载世界，但仍可执行保存命令。

```text
/pit world save [世界名]
```

权限为 `pit.admin`。玩家省略名称时保存当前世界，控制台必须指定名称；每个世界同时只允许一个保存任务。

基准保存在 `plugins/MythicThePit/worlds/<世界名>.terrain`。其中只包含经过清理的 `.mca` 区域文件：保留方块和箱子、告示牌等方块实体，移除区块中的普通实体。不保存或恢复 `level.dat`、`playerdata`、玩家背包、生物、掉落物、盔甲架、画、展示框、统计或业务数据库。启动替换只处理目标世界的 `region` 目录，其他世界文件保持原状。

旧版生成的 `.slime` 基准不会再读取。部署新版后应从启动命令移除 `-javaagent:MythicFramework-World-Agent.jar`；服务端停止后可移除根目录中的旧 Agent 文件。
