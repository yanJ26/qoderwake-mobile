# QoderWake Mobile

面向多个 QoderWake 实例的 Android 客户端。应用在本地保存管理员确认过的 HTTPS 主机列表，并在 WebView 中打开对应 WebUI。

## 功能

- 多主机列表：添加、编辑、删除和连通性提示；
- 从旧版单主机配置自动迁移；
- 只接受不含账号密码的 HTTPS 地址；
- Qoder 登录 popup 仅接受当前主机 callback 与 Qoder 官方登录域；
- 下载只允许来自当前主机的 HTTPS URL，Cookie 不会转发给其他来源；
- Android 明文流量、mixed content、文件访问和应用备份默认关闭。

## 认证边界

QoderWake 包含两层不同认证：

1. daemon owner 使用 Qoder 账号完成厂商侧授权；
2. 每个远程浏览器或设备通过 `/api/frontend-auth/*` 建立自己的 frontend session。

OAuth/PKCE 保护厂商账号授权，但不会自动解决公网入口、反向代理信任、Cookie、CSRF、限流或 APK 更新链问题。公网实例还应使用可信网络、入口 ACL 或独立访问网关。

不要通过伪造 loopback `Host`、删除代理来源头或依赖未公开的内部环境变量绕过 daemon 的远程访问保护。优先采用 QoderWake 官方支持的 external 模式，或保持本地监听并通过 VPN/SSH 隧道访问。

## 构建

需要 Node.js、pnpm、JDK 17、Android SDK 和 Gradle。工具位置由构建环境配置，不写入仓库。

```bash
export JAVA_HOME=<JDK_HOME>
export ANDROID_HOME=<ANDROID_SDK_HOME>
export ANDROID_SDK_ROOT="$ANDROID_HOME"

pnpm install
node scripts/gen-icons.mjs
pnpm build:debug
```

debug 包只用于开发测试。生产构建使用独立 release keystore，通过环境变量注入：

```bash
export ANDROID_RELEASE_STORE_FILE=<ABSOLUTE_KEYSTORE_PATH>
export ANDROID_RELEASE_STORE_PASSWORD=<SECRET>
export ANDROID_RELEASE_KEY_ALIAS=<ALIAS>
export ANDROID_RELEASE_KEY_PASSWORD=<SECRET>
pnpm build
```

keystore 和密码不得提交 GitHub、写入 README、复制到公网下载目录或打印到 CI 日志。

## 发布

发布脚本只接受显式目标目录，并会先用 `apksigner` 验证签名，再生成 SHA-256：

```bash
APK_FILE=<SIGNED_RELEASE_APK> \
PUBLISH_DIR=<APK_PUBLISH_DIR> \
APKSIGNER=<APKSIGNER_PATH> \
bash scripts/deploy-apk.sh
```

下载页应同时公布 APK SHA-256 与签名证书 SHA-256 指纹。MD5 不能作为安全完整性校验。

## 多主机安全说明

主机列表由用户在运行时维护，因此 Capacitor 的静态 `allowNavigation` 无法预先枚举所有域名。当前版本会在入口、OAuth callback 和下载链路执行 HTTPS/origin 校验。添加主机等同于信任该服务器，应只添加自己管理或明确信任的实例。

后续如需进一步缩小原生桥暴露面，可把远程 WebUI 移入不注入 Capacitor bridge 的独立普通 WebView，本地启动页继续使用 Capacitor Preferences。

## 许可

MIT
