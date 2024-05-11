package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserServiceImpl userService;
    //关注或者是取关用户
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key= RedisConstants.FOLLOW_KEY+userId;
        //2.关注
        //判断到底是关注还是取消关注操作
        if (isFollow){
            //如果还没有关注了当前用户，那么进行关注，否则，不更新数据库
            if (!isFollow(followUserId)) {
                Follow follow = new Follow();
                follow.setFollowUserId(followUserId);
                follow.setUserId(userId);
                boolean isSuccess = save(follow);
                //将关注的用户添加到redis当中
                if (isSuccess){
                    stringRedisTemplate.opsForSet().add(key,followUserId.toString());
                }
            }
        }else {
            //取关，删除关注信息
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            //移除redis中该用户关注的用户id
            if (isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    //查询是否关注
    @Override
    public Boolean isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断
        return count>0;
    }

    //查询码当前用户和id用户的共同关注用户并进行返回
    @Override
    public Result commonFollow(Long id) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取当前用户以及需要查询用的的redis查询key
        String key1=RedisConstants.FOLLOW_KEY+userId;
        String key2=RedisConstants.FOLLOW_KEY+id;
        //查询这两个set的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //没有交集那么就返回空
        if (intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
