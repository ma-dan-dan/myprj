# hm-dianping 代码阅读笔记（本仓库）

本文档基于 `d:\project\java\hm-dianping\` 目录下的源码整理，目标是用“按功能拆解 + 关键链路追踪”的方式，帮助快速理解黑马点评项目后端（Spring Boot + MyBatis-Plus + Redis）的整体结构与核心实现。

---

## 1. 项目定位与整体架构

这是一个典型的“点评/探店”类后端服务，核心能力集中在：

- 用户：验证码登录、Token 续期、签到统计（位图）
- 店铺：详情查询（缓存穿透/击穿处理）、按类型查询、按地理位置查询（Redis GEO）
- 博客：热门榜、点赞（ZSet）、关注推送（Feed 流：ZSet）
- 关注：关注/取关、共同关注（Set 交集）
- 优惠券秒杀：多种并发控制方案（本项目用 `MODE` 切换），包括 Lua 原子校验 + 异步下单（阻塞队列 / Redis Stream）

典型的分层结构：

- Controller：HTTP 接口层
- Service / ServiceImpl：业务逻辑层
- Mapper：MyBatis-Plus 数据访问层
- Entity / DTO：领域对象与接口返回/请求对象
- Utils：Redis、缓存、拦截器、分布式锁等基础设施能力
- Config：Spring、MyBatis、异常处理、Redisson 配置等

入口类：[HmDianPingApplication.java](src/main/java/com/hmdp/HmDianPingApplication.java)

---

## 2. 目录结构速览

后端主要代码位于：

- `src/main/java/com/hmdp/controller/`：接口
- `src/main/java/com/hmdp/service/`、`src/main/java/com/hmdp/service/impl/`：业务
- `src/main/java/com/hmdp/mapper/`：DAO
- `src/main/java/com/hmdp/entity/`：实体（对应数据库表）
- `src/main/java/com/hmdp/dto/`：接口 DTO（例如 `Result`、`UserDTO`、`LoginFormDTO`）
- `src/main/java/com/hmdp/utils/`：缓存、Redis Key、拦截器、锁、ID 生成器等
- `src/main/java/com/hmdp/config/`：MVC 拦截器链、MyBatis 分页、Redisson、全局异常处理
- `src/main/resources/`：
  - `application.yaml`：数据库、Redis 连接等配置
  - `db/hmdp.sql`：数据库初始化脚本
  - `seckill.lua`、`seckill_old_jvm.lua`、`unlock.lua`：Lua 脚本

---

## 3. 技术栈与依赖（pom.xml）

依赖核心点（[pom.xml](pom.xml)）：

- Spring Boot 2.3.12.RELEASE（Web、AOP、Test）
- MyBatis-Plus 3.4.3（分页插件见 [MybatisConfig.java](src/main/java/com/hmdp/config/MybatisConfig.java)）
- Redis：`spring-data-redis` + `lettuce`（显式指定版本）
- Redisson 3.13.6（分布式锁，配置见 [RedissonConfig.java](src/main/java/com/hmdp/config/RedissonConfig.java)）
- Hutool 5.7.17（Bean/JSON/随机数等工具）

注意：`pom.xml` 中 `java.version` 为 `1.8`，如果你本地使用 Java 17，需要在构建/IDE 里调整（例如将 `<java.version>` 更新为 `17`），否则可能出现编译级别不一致的问题。

---

## 4. 配置与运行关键点

配置文件：[application.yaml](src/main/resources/application.yaml)

- 服务端口：`8081`
- 数据源：MySQL（`jdbc:mysql://127.0.0.1:3306/redis`）
- Redis：主机、端口、密码、连接池
- Jackson：忽略空字段

数据库脚本：`src/main/resources/db/hmdp.sql`，包含 `tb_shop`、`tb_blog`、`tb_follow`、`tb_seckill_voucher` 等表结构和部分示例数据。

---

## 5. 登录鉴权与 Token 续期链路

### 5.1 验证码发送与登录

核心实现：[UserServiceImpl.java](src/main/java/com/hmdp/service/impl/UserServiceImpl.java)

