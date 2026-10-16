package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
public class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1l, 10L);
    }
    @Test
    public void loadShopData(){
        List<Shop> list1 = shopService.query().eq("type_id", 2).list();
        String key1 = SHOP_GEO_KEY + "2";
        for (Shop shop : list1) {
            stringRedisTemplate.opsForGeo().add(key1, new Point(shop.getX(),shop.getY()), shop.getId().toString());
        }
    }
}
