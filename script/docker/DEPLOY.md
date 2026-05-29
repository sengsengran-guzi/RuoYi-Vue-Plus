# sensenran-guzi 后端部署 SOP

> 部署栈：MySQL 8 + Redis 7 + ruoyi-admin（全部 Docker） · OSS：阿里云 OSS（admin 网页运行时配置）
> 反代：服务器宝塔 nginx → 127.0.0.1:8080

---

## 0. 服务器要求

- Linux x86_64（推荐 Rocky / CentOS / Ubuntu）
- 内存 ≥ 4 GB（MySQL + Redis + JVM）
- 磁盘 ≥ 40 GB
- **预装**：Docker ≥ 20.10、Docker Compose v2、git
- 防火墙：开放 80 / 443（公网）；3306 仅放运维 IP 白名单

---

## 1. 首次部署

```bash
# 1) 克隆代码（含子模块）
cd /www
git clone <repo-url> sensenran-guzi
cd sensenran-guzi/code/main/RuoYi-Vue-Plus

# 2) 生成 .env
cp .env.example .env
openssl rand -base64 24    # 复制输出填入 MYSQL_ROOT_PASSWORD
openssl rand -base64 24    # 复制输出填入 REDIS_PASSWORD
vim .env                   # 把 __CHANGE_ME__ 全部替换
chmod 600 .env             # 限权限

# 3) 构建并启动（首次 ≈ 5-10 分钟，下依赖 + 编译）
docker compose --env-file .env -f script/docker/docker-compose.deploy.yml up -d --build

# 4) 等待健康
docker compose --env-file .env -f script/docker/docker-compose.deploy.yml ps
# 三个服务都 Up (healthy) 即可

# 5) 看日志确认 Flyway 迁移成功
docker logs gz-ruoyi-admin-prod 2>&1 | grep -iE "flyway|migrat|started"
# 期待看到 "Successfully applied N migrations" + "Started RuoyiApplication"
```

---

## 2. 首次配置（admin 网页）

部署完后**立即做**这 3 件事：

### 2.1 改默认密码

浏览器打开 `http://<服务器IP>:8080`（如果已经配 nginx 就用域名）

- 默认账号：`admin` / `admin123`
- 登录后立即改密码 + 关掉所有不用的初始账号

### 2.2 配阿里云 OSS

进 **系统工具 → 对象存储配置**，新增一条：

| 字段 | 填什么 |
|---|---|
| 配置 key | `aliyun` |
| 服务商 | 阿里云 |
| accessKey | 阿里云 RAM 子账号 AK（仅授权目标 bucket 的 OSS 操作） |
| secretKey | 对应 SK |
| bucketName | 你的 bucket 名 |
| endpoint | `oss-cn-shanghai.aliyuncs.com`（按 region 调整） |
| region | `cn-shanghai` |
| domain | CDN 加速域名（可选） |
| isHttps | Y |
| accessPolicy | `private`（默认）或 `public_read`（公开图片等） |

**保存 → 设为默认 → 测试上传一张图**确认通。业务代码（GZ-SYS-005 `gz_file_object`）会自动用这条。

> ⚠️ **RAM 子账号最小授权**：只给 `oss:PutObject` / `oss:GetObject` / `oss:DeleteObject` 对应 bucket 的 ARN，**不要**给 root 账号 AK。

### 2.3 配宝塔 nginx 反代

宝塔 → 网站 → 添加 → 配置反向代理：
- 目标：`http://127.0.0.1:8080`
- 域名：你的业务域名
- 申请 SSL 证书（Let's Encrypt）

---

## 3. 日常运维

### 3.1 查看日志

```bash
# 实时日志（ruoyi-admin）
docker logs -f gz-ruoyi-admin-prod --tail 200

# MySQL 慢查询 / 错误
docker logs gz-mysql-prod --tail 200

# 落盘的应用日志（logback 输出）
ls -lh logs/
```

### 3.2 重启 / 停止 / 启动

```bash
cd /www/sensenran-guzi/code/main/RuoYi-Vue-Plus

# 重启应用（数据库 / Redis 不动）
docker compose --env-file .env -f script/docker/docker-compose.deploy.yml restart ruoyi-admin

# 全栈停 / 起
docker compose --env-file .env -f script/docker/docker-compose.deploy.yml down
docker compose --env-file .env -f script/docker/docker-compose.deploy.yml up -d
```

### 3.3 升级（拉新代码 → 重新构建）

```bash
cd /www/sensenran-guzi
git pull
cd code/main/RuoYi-Vue-Plus
docker compose --env-file .env -f script/docker/docker-compose.deploy.yml up -d --build ruoyi-admin
# Flyway 会自动跑新增的 V20260*__*.sql 迁移
```

### 3.4 Navicat 连库

服务器防火墙白名单开通运维 IP 后：
- Host：服务器公网 IP
- Port：3306
- User：root
- Password：`.env` 里的 `MYSQL_ROOT_PASSWORD`

---

## 4. 备份

### 4.1 MySQL（每天凌晨 3 点 crontab）

```bash
# 加到 crontab：
0 3 * * * cd /www/sensenran-guzi/code/main/RuoYi-Vue-Plus && \
  docker exec gz-mysql-prod mysqldump -uroot -p"$(grep MYSQL_ROOT_PASSWORD .env | cut -d= -f2)" \
  --single-transaction --routines --triggers ry-vue | \
  gzip > /www/backup/mysql/ry-vue-$(date +\%Y\%m\%d).sql.gz
```

### 4.2 OSS 文件备份

阿里云 OSS 自带跨 region 复制（建议开启 → 双 region 冷备）。无需自己脚本。

---

## 5. 故障排查

| 现象 | 原因 | 解决 |
|---|---|---|
| ruoyi-admin 起不来，日志报 `Communications link failure` | mysql 还在初始化 / 密码不对 | `docker logs gz-mysql-prod` 看 mysql 状态；核对 `.env` `MYSQL_ROOT_PASSWORD` |
| 日志报 `Redis connection refused` | redis 容器没起 / 密码不对 | `docker ps` 看 redis 状态；核对 `.env` `REDIS_PASSWORD` |
| 上传文件 500 报 OSS 错误 | sys_oss_config 没配 / RAM 权限不足 | admin 网页重测；阿里云子账号补权限 |
| `bind: address already in use` 3306 / 8080 | 服务器已有 mysql / 其他服务占端口 | `lsof -i:3306` 找凶手，停掉或改 compose 端口映射 |
| 构建慢 / 超时 | 第一次拉 maven 依赖 | 等首次完成；可在 Dockerfile builder 阶段加 `-Dmaven.repo.remote=阿里云 maven 镜像` 加速 |
| Flyway checksum 报错 | 历史迁移 SQL 被改过 | 别改老的 V... 文件！加新的 V<新时间戳>... 修；实在要修：`docker exec ... mysql -e "DELETE FROM flyway_schema_history WHERE script='V...'"` 慎用 |

---

## 6. 端口一览

| 服务 | 容器端口 | 宿主端口 | 暴露范围 |
|---|---|---|---|
| MySQL | 3306 | 3306 | 公网（防火墙白名单） |
| Redis | 6379 | — | 仅容器网络（不暴露） |
| ruoyi-admin | 8080 | 127.0.0.1:8080 | 仅本机（宝塔 nginx 反代） |

---

## 7. 不在此处的事

- 微信支付 / 微信小程序登录 → admin 配 `sys_config`
- 客服配置 → admin 配 customer-service-config（GZ-SYS-004）
- 短信网关 → 业务代码后续扩展
