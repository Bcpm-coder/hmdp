package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author YJn
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private com.hmdp.utils.ShopLocalCache shopLocalCache;

    @Override
    public Result queryById(Long id) {
        Shop shop = shopLocalCache.get(id, () -> cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES));
        if(shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop)  ;
    }
    // 缓存击穿(互斥锁)
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //redis查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if("".equals(shopJson)){
            //命中k：“”数据直接返回
            return null;
        }
        if (StrUtil.isNotBlank(shopJson)) {
            //redis有 直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 实现缓存重建
        Shop shop = null;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            if (!tryLock(lockKey)) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 获取互斥锁成功：第一个发现缓存不失效的线程要查sql更新redis
            shop = getById(id);
            Thread.sleep(200);
            //sql不存在报错
            if(shop == null){
                // null写入redis 应对缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //redis缓存一下
            stringRedisTemplate.opsForValue().set(key,
                    JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL,
                    TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    // 缓存击穿(逻辑过期)
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //redis查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            //redis无 直接返回（热点数据已预加载redis）
            return null;
        }
        // 反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        // 逻辑未过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return shop;
        }
        // 逻辑过期

        // 获取mutex
        String lockKey = LOCK_SHOP_KEY + id;
        // 拿到（第一个线程拿到）开启新线程执行sql查询更新redis
        if (tryLock(lockKey)) {
            // 新线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 未拿到锁
        return shop;
    }
    // 缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //redis查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if("".equals(shopJson)){
            //命中k：“”数据直接返回
            return null;
        }
        if (StrUtil.isNotBlank(shopJson)) {
            //redis有 直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //缓存穿透问题：客户端访问redis未命中访问数据库不存在的数据，大量访问绕过redis频繁访问数据库造成数据库负载严重 请求压力
        //被动解决方案
        //1（采用） 数据库不存在时在redis中新增k：“”数据 定时清除 简单方便 缺点：数据短期不一致 sql插入该数据时访问的还是redis的null 可以插入后实时更新redis解决
        //2 布隆过滤 hashsql数据为二进制01数据，实现复杂，小概率仍有缓存穿透
        //主动解决方案
        //1 增强id复杂度 做校验 2 用户参数限流
        //sql查询
        Shop shop = getById(id);
        //sql不存在报错
        if(shop == null){
            // null写入redis 应对缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //redis缓存一下
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES);
        return shop;
    }
    @Override
    @Transactional // 确保事务原子性
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id为空");
        }
        // 更新数据库
        if (!updateById(shop)) {
            return Result.fail("店铺不存在或更新失败");
        }
        // 数据库提交成功后删除两层缓存，并通知其他实例失效。
        shopLocalCache.invalidateAfterCommit(id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        if (x == null || y == null) {
            // 传统分页查询（无位置）
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * DEFAULT_PAGE_SIZE;
        int end = current * DEFAULT_PAGE_SIZE;

        // 3.使用GEOSEARCH查询附近商户
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),  // 中心点
                        new Distance(5000),                 // 半径5公里
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance()              // 包含距离
                                .limit(end)                     // 获取前end个
                );

        // 4.解析结果，处理分页
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        // 截取当前页数据
        List<Long> ids = new ArrayList<>();
        Map<String, Distance> distanceMap = new HashMap<>();

        content.stream().skip(from).forEach(result -> {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, result.getDistance());
        });

        // 5.根据ID查询完整商户信息
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")") // 保持距离顺序
                .list();

        // 6.设置距离信息
        shops.forEach(shop ->
                shop.setDistance(distanceMap.get(shop.getId().toString()).getValue()));
        log.info(shops.toString());
        return Result.ok(shops);
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private  void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(expireSeconds), shop);

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