1. `sendCode(phone)`：
   - 校验手机号格式（[RegexUtils.java](src/main/java/com/hmdp/utils/RegexUtils.java)）
   - 生成 6 位验证码
   - 写入 Redis：`login:code:{phone}`，TTL 2 分钟（见 [RedisConstants.java](src/main/java/com/hmdp/utils/RedisConstants.java)）

2. `login(loginForm)`：
   - 校验手机号 + 校验 Redis 中验证码
   - 若用户不存在则创建（`createUserWithPhone`）
   - 生成 token（UUID）
   - 把 `UserDTO` 转 Hash 存 Redis：`login:token:{token}`（Hash）
   - 设置 token TTL：`LOGIN_USER_TTL`（代码里用 `TimeUnit.MINUTES`）
   - 返回 token 给前端

### 5.2 拦截器链：解析 token、刷新 TTL、登录拦截

拦截器配置：[MvcConfig.java](src/main/java/com/hmdp/config/MvcConfig.java)

顺序（`order` 越小越先执行）：

1. `RefreshTokenInterceptor`（order 0，拦截全部 `/**`）
2. `LoginInterceptor`（order 1，拦截除白名单外的接口）

#### RefreshTokenInterceptor（token 解析与续期）

实现：[RefreshTokenInterceptor.java](src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java)

- 从请求头取 `authorization`
- 用 `login:token:{token}` 从 Redis 取 Hash 并映射为 `UserDTO`
- 保存到 `ThreadLocal`（[UserHolder.java](src/main/java/com/hmdp/utils/UserHolder.java)）
- 刷新 TTL
- 请求结束 `afterCompletion` 清理 ThreadLocal

#### LoginInterceptor（是否已登录）

实现：[LoginInterceptor.java](src/main/java/com/hmdp/utils/LoginInterceptor.java)

- 判断 `UserHolder.getUser()` 是否为空
- 空：返回 401
- 非空：放行

---

## 6. 店铺缓存：穿透/击穿/逻辑过期

缓存工具类：[CacheClient.java](src/main/java/com/hmdp/utils/CacheClient.java)

提供 3 套策略（在 [ShopServiceImpl.java](src/main/java/com/hmdp/service/impl/ShopServiceImpl.java) 中切换使用）：

1. **缓存穿透（Pass-Through）**
   - 先查 Redis
   - Redis miss：查 DB
   - DB 为 null：写入空字符串（短 TTL），避免同一不存在 key 的反复打 DB

2. **互斥锁缓存击穿（Mutex）**
   - Redis miss：抢互斥锁 `lock:shop:{id}`
   - 抢不到：sleep + 递归重试
   - 抢到：查 DB 并回写缓存

3. **逻辑过期（Logical Expire）**
   - Redis 值存 `RedisData{ data, expireTime }`（见 [RedisData.java](src/main/java/com/hmdp/utils/RedisData.java)）
   - 读到过期数据：先把“旧数据”返回给请求（保证可用性）
   - 后台线程池异步重建缓存（持有 `lock:shop:{id}`）

店铺查询入口：[ShopServiceImpl.queryById](src/main/java/com/hmdp/service/impl/ShopServiceImpl.java)

对应 Key（见 [RedisConstants.java](src/main/java/com/hmdp/utils/RedisConstants.java)）：

- 店铺缓存：`cache:shop:{id}`
- 空值 TTL：2 分钟（`CACHE_NULL_TTL`）
- 店铺缓存 TTL：30 分钟（`CACHE_SHOP_TTL`）
- 互斥锁 key 前缀：`lock:shop:`

---

## 7. 店铺 GEO：按距离排序 + 分页

入口：[ShopServiceImpl.queryShopByType](src/main/java/com/hmdp/service/impl/ShopServiceImpl.java)

- 当 `x/y` 为空：直接按 `type_id` 查数据库分页
- 当 `x/y` 存在：使用 Redis GEO 查询
  - key：`shop:geo:{typeId}`
  - 通过 `GEOSEARCH`（Spring Data Redis 的封装）按半径检索，并携带距离
  - 手动做分页：先取 `end` 条，再截断 `from~end`
  - 再按 shopId 批量查 DB，并用 `ORDER BY FIELD` 保持与 GEO 结果一致
  - 把距离写回 `Shop.distance`

