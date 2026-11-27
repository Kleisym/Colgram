#!/usr/bin/env python3
"""Colgram release publisher: uploads Koliti voice pack, finalizes Dual-APK release,
updates config/telegram-native.json before pruning legacy releases/tags.

SECURITY: Never prints, logs, or formats the GitHub PAT into stdout, stderr, or exceptions.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import sys
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_REPO = "Kleisym/Colgram"
DEFAULT_MODELS_TAG = "koliti-models-v3"
VOICE_PACK_NAME = "koliti-voice-pack-v3.tar.gz"
DEFAULT_BYPASS_PC_DIST = Path(r"F:\brom\BypassPC\dist")


def read_github_token(token_file: Path | None = None) -> str:
    """Read GitHub PAT cleanly without ever echoing it."""
    path = Path(token_file) if token_file else (Path.home() / "gfpat.txt")
    if not path.is_file():
        raise RuntimeError(f"GitHub PAT file not found: {path}")
    token = path.read_text(encoding="utf-8").strip()
    if not token:
        raise RuntimeError(f"GitHub PAT file is empty: {path}")
    return token


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest().lower()


def _find_voice_dirs(repo_root: Path) -> tuple[Path, Path]:
    candidates = [
        (
            repo_root / "Telegram-Src" / "TMessagesProj" / "src" / "main" / "assets" / "koliti_tts",
            repo_root / "Telegram-Src" / "TMessagesProj" / "src" / "main" / "assets" / "koliti_asr",
        ),
        (
            repo_root / "build-tmp" / "koliti-asset-cache" / "koliti_tts",
            repo_root / "build-tmp" / "koliti-asset-cache" / "koliti_asr",
        ),
    ]
    for tts_dir, asr_dir in candidates:
        if tts_dir.is_dir() and asr_dir.is_dir():
            return tts_dir, asr_dir
    raise FileNotFoundError(
        "koliti_tts and koliti_asr directories not found in Telegram-Src or build-tmp/koliti-asset-cache"
    )


def build_voice_pack(repo_root: Path, out_tar_gz: Path | None = None) -> Path:
    """Package koliti_tts + koliti_asr into deterministic koliti-voice-pack-v3.tar.gz."""
    tts_dir, asr_dir = _find_voice_dirs(repo_root)
    required_tts = tts_dir / "espeak-ng-data.zip"
    required_asr = asr_dir / "tokens.txt"
    if not required_tts.is_file() or not required_asr.is_file():
        raise FileNotFoundError("Missing required TTS/ASR files in koliti_tts or koliti_asr")

    out_path = out_tar_gz or (repo_root / "build-tmp" / VOICE_PACK_NAME)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = out_path.with_suffix(".tar.gz.tmp")
    with tarfile.open(tmp_path, "w:gz", compresslevel=6) as tf:
        for folder_name, folder_path in (("koliti_tts", tts_dir), ("koliti_asr", asr_dir)):
            for file_path in sorted(folder_path.rglob("*")):
                if file_path.is_file():
                    rel = file_path.relative_to(folder_path).as_posix()
                    info = tf.gettarinfo(str(file_path), arcname=f"{folder_name}/{rel}")
                    info.mtime = 0
                    info.uid = 0
                    info.gid = 0
                    info.uname = ""
                    info.gname = ""
                    with file_path.open("rb") as src:
                        tf.addfile(info, src)
    tmp_path.replace(out_path)
    return out_path


def _gh_request(
    method: str,
    url: str,
    token: str,
    data: bytes | None = None,
    content_type: str = "application/json",
    timeout: int = 120,
) -> tuple[int, bytes]:
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "Colgram-Release-Publisher",
    }
    if data is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as err:
        body = err.read()
        msg = body.decode("utf-8", errors="replace")[:300]
        raise RuntimeError(f"GitHub API {method} {url} failed ({err.code}): {msg}") from None


def _gh_json(method: str, url: str, token: str, payload: dict | None = None) -> dict | list:
    raw = json.dumps(payload).encode("utf-8") if payload is not None else None
    _, resp = _gh_request(method, url, token, data=raw)
    return json.loads(resp.decode("utf-8")) if resp else {}


def _upload_asset(upload_url_template: str, asset_path: Path, token: str, existing_assets: list[dict]) -> dict:
    name = asset_path.name
    for asset in existing_assets:
        if asset.get("name") == name:
            _gh_request("DELETE", asset["url"], token)
            break
    base_upload = upload_url_template.split("{", 1)[0]
    url = f"{base_upload}?name={urllib.parse.quote(name)}"
    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    payload = asset_path.read_bytes()
    _, resp = _gh_request("POST", url, token, data=payload, content_type=ctype, timeout=600)
    return json.loads(resp.decode("utf-8"))


def _format_size_mb(size_bytes: int | None) -> str:
    if not size_bytes or size_bytes <= 0:
        return "—"
    if size_bytes >= 1024 * 1024 * 1024:
        return f"{size_bytes / (1024 ** 3):.2f} ГБ"
    return f"{size_bytes / (1024 ** 2):.1f} МБ"


def build_release_body(version: str, tag: str, assets_info: dict[str, dict], repo: str = DEFAULT_REPO) -> str:
    """Build polished Russian GitHub Release notes with hero banner & GFM-verified video embed."""
    main_info = assets_info.get(f"Colgram-{version}.apk", {})
    koliti_info = assets_info.get(f"Colgram-{version}-Koliti.apk", {})
    exe_info = assets_info.get("NetBypass.exe", {})
    zip_info = assets_info.get("NetBypass-Portable.zip", {})

    base_dl = f"https://github.com/{repo}/releases/download/{tag}"
    main_name = f"Colgram-{version}.apk"
    koliti_name = f"Colgram-{version}-Koliti.apk"

    lines = [
        f"![Colgram {version} — Освежи свой Telegram](https://raw.githubusercontent.com/{repo}/main/docs/media/colgram-release-poster.png)",
        "",
        f"[![Смотреть видео-обзор Colgram {version} (1080p60)](https://raw.githubusercontent.com/{repo}/main/docs/media/colgram-release-preview.gif)]({base_dl}/colgram-release.mp4)",
        "",
        f"## Colgram {version} Next-Gen — два варианта установки",
        "",
        "| Редакция | Файл | Архитектуры | Размер | SHA-256 |",
        "|---|---|---|---|---|",
        (
            f"| **Основной Colgram** *(без Koliti AI)* | [`{main_name}`]({base_dl}/{main_name}) | "
            f"`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` | {_format_size_mb(main_info.get('size'))} | "
            f"`{main_info.get('sha256', 'см. SHA256SUMS.txt')}` |"
        ),
        (
            f"| **Colgram + Koliti AI** *(полный офлайн ИИ)* | [`{koliti_name}`]({base_dl}/{koliti_name}) | "
            f"`arm64-v8a`, `x86_64` | {_format_size_mb(koliti_info.get('size'))} | "
            f"`{koliti_info.get('sha256', 'см. SHA256SUMS.txt')}` |"
        ),
        "",
        "### Что выбрать?",
        f"- **`{main_name}`** — лёгкий приватный клиент без ИИ-модулей, нативных библиотек `llama.cpp`/`sherpa-onnx` и весов моделей. Работает на всех Android 7+ устройствах и эмуляторах.",
        f"- **`{koliti_name}`** — сборка со встроенным офлайн-ассистентом **Koliti Core v3**, модулем зрения **Vision**, нейроозвучкой **Piper TTS** и распознаванием речи **Sherpa ASR** (без интернета и сторонних серверов).",
        "",
        "### Что нового в Colgram Next-Gen",
        "- **Zero Text-Overlap UI + Флагманский Floating HUD и плавная шкала**: полностью переработана геометрия `CheckBoxCell`, `TextSettingsCell`, `AnimatedTextView`, `TextCheckCell`, `TextDetailSettingsCell`, `SettingCell` и шитов `ColgramUiKit` (`MaxHeightScrollView`); вместо спама Toast при импорте тяжёлых файлов и создании Temp Mail отображается плавающий Floating HUD с 120 FPS шкалой прогресса и отменой.",
        "- **Colgram Premium+ (100% локально на клиенте)**: локальные эмодзи-теги и фильтрация в Избранном (`ColgramSavedTagsStore`), полная блокировка рекламы/спонсорских постов и пиров, 3-уровневая локальная расшифровка ГС (Sherpa ASR / Android 13+ / HUD), локальный прокси-переводчик сообщений и целых чатов, копирование/пересылка без цитаты из `noforwards`-чатов, скрытие Историй, авто-скрытие Архива, стартовая папка, выбор быстрой реакции и все иконки приложения.",
        "- **Бесконечное хранилище (≤ 1 ГБ на диске) и Ленивый сейф**: жёсткая квота кэша в реальном времени, исправленный FUSE CAS, инкрементальное WebP + zlib сжатие, ленивый Shadow Vault анти-удаления и щит автозагрузки против APK/скриптов/RLO-маскировки и гигантских файлов (`ColgramUiKit.showMalwareWarningSheet`).",
        "- **10x Optimization Pass и быстрый старт**: неблокирующая инициализация ядра, WAL + 64 МБ `mmap` и индексы истории правок в SQLite, оптимизированная очередь картинок, приоритет аватаров/стикеров и нулевой джиттер скролла чатов.",
        "- **Усиленный Анти-Пинг + Colemailer и Неразрывный обход блокировок**: фильтрация скрытых и массовых пингов с авто-сбросом серверных `@` без утечки прочтений (`ColgramGhostMode`), очередь рассыльщика Colemailer с джиттером, самовосстанавливающийся DPI/WSS/MASQUE-стек с быстрой загрузкой каналов (DC1/3/5) и жёсткий периметр без утечек DNS/UA/Firebase.",
        "- **Koliti AI Core v3 & Нативное прикрепление файлов до 2–4 ГБ**: фото, PDF, DOCX/ODT вложения, офлайн Vision + TTS/ASR (в Koliti-сборке), защищённый SAF-пикер, 25+ меню `ColgramUiKit`, безлимитный AES-256 сейф аккаунтов и 16 KB `zipalign`.",
        "",
        "---",
        "",
        "## NetBypass v6.0 — компаньон для Windows ПК",
        "В релиз включён новейший **NetBypass v6.0** для Windows (`x64`) — локальный обход блокировок для компьютера без системных драйверов:",
        f"- [`NetBypass.exe`]({base_dl}/NetBypass.exe) ({_format_size_mb(exe_info.get('size'))}) — SHA-256: `{exe_info.get('sha256', 'см. SHA256SUMS.txt')}`",
        f"- [`NetBypass-Portable.zip`]({base_dl}/NetBypass-Portable.zip) ({_format_size_mb(zip_info.get('size'))}) — SHA-256: `{zip_info.get('sha256', 'см. SHA256SUMS.txt')}`",
        "",
        f"Контрольные суммы всех файлов приложены в [`SHA256SUMS.txt`]({base_dl}/SHA256SUMS.txt). Все APK подписаны единым релизным сертификатом Colgram (`3c532696...`) — обновление поверх прежних CI-сборок проходит без потери чатов.",
    ]
    return "\n".join(lines) + "\n"


def update_telegram_native_manifest(manifest_path: Path, main_apk_url: str, main_apk_sha256: str) -> dict:
    """Safely update config/telegram-native.json to point at the verified new Main APK."""
    if not main_apk_url.startswith("https://"):
        raise ValueError(f"Invalid Main APK URL: {main_apk_url}")
    clean_sha = main_apk_sha256.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", clean_sha):
        raise ValueError(f"Invalid SHA-256 hex digest: {main_apk_sha256}")
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    data["apk_url"] = main_apk_url
    data["apk_sha256"] = clean_sha
    tmp = manifest_path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp.replace(manifest_path)
    return data


def legacy_tags_to_delete(releases: list[dict], keep_tags: set[str]) -> list[tuple[int, str]]:
    """Return [(release_id, tag_name)] for all releases outside keep_tags."""
    out: list[tuple[int, str]] = []
    for rel in releases:
        tag = str(rel.get("tag_name") or "").strip()
        rid = rel.get("id")
        if tag and rid is not None and tag not in keep_tags:
            out.append((int(rid), tag))
    return out


def legacy_orphan_tags_to_delete(tags: list[dict], keep_tags: set[str]) -> list[str]:
    """Return all repository git tag names outside keep_tags."""
    out: list[str] = []
    seen: set[str] = set()
    for item in tags:
        name = str(item.get("name") or "").strip()
        if name and name not in keep_tags and name not in seen:
            seen.add(name)
            out.append(name)
    return out


def _gh_paginated(url_base: str, token: str) -> list[dict]:
    items: list[dict] = []
    page = 1
    while True:
        sep = "&" if "?" in url_base else "?"
        batch = _gh_json("GET", f"{url_base}{sep}per_page=100&page={page}", token)
        if not isinstance(batch, list) or not batch:
            break
        items.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    return items


def fetch_remote_manifest_apk_url(repo: str, token: str, ref: str = "main") -> str:
    """Fetch remote config/telegram-native.json:apk_url from GitHub to prevent self-bricking deletes."""
    import base64

    url = f"https://api.github.com/repos/{repo}/contents/config/telegram-native.json?ref={urllib.parse.quote(ref)}"
    obj = _gh_json("GET", url, token)
    raw_b64 = str(obj.get("content") or "")
    decoded = base64.b64decode(raw_b64).decode("utf-8")
    return str(json.loads(decoded).get("apk_url") or "")


def is_remote_manifest_safe_to_prune(remote_apk_url: str, keep_tag: str, delete_tags: set[str]) -> bool:
    """True only when remote manifest points at keep_tag and references no tag scheduled for deletion."""
    if not remote_apk_url or f"/download/{keep_tag}/" not in remote_apk_url:
        return False
    return not any(f"/download/{old}/" in remote_apk_url for old in delete_tags)


def _prune_legacy_releases_and_tags(repo: str, keep_tag: str, token: str, dry_run: bool, require_safe: bool) -> int:
    keep = {keep_tag, DEFAULT_MODELS_TAG}
    all_releases = _gh_paginated(f"https://api.github.com/repos/{repo}/releases", token)
    all_tags = _gh_paginated(f"https://api.github.com/repos/{repo}/tags", token)
    to_remove_releases = legacy_tags_to_delete(all_releases, keep)
    to_remove_tags = legacy_orphan_tags_to_delete(all_tags, keep)
    for _, rel_tag in to_remove_releases:
        if rel_tag not in to_remove_tags and rel_tag not in keep:
            to_remove_tags.append(rel_tag)

    delete_tag_set = set(to_remove_tags) | {t for _, t in to_remove_releases}
    if not delete_tag_set:
        print("[=] No legacy releases or orphan tags to delete.")
        return 0

    remote_apk_url = fetch_remote_manifest_apk_url(repo, token, ref="main")
    if not is_remote_manifest_safe_to_prune(remote_apk_url, keep_tag, delete_tag_set):
        msg = (
            f"Remote main:config/telegram-native.json apk_url ({remote_apk_url}) does not yet point "
            f"exclusively to {keep_tag}. Push updated config/telegram-native.json to origin/main first, "
            f"then run: python scripts/release_publish.py --prune-legacy {keep_tag}"
        )
        if require_safe:
            raise RuntimeError(msg)
        print(f"[!] {msg}")
        return 0

    if dry_run:
        print(f"[dry-run] Would delete {len(to_remove_releases)} releases and {len(to_remove_tags)} tags")
        return 0

    for rid, old_tag in to_remove_releases:
        _gh_request("DELETE", f"https://api.github.com/repos/{repo}/releases/{rid}", token)
        print(f" [-] Deleted legacy release: {old_tag} (id={rid})")
    for old_tag in to_remove_tags:
        try:
            _gh_request(
                "DELETE",
                f"https://api.github.com/repos/{repo}/git/refs/tags/{urllib.parse.quote(old_tag)}",
                token,
            )
            print(f" [-] Deleted legacy tag: {old_tag}")
        except RuntimeError:
            pass
    return 0


def _extract_sha_for_asset(text: str, asset_name: str) -> str | None:
    needle = asset_name.lower()
    for line in text.splitlines():
        if needle in line.lower():
            m = re.search(r"\b([0-9a-fA-F]{64})\b", line)
            if m:
                return m.group(1).lower()
    return None


def _resolve_remote_sha256(asset_name: str, assets_by_name: dict[str, dict]) -> str | None:
    for candidate_name in (f"{asset_name}.sha256", "SHA256SUMS.txt"):
        cand = assets_by_name.get(candidate_name)
        if cand and cand.get("browser_download_url"):
            req = urllib.request.Request(cand["browser_download_url"], headers={"User-Agent": "Colgram-Release-Publisher"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                txt = resp.read().decode("utf-8", errors="replace")
            found = _extract_sha_for_asset(txt, asset_name)
            if not found and candidate_name.endswith(".sha256"):
                m = re.search(r"\b([0-9a-fA-F]{64})\b", txt)
                if m:
                    found = m.group(1).lower()
            if found:
                return found
    return None


def upload_voice_pack_cmd(repo_root: Path, repo: str, models_tag: str, token: str, dry_run: bool) -> int:
    tar_path = build_voice_pack(repo_root)
    sha = sha256_file(tar_path)
    print(f"[+] Built {tar_path.name} ({tar_path.stat().st_size} bytes, sha256={sha})")
    if dry_run:
        print(f"[dry-run] Would upload {tar_path.name} to prerelease {models_tag} on {repo}")
        return 0
    rel = _gh_json("GET", f"https://api.github.com/repos/{repo}/releases/tags/{urllib.parse.quote(models_tag)}", token)
    if not rel.get("prerelease"):
        _gh_json(
            "PATCH",
            f"https://api.github.com/repos/{repo}/releases/{rel['id']}",
            token,
            payload={"prerelease": True},
        )
    _upload_asset(rel["upload_url"], tar_path, token, rel.get("assets", []))
    print(f"[+] Uploaded {tar_path.name} to {repo}@{models_tag} (prerelease=true)")
    return 0


def finalize_release_cmd(
    repo_root: Path,
    repo: str,
    tag: str,
    bypass_dist: Path,
    token: str,
    dry_run: bool,
    skip_cleanup: bool,
) -> int:
    rel = _gh_json("GET", f"https://api.github.com/repos/{repo}/releases/tags/{urllib.parse.quote(tag)}", token)
    existing_assets = rel.get("assets", [])
    by_name = {a["name"]: a for a in existing_assets}

    # Discover version from Main APK asset name
    main_apk_name = next(
        (n for n in by_name if re.fullmatch(r"Colgram-[0-9A-Za-z._-]+\.apk", n) and "-koliti" not in n.lower()),
        None,
    )
    if not main_apk_name:
        raise RuntimeError(f"Release {tag} is missing Main Colgram-<ver>.apk asset")
    version = main_apk_name[len("Colgram-"):-len(".apk")]
    koliti_apk_name = f"Colgram-{version}-Koliti.apk"
    if koliti_apk_name not in by_name:
        raise RuntimeError(f"Release {tag} is missing Koliti APK {koliti_apk_name}")

    main_sha = _resolve_remote_sha256(main_apk_name, by_name)
    koliti_sha = _resolve_remote_sha256(koliti_apk_name, by_name)
    if not main_sha or not koliti_sha:
        raise RuntimeError("Could not resolve SHA-256 digests for both Main and Koliti APKs")

    exe_path = bypass_dist / "NetBypass.exe"
    zip_path = bypass_dist / "NetBypass-Portable.zip"
    mp4_path = repo_root / "docs" / "media" / "colgram-release.mp4"
    for req_file in (exe_path, zip_path, mp4_path):
        if not req_file.is_file():
            raise FileNotFoundError(f"Required release companion missing: {req_file}")

    assets_info: dict[str, dict] = {
        main_apk_name: {"size": by_name[main_apk_name]["size"], "sha256": main_sha},
        koliti_apk_name: {"size": by_name[koliti_apk_name]["size"], "sha256": koliti_sha},
        "NetBypass.exe": {"size": exe_path.stat().st_size, "sha256": sha256_file(exe_path)},
        "NetBypass-Portable.zip": {"size": zip_path.stat().st_size, "sha256": sha256_file(zip_path)},
        "colgram-release.mp4": {"size": mp4_path.stat().st_size, "sha256": sha256_file(mp4_path)},
    }

    with tempfile.TemporaryDirectory() as tmpdir:
        sums_path = Path(tmpdir) / "SHA256SUMS.txt"
        sums_lines = [
            f"{info['sha256']}  {fname}"
            for fname, info in assets_info.items()
        ]
        sums_path.write_text("\n".join(sums_lines) + "\n", encoding="utf-8")

        body = build_release_body(version, tag, assets_info, repo=repo)
        release_title = f"Colgram {version} Next-Gen (+ Koliti AI & NetBypass v6.0)"
        main_url = by_name[main_apk_name]["browser_download_url"]

        if dry_run:
            print(f"[dry-run] Release {tag}: main={main_apk_name} koliti={koliti_apk_name}")
            print(f"[dry-run] Would update config/telegram-native.json -> {main_url} ({main_sha})")
        else:
            for p in (exe_path, zip_path, mp4_path, sums_path):
                _upload_asset(rel["upload_url"], p, token, existing_assets)
            _gh_json(
                "PATCH",
                f"https://api.github.com/repos/{repo}/releases/{rel['id']}",
                token,
                payload={"name": release_title, "body": body, "draft": False, "prerelease": False, "make_latest": "true"},
            )
            # Verify Main APK URL responds cleanly BEFORE updating manifest & pruning legacy releases
            head_req = urllib.request.Request(main_url, method="HEAD", headers={"User-Agent": "Colgram-Release-Publisher"})
            with urllib.request.urlopen(head_req, timeout=60) as resp:
                if resp.status >= 400:
                    raise RuntimeError(f"New Main APK URL returned HTTP {resp.status}")

            manifest_path = repo_root / "config" / "telegram-native.json"
            update_telegram_native_manifest(manifest_path, main_url, main_sha)
            print(f"[+] Updated {manifest_path} to {main_url}")

            if not skip_cleanup:
                _prune_legacy_releases_and_tags(repo, tag, token, dry_run=False, require_safe=False)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--token-file", type=Path, default=None)
    ap.add_argument("--bypass-dist", type=Path, default=DEFAULT_BYPASS_PC_DIST)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--skip-cleanup", action="store_true")
    ap.add_argument("--upload-voice-pack", action="store_true")
    ap.add_argument("--finalize-release", metavar="TAG", default=None)
    ap.add_argument("--prune-legacy", metavar="TAG", default=None)
    args = ap.parse_args(argv)

    repo_root = Path(__file__).resolve().parents[1]
    if not args.upload_voice_pack and not args.finalize_release and not args.prune_legacy:
        ap.error("Specify --upload-voice-pack, --finalize-release TAG, and/or --prune-legacy TAG")

    token = "" if (args.dry_run and args.upload_voice_pack and not args.finalize_release and not args.prune_legacy) else read_github_token(args.token_file)
    if args.upload_voice_pack:
        rc = upload_voice_pack_cmd(repo_root, args.repo, DEFAULT_MODELS_TAG, token, args.dry_run)
        if rc != 0:
            return rc
    if args.finalize_release:
        rc = finalize_release_cmd(
            repo_root, args.repo, args.finalize_release, args.bypass_dist, token, args.dry_run, args.skip_cleanup
        )
        if rc != 0:
            return rc
    if args.prune_legacy:
        return _prune_legacy_releases_and_tags(args.repo, args.prune_legacy, token, args.dry_run, require_safe=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
