#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
publish_gitee.py — 把「合规分发版」APK 自动发布到 Gitee Release（国内高速下载源）

背景:
  GitHub Releases 在国内下载极慢(免费代理实测 0.09~0.35 MB/s), Gitee Release
  附件实测 ~3.4 MB/s 且完全免费。本脚本把上传流程自动化: 打包完成后一条命令
  即可上传/覆盖, 重复执行安全(幂等)。

Gitee 社区版(个人免费)配额:
  * 附件单文件   <= 100 MB      (本脚本硬校验, 超限拒绝)
  * 单仓库附件总量 <= 1 GB       (≈14 个 70MB 版本, 用 --prune N 清理旧包)
  * 单文件超限时可申请 GVP 项目(200MB), 或改用腾讯云 COS

用法:
  # 0. 首次配置(只需一次):
  #    Gitee -> 头像 -> 设置 -> 私人令牌 -> 生成新令牌 -> 勾选 projects 权限
  setx GITEE_TOKEN "你的令牌"

  # 1. 自动挑选 outputs/ 里最新的分发版 APK 并发布
  python publish_gitee.py

  # 2. 指定文件 / tag / 说明
  python publish_gitee.py --apk outputs/QingSongScan-v1.4.22-51-release.apk ^
      --tag v1.4.22 --notes "连拍四项 + 返回修复 + 黑白增强"

  # 3. 只检查不落地(验证令牌/仓库/tag/附件占用)
  python publish_gitee.py --dry-run

  # 4. 查看线上 release 与附件占用(1GB 配额管理)
  python publish_gitee.py --list

  # 5. 上传后只保留最近 N 个 release(删除更早的, 防配额爆掉)
  python publish_gitee.py --prune 10

安全设计(重要, 勿绕过):
  * 令牌只从环境变量 GITEE_TOKEN 读取, 不写入任何文件, 不打印到日志
  * 文件名含 selfuse 的包一律拒绝(自用版含 GPL-3.0 分割模型, 不得外发)
  * 文件 >100MB 一律拒绝(Gitee 社区版单附件硬上限)
  * 覆盖上传采用「先删后传」, 重复执行不会产生重复附件

上传完成后脚本会打印直链, 需把它加进落地页下载卡片 + 后端 DL_HOSTS 白名单
(见输出提示), 再重新发布页面即可对用户可见。
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://gitee.com/api/v5"
GH_API = "https://api.github.com"
MAX_ASSET_BYTES = 100 * 1024 * 1024   # Gitee 社区版单附件上限
REPO_ATTACH_LIMIT = 1024 * 1024 * 1024  # 社区版单仓库附件总量上限
CURL_TIMEOUT = 600                      # 70MB 文件放宽到 10 分钟
DEFAULT_REPO = "zwhapp/qingsongscan-apk"     # Gitee 仓库(已建: 公开/master/有README, 2026-10-24 验证)
DEFAULT_GH_REPO = "zwhapp/qingsongsaomiao"  # GitHub 规范发布页(取发布日志)

# 工程根目录 = 本脚本所在 tools/release 的上两级
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def die(code, msg):
    print("[ERROR] " + msg)
    sys.exit(code)


def human(n):
    if n is None:
        return "?"
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "%.1f %s" % (n, unit) if unit != "B" else "%d B" % n
        n /= 1024.0


def api(method, path, token=None, body=None, timeout=30):
    """调用 JSON API。path 可以是完整 URL。返回 (http_status, data)。"""
    url = path if path.startswith("http") else API + path
    if token:
        url += ("&" if "?" in url else "?") + "access_token=" + urllib.parse.quote(token, safe="")
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw
    except Exception as e:  # 网络不通/DNS 等
        return -1, str(e)


def gitee_msg(status, data):
    if isinstance(data, dict) and data.get("message"):
        return str(data["message"])
    return str(data)[:200] if data else "无响应体"


# ---------------------------------------------------------------- 本地检查

def resolve_apk(arg):
    """确定要上传的 APK: 显式 --apk 优先, 否则取 outputs/ 最新的分发版。"""
    if arg:
        for cand in (arg, os.path.join(ROOT, arg)):
            if os.path.isfile(cand):
                return os.path.abspath(cand)
        die(2, "找不到 APK 文件: %s" % arg)
    cands = [p for p in glob.glob(os.path.join(ROOT, "outputs", "QingSongScan-v*-release.apk"))
             if "selfuse" not in os.path.basename(p).lower()]
    if not cands:
        die(2, "outputs/ 下没有分发版 APK(QingSongScan-v*-release.apk)。"
               "请先 gradlew assembleRelease -PversionName=x.y.z, 或用 --apk 指定。")
    return max(cands, key=os.path.getmtime)