测试中包含 GEO 数据加载逻辑：[HmDianPingApplicationTests.loadShopData](src/test/java/com/hmdp/HmDianPingApplicationTests.java)

---

## 8. 博客：热门/点赞/关注推送（Feed 流）

核心实现：[BlogServiceImpl.java](src/main/java/com/hmdp/service/impl/BlogServiceImpl.java)

### 8.1 热门博客

- 按 `liked` 倒序分页
- 逐条补充作者信息（`queryBlogUser`）
- 若已登录：补充“当前用户是否点赞”（`isBlogLiked`）

### 8.2 点赞（ZSet）

Key：`blog:liked:{blogId}`（见 [RedisConstants.java](src/main/java/com/hmdp/utils/RedisConstants.java)）

- 未点赞：
  - DB `liked = liked + 1`
  - Redis `ZADD key userId score=当前时间戳`
- 已点赞：
  - DB `liked = liked - 1`
  - Redis `ZREM key userId`

查询 top5 点赞用户：`ZRANGE key 0 4`，再按 `ORDER BY FIELD` 保序查用户信息。

### 8.3 关注推送（Feed：ZSet）

Key：`feed:{userId}`（见 [RedisConstants.java](src/main/java/com/hmdp/utils/RedisConstants.java)）

`saveBlog(blog)`：

- 保存博客后，查作者的所有粉丝（`tb_follow`）
- 给每个粉丝的收件箱 `ZADD feed:{fanId} blogId score=时间戳`

`queryBlogOfFollow(max, offset)`：

- `ZREVRANGEBYSCORE` + `LIMIT` 做滚动分页
- 返回 `ScrollResult{ list, minTime, offset }`

---

## 9. 关注：关注/取关/共同关注

核心实现：[FollowServiceImpl.java](src/main/java/com/hmdp/service/impl/FollowServiceImpl.java)

Redis Key：`follows:{userId}`（Set）

- 关注：写 DB（`tb_follow`）成功后 `SADD follows:{userId} followUserId`
- 取关：删 DB 成功后 `SREM follows:{userId} followUserId`
- 共同关注：`SINTER follows:{me} follows:{other}`，再查用户列表返回

---

## 10. 用户签到：Bitmap 与连续签到统计

实现：[UserServiceImpl.sign / signCount](src/main/java/com/hmdp/service/impl/UserServiceImpl.java)

- Key：`sign:{userId}:{yyyyMM}`（见 [RedisConstants.java](src/main/java/com/hmdp/utils/RedisConstants.java)）
- `sign()`：`SETBIT key (dayOfMonth-1) 1`
- `signCount()`：
  - `BITFIELD GET u{dayOfMonth} 0` 获取从月初到今天的 bit 段
  - 通过循环 `num & 1` + 右移统计“连续签到天数”（从今天向前数）

---

## 11. 秒杀下单：多模式并发控制与异步落库

核心实现：[VoucherOrderServiceImpl.java](src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java)

该类通过 `MODE` 常量切换实现方案（当前代码 `MODE = 2`）：

1. MODE 1：JVM `synchronized`（本地锁）+ 一人一单校验 + 扣库存（乐观条件 `stock > 0`）
2. MODE 2：自研 Redis 分布式锁 `SimpleRedisLock`（`SETNX` + Lua 解锁）+ 一人一单 + 扣库存
3. MODE 3：Redisson `RLock` 分布式锁 + 一人一单 + 扣库存
4. MODE 4：Lua 原子判断（库存 + 是否重复）+ JVM 阻塞队列异步创建订单
5. MODE 5：Lua 原子判断（库存 + 是否重复 + 写 Stream）+ Redis Stream 消费者异步创建订单

### 11.1 MODE 2：SimpleRedisLock（当前启用）

锁实现：[SimpleRedisLock.java](src/main/java/com/hmdp/utils/SimpleRedisLock.java)

