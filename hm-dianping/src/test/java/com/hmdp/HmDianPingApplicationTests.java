package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    //500个线程的线程池
    private ExecutorService es=Executors.newFixedThreadPool(500);

    @Test
    public void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        //创建订单号
        Runnable task=()->{
            for (int i = 0; i < 100; i++) {
                Long id = redisIdWorker.nextId("order");
                System.out.println("id="+id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end=System.currentTimeMillis();
        System.out.println("time="+(end-begin));
    }

    @Test
    public void loadShopData(){
        //1.查询所有的店铺信息
        List<Shop> list = shopService.list();
        //2.把店铺按照typeId进行分组,相同的typeId为一组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //获取店铺类型id
            Long type = entry.getKey();
            //存入redis中的key
            String key= RedisConstants.SHOP_GEO_KEY+type;
            //将这个type中的左右店铺的坐标以及店铺id存入到redis当中
            List<Shop> shops = entry.getValue();
            //使用GeoLocation批量写入到redis当中，提升效率
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>();
            for (Shop shop : shops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }



}
