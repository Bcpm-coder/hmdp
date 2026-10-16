# hm-dianping

类似于大众点评等打卡类APP的点评类项目。实现了短信登陆、探店点评、优惠券秒杀等多个模块。用户可以浏览首页的推荐内容，搜索附近的商家，查看商家的详情和评价以及发表探店博客，抢购商家发布的限时秒杀优惠券。

围绕点评业务，提供验证码登录、商铺查询、优惠券秒杀、探店笔记、点赞与关注等接口，并扩展了 RocketMQ 异步订单、商铺二级缓存和 Redis 滑动窗口限流。

## 技术栈

Java 8、Maven 3、Spring Boot 2.3.12、MyBatis-Plus、MySQL、Redis、Redisson、Caffeine 2.9.3、RocketMQ 4.9.8。

## 二次开发内容

本次二次开发主要围绕秒杀订单的异步处理、热点商铺查询和接口限流展开。验证码登录、商铺、探店笔记、点赞与关注等功能沿用原项目业务基础，以下为当前扩展实现。

### 1. RocketMQ 异步秒杀订单

将秒杀下单改为 Redis 预扣、可靠事件转发、RocketMQ 异步消费与 MySQL 事务落单的处理链路，避免依赖应用进程内的本地队列。

```text
秒杀请求 → Redis Lua 预扣库存 / 购买标记 / PENDING 记录 / Stream 待发送事件
        → OutboxRelay → RocketMQ → OrderConsumer
        → Redisson 用户与优惠券锁 → MySQL 事务扣库存、保存订单与最终结果
```

- **预扣与事件同写**：Lua 在一次执行中完成资格检查、库存预扣及待发送事件写入；转发器仅在 MQ 返回发送成功后确认并删除 Stream 事件，支持超时 pending 事件认领与重发。
- **幂等消费**：使用订单 ID、请求记录主键、用户与优惠券唯一索引，以及 Redisson 锁防止重复落单；通过 `TransactionTemplate` 将数据库库存扣减、订单与终态保存放在同一个事务中，提交后再释放锁。
- **失败补偿**：定时扫描明确失败且未补偿的记录，通过 Lua 校验预扣持有者后恢复 Redis 库存与购买资格，支持重复补偿安全执行。
- **异步结果查询**：`POST /voucher-order/seckill/{id}` 返回订单 ID 表示已受理；新增 `GET /voucher-order/result/{orderId}`，仅允许本人查询 `PENDING`、`SUCCESS`、`FAILED` 状态。
- **迁移与部署**：增加 `tb_seckill_request` 请求结果表、用户与优惠券唯一索引，以及 Redis / RocketMQ 的 Docker Compose 开发环境。

关键实现：[`VoucherOrderServiceImpl`](src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java)、[`mq`](src/main/java/com/hmdp/mq)、[`seckill.lua`](src/main/resources/seckill.lua)、[`compensate.lua`](src/main/resources/compensate.lua)。

边界：消息可能重复投递，由业务幂等处理；未知状态和死信消息不会自动释放库存，需要核对与重放。当前方案使用单机 Redis、单 Broker，未实现节点故障高可用；优惠券活动开始与结束时间校验仍待完善。

### 2. Caffeine + Redis 商铺二级缓存

在原有 Redis 商铺缓存之前增加进程内 Caffeine 缓存，减少热点商铺详情查询对 Redis 的重复访问。

```text
查询：Caffeine → Redis → MySQL → 回填缓存
更新：数据库事务提交 → 删除本机缓存与 Redis → Pub/Sub 通知各实例删除本地缓存
```

- **容量与过期控制**：默认最多缓存 500 条商铺详情，写入后 30 秒过期，可通过 `SHOP_LOCAL_CACHE_MAXIMUM_SIZE` 和 `SHOP_LOCAL_CACHE_TTL_SECONDS` 调整。
- **并发加载合并**：同一实例内同一商铺 ID 合并加载；向调用方返回实体副本，避免业务修改共享缓存对象。不存在的商铺不写入本地缓存，继续沿用 Redis 空值缓存。
- **提交后失效**：通过事务提交回调触发缓存删除，回滚时保留缓存；使用 Redis Pub/Sub 广播商铺 ID，让各实例独立清理本地缓存。
- **失败重试与统计**：Redis 删除或广播失败时加入进程内重试集合，默认每 5 秒重试；启用 Caffeine 命中、加载与淘汰统计。

关键实现：[`ShopLocalCache`](src/main/java/com/hmdp/utils/ShopLocalCache.java)、[`ShopCacheConfiguration`](src/main/java/com/hmdp/config/ShopCacheConfiguration.java)、[`ShopServiceImpl`](src/main/java/com/hmdp/service/impl/ShopServiceImpl.java)。

边界：允许短暂旧数据，不保证强一致；Pub/Sub 不回放离线通知，重试集合在进程退出后丢失。本地 TTL 不能保证数据库更新后 30 秒内必然读到新值，仍需处理 Redis 删除失败及并发旧值回填等情况。缓存仅用于商铺详情。

### 3. Redis 滑动窗口限流

新增 `@RateLimit` 注解，通过 Spring AOP 在 Controller 执行业务前进行分布式限流，支持用户、IP、全局三种维度。

```java
@RateLimit(name = "seckill", dimension = LimitDimension.USER,
           windowSeconds = 10, maxRequests = 3)
```