def version_from_name(fn):
    m = re.search(r"-v(\d+(?:\.\d+)*)-(?:selfuse-)?\d+-release\.apk$", fn, re.I)
    return m.group(1) if m else None


def local_guards(apk):
    """上传前的本地硬校验: 自用版拒发 / 超限拒发。"""
    base = os.path.basename(apk)
    if "selfuse" in base.lower():
        die(3, "拒绝上传: %s 是自用版(含 GPL-3.0 分割模型), 不得外发。" % base)
    size = os.path.getsize(apk)
    if size > MAX_ASSET_BYTES:
        die(4, "拒绝上传: 文件 %s 超过 Gitee 单附件 100MB 上限(%s)。"
               "可申请 GVP 项目(200MB)或改用腾讯云 COS。" % (human(size), human(size)))
    return size


def resolve_notes(args, tag):
    if args.notes:
        return args.notes
    if args.notes_file:
        p = args.notes_file if os.path.isfile(args.notes_file) else os.path.join(ROOT, args.notes_file)
        if os.path.isfile(p):
            with open(p, "r", encoding="utf-8") as f:
                return f.read()
    if not args.no_gh_notes:
        st, d = api("GET", "%s/repos/%s/releases/tags/%s" % (GH_API, args.gh_repo, urllib.parse.quote(tag)))
        if st == 200 and isinstance(d, dict) and d.get("body"):
            return (d["body"] +
                    "\n\n---\n本附件为国内加速镜像, 规范发布页: "
                    "https://github.com/%s/releases/tag/%s" % (args.gh_repo, tag))
    return ("轻松扫描 %s 安装包(国内加速下载)。\n发布日志: "
            "https://github.com/%s/releases/tag/%s" % (tag, args.gh_repo, tag))


# ---------------------------------------------------------------- Gitee 操作

def validate_token(token):
    st, d = api("GET", "/user", token=token)
    if st == 200 and isinstance(d, dict) and d.get("login"):
        print("令牌有效: %s (%s)" % (d.get("name") or d.get("login"), d.get("login")))
        return
    if st == 401:
        die(2, "Gitee 返回 401: 令牌无效或已撤销。\n"
               "  请到 https://gitee.com/profile/personal_access_tokens 重新生成"
               "(勾选 projects 权限),\n  然后 setx GITEE_TOKEN \"新令牌\" 并重开终端。")
    die(6, "校验令牌失败: HTTP %s %s" % (st, gitee_msg(st, d)))


def check_repo(token, owner, repo):
    st, d = api("GET", "/repos/%s/%s" % (owner, repo), token=token)
    if st == 200 and isinstance(d, dict):
        print("仓库就绪: https://gitee.com/%s/%s (默认分支 %s)"
              % (owner, repo, d.get("default_branch") or "master"))
        return d.get("default_branch") or "master"
    if st == 404:
        die(5, "Gitee 仓库 %s/%s 匿名不可见(不存在或为私有)。\n"
               "  私有仓库的 Release 链接游客无法下载, 做国内下载源必须是公开仓库:\n"
               "  a) 已创建但为私有: 仓库主页 -> 管理 -> 仓库设置 -> 可见性改为\"公开\"\n"
               "  b) 还没建: 新建公开仓库(建议勾选\"初始化仓库\"生成 README)\n"
               "  然后用 --repo %s/%s 或环境变量 GITEE_REPO 指定。"
               % (owner, repo, owner, repo))
    die(6, "查询仓库失败: HTTP %s %s" % (st, gitee_msg(st, d)))


def get_release_by_tag(token, owner, repo, tag):
    st, d = api("GET", "/repos/%s/%s/releases/tags/%s" % (owner, repo, urllib.parse.quote(tag)), token=token)
    if st == 200 and isinstance(d, dict) and d.get("id"):
        return d
    return None


def create_release(token, owner, repo, tag, name, body, branch):
    payload = {"tag_name": tag, "target_commitish": branch, "name": name,
               "body": body, "prerelease": False}
    st, d = api("POST", "/repos/%s/%s/releases" % (owner, repo), token=token, body=payload)
    if st in (200, 201) and isinstance(d, dict) and d.get("id"):
        return d
    die(6, "创建 Release 失败: HTTP %s %s" % (st, gitee_msg(st, d)))


def list_assets(token, owner, repo, release_id):
    st, d = api("GET", "/repos/%s/%s/releases/%d/attach_files" % (owner, repo, release_id), token=token)
    return d if (st == 200 and isinstance(d, list)) else []


