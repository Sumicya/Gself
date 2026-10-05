#!/usr/bin/env python3
"""Gself 的 Actions artifact 滚动保留。

只清理 Android CI（.github/workflows/android.yml）这个工作流自己产生的 artifact：
按 workflow_run.id 属于该工作流的运行来筛选，不碰其它工作流、Release、tag 或手工上传的对象。

保留名额只算**发行对象**：本工作流产出的产物里，除 PR 检查 / 手动构建其它分支产生的
非发行构建（`Gself-dev-<构建数>`）之外，按创建时间倒序保留最近 KEEP_ARTIFACT 个（默认 5，
必须为正整数），其余删除；历史上一代命名（`fcmself-*`）也在保留对象里，一并纳入既有积压清理。
非发行构建不占名额，交给 upload-artifact 的 `retention-days: 5` 自然过期——否则它们会把发行产物
挤出保留窗口，让下载入口取不到包。

只在 main 出包成功后运行（由 workflow 控制）；删除前二次确认：
  - 本次运行自己的 artifact 必须已经可见，否则整轮放弃，不删任何东西；
  - 比本次运行更新的 artifact（并发运行刚传的）一律延后处理，不删。

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

WORKFLOW_FILENAME = "android.yml"
WORKFLOW_PATH = ".github/workflows/android.yml"
PAGE_SIZE = 100
DEFAULT_KEEP = 5
# 非发行构建的命名（PR 检查 / 手动构建其它分支）：不占保留名额，按 retention-days 过期
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
        """按 per_page=100 完整分页取全量，不用固定 limit 当清单。"""
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

    # 1) 锁定工作流：按文件路径核对，避免误清到别的工作流
    workflow = api.request("GET", f"actions/workflows/{quote(WORKFLOW_FILENAME, safe='')}")
    if workflow.get("path") != WORKFLOW_PATH:
        raise RuntimeError(f"工作流路径应为 {WORKFLOW_PATH}，实际是 {workflow.get('path')!r}：放弃清理")
    workflow_id = workflow["id"]

    # 2) 该工作流的全部运行 id（完整分页）
    runs_by_id = {
        str(item["id"]): item for item in api.list_all(f"actions/workflows/{workflow_id}/runs", "workflow_runs")
    }
    current_run = runs_by_id.get(run_id)
    if current_run is None:
        raise RuntimeError("本次运行不在这条工作流的运行列表里：放弃清理")
    run_started = parse_time(current_run["created_at"])

    # 3) 全部 artifact（完整分页），只留属于该工作流的
    release_candidates: list[dict[str, Any]] = []
    non_release: list[dict[str, Any]] = []
    deferred: list[dict[str, Any]] = []
    current_visible = False
    for artifact in api.list_all("actions/artifacts", "artifacts"):
        artifact_run_id = str((artifact.get("workflow_run") or {}).get("id", ""))
        if artifact_run_id not in runs_by_id:
            continue
        artifact["_created_at"] = parse_time(artifact["created_at"])
        if artifact_run_id == run_id:
            current_visible = True
        if NON_RELEASE_NAME.match(artifact["name"]):
            # 非发行构建：不占保留名额，交给 retention-days 自然过期
            non_release.append(artifact)
        elif artifact["_created_at"] > run_started:
            # 并发运行可能在本轮开始后上传，延迟到下一轮再判断
            deferred.append(artifact)
        else:
            release_candidates.append(artifact)

    if not current_visible:
        raise RuntimeError("本次运行的 artifact 还不可见：放弃清理，避免删掉刚产出的包")

    # 4) 发行产物按创建时间倒序，保留最近 keep 个
    release_candidates.sort(key=lambda item: (item["_created_at"], int(item["id"])), reverse=True)
    kept, to_delete = release_candidates[:keep], release_candidates[keep:]

    print(
        f"工作流 {workflow.get('name', WORKFLOW_FILENAME)}（id {workflow_id}）；"
        f"保留对象（发行产物 / 历史命名）{len(release_candidates)} 个；保留 {len(kept)} 个；"
        f"非发行构建 {len(non_release)} 个（不占名额，按 5 天过期）；"
        f"延后（并发更新的）{len(deferred)} 个"
    )
    for artifact in kept:
        print(f"KEEP   id={artifact['id']} name={artifact['name']} created={artifact['created_at']}")
    for artifact in deferred:
        print(f"DEFER  id={artifact['id']} name={artifact['name']} created={artifact['created_at']}")
    for artifact in non_release:
        print(f"SKIP   id={artifact['id']} name={artifact['name']} created={artifact['created_at']}（非发行构建）")

    deleted = 0
    for artifact in to_delete:
        print(
            f"{'WOULD DELETE' if args.dry_run else 'DELETE'} "
            f"id={artifact['id']} name={artifact['name']} created={artifact['created_at']}"
        )
        if not args.dry_run:
            api.request("DELETE", f"actions/artifacts/{artifact['id']}")
            deleted += 1

    print(f"完成：{'计划删除' if args.dry_run else '已删除'} {len(to_delete)} 个 artifact（实删 {deleted}）。")
    return 0


def main() -> int:
    try:
        return run()
    except (KeyError, TypeError, ValueError, RuntimeError, HTTPError) as error:
        print(f"artifact 清理中止：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