- 加锁：`SETNX lock:{name} threadId EX timeout`
- 解锁：执行 Lua（[unlock.lua](src/main/resources/unlock.lua)）保证“仅持有者释放”

下单流程（简化）：

- 抢 `lock:order:{userId}`
- 查是否已有订单（`tb_voucher_order`）
- 扣库存（`UPDATE ... SET stock=stock-1 WHERE voucher_id=? AND stock>0`）
- 写订单（使用 [RedisIdWorker.java](src/main/java/com/hmdp/utils/RedisIdWorker.java) 生成分布式 ID）
- finally 释放锁

### 11.2 MODE 4/5：Lua 原子校验 + 异步落库

Lua：

- MODE 4：[seckill_old_jvm.lua](src/main/resources/seckill_old_jvm.lua)
  - `get seckill:stock:{voucherId}` 判断库存
  - `sismember seckill:order:{voucherId} {userId}` 判断一人一单
  - `incrby` 扣库存 + `sadd` 记录下单用户
  - 返回码：0 成功 / 1 库存不足 / 2 重复下单

- MODE 5：[seckill.lua](src/main/resources/seckill.lua)
  - 在 MODE 4 基础上追加 `XADD stream.orders ...` 把订单消息写入 Stream

异步线程初始化：

- `@PostConstruct init()`：按 MODE 提交单线程消费者（`Executors.newSingleThreadExecutor()`）
- MODE 4：阻塞队列 `orderTasks`（JVM 内存）
- MODE 5：`XREADGROUP` 消费 Stream，同时处理 `pending-list`

异步订单最终仍会进入 `createVoucherOrder4(voucherOrder)`：

- 用 Redisson 锁再次兜底一人一单（防御性）
- 查单 + 扣库存（DB）+ 保存订单

---

## 12. 统一异常处理与 AOP 记录

### 12.1 全局异常处理

[WebExceptionAdvice.java](src/main/java/com/hmdp/config/WebExceptionAdvice.java)

- 捕获 `RuntimeException`
- 记录日志并返回 `Result.fail("服务器异常")`

### 12.2 自定义注解 + AOP

- 注解：[Log.java](src/main/java/com/hmdp/annotation/Log.java)
- 切面：[LogAspect.java](src/main/java/com/hmdp/annotation/LogAspect.java)

`@Around("@annotation(Log)")` 获取注解内容并在方法执行前后输出信息（当前使用的是标准输出打印）。

示例：`UserServiceImpl.sayHello` 使用 `@Log("用户打招呼")`。

---

## 13. 测试与辅助脚本

测试类在 `src/test/java/com/hmdp/`，常见用途：

- [GenerateTokenTest.java](src/test/java/com/hmdp/GenerateTokenTest.java)：批量生成 token 并写入 `tokens.txt`
- [HmDianPingApplicationTests.java](src/test/java/com/hmdp/HmDianPingApplicationTests.java)：缓存预热、GEO 数据加载、ID 生成压测等
- [RedissonTest.java](src/test/java/com/hmdp/RedissonTest.java)：验证 Redisson 可重入锁行为

---

## 14. 快速启动建议（本地）

1. 导入 MySQL，执行 `src/main/resources/db/hmdp.sql`
2. 启动 Redis（并保证与配置一致）
3. 运行入口类 `com.hmdp.HmDianPingApplication`
4. 请求登录接口获取 token，并在后续请求头携带：
   - `authorization: {token}`

---

## 15. Redis Key 约定（集中清单）

见 [RedisConstants.java](src/main/java/com/hmdp/utils/RedisConstants.java)：

- 登录验证码：`login:code:{phone}`
- 登录 token：`login:token:{token}`
- 店铺缓存：`cache:shop:{id}`
- 店铺互斥锁：`lock:shop:{id}`
- 秒杀库存：`seckill:stock:{voucherId}`
- 博客点赞：`blog:liked:{blogId}`
- Feed 流：`feed:{userId}`
- GEO：`shop:geo:{typeId}`
- 签到：`sign:{userId}:{yyyyMM}`

另外在关注模块中使用：

- 关注集合：`follows:{userId}`