def delete_asset(token, owner, repo, release_id, asset_id, name):
    st, _ = api("DELETE", "/repos/%s/%s/releases/%d/attach_files/%d" % (owner, repo, release_id, asset_id),
                token=token)
    if st in (200, 204):
        print("  已删除同名旧附件: %s" % name)
    else:
        print("  [警告] 删除旧附件失败(HTTP %s): %s, 将继续尝试上传" % (st, name))


def upload_asset(token, owner, repo, release_id, apk):
    """multipart 上传(借 curl 实现, 避免引入 requests 依赖)。"""
    url = "%s/repos/%s/%s/releases/%d/attach_files" % (API, owner, repo, release_id)
    cmd = ["curl", "-sS", "-m", str(CURL_TIMEOUT), "-w", "\n%{http_code}",
           "-F", "access_token=%s" % token,
           "-F", "file=@%s" % apk,
           url]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=CURL_TIMEOUT + 30)
    except FileNotFoundError:
        die(6, "找不到 curl 命令。Windows 10+ 自带 curl.exe; 请确认其在 PATH 中。")
    out = (r.stdout or "").strip()
    if "\n" in out:
        body, _, code_s = out.rpartition("\n")
    else:
        body, code_s = out, ""
    try:
        code = int(code_s.strip())
    except ValueError:
        code = -1
    data = None
    try:
        data = json.loads(body)
    except ValueError:
        pass
    return code, data, (r.stderr or "").strip()


def prune_releases(token, owner, repo, keep):
    st, d = api("GET", "/repos/%s/%s/releases?per_page=100" % (owner, repo), token=token)
    rels = d if (st == 200 and isinstance(d, list)) else []
    rels.sort(key=lambda r: r.get("created_at") or "", reverse=True)
    if len(rels) <= keep:
        print("清理: 共 %d 个 release, 未超过保留数 %d, 无需删除。" % (len(rels), keep))
        return
    for r in rels[keep:]:
        print("  删除旧 release: %s (%s, %s)" % (r.get("tag_name"), r.get("name"), r.get("created_at", "")[:10]))
        st2, _ = api("DELETE", "/repos/%s/%s/releases/%d" % (owner, repo, r["id"]), token=token)
        if st2 not in (200, 204):
            print("  [警告] 删除失败: HTTP %s" % st2)


