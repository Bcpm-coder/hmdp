package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author YJn
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOPTYPE_KEY);
        List<ShopType> list = JSONUtil.toList(shopTypeJson, ShopType.class);
        if(StrUtil.isNotBlank(shopTypeJson)){
            return Result.ok(list);
        }
        list = query().orderByAsc("sort").list();
        // sql数据写入redis缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOPTYPE_KEY, JSONUtil.toJsonStr(list));
        return Result.ok(list);
    }
}
