# Android 防火墙

Magisk模块,可以拦截用户软件及系统软件,内部基于iptables对app的uid拦截



## 使用

1. 刷入magisk模块

2. 配置app列表,添加app的包名

   **默认拦截小米(Hyperos)云控**

   ```shell
   #/data/adb/modules/android_firewall/script/conf.json
   
   com.xiaomi.joyose
   com.miui.powerkeeper
   ```

3. 执行刷新或重启机器

   ```shell
   su #使用root
   sh /data/adb/modules/android_firewall/script/refresh.sh
   ```

4. 查看运行日志

   ```shell
   cat /data/adb/modules/android_firewall/debug.log
   ```

   

## 目录结构说明

```
META-INF: 模块必须依赖
srcipt: 自定义脚本
system: 

customize.sh: magisk安装自定义信息
module.prop: 模块信息
post-fs-data.sh: 
service.sh: 启动后执行
system.prop
```