- **秒杀入口保护**：当前同一用户最近 10 秒最多放行 3 次秒杀请求，额度跨优惠券 ID 共享；多个应用实例连接同一 Redis 时共享额度。
- **原子窗口计算**：使用 Redis ZSet 与 Lua 完成过期记录清理、数量判断、请求记录及 TTL 设置；以 Redis 服务端时间计算窗口，以 UUID 区分同毫秒请求。
- **明确响应语义**：超出额度返回 HTTP `429`，限流 Redis 调用异常返回 `503`；拒绝请求不计数、不续期，已经放行的业务失败请求仍计入额度。
- **可复用规则**：支持 `USER`、`IP`、`GLOBAL`，每个方法配置一条规则；IP 默认读取 `getRemoteAddr()`，不直接信任客户端传入的转发头。

关键实现：[`ratelimit`](src/main/java/com/hmdp/ratelimit)、[`rate-limit.lua`](src/main/resources/rate-limit.lua)、[`WebExceptionAdvice`](src/main/java/com/hmdp/config/WebExceptionAdvice.java)。

边界：AOP 在登录拦截器之后执行，仅作用于经过 Spring 代理且标注了注解的方法；限流不替代库存校验、一人一单和数据库约束。当前没有吞吐压测数据。

### 4. 验证工具与发布整理

- 增加订单消费与消息转发、商铺缓存、限流相关单元测试，以及真实 Redis 缓存广播 / TTL 集成测试。
- 提供 [`verify_redis.py`](scripts/verify_redis.py) 和 [`verify_rate_limit.py`](scripts/verify_rate_limit.py)，通过临时隔离 Redis 实例验证预扣、补偿、并发额度与窗口恢复。
- 将数据库凭据、Redis 连接、服务端口与上传目录改为环境变量配置，Redisson 复用 Spring Redis 连接设置。
- 清理历史 SQL 数据、IDE 配置与构建产物，完善忽略规则及启动文档，统一二次开发维护者标注为 YJn。

测试覆盖与执行条件见下方专题文档。完整 MySQL / RocketMQ 多实例端到端及故障注入验证尚未完成，也未提供性能提升百分比；本节描述当前实现能力，不代表所有部署场景均已验证。

## 本地启动

1. 安装 JDK 8 与 Maven，准备 MySQL（建议 5.7）、Docker Compose。
2. 创建一个空的 `hmdp` 数据库，依次执行以下脚本。**初始化脚本包含 DROP TABLE，只能用于新的开发数据库。**

   ```sh
   mysql -u root -p -e 'CREATE DATABASE hmdp CHARACTER SET utf8mb4;'
   mysql -u root -p hmdp < src/main/resources/db/hmdp.sql
   mysql -u root -p hmdp < src/main/resources/db/rocketmq-migration.sql
   ```

3. 启动 Redis 与 RocketMQ，并创建秒杀主题：

   ```sh
   docker compose -f deploy/docker-compose.yml up -d
   docker compose -f deploy/docker-compose.yml exec broker sh mqadmin updateTopic -n namesrv:9876 -b 127.0.0.1:10911 -t hmdp-seckill-order -r 4 -w 4
   ```

4. 设置连接信息后启动：

   ```sh
   export DB_USERNAME=root
   read -s 'DB_PASSWORD?MySQL password: '
   export DB_PASSWORD
   mvn spring-boot:run
   ```

   上面的密码输入命令适用于 zsh；其他终端可通过 IDE 的环境变量设置 `DB_PASSWORD`。默认监听 `8081`。

| 环境变量 | 用途 / 默认值 |
| --- | --- |
| `DB_URL` | MySQL JDBC URL，默认本机 3306 的 hmdp 数据库 |
| `DB_USERNAME` | 数据库用户名，默认 root |
| `DB_PASSWORD` | 数据库密码，必须设置 |
| `REDIS_HOST` / `REDIS_PORT` | 默认 127.0.0.1 / 6379 |
| `REDIS_PASSWORD` | 默认空，本地无密码 Redis |
| `ROCKETMQ_NAMESERVER` | 默认 127.0.0.1:9876 |
| `SERVER_PORT` | 默认 8081 |
| `IMAGE_UPLOAD_DIR` | 图片上传目录，默认 ./uploads |

数据库脚本仅保留表结构，已移除历史用户、手机号、商铺、笔记及订单等数据。首次启动业务列表为空，需自行准备测试数据。仓库不包含前端与图片素材；图片目录需自行配置静态资源服务。Compose 提供本地开发用的 Redis 和 RocketMQ，MySQL 需另行准备；远程部署时需调整 `deploy/broker.conf` 中的 Broker 地址。

## 二次开发文档

- [RocketMQ 异步订单与补偿机制](README-rocketmq.md)
- [商铺二级缓存与一致性边界](README-cache.md)
- [Redis 滑动窗口限流](README-ratelimit.md)

## 验证与构建

无需连接外部数据库的单元测试：

```sh
mvn -Dtest=ShopLocalCacheTest,VoucherOrderServiceTest,MessageReliabilityTest,RateLimitTest test
mvn -DskipTests package
```

`HmDianPingApplicationTests` 需要完整的外部服务，并会写入 Redis。真实 Redis 集成测试与验证脚本的前置条件见各专题文档。

## 提交到 GitHub

本地密码、`.env`、IDE 文件、上传文件、日志和构建产物已加入 `.gitignore`。Spring Boot 不会自动加载 `.env`，请通过终端或 IDE 注入环境变量。

```sh
git init
git branch -M main
git add .
git diff --cached --stat
git commit -m "chore: prepare YJn secondary development project"
git remote add origin <你的 GitHub 仓库地址>
git push -u origin main
```
