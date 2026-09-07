# QoderWake Mobile

Android 客户端，把 qoderwake-cn WebUI 装进 WebView，单主机管理。

## 与 dsh-mobile 的关系

本项目 fork 自 [yanJ26/dsh-mobile](https://github.com/yanJ26/dsh-mobile)，做了大幅简化：

| 项 | dsh-mobile | qoderwake-mobile |
|---|---|---|
| 主机数 | 多主机列表 + 增删改 | 单主机（默认 qoderwake.rvs-lighting.com），可编辑 URL |
| 悬浮 dock | 鲸鱼（侧边栏）+ 网格（主机切换）+ 主机名 | 无（qoderwake-cn 单一实例，不需要） |
| 一键重启 | 红色 ⟳ 调用 dsh-service-restart 插件 | 无（qoderwake-cn 没有等价插件） |
| 检查更新 | 拉 qhrc.work/dsh-app/latest.json | 无（直接交付 APK，无需 OTA） |
| 鉴权 | 依赖宿主 dsh-auth-gateway 插件 | qoderwake-cn 自带 OAuth（qoder.com.cn 设备授权流） |

## 工作原理

```
[Android APK WebView]
        │ HTTPS
        ▼
[nginx: qoderwake.rvs-lighting.com]
        │ 反代（无认证层 —— 见下方"鉴权说明"）
        ▼
[qoderwake-cn daemon :19830]
```

## 鉴权说明

**本应用不引入额外鉴权层**。决策理由：qoderwake-cn 的登录是 OAuth 2.0 Device Authorization Grant（PKCE/S256），挑战发到 `qoder.com.cn`，本地没有任何独立用户名/密码/TOTP；本地再多一层 OTP 网关无法替代厂商侧认证。

如果以后 qoderwake-cn 增加了本地 TOTP 支持，或者你希望把这台机器从厂商云解耦，可以参考 [dsh-auth-gateway](https://github.com/xbzbing/dsh-auth-gateway) 的设计（密码 scrypt + TOTP AES-256-GCM + HttpOnly+SameSite 30 天会话 + 三层防爆破 + JSONL 审计日志）加一层独立网关。

## 构建

工具链全部装在 `/root/tokenHUB/tokenDYB/qoderwake/build-cache/`：

```bash
export JAVA_HOME=/root/tokenHUB/tokenDYB/qoderwake/build-cache/jdk-17.0.2
export ANDROID_HOME=/root/tokenHUB/tokenDYB/qoderwake/build-cache/android-sdk
export GRADLE_USER_HOME=/root/tokenHUB/tokenDYB/qoderwake/build-cache/.gradle
export ANDROID_USER_HOME=/root/tokenHUB/tokenDYB/qoderwake/build-cache/android-home
export GRADLE_OPTS="-Dorg.gradle.native.lib.dir=/root/tokenHUB/tokenDYB/qoderwake/build-cache/native-libs/linux-amd64/net/rubygrapefruit/platform/linux-amd64 -Duser.home=$ANDROID_USER_HOME"
export PATH=$JAVA_HOME/bin:/root/tokenHUB/tokenDYB/qoderwake/build-cache/gradle-8.7/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

cd /root/tokenHUB/tokenDYB/qoderwake/qoderwake-mobile
pnpm install
node scripts/gen-icons.mjs
npx cap sync android
gradle -p android assembleDebug --no-daemon
# 产物: android/app/build/outputs/apk/debug/app-debug.apk
```

## 调试 keystore

当前是 debug 签名（`~/.android/debug.keystore`）。
**keystore 一旦丢失，新版本无法覆盖安装**。已备份在 `/root/tokenHUB/tokenDYB/qoderwake/build-cache/debug.keystore`。

## 部署

详见 `/root/tokenHUB/tokenDYB/qoderwake/qoderwake-deploy.sh`：

```bash
sudo bash /root/tokenHUB/tokenDYB/qoderwake/qoderwake-deploy.sh
```

它会：
1. 复制 nginx 配置到 `/etc/nginx/sites-enabled/`
2. certbot 申请 qoderwake.rvs-lighting.com 证书
3. nginx -t + reload
4. curl 端到端冒烟

### nginx 反代的关键点（重要）

qoderwake-cn 默认仅监听 loopback，并对每个请求做双重校验：

1. **Host 头必须匹配 `127.0.0.1:19830`**（或 `localhost:19830`）
2. **禁止任何 `X-Forwarded-*` / `X-Real-IP` 头** —— 它假定 loopback 客户端不会经过代理

因此反代必须：
- `proxy_set_header Host "127.0.0.1:19830";`
- `proxy_set_header X-Real-IP "";`
- `proxy_set_header X-Forwarded-For "";`
- `proxy_set_header X-Forwarded-Proto "";`

否则 daemon 会返回 `403 {"code":"daemon_host_rejected"}`。

## 许可

MIT