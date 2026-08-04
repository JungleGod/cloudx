-- Nacos 需要独立的数据库（首次启动前在 MySQL 中执行）
-- docker exec -i cloudx-mysql mysql -uroot -proot123 < docs/sql/init-nacos.sql

CREATE DATABASE IF NOT EXISTS nacos
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
