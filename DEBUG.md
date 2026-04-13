# DEBUG Record

# 2026-04-12

## （1）智能客服接入

1. 修改文件：`pom.xml`  
修改原因：当前项目原本是 Spring Boot 2.3 + Java 8，无法直接接入 Spring AI。  
修改内容：升级到 Java 17、Spring Boot 3.4.4，替换为 `mybatis-plus-spring-boot3-starter`，更新 MySQL 驱动，加入 `spring-ai-starter-model-openai`，并保留原有 Web/Redis/AOP 能力。

2. 修改文件：`src/main/resources/application.yaml`  
修改原因：需要补齐 DashScope 兼容 OpenAI 模式的模型配置，同时保证字符集和数据库驱动与 Java 17 兼容。  
修改内容：将驱动改为 `com.mysql.cj.jdbc.Driver`，新增 `spring.ai.openai.api-key`、`spring.ai.openai.base-url`、`spring.ai.openai.chat.options.model=qwen3-max`，API Key 改为读取环境变量 `DASHSCOPE_API_KEY`。

3. 修改文件：`src/main/java/com/hmdp/controller/AiChatController.java`  
修改原因：需要新增一个可供前端调用的智能客服流式接口。  
修改内容：新增 `/ai/chat/stream` SSE 接口，使用 Spring AI `ChatClient` 调用模型，并按流式 `chunk` 事件把文本逐段返回给前端。

4. 修改文件：`src/main/java/com/hmdp/dto/AiChatRequest.java`  
修改原因：为智能客服接口补充请求体对象。  
修改内容：新增 `message` 字段，用于接收前端用户输入。

5. 修改文件：`src/main/java/com/hmdp/config/MvcConfig.java`  
修改原因：智能客服页面需要在未登录状态下也能访问接口，同时升级到 Spring Boot 3 后要使用 `jakarta` 包。  
修改内容：把 `javax.annotation.Resource` 改为 `jakarta.annotation.Resource`，并将 `/ai/**` 加入登录拦截器白名单。

6. 修改文件：`src/main/java/com/hmdp/utils/LoginInterceptor.java`、`src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java`、`src/main/java/com/hmdp/service/IUserService.java`、`src/main/java/com/hmdp/controller/UserController.java`、`src/main/java/com/hmdp/controller/ShopController.java`、`src/main/java/com/hmdp/controller/ShopTypeController.java`、`src/main/java/com/hmdp/controller/FollowController.java`、`src/main/java/com/hmdp/controller/BlogController.java`、`src/main/java/com/hmdp/controller/VoucherController.java`、`src/main/java/com/hmdp/controller/VoucherOrderController.java`、`src/main/java/com/hmdp/service/impl/UserServiceImpl.java`、`src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`、`src/main/java/com/hmdp/service/impl/FollowServiceImpl.java`、`src/main/java/com/hmdp/service/impl/ShopServiceImpl.java`、`src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java`、`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`、`src/test/java/com/hmdp/HmDianPingApplicationTests.java`、`src/test/java/com/hmdp/GenerateTokenTest.java`、`src/test/java/com/hmdp/RedissonTest.java`  
修改原因：Spring Boot 3 / Jakarta EE 9 迁移后，原有 `javax.*` 包名会直接编译失败。  
修改内容：统一替换为 `jakarta.*` 导入。

7. 修改文件：`src/main/java/com/hmdp/service/impl/FollowServiceImpl.java`、`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`  
修改原因：升级 MyBatis-Plus 版本后，`count()` 返回值类型变为 `long/Long`，原实现使用 `int/Integer` 编译失败。  
修改内容：将相关 `count` 变量统一改为 `long`。

8. 修改文件：`D:\develop\nginx\nginx-1.18.0-redis\conf\nginx.conf`  
修改原因：SSE 流式返回如果被 Nginx 缓冲，会导致前端只能一次性拿到完整结果，失去流式体验。  
修改内容：新增 `/api/ai/chat/stream` 专用代理规则，关闭 `proxy_buffering`，延长超时时间，并保留 `/api` 的原有反向代理。

