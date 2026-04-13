package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
public class GenerateTokenTest {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void createUserAndToken() throws IOException {
        int count = 1000;

        // 1. 查询已有用户数量
        long userCount = userService.count();

        // 2. 如果用户不够，就补齐到1000个
        for (int i = 0; i < count; i++) {
            String phone = "13" + String.format("%09d", i);
            User user = userService.lambdaQuery().eq(User::getPhone, phone).one();
            if (user == null) {
                user = new User();
                user.setPhone(phone);
                user.setNickName("user_" + RandomUtil.randomString(10));
                userService.save(user);
            }
        }

        // 3. 查询前1000个用户
        List<User> userList = userService.lambdaQuery().last("limit " + count).list();

        // 4. 生成 token 并写入 Redis，同时写入文件
        BufferedWriter writer = new BufferedWriter(new FileWriter("tokens.txt"));

        for (User user : userList) {
            // 4.1 生成 token
            String token = UUID.randomUUID().toString(true);

            // 4.2 转成 UserDTO
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

            // 4.3 转成 Map<String, Object>
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);

            // 4.4 存入 Redis
            String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
            Map<String, String> stringMap = userMap.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue() == null ? "" : entry.getValue().toString()
                    ));

            stringRedisTemplate.opsForHash().putAll(tokenKey, stringMap);
            stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

            // 4.5 写入文件：手机号,token
            writer.write(user.getPhone() + "," + token);
            writer.newLine();
        }

        writer.close();
        System.out.println("生成完成，tokens.txt 已输出");
    }
}
