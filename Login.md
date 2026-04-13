# Login Logic

## 1. 先说结论

1. 这个项目的登录校验主链路不是 `Filter`，而是 Spring MVC 的 `HandlerInterceptor`。

2. 实际参与登录链路的核心类有：
- [MvcConfig.java](/D:/project/java/myprj/src/main/java/com/hmdp/config/MvcConfig.java)
- [LoginInterceptor.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/LoginInterceptor.java)
- [RefreshTokenInterceptor.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java)
- [UserHolder.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/UserHolder.java)
- [UserController.java](/D:/project/java/myprj/src/main/java/com/hmdp/controller/UserController.java)
- [UserServiceImpl.java](/D:/project/java/myprj/src/main/java/com/hmdp/service/impl/UserServiceImpl.java)
- [RedisConstants.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/RedisConstants.java)
- [WebExceptionAdvice.java](/D:/project/java/myprj/src/main/java/com/hmdp/config/WebExceptionAdvice.java)

3. 这套登录方案的本质是：
- 登录成功后，服务端生成一个 `token`
- 把用户简要信息存进 Redis，key 是 `login:token:{token}`
- 前端把 `token` 保存到 `sessionStorage`
- 之后每次请求都把 `token` 放到请求头 `authorization`
- 后端拦截器先从 Redis 查这个 token 对应的用户
- 查到了就放进 `ThreadLocal`
- 需要登录的接口再从 `ThreadLocal` 判断是否已登录
- 请求结束后清理 `ThreadLocal`

## 2. 这不是 Filter，是 Interceptor

1. 入口配置在 [MvcConfig.java](/D:/project/java/myprj/src/main/java/com/hmdp/config/MvcConfig.java)：

```java
registry.addInterceptor(new LoginInterceptor())
        .excludePathPatterns(...)
        .order(1);

registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
        .addPathPatterns("/**")
        .order(0);
```

2. 这里注册的是 `HandlerInterceptor`，不是 Servlet `Filter`。

3. 两个拦截器的执行顺序：
- `RefreshTokenInterceptor`：`order(0)`，先执行
- `LoginInterceptor`：`order(1)`，后执行

4. 这个顺序非常关键：
- 先尝试从请求头里解析 token，查 Redis，写入 `ThreadLocal`
- 再判断当前请求是否需要登录、用户是否已经登录

## 3. 哪些接口不需要登录

`LoginInterceptor` 在 [MvcConfig.java](/D:/project/java/myprj/src/main/java/com/hmdp/config/MvcConfig.java) 里排除了这些路径：

- `/shop/**`
- `/voucher/**`
- `/voucher/seckill`
- `/shop-type/**`
- `/upload/**`
- `/blog/hot`
- `/user/code`
- `/user/login`

也就是说，上面这些路径即使没有登录，也能访问。

其余没有排除的接口，默认都需要登录。

例如：
- `/user/me`
- `/user/sign`
- `/blog` 的部分写操作
- `/follow/**`

## 4. 登录接口是怎么走的

### 4.1 发送验证码

入口在 [UserController.java](/D:/project/java/myprj/src/main/java/com/hmdp/controller/UserController.java)：

```java
@PostMapping("code")
public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
    return userService.sendCode(phone, session);
}
```

真正逻辑在 [UserServiceImpl.java](/D:/project/java/myprj/src/main/java/com/hmdp/service/impl/UserServiceImpl.java) 的 `sendCode()`：

1. 校验手机号格式
2. 生成 6 位验证码
3. 存到 Redis

Redis key/value：
- key: `login:code:{phone}`
- value: 验证码
- TTL: 30 分钟

常量定义在 [RedisConstants.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/RedisConstants.java)：

```java
public static final String LOGIN_CODE_KEY = "login:code:";
public static final Long LOGIN_CODE_TTL = 30L;
```

### 4.2 登录

入口在 [UserController.java](/D:/project/java/myprj/src/main/java/com/hmdp/controller/UserController.java)：

```java
@PostMapping("/login")
public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
    return userService.login(loginForm, session);
}
```

真正逻辑在 [UserServiceImpl.java](/D:/project/java/myprj/src/main/java/com/hmdp/service/impl/UserServiceImpl.java) 的 `login()`：

1. 校验手机号格式
2. 去 Redis 取验证码 `login:code:{phone}`
3. 比较前端传来的 `code`
4. 根据手机号查用户表 `tb_user`
5. 如果用户不存在，就自动注册一个新用户
6. 生成随机 `token`
7. 把用户精简信息转成 `Hash`
8. 写入 Redis
9. 返回 token 给前端

