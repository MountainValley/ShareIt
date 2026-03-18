# ShareIt
**局域网文件共享工具 Local Area Network File Sharing Tool**

项目已经拆分为前后端两个子模块：

- `frontend`：Vue 3 + Vite，负责页面和交互
- `backend`：Spring Boot，负责文件、文本、SSE 和部署接口

开发启动：

```bash
./scripts/dev.sh
```

默认端口：

- 前端：`http://localhost:5173`
- 后端：`http://localhost:7777`

局域网开发访问：

- 前端开发服务监听 `0.0.0.0:5173`
- 后端开发服务监听 `0.0.0.0:7777`
- 同局域网设备应访问 `http://你的局域网IP:5173`
- 如果本机能访问、手机不能访问，通常是系统防火墙没有放行 `node` 或终端进程
- 开发模式下会自动打开前端局域网地址，不再自动打开后端 `7777`

生产构建：

```bash
./scripts/build.sh
```

构建脚本会先打包前端，再把前端静态资源复制到 `backend/src/main/resources/static`，最后构建 Spring Boot 包。
