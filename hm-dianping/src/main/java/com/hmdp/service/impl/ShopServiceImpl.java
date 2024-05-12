package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import net.sf.jsqlparser.statement.select.KSQLWindow;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    /**
     * 通过id来查询商铺信息,解决缓存穿透
     * @param id
     * @return
     */
    @Override
    public Shop queryById(Long id) {
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById,
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        return shop;
/*        String key= RedisConstants.CACHE_SHOP_KEY+id;
        //先从redis当中查询店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //如果店铺信息非空，就返回
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //判断命中的是否是空值：空值无法确定是否存在，在查询数据库之前进行空值的判断
        if ("".equals(shopJson)){
            return Result.fail("店铺不存在");
        }
        Shop shop = getById(id);
        if (shop==null){
            //缓存穿透：将null值存储在redis中并设置ttl
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);*/
    }

    /**
     * 添加互斥锁,通过id来查询商铺信息
     * @param id
     * @return
     */

    @Override
    public Shop queryByIdWithMutex(Long id) {
        Shop shop = cacheClient.queryWithMutex(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById,
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        return shop;
        /*
        //1.获取店铺在redis中的key
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        //2.从redis当中查询店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)){
            //2.1.如果redis中存在店铺信息，就返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //3.判断命中的是否是空值：空值无法确定是否存在，在查询数据库之前进行空值的判断
        if ("".equals(shopJson)){
            //redis中存储空值，返回空值，缓解缓存穿透问题
            return Result.fail("店铺不存在");
        }
        //4.防止缓存雪崩
        //4.1.获取锁
        String lock=RedisConstants.LOCK_SHOP_KEY+id; //每个店铺都会设置一个锁
        Shop shop=null;
        try {
            boolean isLock = tryLock(lock);
            //4.2如果当前进程没有获取到互斥锁，就进行休眠
            if (!isLock){
                Thread.sleep(50);
                return queryByIdWithMutex(id);
            }
            //4.2.进行数据库的查询
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            //4.3.redis的写入
            if (shop==null){
                //缓存穿透：将null值存储在redis中并设置ttl
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
        //4.4.释放锁
            unLock(lock);
        }
      //4.5返回数据
        return Result.ok(shop);
       */
    }

    /**
     * 利用逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    @Override
    public Shop queryByIdWithLogicExpire(Long id) {
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        return shop;
       /* //1.获取店铺在redis中的key
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        //2.从redis当中查询店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)){
            // 3.不存在，直接返回
            return Result.fail("店铺信息不存在");
        }
        //4.命中，需要现将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);//RedisData中存储的数据是Object类型，可以考虑使用泛型，但是好像还是取不出数据
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回
            return Result.ok(shop);
        }
        //5.2过期，缓存重建
        //6.缓存重建
        //6.1获得互斥锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2互斥锁获取成功,开启独立线程，重建缓存
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //解锁
                    unLock(lockKey);
                }
            });
        }
        //6.3返回商铺的过期信息
        return Result.ok(shop);*/
    }

    /**
     * 更新店铺数据
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //先查询数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装数据
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);
        //写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 添加互斥锁
     * @param key
     * @return
     */
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS); //setnx:在键不存在时进行设置并返回1，不存在不能进行修改
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 根据店铺的类别来查询店铺
     * @param typeId  店铺的种类
     * @param current 查询的起始页
     * @param x x坐标
     * @param y y坐标
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.如果不通过坐标进行排序，那么就直接返回
        if (x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page =query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //2.计算出分页的起始点
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis，按照距离进行排序、分页。结果是shopId,distance
        String key=RedisConstants.SHOP_GEO_KEY+typeId;
        //以x,y为圆心，5km为半径查询范围内的商店,返回的是店铺的id和店铺的距离
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(key, new Circle(x, y, 5000), RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));
        if (results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long> ids = new ArrayList<>();
        HashMap<String, Distance> map = new HashMap<>();
        //如果需要当前查询出来的数据的总条数已经要小于起点数，那么后面的数据也是查询不到的，所以直接进行返回
        if (list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        //4.截取从from到end的部分
        list.stream().skip(from).forEach(result->{
            //获取店铺id
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            //获取店铺的距离
            Distance distance = result.getDistance();
            map.put(shopId,distance);
        });
        //根据id进行店铺的批量查询，同时进行排序
        String idsStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();
        //将店铺的对应距离传到前端
        for (Shop shop : shops) {
            shop.setDistance(map.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
