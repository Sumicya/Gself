#!/usr/bin/env python3
"""Keep the newest artifact created by this workflow; delete older ones."""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

WORKFLOW_FILENAME = "android.yml"
WORKFLOW_PATH = ".github/workflows/android.yml"
PAGE_SIZE = 100


class GitHubAPI:
    def __init__(self, repository: str, token: str, api_url: str) -> None:
        try:
            owner, name = repository.split("/", 1)
        except ValueError as error:
            raise ValueError("GITHUB_REPOSITORY must be <owner>/<repo>") from error
        self.base_url = (
            f"{api_url.rstrip('/')}/repos/{quote(owner, safe='')}/{quote(name, safe='')}"
        )
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
            raise RuntimeError(
                f"GitHub API {method} failed with HTTP {error.code} for {path}"
            ) from None
        except URLError as error:
            raise RuntimeError(f"GitHub API {method} could not reach {path}: {error.reason}") from None

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
                raise RuntimeError(f"GitHub API response omitted {collection_key}")
            items.extend(page_items)
            if len(page_items) < PAGE_SIZE:
                return items
            page += 1


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def run() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dry-run", action="store_true", help="show the cleanup plan without deleting artifacts"
    )
    args = parser.parse_args()

    raw_keep = os.environ.get("KEEP_ARTIFACT", "1")
    try:
        keep_count = int(raw_keep)
    except ValueError:
        raise ValueError("KEEP_ARTIFACT must be a positive integer") from None
    if keep_count < 1:
        raise ValueError("KEEP_ARTIFACT must be a positive integer")

    repository = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    current_run_id = os.environ.get("GITHUB_RUN_ID", "")
    if not token:
        raise ValueError("GITHUB_TOKEN is required")
    if not current_run_id:
        raise ValueError("GITHUB_RUN_ID is required")

    api = GitHubAPI(repository, token, api_url)
    workflow = api.request("GET", f"actions/workflows/{quote(WORKFLOW_FILENAME, safe='')}")
    if workflow.get("path") != WORKFLOW_PATH:
        raise RuntimeError(
            f"Expected workflow at {WORKFLOW_PATH}, found {workflow.get('path')!r}; refusing cleanup"
        )
    workflow_id = workflow["id"]

    workflow_runs = api.list_all(
        f"actions/workflows/{workflow_id}/runs", "workflow_runs"
    )
    runs_by_id = {str(item["id"]): item for item in workflow_runs}
    current_run = runs_by_id.get(current_run_id)
    if current_run is None:
        raise RuntimeError(
            "Current run is not listed under this workflow; refusing to delete artifacts"
        )
    run_started = parse_time(current_run["created_at"])

    all_artifacts = api.list_all("actions/artifacts", "artifacts")
    candidates: list[dict[str, Any]] = []
    deferred: list[dict[str, Any]] = []
    current_run_has_artifact = False

    for artifact in all_artifacts:
        if artifact.get("expired"):
            continue
        run_info = artifact.get("workflow_run") or {}
        artifact_run_id = str(run_info.get("id", ""))
        if artifact_run_id not in runs_by_id:
            continue

        artifact["_created_at"] = parse_time(artifact["created_at"])
        if artifact_run_id == current_run_id:
            current_run_has_artifact = True
            candidates.append(artifact)
        elif artifact["_created_at"] > run_started:
            # A concurrent run may upload after this cleanup started. Leave it untouched.
            deferred.append(artifact)
        else:
            candidates.append(artifact)

    if not current_run_has_artifact:
        raise RuntimeError(
            "The current run's artifact is not visible yet; refusing to delete anything"
        )

    candidates.sort(
        key=lambda item: (item["_created_at"], int(item["id"])), reverse=True
    )
    kept = candidates[:keep_count]
    to_delete = candidates[keep_count:]

    print(
        f"Workflow: {workflow.get('name', WORKFLOW_FILENAME)} (id {workflow_id}); "
        f"visible artifacts: {len(candidates)}; keep: {len(kept)}; "
        f"defer concurrent newer artifacts: {len(deferred)}"
    )
    for artifact in kept:
        print(f"KEEP id={artifact['id']} name={artifact['name']} created={artifact['created_at']}")
    for artifact in deferred:
        print(
            f"DEFER id={artifact['id']} name={artifact['name']} "
            f"created={artifact['created_at']} (newer concurrent run)"
        )

    for artifact in to_delete:
        action = "WOULD DELETE" if args.dry_run else "DELETE"
        print(
            f"{action} id={artifact['id']} name={artifact['name']} "
            f"created={artifact['created_at']}"
        )
        if not args.dry_run:
            api.request("DELETE", f"actions/artifacts/{artifact['id']}")

    print(f"Cleanup complete: {'planned' if args.dry_run else 'deleted'} {len(to_delete)} artifact(s).")
    return 0


def main() -> int:
    try:
        return run()
    except (KeyError, TypeError, ValueError, RuntimeError, HTTPError) as error:
        print(f"Artifact cleanup aborted: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
