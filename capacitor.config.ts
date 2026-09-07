import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.rvslighting.qoderwake',
  appName: 'QoderWake',
  webDir: 'www',
  server: {
    androidScheme: 'https',
    // 多主机地址在运行时由用户维护；表单仅接受 HTTPS。
    // Capacitor 的静态 allowNavigation 无法表达运行时主机集合。
    allowNavigation: ['*'],
  },
  android: {
    allowMixedContent: false,
  },
};

export default config;
