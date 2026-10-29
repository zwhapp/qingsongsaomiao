#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
release_all.py — 「轻松扫描」一键全自动发布流水线

一条命令完成: 打包 → GitHub Release → Gitee Release → (网页自动同步)

  python release_all.py --version 1.4.23
  python release_all.py --version 1.4.23 --notes "连拍修复 + 界面优化"
  python release_all.py --version 1.4.23 --dry-run     # 只检查不落地

流程与前置:
  1. 环境检查: gh 已安装且已登录(gh auth login); GITEE_TOKEN 已设置
  2. 打包: gradlew assembleRelease -PversionName=<version>
     (JAVA_HOME 自动设为 D:/Android/JDK17, versionCode 自动 +1 并写回 build.gradle.kts)
  3. 守卫: selfuse 包拒发; >100MB 拒发(Gitee 社区版单附件上限)
  4. GitHub: gh release create / upload --clobber (幂等)
  5. Gitee: 复用 tools/release/publish_gitee.py
  6. 本地打 tag 保持同步
  7. 打印两平台 Release 链接

网页无需任何操作:
  落地页的版本号/大小/更新日志由后端 /version 从 GitHub Releases API 拉取
  (10 分钟缓存), Gitee 下载链按同 tag 自动推导 —— release 发布后网页自动更新。

与旧流程的关系:
  publish_gitee.py 仍可单独使用(只发 Gitee); 本脚本是其超集。
