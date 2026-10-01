#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PolyHermes 动态更新服务
负责检查更新、下载更新包、执行更新和回滚
"""

import os
import json
import logging
import subprocess
import time
import shutil
import tarfile
import requests
from pathlib import Path
from threading import Lock, Thread
from flask import Flask, jsonify, request
from datetime import datetime

# ==================== 配置 ====================
app = Flask(__name__)

# 日志配置（允许环境变量覆盖，便于本地测试；生产默认值保持不变）
LOG_FILE = Path(os.getenv('POLYHERMES_UPDATE_LOG', '/var/log/polyhermes/update-service.log'))
LOG_FILE.parent.mkdir(parents=True, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler(LOG_FILE),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# 路径配置（允许环境变量覆盖，便于本地测试；生产默认值保持不变）
APP_DIR = Path(os.getenv('POLYHERMES_APP_DIR', '/app'))
VERSION_FILE = Path(os.getenv('POLYHERMES_VERSION_FILE', str(APP_DIR / 'version.json')))
UPDATES_DIR = Path(os.getenv('POLYHERMES_UPDATES_DIR', str(APP_DIR / 'updates')))
BACKUPS_DIR = Path(os.getenv('POLYHERMES_BACKUPS_DIR', str(APP_DIR / 'backups')))
BACKEND_JAR = Path(os.getenv('POLYHERMES_BACKEND_JAR', str(APP_DIR / 'app.jar')))
FRONTEND_DIR = Path(os.getenv('POLYHERMES_FRONTEND_DIR', '/usr/share/nginx/html'))

# 创建必要目录
UPDATES_DIR.mkdir(parents=True, exist_ok=True)
BACKUPS_DIR.mkdir(parents=True, exist_ok=True)

# GitHub 配置
GITHUB_REPO = os.getenv('GITHUB_REPO', 'WrBug/PolyHermes')
ALLOW_PRERELEASE = os.getenv('ALLOW_PRERELEASE', 'false').lower() == 'true'
BACKEND_URL = os.getenv('POLYHERMES_BACKEND_URL', 'http://localhost:8000')

# 更新状态
update_lock = Lock()
update_status = {
    'updating': False,
    'progress': 0,
    'message': '就绪',
    'error': None
}

# GitHub Release 查询缓存，避免页面自动检查触发未认证 API 限流
RELEASE_CACHE_TTL_SECONDS = 300
RELEASE_CACHE_FILE = Path(os.getenv('POLYHERMES_RELEASE_CACHE', str(UPDATES_DIR / 'release-cache.json')))

# 后端 Java 进程的运行用户（降权运行；更新服务本身需要 root 以替换前端文件并 reload nginx）
BACKEND_RUN_USER = os.getenv('POLYHERMES_BACKEND_USER', 'appuser')


def backend_command():
    """构造后端启动命令：以 root 运行且降权用户存在时，用 runuser 以 BACKEND_RUN_USER 身份启动 Java"""
    cmd = ['java', '-jar', str(BACKEND_JAR), '--spring.profiles.active=prod']
    try:
        import pwd
        if os.geteuid() == 0 and BACKEND_RUN_USER and shutil.which('runuser'):
            pwd.getpwnam(BACKEND_RUN_USER)
            return ['runuser', '-u', BACKEND_RUN_USER, '--'] + cmd
    except (KeyError, ImportError):
        logger.warning(f"降权用户 {BACKEND_RUN_USER} 不存在，后端将以当前用户运行")
    return cmd


release_cache = {
    'data': None,
    'timestamp': 0.0
}
try:
    if RELEASE_CACHE_FILE.exists():
        cached_payload = json.loads(RELEASE_CACHE_FILE.read_text())
        if isinstance(cached_payload.get('data'), dict):
            release_cache = {
                'data': cached_payload['data'],
                'timestamp': float(cached_payload.get('timestamp', 0))
            }
except Exception as cache_error:
    logger.warning(f"读取 Release 缓存失败: {cache_error}")

# ==================== 工具函数 ====================

def get_current_version():
    """获取当前版本"""
    try:
        if VERSION_FILE.exists():
            with open(VERSION_FILE) as f:
                data = json.load(f)
                return data.get('version', 'unknown')
        return 'unknown'
    except Exception as e:
        logger.error(f"读取版本失败: {e}")
        return 'unknown'


def fetch_latest_release():
    """获取最新 Release"""
    now = time.time()
    cached_release = release_cache['data']
    if cached_release and now - release_cache['timestamp'] < RELEASE_CACHE_TTL_SECONDS:
        return cached_release

    def cache_and_return(release):
        release_cache['data'] = release
        release_cache['timestamp'] = time.time()
        try:
            RELEASE_CACHE_FILE.parent.mkdir(parents=True, exist_ok=True)
            RELEASE_CACHE_FILE.write_text(json.dumps(release_cache, ensure_ascii=False))
        except Exception as cache_error:
            logger.warning(f"写入 Release 缓存失败: {cache_error}")
        return release

    try:
        if ALLOW_PRERELEASE:
            # 测试模式：获取所有 Release（包括 pre-release）
            url = f'https://api.github.com/repos/{GITHUB_REPO}/releases'
            response = requests.get(url, headers={'Accept': 'application/vnd.github.v3+json'}, timeout=10)
            if response.status_code != 200:
                logger.warning(f"获取 Release 列表失败: HTTP {response.status_code}")
                return cached_release
            releases = response.json()
            
            if isinstance(releases, list) and releases:
                latest = releases[0]
                logger.info(f"检测到版本: {latest['tag_name']} (pre-release: {latest.get('prerelease', False)})")
                return cache_and_return({
                    'tag': latest['tag_name'],
                    'name': latest['name'],
                    'body': latest['body'],
                    'published_at': latest['published_at'],
                    'assets': latest['assets'],
                    'prerelease': latest.get('prerelease', False)
                })
        else:
            # 生产模式：只获取正式版本
            url = f'https://api.github.com/repos/{GITHUB_REPO}/releases/latest'
            response = requests.get(url, headers={'Accept': 'application/vnd.github.v3+json'}, timeout=10)
            
            if response.status_code == 200:
                data = response.json()
                return cache_and_return({
                    'tag': data['tag_name'],
                    'name': data['name'],
                    'body': data['body'],
                    'published_at': data['published_at'],
                    'assets': data['assets'],
                    'prerelease': False
                })
            logger.warning(f"获取最新 Release 失败: HTTP {response.status_code}")
        
        return cached_release
        
    except Exception as e:
        logger.error(f"获取 Release 失败: {e}")
        return cached_release


def compare_versions(v1, v2):
    """
    比较两个版本号（语义化版本）
    返回: 1 if v1 > v2, -1 if v1 < v2, 0 if equal
    """
    def normalize(v):
        parts = v.replace('v', '').split('-')[0].split('.')
        return [int(x) for x in parts]
    
    try:
        parts1 = normalize(v1)
        parts2 = normalize(v2)
        
        for i in range(max(len(parts1), len(parts2))):
            p1 = parts1[i] if i < len(parts1) else 0
            p2 = parts2[i] if i < len(parts2) else 0
            if p1 > p2:
                return 1
            elif p1 < p2:
                return -1
        return 0
    except:
        return 0


def make_world_readable(path):
    """nginx worker（www-data）与后端（appuser）均为非 root，更新后的文件需保证可读"""
    try:
        path = Path(path)
        targets = [path] + (list(path.rglob('*')) if path.is_dir() else [])
        for target in targets:
            mode = target.stat().st_mode
            extra = 0o555 if target.is_dir() else 0o444
            os.chmod(target, mode | extra)
    except Exception as e:
        logger.warning(f"设置文件权限失败: {path}: {e}")


def check_admin_permission(req):
    """检查管理员权限

    后端鉴权失败时也可能返回 HTTP 200 + body code 非 0，
    因此必须同时满足 HTTP 200 且响应体 JSON 的 code == 0 才算通过
    """
    auth_header = req.headers.get('Authorization')
    if not auth_header or not auth_header.startswith('Bearer '):
        return False

    try:
        response = requests.get(
            f'{BACKEND_URL}/api/auth/verify',
            headers={'Authorization': auth_header},
            timeout=3
        )
        if response.status_code != 200:
            return False
        body = response.json()
        return isinstance(body, dict) and type(body.get('code')) is int and body.get('code') == 0
    except Exception as e:
        logger.error(f"权限验证失败: {e}")
        return False


def download_file(url, dest_path):
    """下载文件"""
    logger.info(f"开始下载: {url}")
    response = requests.get(url, stream=True, timeout=300)
    response.raise_for_status()
    
    total_size = int(response.headers.get('content-length', 0))
    downloaded = 0
    
    with open(dest_path, 'wb') as f:
        for chunk in response.iter_content(chunk_size=8192):
            if chunk:
                f.write(chunk)
                downloaded += len(chunk)
                if total_size > 0:
                    progress = int((downloaded / total_size) * 30)  # 下载占30%
                    update_status['progress'] = progress
    
    logger.info(f"下载完成: {dest_path}")
    return dest_path


def backup_current_version():
    """备份当前版本"""
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    backup_dir = BACKUPS_DIR / timestamp
    backup_dir.mkdir(parents=True, exist_ok=True)
    
    logger.info(f"创建备份: {backup_dir}")
    
    # 备份后端 JAR
    if BACKEND_JAR.exists():
        shutil.copy2(BACKEND_JAR, backup_dir / 'app.jar')
    
    # 备份前端（打包）
    if FRONTEND_DIR.exists():
        frontend_backup = backup_dir / 'frontend.tar.gz'
        with tarfile.open(frontend_backup, 'w:gz') as tar:
            tar.add(FRONTEND_DIR, arcname='.')
    
    # 备份版本信息
    if VERSION_FILE.exists():
        shutil.copy2(VERSION_FILE, backup_dir / 'version.json')
    
    logger.info(f"备份完成: {backup_dir}")
    return backup_dir


def extract_tar_safely(tar, dest_dir):
    """校验并解压 tar，拒绝绝对路径、路径穿越和设备/链接条目。"""
    dest = Path(dest_dir).resolve()
    dest.mkdir(parents=True, exist_ok=True)

    for member in tar.getmembers():
        member_name = member.name.replace('\\', '/')
        member_path = Path(member_name)
        if member_path.is_absolute() or '..' in member_path.parts:
            raise Exception(f"更新包包含非法路径: {member.name}")
        if not (member.isfile() or member.isdir()):
            raise Exception(f"更新包包含不允许的条目类型: {member.name}")

        target = (dest / member_path).resolve()
        if target != dest and dest not in target.parents:
            raise Exception(f"更新包路径越界: {member.name}")

    tar.extractall(dest)


def restore_backup(backup_dir):
    """恢复备份"""
    logger.info(f"开始恢复备份: {backup_dir}")
    
    # 恢复后端 JAR
    backup_jar = backup_dir / 'app.jar'
    if backup_jar.exists():
        shutil.copy2(backup_jar, BACKEND_JAR)
    
    # 恢复前端
    frontend_backup = backup_dir / 'frontend.tar.gz'
    if frontend_backup.exists():
        # 清空前端目录
        if FRONTEND_DIR.exists():
            shutil.rmtree(FRONTEND_DIR)
        FRONTEND_DIR.mkdir(parents=True, exist_ok=True)
        
        # 解压备份
        with tarfile.open(frontend_backup, 'r:gz') as tar:
            extract_tar_safely(tar, FRONTEND_DIR)
        make_world_readable(FRONTEND_DIR)
    if BACKEND_JAR.exists():
        make_world_readable(BACKEND_JAR)
    
    # 恢复版本信息
    backup_version = backup_dir / 'version.json'
    if backup_version.exists():
        shutil.copy2(backup_version, VERSION_FILE)
    
    logger.info("备份恢复完成")


def validate_update_package(extract_dir):
    """在停止后端前校验更新包结构，避免缺文件时误报更新成功。"""
    new_jar = extract_dir / 'backend' / 'polyhermes.jar'
    new_frontend = extract_dir / 'frontend'
    new_version = extract_dir / 'version.json'

    missing = []
    if not new_jar.is_file():
        missing.append('backend/polyhermes.jar')
    if not new_frontend.is_dir():
        missing.append('frontend/')
    if not new_version.is_file():
        missing.append('version.json')
    if missing:
        raise Exception(f"更新包缺少必要文件: {', '.join(missing)}")

    return new_jar, new_frontend, new_version


def rollback_update(backup_dir):
    """停止失败的新后端，恢复备份并重新启动旧后端。"""
    subprocess.run(['pkill', '-9', '-f', 'java.*app.jar'], check=False)
    time.sleep(2)
    restore_backup(backup_dir)

    backend_log_file = LOG_FILE.parent / 'backend-rollback.log'
    with open(backend_log_file, 'a') as backend_log:
        subprocess.Popen(
            backend_command(), stdout=backend_log, stderr=subprocess.STDOUT, start_new_session=True)

    subprocess.run(['nginx', '-s', 'reload'], check=True)
    logger.info(f"旧版本后端已重启，日志: {backend_log_file}")


def perform_update(target_version):
    """执行更新流程"""
    global update_status
    backup_dir = None
    replacement_started = False
    rollback_done = False
    update_completed = False
    
    try:
        update_status['updating'] = True
        update_status['progress'] = 0
        update_status['message'] = '开始更新...'
        update_status['error'] = None
        
        # 1. 获取最新 Release
        update_status['message'] = '获取 Release 信息...'
        release = fetch_latest_release()
        if not release:
            raise Exception("无法获取 Release 信息")
        
        tag = release['tag']
        assets = release['assets']
        
        # 查找更新包
        update_asset = None
        for asset in assets:
            if asset['name'].endswith('-update.tar.gz'):
                update_asset = asset
                break
        
        if not update_asset:
            raise Exception(f"未找到更新包: {tag}")
        
        update_status['progress'] = 10
        
        # 2. 下载更新包
        update_status['message'] = f'下载更新包 {tag}...'
        download_url = update_asset['browser_download_url']
        download_path = UPDATES_DIR / update_asset['name']
        download_file(download_url, download_path)
        
        update_status['progress'] = 40
        
        # 3. 解压并校验更新包
        update_status['message'] = '解压更新包...'
        extract_dir = UPDATES_DIR / 'current'
        if extract_dir.exists():
            shutil.rmtree(extract_dir)
        extract_dir.mkdir(parents=True, exist_ok=True)
        
        with tarfile.open(download_path, 'r:gz') as tar:
            extract_tar_safely(tar, extract_dir)

        new_jar, new_frontend, new_version = validate_update_package(extract_dir)

        # 4. 备份当前版本（仅在校验通过后执行）
        update_status['message'] = '备份当前版本...'
        backup_dir = backup_current_version()
        
        update_status['progress'] = 60
        
        # 5. 停止后端进程
        update_status['message'] = '停止后端服务...'
        logger.info("停止后端进程...")
        replacement_started = True
        subprocess.run(['pkill', '-f', 'java -jar'], check=False)
        time.sleep(2)
        
        update_status['progress'] = 65
        
        # 6. 替换文件
        update_status['message'] = '更新文件...'
        
        # 替换后端 JAR
        shutil.copy2(new_jar, BACKEND_JAR)
        logger.info("后端 JAR 已更新")
        
        # 替换前端文件
        if FRONTEND_DIR.exists():
            shutil.rmtree(FRONTEND_DIR)
        shutil.copytree(new_frontend, FRONTEND_DIR)
        make_world_readable(FRONTEND_DIR)
        make_world_readable(BACKEND_JAR)
        logger.info("前端文件已更新")
        
        # 更新版本信息
        shutil.copy2(new_version, VERSION_FILE)
        logger.info("版本信息已更新")
        
        update_status['progress'] = 75
        
        # 7. 重启后端服务
        update_status['message'] = '重启后端服务...'
        logger.info("重启后端服务...")
        
        # 创建后端日志文件
        backend_log_file = LOG_FILE.parent / 'backend-update.log'
        backend_log = open(backend_log_file, 'w')
        
        backend_process = subprocess.Popen(
            backend_command(), stdout=backend_log, stderr=subprocess.STDOUT, start_new_session=True)
        
        logger.info(f"后端进程已启动 (PID: {backend_process.pid})")
        
        update_status['progress'] = 80
        
        # 8. 重载 Nginx
        update_status['message'] = '重载 Nginx...'
        subprocess.run(['nginx', '-s', 'reload'], check=True)
        
        update_status['progress'] = 85
        
        # 9. 健康检查
        update_status['message'] = '健康检查...'
        logger.info("等待后端服务启动...")
        
        healthy = False
        max_wait_time = 90  # 增加到90秒，给后端更多启动时间
        last_process_check = 0
        
        for i in range(max_wait_time):
            # 每5秒检查一次进程状态
            if i - last_process_check >= 5:
                last_process_check = i
                if backend_process.poll() is not None:
                    # 进程已退出
                    backend_log.close()
                    error_msg = ''
                    try:
                        with open(backend_log_file, 'r') as f:
                            lines = f.readlines()
                            error_msg = ''.join(lines[-50:])  # 读取最后50行
                    except:
                        pass
                    
                    logger.error(f"后端进程异常退出（等待了 {i} 秒），退出码: {backend_process.returncode}")
                    if error_msg:
                        logger.error(f"后端日志最后50行:\n{error_msg}")
                    raise Exception(f"后端服务启动失败，退出码: {backend_process.returncode}")
                else:
                    logger.debug(f"后端进程仍在运行 (PID: {backend_process.pid})")
            
            # 尝试健康检查
            try:
                response = requests.get(f'{BACKEND_URL}/api/system/health', timeout=2)
                if response.status_code == 200:
                    healthy = True
                    backend_log.close()
                    logger.info(f"健康检查通过（等待了 {i+1} 秒）")
                    break
            except requests.exceptions.ConnectionError:
                # 连接被拒绝，说明后端还没启动或端口未监听
                if i % 10 == 0 and i > 0:  # 每10秒记录一次
                    logger.debug(f"健康检查尝试 {i+1}/{max_wait_time}: 连接被拒绝（后端可能还在启动中）")
            except requests.exceptions.Timeout:
                # 超时
                if i % 10 == 0:  # 每10秒记录一次
                    logger.debug(f"健康检查尝试 {i+1}/{max_wait_time}: 请求超时")
            except Exception as e:
                logger.warning(f"健康检查异常: {e}")
            
            time.sleep(1)
        
        if not healthy:
            # 关闭日志文件并尝试读取错误信息
            backend_log.close()
            error_msg = ''
            try:
                with open(backend_log_file, 'r') as f:
                    lines = f.readlines()
                    error_msg = ''.join(lines[-100:])  # 读取最后100行
            except:
                pass
            
            # 检查进程状态
            process_status = backend_process.poll()
            if process_status is None:
                # 进程还在运行，但健康检查失败
                logger.error(f"健康检查失败：后端进程仍在运行 (PID: {backend_process.pid})，但无法访问健康检查端点")
                logger.error("可能的原因：端口未监听、健康检查端点异常、或启动时间过长")
            else:
                # 进程已退出
                logger.error(f"健康检查失败：后端进程已退出，退出码: {process_status}")
            
            if error_msg:
                logger.error(f"后端启动日志（最后100行）:\n{error_msg}")
            
            logger.error("健康检查失败，开始回滚...")
            update_status['message'] = '健康检查失败，回滚中...'
            
            # 确保后端进程已停止
            try:
                backend_process.terminate()
                backend_process.wait(timeout=5)
            except:
                subprocess.run(['pkill', '-9', '-f', 'java.*app.jar'], check=False)
            
            restore_backup(backup_dir)
            
            # 等待一下再重启
            time.sleep(2)
            
            # 重启后端（使用旧版本）
            logger.info("重启旧版本后端服务...")
            subprocess.Popen(
                backend_command(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            
            subprocess.run(['nginx', '-s', 'reload'], check=True)

            rollback_done = True
            
            raise Exception(f"健康检查失败（等待了 {max_wait_time} 秒），已回滚到旧版本。请查看日志文件 {backend_log_file} 了解详情")
        
        # 清理临时文件；清理失败不应把已经健康的新版本误报为更新失败。
        try:
            if download_path.exists():
                download_path.unlink()
            if extract_dir.exists():
                shutil.rmtree(extract_dir)
        except Exception as cleanup_error:
            logger.warning(f"更新成功，但清理临时文件失败: {cleanup_error}")

        update_status['progress'] = 100
        update_status['message'] = f'更新成功：{tag}'
        logger.info(f"更新成功：{tag}")
        update_completed = True
        
    except Exception as e:
        logger.error(f"更新失败: {e}")
        if replacement_started and not rollback_done and not update_completed and backup_dir is not None:
            update_status['message'] = '更新失败，回滚中...'
            try:
                rollback_update(backup_dir)
                rollback_done = True
                logger.info("更新失败后已自动回滚")
            except Exception as rollback_error:
                logger.error(f"自动回滚失败: {rollback_error}", exc_info=True)
                e = Exception(f"{e}; 自动回滚失败: {rollback_error}")
        update_status['error'] = str(e)
        if rollback_done:
            update_status['message'] = f'更新失败，已回滚: {str(e)}'
        else:
            update_status['message'] = f'更新失败: {str(e)}'
    finally:
        update_status['updating'] = False


# ==================== API 路由 ====================

@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    return jsonify({'code': 0, 'data': 'ok', 'message': 'success'})


@app.route('/version', methods=['GET'])
def version():
    """获取当前版本"""
    try:
        if VERSION_FILE.exists():
            with open(VERSION_FILE) as f:
                data = json.load(f)
                return jsonify({
                    'code': 0,
                    'data': {
                        'version': data.get('version', 'unknown'),
                        'tag': data.get('tag', 'unknown'),
                        'buildTime': data.get('buildTime', '')
                    },
                    'message': 'success'
                })
        else:
            return jsonify({
                'code': 0,
                'data': {
                    'version': 'unknown',
                    'tag': 'unknown',
                    'buildTime': ''
                },
                'message': 'success'
            })
    except Exception as e:
        logger.error(f"获取版本失败: {e}")
        return jsonify({
            'code': 500,
            'data': None,
            'message': str(e)
        }), 500


@app.route('/check', methods=['GET'])
def check():
    """检查更新"""
    try:
        current_version = get_current_version()
        release = fetch_latest_release()
        
        if not release:
            return jsonify({
                'code': 500,
                'data': None,
                'message': '无法获取 Release 信息'
            }), 500
        
        latest_tag = release['tag']
        latest_version = latest_tag.lstrip('v')
        
        has_update = compare_versions(latest_version, current_version) > 0
        
        return jsonify({
            'code': 0,
            'data': {
                'hasUpdate': has_update,
                'currentVersion': current_version,
                'latestVersion': latest_version,
                'latestTag': latest_tag,
                'releaseNotes': release.get('body', ''),
                'publishedAt': release.get('published_at', ''),
                'prerelease': release.get('prerelease', False)
            },
            'message': 'success'
        })
        
    except Exception as e:
        logger.error(f"检查更新失败: {e}")
        return jsonify({
            'code': 500,
            'data': None,
            'message': str(e)
        }), 500


@app.route('/update', methods=['POST'])
def update():
    """执行更新（需要管理员权限）"""
    
    # 权限检查
    if not check_admin_permission(request):
        return jsonify({
            'code': 403,
            'data': None,
            'message': '需要管理员权限'
        }), 403
    
    # 在启动线程前原子占用更新状态，避免并发请求同时启动多个更新。
    with update_lock:
        if update_status['updating']:
            return jsonify({
                'code': 409,
                'data': None,
                'message': '正在更新中，请稍后'
            }), 409
        update_status.update({
            'updating': True,
            'progress': 0,
            'message': '开始更新...',
            'error': None
        })

    try:
        # 异步执行更新
        thread = Thread(target=perform_update, args=('latest',))
        thread.start()
    except Exception as e:
        with update_lock:
            update_status.update({
                'updating': False,
                'message': f'启动更新失败: {e}',
                'error': str(e)
            })
        logger.error(f"启动更新线程失败: {e}", exc_info=True)
        return jsonify({
            'code': 500,
            'data': None,
            'message': str(e)
        }), 500

    return jsonify({
        'code': 0,
        'data': '更新已启动',
        'message': 'success'
    })


@app.route('/status', methods=['GET'])
def status():
    """获取更新状态"""
    return jsonify({
        'code': 0,
        'data': {
            'updating': update_status['updating'],
            'progress': update_status['progress'],
            'message': update_status['message'],
            'error': update_status['error']
        },
        'message': 'success'
    })


@app.route('/logs', methods=['GET'])
def logs():
    """获取更新日志（需要管理员权限）"""
    
    # 权限检查
    if not check_admin_permission(request):
        return jsonify({
            'code': 403,
            'data': None,
            'message': '需要管理员权限'
        }), 403
    
    try:
        if LOG_FILE.exists():
            with open(LOG_FILE) as f:
                lines = f.readlines()
                return jsonify({
                    'code': 0,
                    'data': ''.join(lines[-1000:]),  # 最后1000行
                    'message': 'success'
                })
        return jsonify({
            'code': 0,
            'data': '',
            'message': 'success'
        })
    except Exception as e:
        logger.error(f"获取日志失败: {e}")
        return jsonify({
            'code': 500,
            'data': None,
            'message': str(e)
        }), 500


# ==================== 主程序 ====================

if __name__ == '__main__':
    logger.info("=" * 50)
    logger.info("PolyHermes 更新服务启动")
    logger.info(f"GitHub 仓库: {GITHUB_REPO}")
    logger.info(f"允许 Pre-release: {ALLOW_PRERELEASE}")
    logger.info(f"当前版本: {get_current_version()}")
    logger.info("=" * 50)
    
    # 启动 Flask 服务
    app.run(host='0.0.0.0', port=9090, debug=False)
