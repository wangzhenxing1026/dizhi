# 百度地图地址查询与格式化工具

基于 Java 8 + Maven 的控制台程序：读取 `dizhi.txt` 中的地址，调用百度地图 PC 端搜索接口获取每个地址的
中心点坐标与楼栋多边形边界，转换为 BD-09 经纬度后输出 `output.txt`，并生成可在百度地图上人工复核的
`map.html`。需求详情见 `src/main/resources/xuqiu.md`，开发条件核对清单见 `开发条件清单.md`。

## 功能特性

- **多楼栋拆分**：`佳兆业中心A、B座`、`统建楼办公楼1栋、2栋`、`韵动家园AB栋`、`中海华庭华景峰1/2座`、
  `前海天境花园1-4栋` 等写法自动拆分为独立子地址，分别查询后用**凸包**合并多边形，中心点取合并区域质心
- **多结果择优**：对返回的所有候选 POI 按需求第五章评分（名称 40 / 地址关键词 25 / 楼栋单元 20 / 类型 10 / 多边形 5），
  含并列决胜与低置信度阈值（默认 40）
- **坐标精确转换**：BD-09 墨卡托 → BD-09 经纬度采用百度官方 MC2LL 分段多项式算法（需求文档中的简化公式
  对百度墨卡托纬度偏差约 0.14°（≈15 公里），仅保留作对照，可用 `coord.algorithm=simple` 切换）
- **鉴权自动刷新**：检测到 `result.error != 0` / `need_recaptcha` / 连续空结果时，先采用响应携带的
  `anti_auth`，仍失败则通过 **CDP 自动调用 Edge 浏览器**抓取新的 auth/seckey/cookie 并回写配置
- **离线复核**：所有响应先落盘 `cache/` 再解析；`--offline` 模式不联网、直接读缓存重放全链路，
  调参（阈值/拆分规则/坐标算法）后无需重新请求百度
- **复核输出**：`output.txt`（制表符分隔）、`map.html`（按状态绿/黄着色、过滤控件、点击查看追溯信息）、
  `failed.txt`（失败清单）、`review.txt`（低置信度清单）

## 环境要求

- JDK 8（不得使用 Java 11+ API）
- Maven 3.6+（本机：`D:\java\maven\apache-maven-3.6.3`，未入 PATH 时需先 `set JAVA_HOME=...`）
- 依赖仅 `jackson-databind`（本地仓库已缓存，可离线编译）

## 快速开始

