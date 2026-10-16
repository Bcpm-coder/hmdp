# Redis 滑动窗口限流

## 使用

秒杀 Controller 已配置：

```java
@RateLimit(name="seckill", dimension=LimitDimension.USER, windowSeconds=10, maxRequests=3)
```

同一用户调用秒杀入口（跨所有优惠券 ID、跨所有应用实例），最近 10 秒最多放行 3 次。限制的是进入业务的请求次数，不是成功订单数；放行后库存不足等业务失败也计入额度。第 4 次返回 HTTP 429，body 仍为 Result.fail。Redis 限流调用异常返回 HTTP 503，不静默放行。

可在其他 Controller 方法添加注解，dimension 支持 USER、IP、GLOBAL 三选一；每个方法只支持一条规则，不叠加。name 是稳定接口标识；同 name、同维度必须使用相同窗口和额度，否则共用 key 会互相干扰。默认窗口 10 秒、额度 3，窗口允许 1～86400 秒、额度允许 1～100000；大额度仅是配置边界，不代表推荐值。

## 执行链路

登录拦截器 → Controller AOP → Redis Lua 限流 → 原秒杀 Lua → 待发送事件 → RocketMQ 落单。

- RateLimit：自定义方法注解，只配置规则，不执行业务。
- RateLimitAspect：Spring AOP 在标注方法执行前获取规则，拼 key 并调用限流器，通过后才 proceed。
- RedisWindowLimiter：StringRedisTemplate 执行 Lua，生成 UUID 作为 ZSet member；与秒杀 Lua 是不同脚本。
- rate-limit.lua：使用 Redis TIME 取得毫秒时间，删除 score <= now-window 的记录；ZCARD 达到上限返回 0，否则 ZADD 并 PEXPIRE，返回 1。窗口定义为 (now-window, now]。
- WebExceptionAdvice：将限流异常分别映射为 HTTP 429、503；缺失用户身份为 401。原登录拦截器通常会先拒绝未登录请求。

key 示例：

| 维度 | key | 配额共享范围 |
|---|---|---|
| USER | rate:seckill:USER:42 | 用户 42，跨实例 |
| IP | rate:seckill:IP:192.0.2.1 | 相同来源 IP |
| GLOBAL | rate:seckill:GLOBAL:all | 该接口所有请求 |

ZSet score 为 Redis 服务端时间，member 为每次请求的 UUID，避免同毫秒请求互相覆盖。所有检查和更新在 Lua 中原子执行。拒绝请求不添加记录、不续期；过期记录在下一次检查时移除，空闲 key 通过 TTL 自动清理。Redis TIME 减少不同应用实例时钟不一致，但仍受 Redis 服务器时钟调整影响。

## 部署和边界

使用 Spring Boot 管理的 spring-boot-starter-aop 替代旧 AspectJ 1.5.4 依赖；保持 Java 8 目标。注解应放在经 Spring 代理调用的 Controller 公共方法上，同类内部调用不会自动经过 AOP。

IP 默认读取 request.getRemoteAddr，不直接相信客户端 X-Forwarded-For。经网关/反向代理部署时，需在可信边界剥离伪造转发头并配置服务器可信代理，否则所有用户可能共享代理 IP 额度。IP 限流也会影响同 NAT 下用户，不能代替登录用户限流。

本实现使用现有单机 Redis 和 spring.redis 配置。多个实例指向同一 Redis 可共享额度；Redis 重启/数据丢失会重置额度。ZSet 适合短窗口、小额度；大规模全局限流需评估 Redis 负载，不能把限流本身变成瓶颈。AOP 在登录拦截器之后，不保护此前的登录态 Redis 查询；未标注接口也不会被限制。

Redis 故障采取拒绝策略，但请求仍可能在登录拦截器等此前依赖处失败。没有实现完整系统降级。限流不是库存正确性保障，库存和一人一单仍由秒杀 Lua、锁、数据库条件更新和唯一索引负责。

## 测试

```sh
mvn -Dtest=RateLimitTest,ShopLocalCacheTest,VoucherOrderServiceTest,MessageReliabilityTest test
python3 scripts/verify_rate_limit.py
```

较新 JDK 本机验证使用 JDK 17，并临时传入 -Dlombok.version=1.18.30；源码目标仍为 Java 8。

单元测试使用真实 AspectJProxyFactory 和 MVC 异常处理，检查三个维度的 key、未登录、忽略伪造 IP 头、拒绝与故障传播及 HTTP 状态。脚本启动独立 Redis Unix socket 实例，不修改现有数据，验证并发额度、身份隔离、旧记录清理、拒绝不计数/不续期和过期恢复。

部署后实际联调：登录取得 token，10 秒内连续请求秒杀 4 次，前三次进入业务（业务结果取决于资格），第四次应为 429；更换用户有独立额度，等待窗口过去后再次允许。跨两个应用进程及完整 MySQL/RocketMQ 端到端测试尚未执行，也没有吞吐压测数据。

本次验证：22 项相关单元测试通过（其中 8 项限流测试）；真实 Redis 滑动窗口脚本 6 类验证通过。
