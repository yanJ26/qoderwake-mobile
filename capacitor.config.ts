import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.rvslighting.qoderwake',
  appName: 'QoderWake',
  webDir: 'www',
  server: {
    androidScheme: 'https',
    // 单主机模式：默认指向 qoderwake.rvs-lighting.com 子域；
    // 允许用户编辑为其它 https:// 主机，故 allowNavigation 通配放行。
    allowNavigation: ['*'],
  },
  android: {
    // 用户可能编辑为 http:// 内网主机
    allowMixedContent: true,
  },
};

export default config;