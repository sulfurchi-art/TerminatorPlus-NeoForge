"""Import saved development checkpoints without rewriting the active checkout."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import urllib.parse
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT / "outputs/TerminatorPlus-NeoForge"
WORK = ROOT / "work"
OUT = ROOT / "outputs"
STAGE = WORK / "github-history-import-20261007"
DELIVERY = WORK / "github-archive-assets-20261007"
BASE = "c1fcf776396455d25bb57000d15f0c082f174498"
REMOTE = "sulfurchi-art/TerminatorPlus-NeoForge"
EXT = Path("E:/codex/claude code/TerminatorPlus-NeoForge/docs")
TEXT_EXT = {".md", ".txt", ".json", ".log", ".py", ".ps1", ".java", ".diff", ".patch", ".properties", ".gradle", ".toml", ".cfg", ".snbt", ".mcfunction", ".mcmeta", ".bat"}

def sha(data):
    return hashlib.sha256(data).hexdigest().upper()

def command(args, cwd=REPO, **kwargs):
    result = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    if result.returncode:
        raise RuntimeError(f"Command failed: {args[:4]}\n{result.stderr.decode('utf-8', 'replace')[-3000:]}")
    return result.stdout.decode("utf-8", "replace").strip()

def git(*args, cwd=STAGE):
    return command(["git", *args], cwd=cwd)

def current_files():
    raw = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=REPO)
    return [n.decode("utf-8") for n in raw.split(b"\0") if n and "__pycache__" not in n.decode("utf-8") and not n.decode("utf-8").endswith(".pyc")]

def work_records():
    return sorted(p for p in WORK.iterdir() if p.is_file() and p.suffix in TEXT_EXT and p.name != Path(__file__).name and not p.name.startswith("github-archive-"))

def output_records():
    return sorted(p for p in OUT.iterdir() if p.is_file() and p.suffix in TEXT_EXT)

def zip_entries(path):
    with zipfile.ZipFile(path) as handle:
        for name in handle.namelist():
            posix = PurePosixPath(name)
            if posix.is_absolute() or ".." in posix.parts or "\\" in name or ":" in name:
                raise RuntimeError(f"Unsafe archive entry in {path.name}")
            if not name.endswith("/"):
                yield name, handle.read(name)

def checkpoints():
    return [(v, WORK / f"TerminatorPlus-NeoForge-4.{v}-source-checkpoint.zip") for v in range(15, 23)]

def benchmark_files():
    bench = WORK / "ai-thread-benchmark"
    roots = [bench / name for name in ("src", "gradle", "extras", "tools")]
    files = [p for r in roots if r.is_dir() for p in r.rglob("*") if p.is_file() and "__pycache__" not in p.parts]
    names = ("build.gradle", "gradle.properties", "settings.gradle", "gradlew", "gradlew.bat", "AGENTS.md", "LICENSE", ".gitignore", ".gitattributes", "benchmark-console.log", "attempt-1-diagnostic/console.log")
    return sorted(set(files + [bench / n for n in names if (bench / n).is_file()]))

def scan(name, data, findings):
    if len(data) > 15_000_000:
        raise RuntimeError(f"Unexpected large text: {name}")
    text = data.decode("utf-8-sig", "replace")
    rules = {
        "github_token": r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{35,})\b",
        "private_key": r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
        "aws_key": r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
        "credential_url": r"https?://[^\s/:]+:[^\s/@]+@",
        "discord_webhook": r"https://(?:discord(?:app)?\.com)/api/webhooks/\d+/[\w-]+",
        "configured_rcon_password": r"(?mi)^[ \t]*rcon[.-]password[ \t]*[=:][ \t]*[^\s]+",
    }
    for rule, pattern in rules.items():
        for match in re.finditer(pattern, text):
            findings.append({"file": name, "line": text.count("\n", 0, match.start()) + 1, "rule": rule})

def inventory():
    findings, items = [], []
    for n in current_files():
        p = REPO / n
        if p.is_file():
            data = p.read_bytes()
            if p.suffix in TEXT_EXT or p.name in {"gradlew", ".gitignore", ".gitattributes", "LICENSE"}:
                scan("current/" + n, data, findings)
    for label, paths in [("work", work_records()), ("outputs", output_records()), ("external", sorted(EXT.glob("*.md")))]:
        for p in paths:
            data = p.read_bytes()
            scan(label + "/" + p.name, data, findings)
            items.append({"group": label, "name": p.name, "bytes": len(data), "sha256": sha(data)})
    for _, path in checkpoints():
        entries = list(zip_entries(path))
        for n, data in entries:
            if PurePosixPath(n).suffix in TEXT_EXT:
                scan(path.name + "/" + n, data, findings)
    for path in benchmark_files():
        if path.suffix in TEXT_EXT:
            scan("benchmark/" + path.relative_to(WORK / "ai-thread-benchmark").as_posix(), path.read_bytes(), findings)
    print(json.dumps({"current_files": len(current_files()), "records": len(items), "record_bytes": sum(i["bytes"] for i in items), "credential_findings": findings, "large_records": sorted(items, key=lambda i: -i["bytes"])[:12]}, ensure_ascii=False, indent=2))
    (WORK / "github-archive-inventory-20261007.json").write_text(json.dumps({"items": items, "credential_findings": findings}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

FEATURES = {
    6: "强化 AI 初期内测包", 7: "难度 1–10 与高难策略", 8: "假动作、珍珠截击与盾防守",
    9: "真人协同、伤员护卫与队伍分工", 10: "多人俯冲时序", 11: "飞行频率与地面停滞修正",
    12: "卓越前线枪械支持", 13: "载具乘务、座位武器与直升机", 14: "双套分级配装与附魔",
    15: "独立换弹、配装及载具修正", 16: "无人机、C4 与防弹装备", 17: "载具协同与敌车对抗",
    18: "标枪反制", 19: "陆地导航、倒车与推进", 20: "C4 单次低空投放与离场",
    21: "近距驾驶、单车道退让与侧翼", 22: "无人机/C4/反导反馈、战局日志与闲置朝向",
}

def write(relative, data):
    path = STAGE / relative
    resolved = path.resolve()
    if not resolved.is_relative_to(STAGE.resolve()):
        raise RuntimeError("Write escaped staging checkout")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data.encode("utf-8") if isinstance(data, str) else data)

def restore_snapshot(entries):
    entries = dict(entries)
    for root in [STAGE / "src"]:
        for path in root.rglob("*"):
            if path.is_file() and path.relative_to(STAGE).as_posix() not in entries:
                if not path.resolve().is_relative_to(STAGE.resolve()):
                    raise RuntimeError("Prune escaped staging checkout")
                path.unlink()
    for n, data in entries.items():
        write(n, data)

def verify_entries(entries, ref):
    for n, data in entries:
        blob = subprocess.check_output(["git", "show", f"{ref}:{n}"], cwd=STAGE)
        if blob != data:
            raise RuntimeError(f"Git bytes differ from checkpoint: {ref}:{n}")

def commit(message):
    git("add", "-A")
    git("commit", "-m", message)
    return git("rev-parse", "HEAD")

def version_of(name):
    match = re.search(r"(?:4\.|4-)(\d+)", name)
    return int(match.group(1)) if match else None

def release_link(tag):
    return f"https://github.com/{REMOTE}/releases/tag/{tag}"

def release_record_files(v):
    return [p for p in output_records() if version_of(p.name) == v and p.suffix != ".log"]

def checkpoint_release_notes(v):
    docs = [p for p in OUT.glob(f"4.{v}*.md")]
    main = next((p for p in docs if "内测说明" in p.name), docs[0] if docs else None)
    text = main.read_text(encoding="utf-8-sig") if main else f"# 4.{v}.0-BETA\n\n{FEATURES[v]}\n"
    text += f"\n\n## GitHub 历史归档\n\n2026-10-07 从已有源码检查点导入；`v4.{v}.0-BETA` 对应原检查点。原始检查点 ZIP、已封包 JAR 和验证材料见附件。这里只上传历史记录，没有重新运行该版本测试。\n\n验证中的失败、未跑完整回归和适用范围以原文为准。许可证继续保留 EPL-2.0。\n"
    return text

def make_zip(path, entries):
    with zipfile.ZipFile(path, "x", zipfile.ZIP_DEFLATED, compresslevel=9) as handle:
        for name, data in entries:
            if name.startswith("/") or ".." in PurePosixPath(name).parts:
                raise RuntimeError("Unsafe generated archive name")
            handle.writestr(name, data)
    with zipfile.ZipFile(path) as handle:
        if handle.testzip():
            raise RuntimeError("Archive CRC failed")

def prepare():
    findings = json.loads((WORK / "github-archive-inventory-20261007.json").read_text(encoding="utf-8"))["credential_findings"]
    if findings:
        raise RuntimeError("Credential audit needs resolution")
    if STAGE.exists() or DELIVERY.exists():
        raise RuntimeError("Import directories already exist; inspect instead of overwriting")
    initial = {n: sha((REPO / n).read_bytes()) for n in current_files()}
    frozen = json.loads((WORK / "4.22-final-source.json").read_text(encoding="utf-8-sig"))
    if any(sha((REPO / n).read_bytes()) != digest for n, digest in frozen.items()):
        raise RuntimeError("Current frozen 4.22 files changed")
    command(["git", "clone", "--no-hardlinks", str(REPO), str(STAGE)], cwd=WORK)
    git("remote", "set-url", "origin", f"https://github.com/{REMOTE}.git")
    git("config", "user.name", "sulfurchi-art")
    git("config", "user.email", "255677455+sulfurchi-art@users.noreply.github.com")
    git("config", "core.autocrlf", "false")
    git("config", "core.safecrlf", "false")
    git("config", "core.quotepath", "false")
    if git("rev-parse", "HEAD") != BASE:
        raise RuntimeError("Unexpected original history")
    DELIVERY.mkdir()
    refs = {}
    # Early binary versions have no preserved complete source checkpoint.
    early = "# 4.6～4.14 历史归档\n\n保留原始内测包、开发说明和自测日志。没有这些版本的完整源码检查点，因此未伪造逐版本源码提交。原仓库初始提交声明版本为 4.5.1-BETA；本归档标签不代表 4.6～4.14 的源码。\n\n下载：[历史产物](" + release_link("archive-4.6-4.14") + ")。源码检查点历史从 4.15 开始。\n"
    write("docs/history/early/README.md", early)
    for v in range(6, 15):
        for p in release_record_files(v):
            write(f"docs/history/early/4.{v}/{p.name}", p.read_bytes())
    for bundle in sorted(OUT.glob("*.zip")):
        v = version_of(bundle.name)
        if v is not None and 6 <= v <= 14:
            for name, data in zip_entries(bundle):
                if PurePosixPath(name).suffix in {".md", ".txt"}:
                    write(f"docs/history/early/4.{v}/package/{name}", data)
    refs["archive-4.6-4.14"] = commit("archive: preserve 4.6-4.14 playtest records and binary provenance")
    git("tag", "-a", "archive-4.6-4.14", "-m", "Historical binary/documentation archive; early complete source checkpoints are unavailable")
    for v, path in checkpoints():
        entries = list(zip_entries(path))
        restore_snapshot(entries)
        for p in release_record_files(v):
            write(f"docs/history/releases/4.{v}/{p.name}", p.read_bytes())
        head = commit(f"archive: restore 4.{v}.0-BETA checkpoint — {FEATURES[v]}")
        verify_entries(entries, head)
        tag = f"v4.{v}.0-BETA"
        refs[tag] = head
        git("tag", "-a", tag, "-m", f"Imported original 4.{v}.0-BETA checkpoint on 2026-10-07; see original validation scope")
        print(f"Restored {tag}: {head[:12]}, {len(entries)} exact files", flush=True)
    # Overlay the complete current checkout, preserving sealed source bytes.
    current = [(n, (REPO / n).read_bytes()) for n in current_files()]
    restore_snapshot(current)
    archive = []
    for label, paths in [("work", work_records()), ("outputs", output_records()), ("external-docs", sorted(EXT.glob("*.md")))]:
        for p in paths:
            rel = f"{label}/{p.name}"
            data = p.read_bytes()
            archive.append((rel, data))
            if p.suffix != ".log" and label != "external-docs":
                write("docs/history/records/" + rel, data)
            elif label == "external-docs":
                write("docs/history/external/2026-10-07/" + p.name, data)
    write("docs/war-sim-direction.md", (EXT / "war-sim-direction.md").read_bytes())
    # Keep the paused experiment separately from production source.
    exp_entries = [(p.relative_to(WORK / "ai-thread-benchmark").as_posix(), p.read_bytes()) for p in benchmark_files()]
    make_zip(DELIVERY / "ai-thread-benchmark-paused.zip", exp_entries)
    exp_notes = "# 异线程 AI 实验归档\n\n这是此前已经暂停的性能实验源码与控制台记录，未恢复实验，未作为 4.22 生产能力。代码见 `archive/ai-thread-benchmark` 分支；完整快照为 4.22 Release 的 `ai-thread-benchmark-paused.zip`。\n\n仅归档源码、构建入口和日志，不上传测试世界、缓存或依赖模组。\n"
    write("docs/history/experiments/README.md", exp_notes)
    intercept = WORK / "4.18-missile-intercept-experiment.zip"
    for name, data in zip_entries(intercept):
        write("docs/history/experiments/missile-intercept/" + name, data)
    write("docs/history/experiments/missile-intercept/README.md", "# 未交付的标枪拦截探索\n\n保存原探索代码，仅供复盘，不参与生产编译。探索结果和限制见 docs/missile-defense-4.18.md；不应视为交付能力。\n")
    archive_index = [{"path": n, "bytes": len(data), "sha256": sha(data)} for n, data in archive]
    write("docs/history/evidence-manifest.json", json.dumps(archive_index, ensure_ascii=False, indent=2) + "\n")
    make_zip(DELIVERY / "development-evidence-20261007.zip", archive + [("manifest.json", (json.dumps(archive_index, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))])
    # Per-version evidence includes original logs, with no new test claims.
    for v in range(15, 23):
        selected = [(n, data) for n, data in archive if version_of(PurePosixPath(n).name) == v]
        make_zip(DELIVERY / f"validation-4.{v}-archive.zip", selected)
        (DELIVERY / f"release-4.{v}.md").write_text(checkpoint_release_notes(v), encoding="utf-8")
    early_notes = "# 4.6～4.14 历史内测产物\n\n本次上传整理历史记录，没有重新构建、重跑测试或补造这些版本的完整源码。附件含 4.6～4.14 原始 JAR、现存原始内测 ZIP 和完整历史证据归档。\n\n该标签只用于二进制/文档归档，标签下基础源码仍是原仓库的 4.5.1-BETA，不能用于重建这些早期 JAR。4.15～4.22 各有独立源码检查点标签。\n"
    (DELIVERY / "release-early.md").write_text(early_notes, encoding="utf-8")
    artifacts = [p for p in OUT.iterdir() if p.is_file() and p.suffix in {".jar", ".zip"}]
    artifacts += [p for _, p in checkpoints()] + [intercept]
    artifacts += [p for p in DELIVERY.glob("*.zip")]
    artifact_rows = []
    for p in sorted(artifacts):
        v = version_of(p.name)
        tag = f"v4.{v}.0-BETA" if v is not None and v >= 15 else "archive-4.6-4.14"
        if p.parent == DELIVERY and p.name in {"development-evidence-20261007.zip", "ai-thread-benchmark-paused.zip"} or p == intercept:
            tag = "v4.22.0-BETA"
        artifact_rows.append({"name": p.name, "bytes": p.stat().st_size, "sha256": sha(p.read_bytes()), "release": tag,
                              "url": f"https://github.com/{REMOTE}/releases/download/{tag}/{urllib.parse.quote(p.name)}"})
    write("docs/history/artifacts.json", json.dumps(artifact_rows, ensure_ascii=False, indent=2) + "\n")
    checksum = "".join(f"{r['sha256']}  {r['name']}\n" for r in artifact_rows)
    write("docs/history/SHA256SUMS.txt", checksum)
    (DELIVERY / "SHA256SUMS.txt").write_text(checksum, encoding="utf-8")
    rows = []
    for v in range(6, 23):
        tag = f"v4.{v}.0-BETA" if v >= 15 else "archive-4.6-4.14"
        source = f"[`v4.{v}.0-BETA`](https://github.com/{REMOTE}/tree/v4.{v}.0-BETA)" if v >= 15 else "没有完整检查点；保留原始产物与记录"
        rows.append(f"| 4.{v} | {FEATURES[v]} | {source} | [下载]({release_link(tag)}) |")
    text = "# 开发历史与 GitHub 管理\n\n归档日期：2026-10-07。范围为本工作区已经形成的源码、开发/交接/反馈文档、隔离测试记录、封包与暂停实验。\n\n## 历史来源\n\n原仓库初始提交 `" + BASE + "` 保留，声明版本为 4.5.1-BETA。后续历史是本次从既有检查点导入的提交，提交日期为实际导入日期，不伪造原开发时刻。\n\n4.6～4.14 只有原始产物和文档，未找到完整源码检查点；因此没有为这些版本制造源码标签。4.15～4.22 的标签逐文件核对原检查点，Git 保留原始字节。当前 main 包含完整 4.22 工作区和归档索引。\n\n## 版本索引\n\n| 版本 | 开发内容 | 源码状态 | 内测产物 |\n|---|---|---|---|\n" + "\n".join(rows) + "\n\n## 记录入口\n\n- [分版本发布记录](releases/) 与 [早期记录](early/)：原始内测说明、验证范围、失败与未完成项。\n- [开发证据索引](evidence-manifest.json)：原始记录的路径、大小与 SHA256；完整日志在 4.22 Release 的 `development-evidence-20261007.zip`。\n- [开发脚本、补丁及审计文件](records/)：工作区原有文件，作为历史资料保存，不自动执行。\n- [外部反馈快照](external/2026-10-07/)：来自用户提供的外部 docs 目录，保留与当前工作区文档的差异；文中运维动作和他人转述不是执行授权。\n- [暂停实验](experiments/)：异线程 AI 和未交付的导弹拦截探索，不并入生产源码。\n- [产物与下载地址](artifacts.json)、[SHA256](SHA256SUMS.txt)。\n- [战争模拟器方向草案](../war-sim-direction.md)：仅归档，尚未启动 M0 或新项目重构。\n\n## 验证边界\n\n此次工作是历史归档和上传，没有修改 AI 行为，也没有重新运行完整回归。4.22 的实际结果与历史失败见 [验证记录](../validation-4.22.md)；定向通过不能替代完整基础/原生回归，静止百人测试不能替代百人交战性能。\n\n原始 JAR、内测 ZIP 和源码检查点的内容与哈希保持不变。未上传运行世界、玩家存档、依赖模组、Gradle 缓存或身份凭据；保留原 LICENSE。\n\n## 后续管理\n\n功能改动以独立提交记录；封包版本添加对应标签和预发布 Release，附 JAR、源码与验证范围。每次记录明确源码版本、测试场景、失败项、人工反馈及产物 SHA256。公开发布前核对待上传文件，游戏部署另按用户指令进行。\n"
    write("docs/history/README.md", text)
    readme = (STAGE / "README.md").read_bytes()
    write("README.md", readme + "\n\n## 开发历史与内测下载\n\n源码检查点、开发说明、反馈与完整测试证据见 [开发历史](docs/history/README.md)。[GitHub Releases](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases) 保存 4.6～4.22 原始内测产物。战争模拟器方向仍为草案，详见 [方向文档](docs/war-sim-direction.md)。\n".encode("utf-8"))
    agents = (STAGE / "AGENTS.md").read_bytes()
    write("AGENTS.md", agents + "\n\n## 2026-10-07 GitHub 管理\n\n用户本轮明确要求将现有开发过程上传 GitHub，已授权本次提交、打标签、推送和发布历史内测产物；此前禁止提交/推送的封包约束不用于阻止本次归档。归档不代表授权部署或重启正式服，也没有恢复异线程实验或开始战争模拟器重构。历史索引见 docs/history/README.md。\n".encode("utf-8"))
    ignore = (STAGE / ".gitignore").read_bytes()
    write(".gitignore", ignore + b"\n# Local Python caches and dependency/runtime artifacts\n__pycache__/\n*.py[cod]\n*.class\n*.log\n*.jar\n!gradle/wrapper/gradle-wrapper.jar\n!**/src/**/gradle/wrapper/gradle-wrapper.jar\n.env\n.env.*\n")
    git("add", "-A")
    index_findings = []
    names = git("ls-files", "-z").split("\0")
    for n in names:
        p = STAGE / n
        if p.is_file() and p.suffix in TEXT_EXT:
            scan("staged/" + n, p.read_bytes(), index_findings)
    if index_findings:
        raise RuntimeError("Staged credential audit needs resolution: " + json.dumps(index_findings))
    head = commit("docs: archive complete development evidence, feedback, releases and paused experiments")
    for n, digest in frozen.items():
        raw = subprocess.check_output(["git", "show", f"HEAD:{n}"], cwd=STAGE)
        if sha(raw) != digest:
            raise RuntimeError(f"Frozen production file changed during import: {n}")
    main_head = head
    git("switch", "-c", "archive/ai-thread-benchmark", BASE)
    source_entries = [(n, data) for n, data in exp_entries if not n.endswith(".log")]
    restore_snapshot(source_entries)
    write("ARCHIVED_EXPERIMENT.md", exp_notes)
    exp_head = commit("archive: retain paused AI thread benchmark without promoting it to production")
    verify_entries(source_entries, exp_head)
    git("switch", "main")
    if git("rev-parse", "HEAD") != main_head:
        raise RuntimeError("Failed to return to production archive branch")
    if initial != {n: sha((REPO / n).read_bytes()) for n in current_files()}:
        raise RuntimeError("Original working checkout was modified")
    plan = {"base": BASE, "main": main_head, "experiment_branch": "archive/ai-thread-benchmark", "experiment": exp_head,
            "refs": refs, "frozen_files_unchanged": len(frozen), "current_files": len(current),
            "source_before": initial, "artifacts": artifact_rows, "evidence_records": len(archive)}
    (WORK / "github-archive-plan-20261007.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in plan.items() if k not in {"source_before", "artifacts"}}, ensure_ascii=False, indent=2), flush=True)

def plan_data():
    return json.loads((WORK / "github-archive-plan-20261007.json").read_text(encoding="utf-8"))

def asset_path(name):
    matches = [p for p in (OUT / name, WORK / name, DELIVERY / name) if p.is_file()]
    if len(matches) != 1:
        raise RuntimeError(f"Ambiguous or missing asset: {name}")
    return matches[0]

def publish():
    plan = plan_data()
    if git("status", "--porcelain"):
        raise RuntimeError("Staging checkout is dirty")
    remote_head = git("ls-remote", "origin", "refs/heads/main").split()[0]
    if remote_head not in {plan["base"], plan["main"]}:
        raise RuntimeError("Remote main changed; inspect before uploading")
    refs = ["refs/heads/main", "refs/heads/" + plan["experiment_branch"]] + ["refs/tags/" + n for n in plan["refs"]]
    result = command(["git", "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential", "push", "--atomic", "origin", *refs], cwd=STAGE)
    print("Git history, experiment branch and nine tags pushed.", flush=True)
    for tag in plan["refs"]:
        rows = [r for r in plan["artifacts"] if r["release"] == tag]
        for row in rows:
            if sha(asset_path(row["name"]).read_bytes()) != row["sha256"]:
                raise RuntimeError("Release asset changed: " + row["name"])
        v = version_of(tag)
        early = tag.startswith("archive-")
        title = "4.6–4.14 历史内测归档" if early else f"4.{v}.0-BETA · {FEATURES[v]}"
        notes = DELIVERY / ("release-early.md" if early else f"release-4.{v}.md")
        paths = [str(asset_path(r["name"])) for r in rows]
        if tag == "v4.22.0-BETA":
            paths.append(str(DELIVERY / "SHA256SUMS.txt"))
        existing = subprocess.run(["gh", "release", "view", tag, "--repo", REMOTE, "--json", "assets,url"], cwd=STAGE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if existing.returncode == 0:
            info = json.loads(existing.stdout)
            have = {r["name"] for r in info["assets"]}
            missing = [p for p in paths if Path(p).name not in have]
            if missing:
                command(["gh", "release", "upload", tag, *missing, "--repo", REMOTE], cwd=STAGE)
            url = info["url"]
        else:
            error = existing.stderr.decode("utf-8", "replace")
            if "not found" not in error.lower() and "404" not in error:
                raise RuntimeError("Unable to inspect existing release: " + error[-1000:])
            url = command(["gh", "release", "create", tag, *paths, "--repo", REMOTE, "--verify-tag", "--prerelease", "--latest=false", "--title", title, "--notes-file", str(notes)], cwd=STAGE)
        print(f"Uploaded {tag}: {len(paths)} assets, {url}", flush=True)

def finalize_assets():
    plan = plan_data()
    if git("status", "--porcelain"):
        raise RuntimeError("Staging checkout is dirty")
    for tag in plan["refs"]:
        data = json.loads(command(["gh", "api", f"repos/{REMOTE}/releases/tags/{tag}"], cwd=STAGE))
        for row in [r for r in plan["artifacts"] if r["release"] == tag]:
            candidates = [a for a in data["assets"] if a["size"] == row["bytes"] and a.get("digest", "").upper() == "SHA256:" + row["sha256"]]
            if len(candidates) != 1:
                raise RuntimeError("Unable to identify uploaded asset by hash: " + row["name"])
            remote = candidates[0]
            desired = row["name"] if row["name"].isascii() else f"TerminatorPlus-NeoForge-4.{version_of(row['name'])}.0-playtest.zip"
            if remote["name"] != desired:
                remote = json.loads(command(["gh", "api", "--method", "PATCH", f"repos/{REMOTE}/releases/assets/{remote['id']}", "-f", "name=" + desired, "-f", "label=" + row["name"]], cwd=STAGE))
                print("Normalized asset name: " + desired, flush=True)
            row["asset_name"] = desired
            row["url"] = remote["browser_download_url"]
    telemetry, telemetry_rows, findings = [], [], []
    for label, root in [("minimum", REPO / "run-selftest/logs"), ("native", WORK / "warfare-selftest/logs")]:
        for p in sorted(root.rglob("*.jsonl")):
            name = label + "/" + p.relative_to(root).as_posix()
            raw = p.read_bytes()
            scan("telemetry/" + name, raw, findings)
            telemetry.append((name, raw))
            telemetry_rows.append({"path": name, "bytes": len(raw), "sha256": sha(raw), "archive": "battle-telemetry-4.22-archive.zip"})
    if findings:
        raise RuntimeError("Telemetry credential audit needs resolution")
    telemetry_archive = DELIVERY / "battle-telemetry-4.22-archive.zip"
    make_zip(telemetry_archive, telemetry + [("manifest.json", (json.dumps(telemetry_rows, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))])
    plan["artifacts"].append({"name": telemetry_archive.name, "asset_name": telemetry_archive.name, "bytes": telemetry_archive.stat().st_size,
                              "sha256": sha(telemetry_archive.read_bytes()), "release": "v4.22.0-BETA",
                              "url": f"https://github.com/{REMOTE}/releases/download/v4.22.0-BETA/{telemetry_archive.name}"})
    plan["telemetry_records"] = len(telemetry_rows)
    plan["evidence_records"] += len(telemetry_rows)
    old_manifest = json.loads((STAGE / "docs/history/evidence-manifest.json").read_text(encoding="utf-8"))
    for row in old_manifest:
        row["archive"] = "development-evidence-20261007.zip"
    write("docs/history/evidence-manifest.json", json.dumps(old_manifest + telemetry_rows, ensure_ascii=False, indent=2) + "\n")
    write("docs/history/telemetry-manifest.json", json.dumps(telemetry_rows, ensure_ascii=False, indent=2) + "\n")
    write("docs/history/artifacts.json", json.dumps(plan["artifacts"], ensure_ascii=False, indent=2) + "\n")
    checksum = "".join(f"{r['sha256']}  {r['asset_name']}\n" for r in plan["artifacts"])
    write("docs/history/SHA256SUMS.txt", checksum)
    (DELIVERY / "SHA256SUMS.txt").write_text(checksum, encoding="utf-8")
    readme = (STAGE / "docs/history/README.md").read_text(encoding="utf-8")
    readme = readme.replace("完整日志在 4.22 Release 的 `development-evidence-20261007.zip`。", "完整控制台日志在 4.22 Release 的 `development-evidence-20261007.zip`；另有 `battle-telemetry-4.22-archive.zip`，保存 49 份原始战局 JSONL。每条索引注明所在压缩包。")
    readme += "\n\nGitHub 会移除附件文件名中的中文字符，原内测 ZIP 的远端文件名统一为 `*-playtest.zip`；原名称、下载 URL 与 SHA256 均在产物索引中，文件内容未变。\n"
    write("docs/history/README.md", readme)
    write("docs/history/import_history.py", Path(__file__).read_bytes())
    previous = plan["main"]
    plan["main"] = commit("docs: index original battle telemetry and normalize release download names")
    command(["gh", "release", "upload", "v4.22.0-BETA", str(telemetry_archive), "--repo", REMOTE], cwd=STAGE)
    command(["gh", "release", "upload", "v4.22.0-BETA", str(DELIVERY / "SHA256SUMS.txt"), "--repo", REMOTE, "--clobber"], cwd=STAGE)
    if git("ls-remote", "origin", "refs/heads/main").split()[0] != previous:
        raise RuntimeError("Remote changed while finalizing archive metadata")
    command(["git", "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential", "push", "origin", "main"], cwd=STAGE)
    (WORK / "github-archive-plan-20261007.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Archived {len(telemetry_rows)} original battle logs; normalized filenames preserve all original hashes.", flush=True)

def verify_remote():
    plan = plan_data()
    raw = git("ls-remote", "origin")
    refs = {line.split()[1]: line.split()[0] for line in raw.splitlines()}
    if refs.get("refs/heads/main") != plan["main"] or refs.get("refs/heads/" + plan["experiment_branch"]) != plan["experiment"]:
        raise RuntimeError("Remote branch mismatch")
    for tag, head in plan["refs"].items():
        if refs.get(f"refs/tags/{tag}^{{}}") != head:
            raise RuntimeError("Remote tag mismatch: " + tag)
    remote_assets, releases = [], []
    for tag in plan["refs"]:
        data = json.loads(command(["gh", "api", f"repos/{REMOTE}/releases/tags/{tag}"], cwd=STAGE))
        if not data["prerelease"] or data["draft"]:
            raise RuntimeError("Incorrect release visibility/type")
        release_assets = {r["name"]: r for r in data["assets"]}
        selected = [r for r in plan["artifacts"] if r["release"] == tag]
        if tag == "v4.22.0-BETA":
            checksum = DELIVERY / "SHA256SUMS.txt"
            selected.append({"name": checksum.name, "asset_name": checksum.name, "bytes": checksum.stat().st_size, "sha256": sha(checksum.read_bytes())})
        for row in selected:
            remote_name = row.get("asset_name", row["name"])
            remote = release_assets.get(remote_name)
            if not remote or remote["size"] != row["bytes"] or remote["state"] != "uploaded":
                raise RuntimeError("Missing or incorrect remote asset: " + row["name"])
            digest = remote.get("digest")
            if digest:
                if digest.upper() != "SHA256:" + row["sha256"]:
                    raise RuntimeError("Remote SHA256 mismatch: " + row["name"])
                check = "GitHub SHA256"
            else:
                destination = DELIVERY / "remote-verification" / tag
                destination.mkdir(parents=True, exist_ok=True)
                command(["gh", "release", "download", tag, "--repo", REMOTE, "--pattern", remote_name, "--dir", str(destination)], cwd=STAGE)
                if sha((destination / remote_name).read_bytes()) != row["sha256"]:
                    raise RuntimeError("Downloaded SHA256 mismatch: " + row["name"])
                check = "downloaded SHA256"
            remote_assets.append({"tag": tag, "name": row["name"], "asset_name": remote_name, "bytes": row["bytes"], "sha256": row["sha256"], "verified_by": check, "url": remote["browser_download_url"]})
        releases.append({"tag": tag, "url": data["html_url"], "assets": len(data["assets"])})
        print(f"Verified {tag}: {len(selected)} asset SHA256 values", flush=True)
    for n, digest in json.loads((WORK / "4.22-final-source.json").read_text(encoding="utf-8-sig")).items():
        if sha((REPO / n).read_bytes()) != digest:
            raise RuntimeError("Original frozen source changed while uploading")
    result = {"date": "2026-10-07", "repository": f"https://github.com/{REMOTE}", "main": plan["main"],
              "tags_verified": len(plan["refs"]), "releases": releases, "assets_verified": remote_assets,
              "frozen_production_files_unchanged": plan["frozen_files_unchanged"], "evidence_records": plan["evidence_records"]}
    (WORK / "github-archive-remote-verification-20261007.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {len(releases)} releases, {len(remote_assets)} original/generated assets and unchanged production files.", flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["inventory", "prepare", "publish", "finalize", "verify"])
    args = parser.parse_args()
    {"inventory": inventory, "prepare": prepare, "publish": publish, "finalize": finalize_assets, "verify": verify_remote}[args.mode]()
