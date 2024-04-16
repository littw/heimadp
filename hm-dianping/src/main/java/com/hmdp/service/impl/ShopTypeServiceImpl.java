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

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result listShopType() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        //先查询redis中是否有缓存有店铺种类的列表
        String shopType = stringRedisTemplate.opsForValue().get(key);
        //缓存中存在，直接返回
        if (StrUtil.isNotBlank(shopType)){
            List<ShopType> shopTypeList = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //不存在，从数据库中进行查询
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList.isEmpty()){
            return Result.fail("店铺信息不存在");
        }
        //存在，将数据缓存到redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);
    }
}
