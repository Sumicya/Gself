#!/usr/bin/env python3
"""Gself 的 Actions artifact 滚动保留（GLOBAL.md 第二十一版：【CI 滚动清理：筛选、串行与积压】）。

范围：本仓库自己工作流产出的对象，按**项目名前缀**筛选——`Gself-`（大小写不敏感），
以及改名前的遗留前缀 `fcmself-` / `fcmfix-`。不碰其它项目、手工上传的对象、Release 与 tag。
保留：按创建时间倒序完整分页后，保留最近 KEEP_ARTIFACT 个（默认 5，必须为正整数），其余删除。

保留窗口只算**发行对象**（`Gself-<五段版本>` 与遗留名）：PR / 其它分支的非发行包 `Gself-dev-<构建数>`
不占名额，交给 `upload-artifact` 的 `retention-days: 5` 过期。
与规范字面的差异：规范第二十一版写「Actions artifact 保留最近 5 个」，主人当轮指示为「只按发行包计数」；
按【权威与冲突】的权威顺序（主人当轮指示 > 规范最新版）执行主人指示，并在 CHANGELOG 记账。

安全闸（缺一不动手）：
  - 本次运行自己的 artifact 必须已经可见，否则整轮放弃，避免删掉刚产出的包；
  - 比本次运行更新的 artifact（并发运行刚上传的）延后到下一轮再判断；
  - 删除前把完整清单逐条打印出来（名称、id、创建时间）。

用法：
  python3 .github/scripts/cleanup_artifacts.py [--dry-run]
环境：
  GITHUB_TOKEN / GITHUB_REPOSITORY / GITHUB_RUN_ID / GITHUB_API_URL（Actions 自带）
  KEEP_ARTIFACT（可选，默认 5）
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

PAGE_SIZE = 100
DEFAULT_KEEP = 5
# 本项目自己的 artifact 名前缀：对外名 Gself，改名前的遗留名一并纳入（首次启用时清理积压）
PROJECT_PREFIXES = ("gself-", "fcmself-", "fcmfix-")
# 非发行构建（PR / 其它分支）：不占保留名额，按 retention-days 过期
NON_RELEASE_NAME = re.compile(r"^Gself-dev-\d+$")


class GitHubAPI:
    """最小 GitHub REST 客户端：GET / DELETE + 完整分页。"""

    def __init__(self, repository: str, token: str, api_url: str) -> None:
        try:
            owner, name = repository.split("/", 1)
        except ValueError as error:
            raise ValueError("GITHUB_REPOSITORY 必须是 <owner>/<repo>") from error
        self.base_url = f"{api_url.rstrip('/')}/repos/{quote(owner, safe='')}/{quote(name, safe='')}"
        self.token = token

    def request(self, method: str, path: str) -> Any:
        request = Request(
            f"{self.base_url}/{path.lstrip('/')}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
            method=method,
        )
        try:
            with urlopen(request, timeout=30) as response:
                body = response.read()
        except HTTPError as error:
            raise RuntimeError(f"GitHub API {method} 失败：HTTP {error.code}（{path}）") from None
        except URLError as error:
            raise RuntimeError(f"GitHub API {method} 连不上：{path}: {error.reason}") from None

        if method == "DELETE" or not body:
            return None
        return json.loads(body)

    def list_all(self, endpoint: str, collection_key: str) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        page = 1
        while True:
            query = urlencode({"per_page": PAGE_SIZE, "page": page})
            payload = self.request("GET", f"{endpoint}?{query}")
            page_items = payload.get(collection_key)
            if not isinstance(page_items, list):
                raise RuntimeError(f"接口响应缺少 {collection_key}：{endpoint}")
            items.extend(page_items)
            if len(page_items) < PAGE_SIZE:
                return items
            page += 1


def is_project_artifact(name: str) -> bool:
    """按前缀判断是否本项目自己的对象（大小写不敏感）。"""
    lowered = name.lower()
    return any(lowered.startswith(prefix) for prefix in PROJECT_PREFIXES)


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def run() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="只打印计划，不删除")
    args = parser.parse_args()

    keep = int(os.environ.get("KEEP_ARTIFACT", str(DEFAULT_KEEP)))
    if keep < 1:
        raise ValueError("KEEP_ARTIFACT 必须是正整数")

    repository = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    if not token:
        raise ValueError("缺少 GITHUB_TOKEN")
    if not run_id:
        raise ValueError("缺少 GITHUB_RUN_ID")

    api = GitHubAPI(repository, token, api_url)

    # 本次运行的开始时间：比它更新的对象一律延后，避免和并发运行抢
    current_run = api.request("GET", f"actions/runs/{quote(run_id, safe='')}")
    run_started = parse_time(current_run["created_at"])

    candidates: list[dict[str, Any]] = []
    non_release: list[dict[str, Any]] = []
    deferred: list[dict[str, Any]] = []
    current_visible = False
    for artifact in api.list_all("actions/artifacts", "artifacts"):
        if not is_project_artifact(artifact["name"]):
            continue
        artifact["_created_at"] = parse_time(artifact["created_at"])
        if str((artifact.get("workflow_run") or {}).get("id", "")) == run_id:
            current_visible = True
        if NON_RELEASE_NAME.match(artifact["name"]):
            non_release.append(artifact)
        elif artifact["_created_at"] > run_started:
            deferred.append(artifact)
        else:
            candidates.append(artifact)

    if not current_visible:
        raise RuntimeError("本次运行的 artifact 还不可见：放弃清理，避免删掉刚产出的包")

    candidates.sort(key=lambda item: (item["_created_at"], int(item["id"])), reverse=True)
    kept, to_delete = candidates[:keep], candidates[keep:]
    kept_ids = {artifact["id"] for artifact in kept}

    print(
        f"本项目（前缀 {'、'.join(PROJECT_PREFIXES)}）发行对象 {len(candidates)} 个；"
        f"保留最近 {len(kept)} 个；非发行 {len(non_release)} 个（不占名额）；延后 {len(deferred)} 个"
    )
    for artifact in candidates:
        tag = "KEEP  " if artifact["id"] in kept_ids else "DELETE"
        print(
            f"{tag} id={artifact['id']} name={artifact['name']} "
            f"created={artifact['created_at']} size={artifact['size_in_bytes']}B"
        )
    for artifact in non_release:
        print(f"SKIP   id={artifact['id']} name={artifact['name']} created={artifact['created_at']}（非发行，按 5 天过期）")
    for artifact in deferred:
        print(f"DEFER  id={artifact['id']} name={artifact['name']} created={artifact['created_at']}")

    deleted = 0
    verb = "WOULD DELETE" if args.dry_run else "DELETE"
    for artifact in to_delete:
        print(f"{verb} id={artifact['id']} name={artifact['name']}")
        if not args.dry_run:
            api.request("DELETE", f"actions/artifacts/{artifact['id']}")
            deleted += 1

    print(f"完成：{'计划删除' if args.dry_run else '已删除'} {len(to_delete)} 个（实删 {deleted}），保留 {len(kept)} 个。")
    return 0


def main() -> int:
    try:
        return run()
    except (KeyError, TypeError, ValueError, RuntimeError, HTTPError) as error:
        print(f"artifact 清理中止：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
