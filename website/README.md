# Miracast Receiver 介绍网站

使用 Astro 生成静态网页，与 Android 工程独立。

本地开发：`npm install` 后执行 `npm run dev`。
构建：`npm run build`，输出目录为 `dist`。

Cloudflare Pages 连接此 GitHub 仓库后配置：
- 根目录：`website`
- 构建命令：`npm run build`
- 输出目录：`dist`

启用 Preview deployments 可为 PR 自动生成预览链接。发布分支选用仓库实际主分支。首次连接需要仓库所有者在 Cloudflare 授权 GitHub 访问。

下载按钮指向 GitHub Releases，不固定测试包或过时版本。页面图片来自仓库 docs/images/tv-name-home.png。