def show_inventory(token, owner, repo):
    st, d = api("GET", "/repos/%s/%s/releases?per_page=100" % (owner, repo), token=token)
    if st != 200 or not isinstance(d, list):
        die(6, "拉取 release 列表失败: HTTP %s %s" % (st, gitee_msg(st, d)))
    rels = sorted(d, key=lambda r: r.get("created_at") or "", reverse=True)
    total = 0
    print("%-14s %-12s %8s  %s" % ("TAG", "日期", "附件数", "占用"))
    print("-" * 52)
    for r in rels:
        assets = list_assets(token, owner, repo, r["id"])
        size = sum(a.get("size") or 0 for a in assets)
        total += size
        print("%-14s %-12s %8d  %s" % (r.get("tag_name"), (r.get("created_at") or "")[:10],
                                       len(assets), human(size)))
    print("-" * 52)
    print("附件总占用: %s / 1 GB (社区版单仓库上限)" % human(total))
    if total > REPO_ATTACH_LIMIT * 0.8:
        print("[提示] 占用已超 80%%, 建议尽快 --prune N 清理旧版本。")


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser(
        description="把合规分发版 APK 自动发布到 Gitee Release(国内加速下载源)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="令牌从环境变量 GITEE_TOKEN 读取(Gitee 私人令牌, projects 权限)。")
    ap.add_argument("--apk", help="APK 路径; 缺省自动取 outputs/ 最新的分发版")
    ap.add_argument("--repo", default=os.environ.get("GITEE_REPO", DEFAULT_REPO),
                    help="Gitee 仓库 owner/name (缺省 %(default)s)")
    ap.add_argument("--gh-repo", default=DEFAULT_GH_REPO,
                    help="GitHub 规范发布页 owner/name, 用于同步发布日志 (缺省 %(default)s)")
    ap.add_argument("--tag", help="Release tag; 缺省从文件名推导, 如 v1.4.22")
    ap.add_argument("--name", help="Release 名称; 缺省\"轻松扫描 <tag>\"")
    ap.add_argument("--notes", help="Release 说明(直接文本)")
    ap.add_argument("--notes-file", help="Release 说明(文件路径, 如 CHANGELOG 摘录)")
    ap.add_argument("--no-gh-notes", action="store_true", help="不从 GitHub 同步发布日志")
    ap.add_argument("--dry-run", action="store_true", help="只检查不落地")
    ap.add_argument("--list", action="store_true", dest="list_only", help="查看线上 release 与附件占用")
    ap.add_argument("--prune", type=int, default=0, metavar="N",
                    help="上传后只保留最近 N 个 release, 删除更早的(防 1GB 配额爆掉)")
    args = ap.parse_args()

    token = os.environ.get("GITEE_TOKEN", "").strip()
    if not token:
        die(2, "未设置环境变量 GITEE_TOKEN。\n"
               "  1. 打开 https://gitee.com/profile/personal_access_tokens 生成令牌"
               "(勾选 projects 权限)\n"
               "  2. setx GITEE_TOKEN \"你的令牌\" 并重开终端\n"
               "  注意: 令牌只放环境变量, 不要写进任何脚本或提交进仓库。")
    if "/" not in args.repo:
        die(2, "--repo 需要 owner/name 格式, 当前: %s" % args.repo)
    owner, repo = args.repo.split("/", 1)

    if args.list_only:
        validate_token(token)
        show_inventory(token, owner, repo)
        return

    # 本地校验先行(不依赖网络), 再做令牌/仓库等远端检查
    apk = resolve_apk(args.apk)
    base = os.path.basename(apk)
    size = local_guards(apk)
    version = version_from_name(base) or "unknown"
    tag = args.tag or ("v" + version)
    name = args.name or ("轻松扫描 " + tag)
    body = resolve_notes(args, tag)
    print("待发布: %s (%s)" % (base, human(size)))
    print("目标:   https://gitee.com/%s/%s/releases/tag/%s" % (owner, repo, tag))

    validate_token(token)

    branch = check_repo(token, owner, repo)
    rel = get_release_by_tag(token, owner, repo, tag)
    if rel:
        release_id = rel["id"]
        print("Release 已存在(id=%d), 将覆盖上传同名附件。" % release_id)
    else:
        release_id = None
        print("Release 不存在, 将新建(tag=%s, 分支=%s)。" % (tag, branch))

    existing = list_assets(token, owner, repo, release_id) if release_id else []
    same = [a for a in existing if a.get("name") == base]

    if args.dry_run:
        print("\n--- DRY-RUN(不落地) ---")
        print("将上传: %s (%s)" % (base, human(size)))
        print("将删除同名旧附件: %s" % (", ".join(a["name"] for a in same) if same else "无"))
        print("直链将是: https://gitee.com/%s/%s/releases/download/%s/%s" % (owner, repo, tag, base))
        print("页面侧待办: 下载卡片加该直链 + 后端 DL_HOSTS 白名单加 gitee.com, 然后重新发布页面。")
        return

    if not release_id:
        rel = create_release(token, owner, repo, tag, name, body, branch)
        release_id = rel["id"]
        print("Release 已创建: id=%d" % release_id)

    for a in same:
        delete_asset(token, owner, repo, release_id, a["id"], a["name"])

    print("上传中(%s)..." % human(size))
    code, data, err = upload_asset(token, owner, repo, release_id, apk)
    if code in (200, 201) and isinstance(data, dict) and data.get("name") == base:
        pass
    elif code in (200, 201):
        # 个别情况下返回体不含 name, 以状态码为准, 再拉一次列表确认
        ok = any(a.get("name") == base for a in list_assets(token, owner, repo, release_id))
        if not ok:
            die(7, "上传返回 HTTP %s 但附件列表未见 %s。响应: %s %s" % (code, base, data, err))
    else:
        die(7, "上传失败: HTTP %s %s %s" % (code, data, err))

    direct = "https://gitee.com/%s/%s/releases/download/%s/%s" % (owner, repo, tag, base)
    print("\n上传完成!")
    print("  直链: %s" % direct)
    print("  页面: https://gitee.com/%s/%s/releases/tag/%s" % (owner, repo, tag))
    print("\n下一步(让用户能用到):")
    print("  1. 落地页下载区加一张卡片: 名称「Gitee 极速下载」, 地址填上面直链")
    print("  2. 后端 DL_HOSTS 白名单加 \"gitee.com\" 并重新部署 qingsong-site-api")
    print("  3. 重新发布落地页")

    if args.prune and args.prune > 0:
        print("\n清理旧 release(保留最近 %d 个)..." % args.prune)
        prune_releases(token, owner, repo, args.prune)


if __name__ == "__main__":
    main()
