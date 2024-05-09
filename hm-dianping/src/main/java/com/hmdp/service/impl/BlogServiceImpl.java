package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询单个blog
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 根据博客的id进行查询
        Blog blog = getById(id);
        if (blog==null){
            return Result.fail("笔记不存在!");
        }
        //设置用户的昵称、图标
        queryBlogUser(blog);
        //设置用户是否对本篇博文点赞
        isBlogLike(blog);
        return Result.ok(blog);
    }

    /**
     * 按照点赞数量分页查询笔记
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询 按照点赞数量由高到低进行排序
        Page<Blog> page = query()
                        .orderByDesc("liked")
                        .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户，把用户的昵称、图标icon以及是否点赞都查询出来放到blog当中
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLike(blog);
        });
        return Result.ok(records);
    }

    /**
     * 在用户在前端对笔记进行点赞的时候限制单个用户只能给同一篇笔记点一次赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key=RedisConstants.BLOG_LIKED_KEY+id;
        //2.先查询笔记是否已经被当前用户点赞
        boolean isLiked = stringRedisTemplate.opsForSet().isMember(key,userId.toString());
        //3.如果用户已经点过赞
        if (BooleanUtil.isFalse(isLiked)) {
            //4.如果没有点赞，可以点赞
            //4.1将博客的点赞数量+1
            boolean success = update().setSql("liked=liked+1").eq("id", id).update();
            //4.2将用户添加到redis的点赞缓存当中
            if (success) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        }else {
            //3.1在数据库，那么将blog的点赞数量-1
            boolean success = update().setSql("liked=liked-1").eq("id", id).update();
            if (success){
                //3.2将用户从redis当中剔除
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        //4.如果已经点赞，那么取消点赞
        return Result.ok();
    }

    //给笔记添加用户昵称、icon等信息
    public void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    //给笔记添加是否被当前用户点赞的信息
    public void isBlogLike(Blog blog){
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key=RedisConstants.BLOG_LIKED_KEY+blog.getId();
        //2.先查询笔记是否已经被当前用户点赞
        boolean isLiked = stringRedisTemplate.opsForSet().isMember(key,userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isLiked));
    }
}