9. 修改文件：`D:\develop\nginx\nginx-1.18.0-redis\html\hmdp\js\common.js`  
修改原因：需要在现有页面底部提供智能客服入口，且尽量减少对每个页面的侵入式修改。  
修改内容：通过通用脚本在所有非聊天页底部右下角插入“智能客服”按钮，点击后跳转聊天页。

10. 修改文件：`D:\develop\nginx\nginx-1.18.0-redis\html\hmdp\chat-ai.html`  
修改原因：需要新增独立的人机交互页面，并支持前端按流式方式实时展示模型回复。  
修改内容：新增客服聊天页面，使用 `fetch + ReadableStream + TextDecoder` 解析 SSE 流，按 `chunk` 增量渲染回复文本，并保留移动端适配。

## 调试与验证

1. 执行验证：`mvn -q -DskipTests compile`  
验证结果：编译通过，说明 Java 17、Spring Boot 3、Spring AI、Jakarta 迁移后的后端代码已经能正常构建。

2. 过程中修复的问题：  
- `FollowServiceImpl` 中 `count()` 结果由 `Integer` 改为 `long`。  
- `VoucherOrderServiceImpl` 中多个 `count()` 结果由 `int` 改为 `long`。  
- 所有 `javax.*` 相关导入迁移到 `jakarta.*` 后，编译错误消失。

3. 当前未完成的真实联调项：  
- 当前环境未配置 `DASHSCOPE_API_KEY`，因此本次未对真实 DashScope 模型请求做在线调用验证。  
- 前端页面、Nginx 代理和后端 SSE 接口已经就位；只要补上环境变量并重启后端与 Nginx，即可继续做真实对话联调。


## （2）Redis 连接问题排查

### 问题现象

1. 项目运行过程中经常提示 Redis 连接失败。  
2. 但同一台机器上，使用 RESP 工具可以正常连接 `192.168.233.128:6379`。  
3. 表面上看像是 Redis 服务不稳定，实际上不是。

### 排查过程

1. 先检查网络连通性：  
执行 `Test-NetConnection 192.168.233.128 -Port 6379`，结果 `TcpTestSucceeded=True`，说明当前机器到 Redis 端口是通的。

2. 再检查项目里的两套 Redis 客户端：  
- `StringRedisTemplate` / Lettuce 走 Spring Boot 自动配置。  
- `RedissonClient` 走自定义 `RedissonConfig`。  
这意味着如果两边没共用同一套配置，就会出现“一部分代码能连，一部分代码不能连”的情况。

3. 运行 Redis 相关测试验证真实行为：  
执行 `mvn -q -Dtest=HmDianPingApplicationTests#testSaveShop test` 后，报错显示：
`Unable to connect to localhost/127.0.0.1:6379`

4. 同时启动日志中 Redisson 已成功连接到：
`192.168.233.128:6379`

### 根因总结

1. 项目升级到 Spring Boot 3 后，Spring Data Redis 的配置前缀应为 `spring.data.redis`。  
2. 当时配置文件里仍然写的是旧前缀 `spring.redis`，导致 Lettuce 没读到远端 Redis 配置，自动回退到默认值 `localhost:6379`。  
3. `RedissonConfig` 又单独硬编码了 `192.168.233.128:6379`，所以形成了：
- `Redisson` 能连远端 Redis  
- `StringRedisTemplate` 却去连本机 `localhost`

### 修改文件与原因

1. 修改文件：`src/main/resources/application.yaml`  
修改原因：让 Spring Boot 3 下的 Lettuce 正确读取 Redis 配置。  
修改内容：把 Redis 配置从 `spring.redis` 改为 `spring.data.redis`。

2. 修改文件：`src/main/java/com/hmdp/config/RedissonConfig.java`  
修改原因：避免 Redisson 和 Spring Data Redis 使用两套不同的连接参数。  
修改内容：Redisson 改为读取 `RedisProperties`，不再继续依赖单独硬编码地址。

### 验证结果

1. 执行验证：  
`mvn -q -Dtest=HmDianPingApplicationTests#testSaveShop test`