## 5. token 保存在哪里

### 5.1 服务端保存位置

在 [UserServiceImpl.java](/D:/project/java/myprj/src/main/java/com/hmdp/service/impl/UserServiceImpl.java)：

```java
String token = UUID.randomUUID().toString(true);
UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
        CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
String tokenKey = LOGIN_USER_KEY + token;
stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
return Result.ok(token);
```

保存方式是 Redis Hash，不是 Session。

Redis key 格式：
- `login:token:{token}`

Hash 里的字段来自 `UserDTO`：
- `id`
- `nickName`
- `icon`

这个 DTO 定义在 [UserDTO.java](/D:/project/java/myprj/src/main/java/com/hmdp/dto/UserDTO.java)。

### 5.2 前端保存位置

前端登录页在：
[login.html](</D:/develop/nginx/nginx-1.18.0-redis/html/hmdp/login.html>)

登录成功后会执行：

```javascript
sessionStorage.setItem("token", data);
```

所以前端是把 token 存在浏览器的 `sessionStorage`，不是 cookie。

## 6. 后续请求怎么带 token

前端公共请求配置在：
[common.js](</D:/develop/nginx/nginx-1.18.0-redis/html/hmdp/js/common.js>)

里面先从 `sessionStorage` 取 token：

```javascript
let token = sessionStorage.getItem("token");
```

然后通过 axios 请求拦截器放进请求头：

```javascript
axios.interceptors.request.use(
  config => {
    if(token) config.headers['authorization'] = token
    return config
  }
)
```

所以后端读取 token 的位置就是请求头：
- header 名：`authorization`

## 7. token 刷新是怎么做到的

核心类是 [RefreshTokenInterceptor.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java)。

它的 `preHandle()` 流程是：

1. 从请求头拿 token

```java
String token = request.getHeader("authorization");
```

2. 如果 token 为空，直接放行，不报错

```java
if (StrUtil.isBlank(token)) {
    return true;
}
```

3. 用 token 去 Redis 查用户

```java
String key  = LOGIN_USER_KEY + token;
Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
```

4. 如果 Redis 里没有这个 token，也直接放行

```java
if (userMap.isEmpty()) {
    return true;
}
```

5. 如果查到了用户，就转成 `UserDTO`

```java
UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
```

6. 把用户保存到 `ThreadLocal`

```java
UserHolder.saveUser(userDTO);
```

7. 刷新 Redis 中这个 token 的 TTL

```java
stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
```

这就是“token 刷新”的本质：
- 用户只要持续访问系统
- 每次请求都会把 Redis 中 `login:token:{token}` 的过期时间重新续上
- 所以登录状态会滑动续期

这不是重新签发一个新 token，而是延长旧 token 在 Redis 里的存活时间。

## 8. token 是什么时候放到 ThreadLocal 的

放入时机：
- 每次请求进入 `RefreshTokenInterceptor.preHandle()` 时
- 并且请求头里有 token
- Redis 里也能查到该 token 对应用户

对应代码在 [RefreshTokenInterceptor.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java)：

```java
UserHolder.saveUser(userDTO);
```

`UserHolder` 本质就是一个静态 `ThreadLocal<UserDTO>`，定义在 [UserHolder.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/UserHolder.java)：

```java
private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();
```

它提供了三个方法：
- `saveUser(user)`
- `getUser()`
- `removeUser()`

## 9. 登录校验是怎么做的

真正拦截“未登录不能访问”的，不是 `RefreshTokenInterceptor`，而是 [LoginInterceptor.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/LoginInterceptor.java)。

它的逻辑非常简单：

```java
if (UserHolder.getUser() == null) {
    response.setStatus(401);
    return false;
}
return true;
```

意思是：

1. 先看 `ThreadLocal` 里有没有用户
2. 没有就返回 `401`
3. 有就放行

所以它依赖前一个拦截器先把用户放进 `ThreadLocal`。

## 10. ThreadLocal 是什么时候清理的

清理动作在 [RefreshTokenInterceptor.java](/D:/project/java/myprj/src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java) 的 `afterCompletion()`：

```java
UserHolder.removeUser();
```

调用时机：
- 整个请求处理完成之后

这么做是必须的，否则 Tomcat 线程复用时会把上一个请求的用户残留到下一个请求里。

## 11. 一个需要登录的接口是怎么取当前用户的

例如 [UserController.java](/D:/project/java/myprj/src/main/java/com/hmdp/controller/UserController.java) 的 `/user/me`：

```java
@GetMapping("/me")
public Result me(){
    UserDTO user = UserHolder.getUser();
    return Result.ok(user);
}
```

