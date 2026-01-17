# app-parser

本项目是仿造 `apk-parser` 编写的简化实现，当前聚焦于解析 APK 内的 `AndroidManifest.xml`（二进制 XML）并输出文本 XML。

## 当前功能范围

- 解析 APK 中的 `AndroidManifest.xml`（二进制 XML -> 文本 XML）
- 可选解析 `resources.arsc`，将资源引用解析为 `@type/name` 或具体字符串值
- 对部分整数型 manifest 属性做可读化映射（如 `screenOrientation` 等）

## 使用用例

```java
import com.github.rayinfinite.AndroidParser;

import java.io.File;

public class Demo {
    public static void main(String[] args) {
        File apk = new File("app.apk");

        // 仅解码 manifest，不解析资源表
        String manifestXml = AndroidParser.decode(apk, 0);

        // 解析 resources.arsc 并尝试将引用解析为字符串值
        String manifestWithRes = AndroidParser.decode(apk);

        System.out.println(manifestXml);
        System.out.println(manifestWithRes);
    }
}
```

## 说明

- 该项目用于复刻 `apk-parser` 的部分能力；完整功能请参考原项目。