2. 验证结果：  
测试通过，说明 `StringRedisTemplate` 已经不再回退到 `localhost:6379`，而是能正确连接远端 Redis。

### 最终结论

1. 这次“项目老是莫名其妙连不上 Redis”的直接原因，不是 Redis 服务异常，也不是 RESP 工具有问题。  
2. 真正原因是 Spring Boot 3 升级后 Redis 配置前缀变化，导致 Lettuce 读不到配置并回退到默认 `localhost:6379`。  
3. 修复后，项目里的 `Lettuce` 和 `Redisson` 已统一使用同一套 Redis 连接信息。



## （3）前端页面找不到 AI 客服入口

### 问题现象

1. 前端首页和其他静态页面中看不到“智能客服”入口。  
2. 用户无法进入客服聊天页。

### 根因总结

1. `html/hmdp/js/common.js` 中原本存在 JavaScript 语法错误。  
2. 具体表现为字符串缺少结束引号，导致浏览器加载该脚本时直接报错。  
3. 由于脚本在执行入口注入逻辑之前就已经中断，所以原本用于插入悬浮“智能客服”按钮的代码没有真正运行。  
4. 同时底部导航原先也没有明确的客服跳转入口，因此页面上完全看不到 AI 客服入口。

### 修改文件与原因

1. 修改文件：`D:\develop\nginx\nginx-1.18.0-redis\html\hmdp\js\common.js`  
修改原因：修复语法错误，让悬浮“智能客服”按钮能够真正注入页面。  
修改内容：重写脚本，修复 axios 响应拦截器中的坏字符串，并保留悬浮客服按钮逻辑。

2. 修改文件：`D:\develop\nginx\nginx-1.18.0-redis\html\hmdp\js\footer.js`  
修改原因：给用户一个稳定、明确可见的客服入口，不只依赖悬浮按钮。  
修改内容：在底部导航中加入“客服”按钮，并跳转到 `/chat-ai.html`。

### 结果

1. 页面右下角会显示悬浮“智能客服”按钮。  
2. 底部导航中新增“客服”入口。  
3. 用户可以稳定进入 AI 客服聊天页。

## （4）进入聊天页后点击发送没有任何反应

### 问题现象

1. 进入客服聊天页后，点击“发送”按钮没有任何反应。  
2. 浏览器开发者工具的网络面板中看不到 `/api/ai/chat/stream` 请求。  
3. 这说明前端根本没有把请求发出去，而不是后端无响应。

### 根因总结

1. `html/hmdp/chat-ai.html` 原文件中存在多处 HTML 和 JavaScript 语法损坏。  
2. 包括但不限于：
- HTML 标签未正确闭合。  
- 多个中文字符串缺少结束引号。  
- 内联脚本中存在坏掉的文本与拼接字符串。  
3. 这些语法错误导致聊天页脚本在加载阶段就报错中断。  
4. 因此 `sendBtn.addEventListener("click", sendMessage)` 虽然写在页面里，但实际上没有成功执行，点击发送自然不会触发任何请求。

### 修改文件与原因

1. 修改文件：`D:\develop\nginx\nginx-1.18.0-redis\html\hmdp\chat-ai.html`  
修改原因：修复页面结构和脚本执行链，保证点击发送后一定会真正发起请求。  
修改内容：整页重写为干净版本，修复所有损坏的 HTML/JS；并将发送逻辑改为独立的原生 `fetch`，不再依赖 `common.js` 中的 axios 初始化。

### 结果

1. 页面加载后脚本可以正常执行。  
2. “发送”按钮点击后会实际发起 `POST /api/ai/chat/stream` 请求。  
3. 浏览器网络面板中能够看到对应请求。  
4. 至少从前端链路上，发送动作已经不再丢失。




# 2026-04-11

## 商铺查询页面显示问题

## 1. 问题定位

1. 用户反馈现象：
`ShopTypeController.queryTypeList()` 在后端打印出来是 `List`，但前端拿到的响应里看到 `{"success":true,"data":[]}`。