1. **配置**：把 `src/main/resources/config.properties` 复制到项目根目录（运行时优先读取），
   填入最新的 `baidu.auth` / `baidu.seckey` / `baidu.cookie`（浏览器 F12 → Network → 筛选 `map.baidu.com`
   → 任一 `qt=s` 请求的 URL 参数与 Cookie 请求头），并把 `html.ak` 换成自己在
   [百度地图开放平台](https://lbsyun.baidu.com) 申请的**浏览器端 AK**。

2. **编译**：
   ```bash
   mvn compile
   ```

3. **自测**（离线，不联网）：
   ```bash
   java -cp target/classes com.fh.Main --selftest
   ```

4. **运行**（需先把 `dizhi.txt` 放到工作目录，或用 `input.path` 指定路径）：
   ```bash
   # 在线全量
   java -cp "target/classes;<jackson三个jar的路径>" com.fh.Main
   # 离线重放（读 cache/ 重新生成 output.txt 与 map.html）
   java -cp "target/classes;<jackson三个jar的路径>" com.fh.Main --offline
   ```
   更方便的方式是直接在 IDEA 中运行 `com.fh.Main`（工作目录设为项目根）。

5. **复核**：浏览器打开生成的 `map.html`，绿/黄点分别为成功/低置信度地址，点击标记或多边形可查看
   输入地址 → 选中候选 → 评分明细的追溯链；用顶部按钮过滤，优先人工复核黄色（低置信度）项。

## 输入输出格式

### 输入 `dizhi.txt`（UTF-8，每行一个地址）
- 空行和 `#` 开头跳过；首行标题"具体位置描述"自动跳过；完全重复的地址只处理一次并记录日志。

### 输出 `output.txt`（制表符分隔）
```
region_id	region_name	center_lonlat	address	place_type
10124	领航城·领秀花园-E栋	{"lon":113.8608…,"lat":22.6188…}	[{"lon":…,"lat":…},…]	内部楼栋
```
- `region_id` 从 `region.id.start`（默认 10124）自增
- 经纬度最多 14 位小数；多边形首尾闭合；无多边形时 `address` 为 `[]`；类型缺失填"未知"
- 多楼栋地址：`region_name` 用原始输入地址，多边形为各楼栋凸包，中心点为质心

### `map.html`
数据内嵌，无需额外请求。**需先把其中 AK 占位符换成有效浏览器端 AK**（或在配置里设置 `html.ak` 后重新生成）。

## 配置项

见 `src/main/resources/config.properties` 内注释。关键项：

| 配置 | 说明 |
|---|---|
| `baidu.auth` / `baidu.seckey` / `baidu.cookie` | 接口鉴权与反爬凭证，会过期，可手动更新；开启自动刷新时程序也会回写 |
| `baidu.autoRefresh` / `edge.debugPort` / `edge.autoLaunch` | 鉴权失效时是否自动经 Edge CDP 刷新；调试端口；端口不可用时是否自动拉起独立 Edge |
| `offline` / `cache.dir` | 离线重放开关与缓存目录 |
| `match.score.threshold` | 择优最低置信分，低于标记"低置信度" |
| `request.delay.ms` | 请求间隔（默认 300ms，约 1300 个地址全量约 20-30 分钟） |
| `coord.algorithm` | `official`（默认，推荐）/ `simple` |

## 鉴权过期后如何更新（重要）

百度接口的 `auth` / `seckey` / `cookie` 会过期，过期表现为接口返回 `result.error != 0` 或
`need_recaptcha`（content 恒为空）。两种更新方式：

**方式一：自动刷新（默认开启，推荐）**
- 程序检测到失效后自动通过 Edge 浏览器 CDP 抓取新 key 并回写到工作目录的 `config.properties`，无需人工干预；
- 前提：Edge 需以 `--remote-debugging-port=9222` 启动；若端口不可用且 `edge.autoLaunch=true`，
  程序会自动用独立 profile（`./edge-cdp-profile`）拉起一个专用 Edge 实例（不影响日常使用的 Edge）。

**方式二：手动抓包更新**
1. 浏览器打开 map.baidu.com，搜索任意地址；
2. F12 → Network → 筛选 `map.baidu.com`，找到 `qt=s` 请求；
3. 从该请求的 URL 复制 `auth`、`seckey` 参数，从请求头复制完整 `Cookie`；
4. 粘贴到工作目录 `config.properties` 的 `baidu.auth` / `baidu.seckey` / `baidu.cookie`，保存后重跑。

> 注意：`cookie` 是反爬必需项（缺失会触发 need_recaptcha），与 auth/seckey 一样会变化，建议一并更新。
> 首次运行前必须完成一次手动配置（仓库内的 config.properties 模板只有占位符，不含真实密钥）。

## 配置项与默认值

所有配置项在代码中均有默认值（见 `AppConfig.java`），`src/main/resources/config.properties`
为带注释的模板；运行时优先读取**工作目录**下的 `config.properties`（可从模板复制后修改）。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `baidu.auth` / `baidu.seckey` / `baidu.validate` / `baidu.cookie` | 无（必填） | 接口鉴权与反爬凭证，获取方式见上节 |
| `baidu.cityCode` | 340 | 城市代码（深圳） |
| `baidu.viewBound` | `(12650000,2550000;12750000,2600000)` | 搜索视野（墨卡托），勿用抓包里的小视野 |
| `baidu.bounds` | `113.75,22.40,114.65,22.88` | 结果坐标合理范围，越界候选跳过 |
| `baidu.autoRefresh` | true | 鉴权失效时自动经 Edge CDP 刷新 |
| `edge.debugPort` | 9222 | Edge CDP 调试端口 |
| `edge.autoLaunch` | true | 端口不可用时自动拉起独立 Edge |
| `edge.captureSeconds` | 25 | 单次抓取超时秒数 |
| `auth.maxRefreshes` | 10 | 单次运行最多刷新次数 |
| `auth.emptyThreshold` | 3 | 连续空结果判失效的阈值 |
| `input.path` | dizhi.txt | 输入文件（工作目录优先，回退 classpath） |
| `output.path` | output.txt | 结果输出 |
| `html.path` | map.html | 地图展示文件 |
| `html.ak` | 【你的AK】 | 百度地图 JS API 浏览器端 AK |
| `cache.dir` | cache | 原始响应缓存目录 |
| `region.id.start` | 10124 | region_id 起始自增值 |
| `request.delay.ms` | 300 | 请求间隔毫秒 |
| `http.connectTimeoutMs` / `http.readTimeoutMs` | 10000 / 20000 | 超时 |
| `http.retries` | 2 | 失败重试次数 |
| `match.score.threshold` | 40 | 择优最低置信分 |
| `offline` | false | 离线重放开关 |
| `coord.algorithm` | official | 坐标转换算法（official/simple） |

## 鉴权自动刷新（Edge CDP）说明

- **只有以 `--remote-debugging-port=9222` 启动的 Edge 才能被接管**。日常双击打开的 Edge 不开放调试端口。
- 若端口不可用且 `edge.autoLaunch=true`，程序会自动用**独立 profile**（`./edge-cdp-profile`）拉起一个
  专用 Edge 实例，不影响日常使用的 Edge；刷新成功后回写配置，可手动关闭该实例。
- 刷新原理：新建标签页打开百度地图并搜索一个探针地址，通过 CDP 网络事件捕获 `qt=s` 请求，
  提取 URL 中的 `auth`/`seckey` 与请求头 `Cookie`。

## 多楼栋拆分规则

1. 去掉括号别名后按 `、，,；;` 切分：所有片段均为楼栋片段（带 栋/座/楼/幢/单元/号楼 后缀，
   或纯编号/字母/中文数字）时，剥离公共前缀逐个生成 `前缀+片段` 查询，纯编号片段补兄弟片段的后缀。
2. 无分隔符时识别内嵌编号组：`AB栋`（字母逐个展开）、`1/2座`（列表）、`1-5单元`（数字范围，跨度≤12）。
3. 无法可靠拆分则整串查询取择优第一。部分子地址失败时用成功部分合并，全部失败则整条记为失败。

## 验证记录（2026-09-12，便于回溯）

全量跑批：1285 条唯一地址 → output.txt 1283 行（成功 1084 + 低置信度 199）+ failed.txt 2 行，账目平衡。
自测 45/45 通过（`--selftest`，含 6 个错配回归用例）。迭代修复记录见 `开发条件清单.md`。

### 抽样比对（随机 5 条，与百度地图网页逐条核对）

| 输入地址 | 选中 POI | 中心点 | 网页比对结果 |
|---|---|---|---|
| 水榭花都听水居3栋 | 香蜜湖水榭花都-听水居3栋 | 114.04350, 22.55401 | ✓ 与网页建议第一项一致，位置香蜜湖片区吻合（迭代3修复：单元号左边界） |
| 南方国际广场 | 南方国际广场 | 114.06208, 22.53241 | ✓ 网页第一结果同名同址（福田区益田路3013号），无轮廓与网页一致（迭代4修复：设施名降权） |
| 鹤洲新村4巷2号 | 鹤洲新村-三巷4栋 | 113.86910, 22.63499 | ✓ 与网页第一结果一致（村屋无精确 POI，数据源本身模糊） |
| 铲岛路2号缤纷世界 小区A1-1 | 碧海富通城1期-A1栋 | 113.86758, 22.56990 | ✓ 迭代4修复后选同址楼栋（网页"驻诗寓-A1栋"未出现在接口返回中，已选最优可用候选） |
| 崇文花园12栋 | 崇文花园-12栋 | 113.99658, 22.59704 | ✓ 网页第一结果同名同址（南山区仙科路），多边形 9 顶点闭合 |

### 重点场景确认

| 场景 | 结果 |
|---|---|
| 多楼栋合并：佳兆业中心A、B座 | ✓ A/B 座子查询分别正确命中（南园路866号），凸包 10 顶点，合并中心 114.10356, 22.54556 落在两座之间园区内 |
| 易混淆：香蜜楼B单元 | ✓ 接口无"B单元"子 POI，选母体"香蜜楼"（40分），未误选其他小区 |
| 无多边形地址 | ✓ address=[] 正确输出（506 条），未错误借用其他楼栋边界 |
| 评分明细 | ✓ 每条地址的候选名称、五维得分、总分、选中项均打印到运行日志；低置信度另见 review.txt |
| 过滤器 | ✓ 成功/低置信度过滤生效（修复：JS API 3.0 覆盖层无 setVisible，改 hide/show，并修正多边形数组索引错位） |

### 已知边界情况

- 机训南片（rid 10430）：接口无该 POI、全部候选≤20 分，已标记低置信度（map.html 黄色），中心点落在验收框外（114.373, 22.697），需人工在地图上确认；
- 香榭名园3栋、沙河武装部：百度查无结果，记录于 failed.txt。

## 已知限制

- `香蜜新村5栋一、二、三单元` 这类前缀里含楼栋号的分布式写法，会拆成 `香蜜新村5栋一/二/三单元`
  形式（丢一个"单元"后缀的拼接差异），评分与人工复核可兜底。
- 接口未收录的地址（如部分"XX单元"）返回相近候选，择优结果标记为成功但建议在 map.html 中复核。
- 大批量运行时如遇 IP 风控（持续 need_recaptcha），需更换网络或降低频率（调大 `request.delay.ms`）。

## 项目结构

```
src/main/java/com/fh/
├── Main.java                    入口（--offline / --selftest）
├── SelfTest.java                离线自测（37 项断言）
├── config/AppConfig.java        配置读取与 auth 回写
├── model/                       AddressResult / Candidate
├── service/
│   ├── BaiduMapService.java     qt=s 请求 + 缓存（先落盘再解析）
│   ├── ResponseParser.java      JSON 解析与字段提取
│   ├── BaiduAuthManager.java    鉴权管理（anti_auth 采纳 / Edge 刷新调度）
│   ├── AuthRefresher.java       Edge CDP 刷新（含零依赖 WebSocket 客户端）
│   ├── AddressSplitter.java     多楼栋拆分
│   └── AddressMatcher.java      多结果评分择优
└── util/
    ├── CoordinateConverter.java 官方 MC2LL（默认）+ 简化公式（对照）
    ├── PolygonUtils.java        bud_geom 解析 / 射线法 / 面积 / 质心 / 多边形筛选
    ├── PolygonMerger.java       凸包合并（Andrew 单调链）
    ├── OutputFormatter.java     output.txt / map.html / failed.txt / review.txt
    └── Util.java                md5 / 数值格式化
src/test/resources/fixture_qts.json   HAR 中 qt=s 真实响应（离线解析测试）
```
