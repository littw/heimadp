package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.index.PathBasedRedisIndexDefinition;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@Slf4j
public class GenerateTokenTest {

    @Resource
    private UserServiceImpl userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Test
    public void testMultiLogin() throws IOException {
       /* //从数据库中获取到前1000条数据，last()函数实在查询语句的末尾拼接上限制查询前一千条的条件
        List<User> users = userService.lambdaQuery().last("limit 1000").list();

        //将1000个user生成token之后重新写入到redis数据库中
        for (User user : users) {
            //5.用户存在将用户保存到redis
            UserDTO userDTO= BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
            //5.1随机生成token作为在redis中存储的key以及登录的令牌
            String token = UUID.randomUUID().toString();
            //5.2 将user对象按照hash进行存储
            stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,userMap);
            //5.3设置token有效期
            stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        }
*/
        //获取redis数据库中所有的用户登录token
        Set<String> keys = stringRedisTemplate.keys(RedisConstants.LOGIN_USER_KEY + "*");
        //将这些token全部写入到文件当中
        @Cleanup
        FileWriter fileWriter = new FileWriter(System.getProperty("user.dir")+"\\token.txt");
        @Cleanup
        BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
        assert keys!=null;
        for (String key : keys) {
            String token = key.substring(RedisConstants.LOGIN_USER_KEY.length());
            String text=token+"\n";
            bufferedWriter.write(text);
        }
        System.out.println("finished!");
    }
}
