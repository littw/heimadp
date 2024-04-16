package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

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

    /**
     * 通过id来查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        //先从redis当中查询店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        Shop shop = getById(id);
        if (shop==null){
            return Result.fail("店铺不存在");
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        return Result.ok(shop);

       /* Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(key);
        //redis当中存在，直接返回
        if (!shopMap.isEmpty()){
            return Result.ok(BeanUtil.toBean(shopMap, Shop.class,
                    CopyOptions.create()
                            .setIgnoreProperties("createTime")
                            .setIgnoreProperties("updateTime")));
        }
        //redis中不存在，从sql数据库中进行查询
        Shop shop = getById(id);
        //sql中不存在，返回false
        if (shop==null){
            return Result.fail("店铺不存在");
        }
        //sql中存在，将数据缓存到redis中
        stringRedisTemplate.opsForHash().putAll(key,BeanUtil.beanToMap(shop, new HashMap<>(),
                CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> {
                            if (fieldValue == null) {
                                fieldValue = "0";
                            } else {
                                fieldValue = fieldValue.toString();
                            }
                            return fieldValue;
                        }
                )));
        //返回数据*/
//        return Result.ok(shop);
    }


}