"""

import argparse
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))          # 工程根(DocScanPro)
GRADLEW = os.path.join(ROOT, "gradlew.bat")
GRADLE_KTS = os.path.join(ROOT, "app", "build.gradle.kts")
JAVA_HOME = "D:/Android/JDK17"
MAX_ASSET = 100 * 1024 * 1024
GITEE_SCRIPT = os.path.join(HERE, "publish_gitee.py")


def info(msg):
    print("[..] " + msg)


def ok(msg):
    print("[OK] " + msg)


def warn(msg):
    print("[!!] " + msg)


def die(code, msg):
    print("[ERROR] " + msg)
    sys.exit(code)


def run(cmd, cwd=ROOT, check=True, shell=False):
    """执行命令, 实时透传输出。check=True 时失败即退出。"""
    print("      $ " + " ".join(cmd) if isinstance(cmd, list) else "      $ " + cmd)
    r = subprocess.call(cmd, cwd=cwd, shell=shell)
    if check and r != 0:
        die(10, "命令执行失败(exit=%d): %s" % (r, cmd[0] if isinstance(cmd, list) else cmd))
    return r


def find_gh():
    """定位 gh.exe: PATH -> 默认安装路径。"""
    from shutil import which
    p = which("gh") or which("gh.exe")
    if p:
        return p
    default = r"C:\Program Files\GitHub CLI\gh.exe"
    if os.path.exists(default):
        return default
    return None


def check_env():
    """环境前置检查, 返回 gh 路径。"""
    gh = find_gh()
    if not gh:
        die(2, "未找到 gh (GitHub CLI)。安装: winget install GitHub.cli\n"
               "      然后执行: gh auth login  (浏览器授权, 无需 PAT)")
    # gh auth status 非零=未登录
    r = subprocess.call([gh, "auth", "status"], cwd=ROOT,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if r != 0:
        die(3, "gh 未登录。请先执行: gh auth login\n"
               "      (选择 GitHub.com -> SSH 或 HTTPS -> 浏览器授权)")
    ok("gh 已安装且已登录")

    tok = os.environ.get("GITEE_TOKEN") or _persisted_env("GITEE_TOKEN")
    if not tok:
        die(4, "GITEE_TOKEN 未设置。请执行:\n"
               "      setx GITEE_TOKEN \"你的令牌\"  (然后重开终端)\n"
               "      令牌获取: gitee.com/profile/personal_access_tokens (勾选 projects)")
    ok("GITEE_TOKEN 已设置 (%d 字符)" % len(tok))
    return gh


def _persisted_env(name):
    """从 Windows 用户级持久化环境变量读(当前进程可能没继承到)。"""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
            v, _ = winreg.QueryValueEx(k, name)
            return v or None
    except Exception:
        return None


def git_clean_check():
    r = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT,
                       capture_output=True, text=True)
    dirty = [l for l in r.stdout.splitlines() if l.strip()]
    if dirty:
        warn("工作区有 %d 个未提交变更(不影响发布, 但建议发布前先提交):" % len(dirty))
        for l in dirty[:6]:
            print("        " + l)
        if len(dirty) > 6:
            print("        ... 共 %d 个" % len(dirty))
    else:
        ok("git 工作区干净")


def bump_version_code(dry):
    """读 build.gradle.kts 的 versionCode 并 +1 写回, 返回 (old, new)。"""
    src = open(GRADLE_KTS, "r", encoding="utf-8").read()
    m = re.search(r"versionCode\s*=\s*(\d+)", src)
    if not m:
        die(5, "在 app/build.gradle.kts 中找不到 versionCode")
    old = int(m.group(1))
    new = old + 1
    if dry:
        info("dry-run: versionCode %d -> %d (不写回)" % (old, new))
        return old, new
    out = src[:m.start(1)] + str(new) + src[m.end(1):]
    open(GRADLE_KTS, "w", encoding="utf-8", newline="").write(out)
    ok("versionCode %d -> %d (已写回 app/build.gradle.kts)" % (old, new))
    return old, new


def build(version, dry):
    if dry:
        info("dry-run: 跳过打包 (gradlew assembleRelease -PversionName=%s)" % version)
        return None
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    if not os.path.exists(JAVA_HOME):
        die(6, "JAVA_HOME 不存在: %s (本工程必须用 JDK17)" % JAVA_HOME)
    info("开始打包 (release, versionName=%s) ..." % version)
    r = subprocess.call([GRADLEW, "assembleRelease", "-PversionName=" + version],
                        cwd=ROOT, env=env)
    if r != 0:
        die(7, "打包失败, 请检查上方 Gradle 输出")
    # 找产物: outputs/ 优先(归档), 其次 build/outputs
    cands = []
    for d in (os.path.join(ROOT, "outputs"),
              os.path.join(ROOT, "app", "build", "outputs", "apk", "release")):
        if os.path.isdir(d):
            cands += [os.path.join(d, f) for f in os.listdir(d)
                      if f.endswith(".apk") and "release" in f]
    # 只留本次版本且非 selfuse
    mine = [p for p in cands
            if ("v%s-" % version) in os.path.basename(p)
            and "selfuse" not in os.path.basename(p)]
    if not mine:
        die(8, "打包后找不到产物 (期望文件名含 v%s- 且不含 selfuse)。\n"
               "      已找目录: outputs/ 与 app/build/outputs/apk/release/" % version)
    apk = max(mine, key=os.path.getmtime)
    size = os.path.getsize(apk)
    ok("打包完成: %s (%.1f MB)" % (os.path.basename(apk), size / 1048576))
    return apk


def guard(apk):
    """安全守卫: selfuse 拒发 + 体积上限。"""
    if apk is None:
        return None
    base = os.path.basename(apk)
    if "selfuse" in base:
        die(20, "安全拦截: %s 是自用版(含 GPL-3.0 模型), 禁止发布!" % base)
    size = os.path.getsize(apk)
    if size > MAX_ASSET:
        die(21, "安全拦截: %.1f MB 超过 Gitee 单附件 100MB 上限" % (size / 1048576))
    ok("守卫通过: 分发版, %.1f MB < 100MB" % (size / 1048576))
    return size


def extract_notes(version, notes_arg):
    """release 说明: --notes 参数 > CHANGELOG.md 的「未发布」段 > 默认文案。"""
    if notes_arg:
        return notes_arg
    cl = os.path.join(ROOT, "CHANGELOG.md")
    if os.path.exists(cl):
        src = open(cl, "r", encoding="utf-8").read()
        m = re.search(r"^## 未发布\s*\n(.*?)(?=^## |\Z)", src, re.S | re.M)
        if m:
            body = m.group(1).strip()
            if body:
                return body
    return "轻松扫描 v%s 发布。" % version


def github_release(gh, version, apk, tag, notes, dry):
    """gh release create; 已存在则 upload --clobber。"""
    title = "轻松扫描 v%s" % version
    if dry:
        info("dry-run: GitHub Release 计划: tag=%s title=%s asset=%s"
             % (tag, title, os.path.basename(apk) if apk else "(无)"))
        return
    # release 是否已存在
    r = subprocess.run([gh, "release", "view", tag], cwd=ROOT,
                       capture_output=True, text=True)
    if r.returncode == 0:
        info("GitHub Release %s 已存在, 覆盖上传 asset ..." % tag)
        run([gh, "release", "upload", tag, apk, "--clobber"])
    else:
        info("创建 GitHub Release %s ..." % tag)
        nf = os.path.join(ROOT, "outputs", "_release_notes_%s.md" % tag)
        os.makedirs(os.path.dirname(nf), exist_ok=True)
        open(nf, "w", encoding="utf-8").write(notes + "\n")
        try:
            run([gh, "release", "create", tag, apk, "--title", title,
                 "--notes-file", nf])
        finally:
            try:
                os.remove(nf)
            except OSError:
                pass
    ok("GitHub Release 完成: https://github.com/zwhapp/qingsongsaomiao/releases/tag/%s" % tag)


def gitee_release(version, apk, tag, notes, dry):
    """复用 publish_gitee.py (自带 selfuse/100MB 守卫与幂等逻辑)。"""
    if apk is None:
        info("dry-run: 跳过 Gitee 上传")
        return
    cmd = [sys.executable, GITEE_SCRIPT, "--apk", apk, "--tag", tag]
    if notes and notes != "轻松扫描 v%s 发布。" % version:
        cmd += ["--notes", notes]
    if dry:
        cmd += ["--dry-run"]
    info("调用 publish_gitee.py ...")
    r = subprocess.call(cmd, cwd=ROOT)
    if r != 0:
        die(30, "Gitee 上传失败 (exit=%d), GitHub 侧已发布——可修后单独重跑:\n"
                "      python tools/release/publish_gitee.py --apk %s --tag %s"
                % (r, apk, tag))
    ok("Gitee Release 完成: https://gitee.com/zwhapp/qingsongscan-apk/releases/tag/%s" % tag)


def local_tag(tag, dry):
    """本地打 tag 与远端同步(已存在则跳过)。"""
    r = subprocess.run(["git", "rev-parse", "-q", "--verify", "refs/tags/" + tag],
                       cwd=ROOT, capture_output=True, text=True)
    if r.returncode == 0:
        info("本地 tag %s 已存在, 跳过" % tag)
        return
    if dry:
        info("dry-run: 将创建本地 tag %s (不推送)" % tag)
        return
    r = subprocess.call(["git", "tag", tag], cwd=ROOT)
    if r != 0:
        warn("本地打 tag 失败(不影响已发布的 release)")
        return
    # tag 推送走 SSH(已验证可用); 失败只警告
    r = subprocess.call(["git", "push", "origin", tag], cwd=ROOT,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if r == 0:
        ok("本地 tag %s 已创建并推送" % tag)
    else:
        warn("本地 tag 已创建但推送失败(不影响 release, 可稍后 git push origin %s)" % tag)


def main():
    ap = argparse.ArgumentParser(description="一键发布: 打包 -> GitHub -> Gitee -> 网页自动同步")
    ap.add_argument("version_pos", nargs="?", default=None,
                    help="分发版版本号, 如 1.4.23 (也可用 --version 指定)")
    ap.add_argument("--version", dest="version_opt", default=None,
                    help="分发版版本号(位置参数的等价写法)")
    ap.add_argument("--notes", default=None, help="Release 说明(默认取 CHANGELOG.md 未发布段)")
    ap.add_argument("--dry-run", action="store_true", help="只检查不落地")
    ap.add_argument("--no-build", action="store_true",
                    help="跳过打包(用已有产物, 需能按版本号找到 apk)")
    ap.add_argument("--code", type=int, default=None,
                    help="手动指定 versionCode(默认自动 +1 写回 build.gradle.kts)")
    args = ap.parse_args()

    version_raw = args.version_opt or args.version_pos
    if not version_raw:
        ap.error("必须提供版本号, 如: release_all.py 1.4.23  或  --version 1.4.23")
    version = version_raw.strip().lstrip("v")
    if not re.match(r"^\d+\.\d+(\.\d+)?$", version):
        die(1, "版本号格式应为 x.y 或 x.y.z, 收到: %s" % version)
    tag = "v" + version

    print("=" * 62)
    print("  轻松扫描一键发布   v%s   (tag: %s)%s" % (version, tag, "  [DRY-RUN]" if args.dry_run else ""))
    print("=" * 62)

    gh = check_env()
    git_clean_check()

    if args.code is not None:
        info("手动指定 versionCode=%d (不自动 +1)" % args.code)
        code = args.code
    else:
        _, code = bump_version_code(args.dry_run)

    if args.no_build:
        info("--no-build: 跳过打包, 查找已有产物 ...")
        apk = None
        for d in (os.path.join(ROOT, "outputs"),
                  os.path.join(ROOT, "app", "build", "outputs", "apk", "release")):
            if os.path.isdir(d):
                for f in os.listdir(d):
                    if ("v%s-" % version) in f and f.endswith(".apk") and "selfuse" not in f:
                        p = os.path.join(d, f)
                        if apk is None or os.path.getmtime(p) > os.path.getmtime(apk):
                            apk = p
        if apk is None:
            die(9, "--no-build 但找不到 v%s 的既有产物" % version)
        ok("使用已有产物: %s" % os.path.basename(apk))
    else:
        apk = build(version, args.dry_run)

    guard(apk)
    notes = extract_notes(version, args.notes)

    github_release(gh, version, apk, tag, notes, args.dry_run)
    gitee_release(version, apk, tag, notes, args.dry_run)
    local_tag(tag, args.dry_run)

    print()
    print("=" * 62)
    if args.dry_run:
        print("  DRY-RUN 完成, 未做任何实际发布。去掉 --dry-run 正式执行。")
    else:
        print("  全部完成!")
        print("  GitHub : https://github.com/zwhapp/qingsongsaomiao/releases/tag/%s" % tag)
        print("  Gitee  : https://gitee.com/zwhapp/qingsongscan-apk/releases/tag/%s" % tag)
        print("  网页   : 自动同步(约10分钟内), 无需操作:")
        print("           https://workbuddy.link/p/qtpAyiex4hgUEH5SKhUIMu")
    print("=" * 62)


if __name__ == "__main__":
    main()
