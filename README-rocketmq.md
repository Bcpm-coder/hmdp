# RocketMQ 秒杀改造

## 业务代码位置

`OrderConsumer.onMessage` 只接收消息并调用 `IVoucherOrderService.handleOrder`；`VoucherOrderServiceImpl.handleOrder` 负责锁和事务，内部 `persistOrder` 负责幂等检查、扣库存、保存订单及处理结果。请求入口 `seckillVoucher` 与查询入口 `queryResult` 也保留在该 Service。

## 数据流

请求 → Lua（预扣库存、购买标记、待发送 Stream、PENDING 查询记录）→ 转发器 → RocketMQ → Redisson 锁 → MySQL 事务（扣库存、订单、最终结果）。

POST `/voucher-order/seckill/{id}` 成功返回订单 ID，只代表已受理。GET `/voucher-order/result/{orderId}` 携带 `authorization` token 查询本人结果，返回 `orderId`、`status`（PENDING/SUCCESS/FAILED）及可选 `reason`。没有资格的请求直接失败，不创建待处理记录。

### 可靠性

- 本地队列已移除。Lua 同时记录库存预扣与待发送事件，应用退出后 Stream 事件仍可恢复。
- Stream 消费组 `rocketmq-relay` 从 `0` 创建，处理历史记录。每轮读取最多 20 条新事件和 20 条超时 pending 事件。60 秒未确认的 pending 可被其他实例认领。仅 SEND_OK 后 XACK/XDEL；发送成功但未 ACK 会重复发送。
- RocketMQ Topic 默认 `hmdp-seckill-order`，消息 key 为订单 ID，集群消费组 `hmdp-seckill-order-consumer`；消费最多重试 16 次后进入 `%DLQ%hmdp-seckill-order-consumer`。
- Redisson 锁为用户+优惠券，拿锁失败抛异常重试。VoucherOrderServiceImpl 在锁内通过 TransactionTemplate 开启事务，提交后释放锁；订单 ID、请求表主键与用户/优惠券唯一索引共同兜底幂等。
- 数据库扣库存失败记录 FAILED，不插入订单。数据库异常、订单插入异常回滚并触发重试。请求终态与订单及扣库存同一事务提交。
- 补偿器每 5 秒扫描明确 FAILED 且未补偿记录。Lua 只撤销仍由此订单持有的预扣，重复执行、旧订单补偿均不会重复加库存。Redis 操作后数据库标记失败也可安全重试。未知状态、数据库不可用、死信消息不会自动释放库存。
- Redis Lua 原子执行不等于发生运行时错误会回滚。脚本预检查 key 类型，部署必须 noeviction、预留容量并监控 OOM；Redis 持久性或硬件损坏不在强一致保证内。

## 本地启动

需要 JDK 8、Maven、Docker Compose、MySQL。依赖固定为 rocketmq-spring-boot-starter 2.2.3，Broker 4.9.8。当前 Docker 未运行时先启动 Docker Desktop。

1. 已有 MySQL hmdp 数据库先备份，并暂停旧应用的秒杀写入。全新数据库先导入 `src/main/resources/db/hmdp.sql`；已有库不可重导（其中包含 DROP TABLE）。
2. 执行独立迁移：

```sh
mysql -h 127.0.0.1 -u root -p hmdp < src/main/resources/db/rocketmq-migration.sql
```

迁移检测存量重复用户/优惠券订单并报错停止，不删除数据。失败后先人工处理重复，再 `DROP PROCEDURE IF EXISTS migrate_seckill_mq;` 后重跑脚本。检查索引 `uk_user_voucher` 及 `tb_seckill_request` 存在。DDL 不受应用事务回滚保护。

3. 启动 MQ。若已有 Redis 占用 6379，可只启动 namesrv、broker，并单独为现有 Redis 配置 AOF/noeviction；不得覆盖现有库存数据。

```sh
docker compose -f deploy/docker-compose.yml up -d
# 或：docker compose -f deploy/docker-compose.yml up -d namesrv broker
```

4. 等待 Broker 注册后初始化 Topic：

```sh
docker compose -f deploy/docker-compose.yml exec broker sh mqadmin updateTopic -n namesrv:9876 -b 127.0.0.1:10911 -t hmdp-seckill-order -r 4 -w 4
```

brokerIP1=127.0.0.1 适用于应用运行在宿主机。远程机器或容器内应用必须改为应用可达的 Broker IP，且消费组名称在同一环境中保持一致。启动第二个应用实例仅改变 server.port，不改变消费组。

5. 运行 `mvn spring-boot:run`。可配置 ROCKETMQ_NAMESERVER、ROCKETMQ_SEND_TIMEOUT_MS、SECKILL_TOPIC、SECKILL_CONSUMER_GROUP。MySQL/Redis 连接使用现有 application.yaml；Redisson 与 StringRedisTemplate 共享 spring.redis 配置，生产凭据使用外部配置。