它根本不需要自己解析 token，因为在进入 Controller 之前，拦截器已经把用户放进 `ThreadLocal` 了。

再比如 [UserServiceImpl.java](/D:/project/java/myprj/src/main/java/com/hmdp/service/impl/UserServiceImpl.java) 的 `sign()` 和 `signCount()`，也是直接：

```java
Long userId = UserHolder.getUser().getId();
```

## 12. 整体请求时序

### 12.1 登录成功时

1. 前端调用 `/user/login`
2. 后端校验手机号和验证码
3. 查用户，不存在就创建
4. 生成 token
5. Redis 保存 `login:token:{token}` -> `UserDTO`
6. 后端把 token 返回给前端
7. 前端把 token 保存到 `sessionStorage`

### 12.2 登录后的普通请求

1. 前端从 `sessionStorage` 读 token
2. axios 把 token 放到请求头 `authorization`
3. `RefreshTokenInterceptor.preHandle()` 先执行
4. 从 Redis 读取 `login:token:{token}`
5. 查到用户就放入 `ThreadLocal`
6. 同时刷新 Redis TTL
7. `LoginInterceptor.preHandle()` 再执行
8. 它检查 `ThreadLocal` 中是否有用户
9. 有用户则放行，没有则返回 401
10. Controller / Service 通过 `UserHolder.getUser()` 获取当前登录用户
11. 请求完成后，`afterCompletion()` 清理 `ThreadLocal`

## 13. WebExceptionAdvice 是干什么的

全局异常处理在 [WebExceptionAdvice.java](/D:/project/java/myprj/src/main/java/com/hmdp/config/WebExceptionAdvice.java)：

```java
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
```

它和登录不是同一层职责。

它的作用是：
- Controller 或 Service 里如果抛出 `RuntimeException`
- 不让异常直接把页面打崩
- 而是统一返回：
`Result.fail("服务器异常")`

所以：
- `MvcConfig`：负责注册拦截器，属于请求入口控制
- `RefreshTokenInterceptor`：负责解析 token、查 Redis、写 ThreadLocal、刷新 TTL
- `LoginInterceptor`：负责判断是否已登录
- `WebExceptionAdvice`：负责统一处理运行时异常

## 14. 这个项目登录链路里几个容易混淆的点

1. 不是用 `HttpSession` 做登录态
- 虽然 Controller 方法里保留了 `HttpSession session` 参数
- 但当前真正的登录态不是存在 Session
- 而是存在 Redis + token

2. 不是 JWT
- token 只是随机字符串
- 用户信息不在 token 本身里
- 用户信息在 Redis 的 `login:token:{token}` 里

3. `RefreshTokenInterceptor` 不负责拦截未登录
- 它只负责“尝试解析登录用户”
- 真正拦截未登录的是 `LoginInterceptor`

4. ThreadLocal 只在单次请求线程内有效
- 不能跨请求保存
- 所以每次请求都要重新从 Redis 查 token 并写入

5. token 刷新不是重新生成 token
- 只是重新设置 Redis 过期时间

## 15. 当前代码里还存在的实际问题

1. `/user/logout` 还没实现

在 [UserController.java](/D:/project/java/myprj/src/main/java/com/hmdp/controller/UserController.java)：

```java
@PostMapping("/logout")
public Result logout(){
    return Result.fail("功能未完成");
}
```

正常应该至少做两件事：
- 删除 Redis 中 `login:token:{token}`
- 前端清理 `sessionStorage` 的 token

2. 前端 `common.js` 里的 token 是页面加载时读一次

```javascript
let token = sessionStorage.getItem("token");
```

这意味着：
- 如果页面已经加载完成后，token 发生变化
- 这个变量不会自动重新读取

但当前登录页登录成功后会直接跳转 `index.html`
所以新页面重新加载时会重新读 `sessionStorage`
在现有流程里基本够用。

3. `LOGIN_USER_TTL = 36000L`，配合 `TimeUnit.MINUTES`

这意味着 token 有效期是：
- 36000 分钟
- 也就是 600 小时
- 大约 25 天

这是比较长的滑动过期时间。

## 16. 一句话记忆版本

1. 登录成功时：生成 token，用户信息存 Redis，token 回前端。

2. 后续请求时：前端把 token 放请求头，后端先查 Redis，再把用户写进 `ThreadLocal`，同时刷新 TTL。

3. 需要登录的接口：再由 `LoginInterceptor` 检查 `ThreadLocal` 里有没有用户。

4. Controller / Service 取当前用户：统一用 `UserHolder.getUser()`。