2. 实际排查结果：
直接请求后端和 Nginx 转发地址后确认：
`/shop-type/list` 返回正常，不是空数组。

3. 真正返回空数组的接口：
`/shop/of/type`

4. 根因：
前端页面会携带 `x/y` 参数调用店铺列表接口，后端 [src/main/java/com/hmdp/service/impl/ShopServiceImpl.java](/D:/project/java/myprj/src/main/java/com/hmdp/service/impl/ShopServiceImpl.java) 在这种情况下优先走 Redis GEO 查询。
当前 Redis 中没有对应的 `shop:geo:{typeId}` 地理索引数据，所以接口返回空数组。

5. 前端结论：
Nginx 前端项目路径 `D:\develop\nginx\nginx-1.18.0-redis` 的请求链路本身没有发现问题，`/api/shop-type/list` 和 `/api/shop/of/type` 都能正常转发到 `8081`。

## 2. 本次修改文件

1. 修改文件：
[src/main/java/com/hmdp/service/impl/ShopServiceImpl.java](/D:/project/java/myprj/src/main/java/com/hmdp/service/impl/ShopServiceImpl.java)

修改内容：
- 给 `queryShopByType(Integer typeId, Integer current, Double x, Double y)` 增加了数据库兜底逻辑。
- 当 `x/y` 为空时，直接走数据库分页查询。
- 当 Redis GEO key 不存在时，回退数据库分页查询。
- 当 Redis GEO 查询结果为 `null` 时，回退数据库分页查询。
- 保留原来的 GEO 查询逻辑，用于 Redis 索引存在时按距离排序。
- 新增私有方法 `queryShopByTypeFromDb()`，统一处理数据库分页查询。

修改目的：
- 避免因为 Redis GEO 数据未预热，导致前端店铺列表页面直接显示空数组。

2. 修改文件：
[src/main/resources/application.yaml](/D:/project/java/myprj/src/main/resources/application.yaml)

修改内容：
- 新增：
`server.servlet.encoding.charset: UTF-8`
- 新增：
`server.servlet.encoding.enabled: true`
- 新增：
`server.servlet.encoding.force: true`
- 数据库连接串追加：
`useUnicode=true&characterEncoding=UTF-8`

修改目的：
- 修复接口响应和数据库读取过程中的中文乱码问题。

## 3. 调试验证结果

1. 已验证接口：
`http://127.0.0.1:8081/shop-type/list`

结果：
返回的是完整分类列表，不是空数组。

2. 已验证接口：
`http://127.0.0.1:8080/api/shop-type/list`

结果：
经过 Nginx 转发后，仍然返回完整分类列表，不是空数组。

3. 已验证接口：
`http://127.0.0.1:8080/api/shop/of/type?typeId=1&current=1&x=120.149993&y=30.334229`

结果：
在本次修改前返回：
`{"success":true,"data":[]}`

4. 已验证接口：
`http://127.0.0.1:8080/api/shop/of/type?typeId=1&current=1`

结果：
不带坐标时能从数据库查出正常店铺数据，说明数据库本身不是空的，问题在 Redis GEO 查询链路。

5. 编译验证：
已执行：
```bash
mvn -q -DskipTests compile
```

结果：
编译通过。

## 4. 当前结论

1. 这次“前端拿到空数组”的直接原因不是 `ShopTypeController.queryTypeList()`。

2. 真正的问题是店铺列表接口 `/shop/of/type` 在带经纬度参数时依赖 Redis GEO 数据，而当前 Redis 没有对应索引。

3. 这次代码调整后，即使 Redis GEO 没有预热，后端也会回退数据库查询，页面不会再因为 GEO 缺数据而直接空掉。

## 5. 后续继续调试时的追加规则

1. 后续每次修改都按新的序号继续往下追加，不要覆盖旧记录。

2. 每次追加至少记录以下内容：
- 修改文件
- 修改原因
- 修改内容
- 验证方式
- 验证结果

3. 如果后续执行了 Redis GEO 预热，例如运行测试里的 `loadShopData()`，也请单独追加一条记录，注明执行时间、执行方式和结果。
