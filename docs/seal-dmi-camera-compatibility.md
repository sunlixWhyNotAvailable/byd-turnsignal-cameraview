# 海豹 DM-i 摄像头兼容性分析（2026-10-23）

## 证据

分析输入是用户提供的两个诊断包，以及工作目录中的
`byd-extend-compatibility-20261023-134119-003.zip`。包内文件仅作为诊断资料读取。

- 设备：DiLink 5.0，Android 12 / SDK 32，SM7325。
- 应用：BYD Extend 1.8.0（120）。
- 原厂车型识别：`ocean/hx/hx`，车型值 `140`。
- 兼容性包 `camera/discovery-config.txt`：
  `pano_l:2;dms:1;rear:0;`。
- 同文件 AVM 参数：
  `supplier:byd_six_factory;resolution:5120x960;fps:30.0;datatype:yuv422;module:ov0x1f;`。
- 新诊断包的连续 logcat 在 21:44:06 等时刻记录了相同的摄像头标签映射。
- 直连失败发生于发现阶段：`Camera tag is unavailable: pano_h`，尚未打开摄像头。
- 原厂全景在 `select_renderer` 阶段因读取
  `/collect2/autovideo/sdk/pano_sdk.txt` 被拒绝而退出。
- 用户确认 bydmate 的车外四路、360 全景和盲区有画面；这说明有可用的取流路径，
  但不能仅据此推断 bydmate 使用的具体 API。

## 原厂框架核对

对兼容性包内的框架、AutoVideo APK 做了只读 DEX 分析：

- `BmmCameraInfo` 根据全景分辨率区分 `pano_l` / `pano_h`，高度不超过 960
  的配置可被归为 `pano_l`。不能把 `pano_h` 当作所有车型的固定标签。
- 该框架的 `AVMCamera.open(int)` 接受摄像头 ID，
  `addPreviewSurface(Surface, int)` 转交 JNI，没有 Java 层的 `pano_h` 标签限制。
  这支持采用同一 AVM API 选择 `pano_l`，但不等于已验证其实际输出。
- AutoVideo 的 `HXCar.isIntercept()` 匹配车型值 140；
  `getRenderSdkDirectAvm`、`getRenderSdkHi`、`getRenderSdkLow` 返回 `API_YZL`。
  `CarRenderSDK` 将该枚举的名称定义为 `PI`。
  因此不能给这台车强行添加 BYD 或 TS 渲染器回退。

## 本次修改

- 新增统一的全景源选择：先查询 `pano_h`，缺失时查询 `pano_l`。
  使用车机返回的 ID，不写死为 2，不尝试 DVR、DMS 或 APA 标签。
- 四路共享生产者、多路备用路径和检测源打开逻辑使用统一选择。
- 通过通用发现流程选中的 `pano_l` 也接入共享生产者，避免被误当作独立的一次性源。
- 生产者存续期间保留其物理源身份；诊断事件增加 `physical_camera_tag`，
  区分旧客户端的逻辑 `pano_h` 请求和实际摄像头。
- `openAvmCamera` 中的危险 ID 拦截保持原样；存在但不受支持的高分辨率源
  不会被当成“缺失”而绕过检查。

## 验证和边界

`PanoCameraSourceTest` 的 10 项 JUnit 测试通过，包括本车映射、原有高分辨率优先、
缺失源、框架异常、ID 0、危险 ID 不被选择器替换、共享源标签识别。
测试使用 JDK 11 和 JUnit 4.13.2 单独编译执行；`git diff --check` 通过。

已使用临时安装的 JDK 17、Android SDK、固定版本 NDK / Go 完成
`assemblePerformance -PtargetAbis=arm64-v8a` 发行构建，发行 Lint 检查通过。
产物为 `build_output/byd-extend-v1.8.0-arm64-v8a.apk`（63,325,663 字节），
已验证非调试、仅包含 arm64-v8a 原生库、APK 签名有效、ZIP 对齐正确，
且 DEX 中包含本次摄像头修复类。校验值及验证记录与 APK 一同保存。
本地构建签名密钥保存在忽略版本控制的 `build_output/signing/local-build.keystore`；
该密钥与车机当前已装版本的签名是否一致尚未确认。未安装到车机。

尚未进行实车取流验证。安装修复构建后应先验证直连左右盲区、前后四路、后视镜，
再验证多窗口同时显示。检查日志是否出现 `physical_camera_tag=pano_l`、
`camera_id=2` 和首帧事件；核对方向、比例、裁剪和持续画面。
低分辨率源可能需要重新校准画面，不能把选择器测试当作出图验证。

PI 原厂全景渲染器仍未适配；采用原厂渲染器的路径仍可能失败。
运动检测另有 `7680x1300` 尺寸校验，本次未解除该限制。


## 实车反馈后的前视／右视修正

用户安装首个 arm64 修复包后确认其余功能正常，仅前视和右视互换。
应用逻辑通道沿用 `pano_h` 顺序：右视 3、前视 4；本车 `pano_l` 输出对应为
前视 3、右视 4。现在仅在 `pano_l` 的原厂接口调用边界交换 3 / 4，
添加与移除预览 Surface 使用相同映射；逻辑通道、裁剪和界面配置继续使用原顺序。
共享生产者的新建、复用、FPS 重连和清理均携带同一源标签。其他后端、
后视、左视、全景原始通道和特殊检测通道保持原映射。

新增前／右通道、其他后端和特殊通道回归用例，10 项测试通过。
arm64 发行构建及 Lint 通过。更新包为
`build_output/byd-extend-v1.8.0-arm64-v8a-front-right-fix.apk`，
已确认仅含 arm64 原生库、包含通道映射方法、签名及 ZIP 对齐校验通过，
且证书与首个修复包一致，可覆盖更新。前／右修正尚待用户实车复测。
