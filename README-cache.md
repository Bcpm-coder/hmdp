# 商铺详情二级缓存

## 流程

读取：`ShopServiceImpl.queryById` → `ShopLocalCache.get` → Caffeine → 原 `CacheClient` 的 Redis/数据库查询 → 回填本地缓存。

默认本地最多 500 条，写入后 30 秒过期（读取不会续期）。只缓存实际存在的商铺，缺失结果继续使用 Redis 空值缓存。同一实例同一个 ID 的加载合并，不能消除多实例回源竞争。返回实体副本，避免调用方修改共享缓存对象。

更新：`update` 检查数据库更新成功后注册事务回调；提交成功后删除本机缓存、删除 Redis，再通过 `cache:shop:invalidate` 发布商铺 ID。每个实例的 RedisMessageListenerContainer 都订阅此频道，收到通知只删除本地缓存。回滚不发布通知、不删除缓存。

删除 Redis 或发布通知异常时记录错误，并每 5 秒重试。这是进程内重试集合，进程退出后会丢失，不是可靠 outbox。必须监控 `Shop cache invalidation pending` 日志；必要时人工删除对应 Redis key。请求成功表示数据库更新已提交，不代表所有缓存同步失效。

## 配置

application.yaml：

- SHOP_LOCAL_CACHE_MAXIMUM_SIZE：默认 500 条，限制数量而非字节数。
- SHOP_LOCAL_CACHE_TTL_SECONDS：默认 30 秒。
- shop.local-cache.retry-delay-ms：默认 5000 毫秒。

使用 Caffeine 2.9.3，保持 Java 8 兼容。Redis 监听和业务查询共享原 spring.redis 连接配置。秒杀库存、订单状态、登录态不接入本地缓存。

## 一致性边界

这是允许短暂旧数据的基础方案，不保证强一致。

- Redis Pub/Sub 不保存离线期间的通知。本地写入 TTL 为遗漏通知兜底；订阅断线时不会回放。
- 30 秒限制的是一次本地写入的驻留时间，不是数据库更新后必然 30 秒内读到新值。如果 Redis 删除失败，或并发读回填了旧值，仍可能再次加载旧数据。
- 事务提交后删除减少未提交旧值回填的窗口，但不能完全消除先读旧值、后写缓存的竞态。更严格需求应再引入版本/加载失效协调或可靠失效事件。
- 缓存失效广播需要每个实例收到，不使用秒杀 MQ 的同组分担模式。
- 本地 TTL 到期后恢复依赖正确的 Redis/数据库数据，Pub/Sub 不是唯一一致性保障。

## 验证

隔离单元测试（不启动整个应用、不连接现有数据库）：

```sh
mvn -Dtest=ShopLocalCacheTest,VoucherOrderServiceTest,MessageReliabilityTest test
```

真实 Redis 测试（需要 redis-server 命令，启动临时隔离实例，自动关闭）：

```sh
mvn -Dshop.redis.integration=true -Dtest=ShopCacheRedisTest test
```

较新 JDK 本机验证使用 JDK 17 和临时参数 `-Dlombok.version=1.18.30`；项目源码目标仍为 Java 8。

覆盖：缓存命中与对象隔离、本地失效重新加载、同 key 并发加载合并、缺失对象不写本地缓存、提交后失效与回滚保留、Redis 删除失败重试。真实 Redis 验证两个独立缓存/订阅者均失效，以及没有订阅者时通过 TTL 重新加载。

完整应用双进程验证：启动 8081、8082 两个实例，先分别访问同一个商铺预热；在 8081 修改，再在 8082 查询，确认加载新值。实际 MySQL 回滚及双进程端到端测试尚未执行。

性能对比：用相同请求分布分别测试改造前后，记录 Redis GET 数量、接口 P95、吞吐和堆内存。启用的 Caffeine recordStats 可通过 ShopLocalCache.stats 查看命中/加载/淘汰统计；目前未压测，不声称具体提升幅度。

本次验证结果：14 项单元测试及 1 项真实 Redis 双缓存广播/TTL 集成测试全部通过。真实 Redis 测试使用同一 JVM 中两个独立缓存及订阅连接模拟应用实例，不能替代完整应用双进程/MySQL 端到端验证。尚无压测数据。
