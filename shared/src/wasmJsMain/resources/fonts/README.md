# ThingsTalk Web 中文字体

`ThingsTalkSansSC-Regular.ttf` 是 Noto Sans SC 的 Regular (400) 静态子集，
仅随 Web 资源发布，不增加 Android/iOS 包体。入口在渲染应用前预加载本地字体，
避免界面中文依赖外部字体 CDN。

- 上游：[Google Fonts / Noto Sans SC](https://github.com/google/fonts/tree/main/ofl/notosanssc)
- 来源文件：`NotoSansSC[wght].ttf`
- 源文件 SHA-256：`a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`
- 许可：同目录 `OFL.txt`，SIL Open Font License 1.1；保留上游版权信息。
- 包含 GB2312 字符集（6,763 个常用汉字）、拉丁字符，以及生成时项目 Kotlin 源码中的字符。
  对用户输入或消息中的罕见汉字、其他文字与 Emoji，Compose 仍可按需使用其自动字体回退；
  因而这些字符的完整离线覆盖不在本地子集保证范围内。

修改了界面文字后，可使用 `scripts/subset_web_font.py` 和上游字体重建。
项目构建使用已提交的字体文件，不在构建或启动时下载源字体。