Redis 至少 6.2（XAutoClaim），仅支持单机 Redis。Compose 提供 Redis 7.2.5，AOF everysec，在极端退出时可能损失最近约一秒写入；如需更低丢失窗口可使用 appendfsync always（需评估延迟）。Broker 同步刷盘、持久化卷，单 Broker 不提供节点故障高可用。不要执行 `docker compose down -v`，该命令会删除持久化数据。

旧应用本地队列不能自动迁移。升级前应停止新请求并确认旧队列已消费完成，核对 Redis 与数据库库存；无法确认的预扣人工核对。当前已有优惠券 begin/end 时间校验缺口未在本次扩展，测试需使用专用优惠券，不将此版本视为完整活动时间控制实现。

## 死信、告警与重放

- 配置日志告警：`Outbox relay unavailable`、`retained for retry`、`Compensation pending`、RocketMQ 消费异常持续出现时通知运维。
- 监控 Stream XLEN、XPENDING 和最老 pending 年龄；监控 FAILED 且 compensated=0 数量、数据库库存、Redis 内存与 AOF 状态。
- 每分钟检查 `%DLQ%hmdp-seckill-order-consumer` Topic 写入 offset 增长，发生增长立即告警（可用 RocketMQ Console 或 `mqadmin topicStatus -n namesrv:9876 -t '%DLQ%hmdp-seckill-order-consumer'`）。默认组名变更时同步调整监控。
- 修复依赖或数据错误后，从死信队列读取消息完整内容，校验 schemaVersion、orderId、userId、voucherId，通过 RocketMQ Console 重发至原 Topic，key 保持原订单 ID；数据库终态存在时直接幂等确认。仅在与用户/数据库核对后处理不可恢复消息，不直接删除购买标记或手动加库存。
- 配置 Broker 保留消息 7 天；必须在保留期内处理死信，必要时导出归档。没有自动订阅死信以免确认后消失。
- Redis 查询记录与成功预扣持有者目前不自动过期，避免短 TTL 破坏查询或幂等；上线需按活动归档，在确认所有 pending、重试和死信已处理且活动不再接单后清理。Stream 已确认事件会删除。

## 验证

```sh
python3 scripts/verify_redis.py
mvn -Dtest=VoucherOrderServiceTest,MessageReliabilityTest test
mvn -DskipTests package
```

本机使用较新 JDK 验证时加 `-Dlombok.version=1.18.30`；JDK 8 可使用原依赖版本。旧有 `HmDianPingApplicationTests` 会连接真实服务并写入缓存，不包含在上述隔离单元测试命令中。

Redis 验证使用临时 Unix socket 独立实例，覆盖有限库存并发、同用户并发、预扣与 outbox 同写、补偿重复执行、旧订单不影响新持有者、异常 pending 认领及错误类型预检查。Java 单元测试覆盖重复消费、库存不足、已有订单、插入失败异常传播及成功终态，mock 测试不证明真实数据库回滚。

集成验收（独立测试库、测试优惠券、两个应用实例）：

| 场景 | 操作与断言 |
|---|---|
| 同用户并发 | 并发提交同一优惠券，数据库最多一个订单 |
| 有限库存竞争 | 库存 N，多于 N 个用户请求；订单数不超过 N，数据库和 Redis 库存不负 |
| 重复消费 | 同一 orderId 消息重发多次；库存只扣一次，只有一条结果 |
| 真实事务回滚 | 用测试库触发器使订单 INSERT 抛错；请求结果、订单均不写入，数据库库存不变；恢复后重试成功 |
| MQ 不可用 | Broker 停止后提交请求，Redis 为 PENDING，Stream 保留；Broker 恢复后最终成功 |
| 发送后退出 | 断点停在 syncSend 返回后、ACK 前并终止进程；重启/另一实例认领后订单仅创建一次 |
| 消费后退出 | 断点停在事务返回后、监听器返回前并终止进程；重投后不再次扣库存 |
| 补偿恢复 | 构造 Redis 有库存而 DB 无库存，等待 FAILED 和补偿；补偿后库存只恢复一次；DB 标记失败时重复补偿安全 |
| 权限查询 | 其他用户查询该 orderId 返回不存在；未知 orderId 返回不存在 |
| 死信 | 持续消费错误到重试耗尽，消息保留死信，库存不自动回补；修复后用原 ID 重放 |

## 本次验证结果

- 隔离真实 Redis 脚本验证：通过（7 类场景）。
- Docker Compose 配置校验：通过；可执行 JAR 打包：通过。
- Java 编译及订单事务分支单元测试：通过（9 项，含发送失败保留事件、发送确认后 ACK、锁竞争重试和事务异常传播）。本机使用 JDK 17 并临时指定 `-Dlombok.version=1.18.30` 解决旧 Lombok 与较新 JDK 的兼容问题，Java 编译目标仍为 8。
- MySQL/RocketMQ 端到端、多实例、故障注入：本机 Docker daemon 未启动，尚未执行；不能据此宣称集群可靠性已经验证。
