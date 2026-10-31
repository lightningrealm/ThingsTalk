# 应用图标

源文件位于 [assets/branding/vector-v2](../../assets/branding/vector-v2/)：`foreground.svg` 为透明画布上的对话气泡与连接节点，`background.svg` 为全幅暖白背景，`icon.svg` 为合成源图。全部是可编辑 SVG，无嵌入位图。

绿色 `#416D60`，暖白 `#F5F6F1`。前景内部节点和连线为暖白实心形状，标志之外透明。108×108 viewBox，平台负责最终圆角与遮罩。

- Android 自适应图标使用 VectorDrawable 前景/背景；兼容图标提供五档密度普通及圆形 PNG。
- iOS 使用由合成 SVG 导出的 1024×1024 无 alpha PNG。
- Web 使用 SVG favicon 与 180×180 Apple touch icon。

修改源图后同步平台资源，并检查小尺寸辨识度、圆形/圆角裁切及 iOS 无透明通道。旧方案、作图提示词及预览放在本地 `docs/local/design/`，不提交。当前没有专用深色或主题动态图标变体。
