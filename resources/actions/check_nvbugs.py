#!/usr/bin/env python3
"""Verify an NVBugs bug matches a configured module and assigned engineer.

Pure HTTP client. The caller (the `nvbugs` ci-demo module) is responsible
for extracting the bug ID and commit author email from Jenkins state and
passing them in via --bug-id and --author-email.

Reads the API Bearer token from NVBUGS_API_TOKEN env var.

Exit codes:
  0 - bug checks passed
  1 - bug-check failed (module mismatch, email mismatch, API error)
"""

import argparse
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_API_URL = "https://prod.api.nvidia.com/int/nvbugs"
# Allow-list of trusted NVBugs API hosts. The Bearer token is sent here, so
# the list is restricted to known endpoints from the NVBugs REST API docs.
ALLOWED_API_HOSTS = frozenset({
    "prod.api.nvidia.com",
    "dev.api.nvidia.com",
    "nvbugsapi.nvidia.com",
    "stbugsapi.nvidia.com",
})
MODULE_PLACEHOLDER = "TBD_FILL_IN"


def log(msg):
    print(f"[check_nvbugs] {msg}", flush=True)


def fail(msg):
    log(f"FAIL: {msg}")
    sys.exit(1)


def warn(msg):
    log(f"WARN: {msg}")


def parse_args():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--bug-id", required=True, type=int)
    p.add_argument("--author-email", required=True)
    p.add_argument("--commit-sha", default="",
                   help="Commit SHA for log context; not used for any check.")
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--module-id",
                   help="Numeric NVBugs ModuleInfo.Key to match against.")
    g.add_argument("--module-name",
                   help="NVBugs ModuleInfo.Value to match against.")
    p.add_argument("--on-email-mismatch", choices=["warn", "fail"], default="warn")
    p.add_argument("--on-bug-not-in-module", choices=["warn", "fail"], default="fail")
    p.add_argument("--api-url", default=DEFAULT_API_URL)
    return p.parse_args()


def validate_api_url(api_url):
    parsed = urllib.parse.urlparse(api_url)
    if parsed.scheme != "https":
        fail(f"refusing to send Bearer token over non-HTTPS scheme {parsed.scheme!r}")
    host = (parsed.hostname or "").lower()
    if host not in ALLOWED_API_HOSTS:
        fail(f"--api-url host {host!r} is not in the allow-list "
             f"{sorted(ALLOWED_API_HOSTS)}")


def fetch_bug(api_url, bug_id, token):
    url = f"{api_url.rstrip('/')}/api/Bug/GetBug/{bug_id}"
    req = urllib.request.Request(url)
    req.add_header("Content-type", "application/json")
    req.add_header("Authorization", f"Bearer {token}")
    log(f"GET {url}")
    try:
        with urllib.request.urlopen(req, timeout=30,
                                    context=ssl.create_default_context()) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            fail(f"bug {bug_id} not found (HTTP 404)")
        fail(f"API error {e.code} {e.reason}")
    except urllib.error.URLError as e:
        fail(f"connection error: {e.reason}")
    except json.JSONDecodeError as e:
        fail(f"response parse error: {e}")
    return data.get("ReturnValue") or {}


def main():
    args = parse_args()

    if args.module_id == MODULE_PLACEHOLDER or args.module_name == MODULE_PLACEHOLDER:
        fail(f"module identifier still set to placeholder {MODULE_PLACEHOLDER!r}")

    validate_api_url(args.api_url)

    author_email = (args.author_email or "").strip().lower()
    log(f"Commit         : {args.commit_sha or '<unknown>'}")
    log(f"NVBug reference: {args.bug_id}")

    token = os.environ.get("NVBUGS_API_TOKEN")
    if not token:
        fail("NVBUGS_API_TOKEN env var not set (expected from Jenkins credentialsId)")

    bug = fetch_bug(args.api_url, args.bug_id, token)
    if not bug:
        fail(f"bug {args.bug_id}: empty ReturnValue from API")

    log(f"Bug {args.bug_id} BugAction: {(bug.get('BugAction') or {}).get('Value')}")

    module_info = bug.get("ModuleInfo") or {}
    bug_module_id = module_info.get("Key")
    bug_module_name = module_info.get("Value")
    log(f"Bug {args.bug_id} Module   : Key={bug_module_id} Value={bug_module_name!r}")

    if args.module_id is not None:
        try:
            module_ok = str(bug_module_id) == str(int(args.module_id))
        except ValueError:
            fail(f"--module-id must be an integer, got {args.module_id!r}")
    else:
        module_ok = (bug_module_name or "").strip().lower() == args.module_name.strip().lower()

    failures = []
    if not module_ok:
        expected = args.module_id if args.module_id is not None else args.module_name
        msg = (f"bug {args.bug_id} is in module {bug_module_name!r} "
               f"(Key={bug_module_id}), expected {expected!r}")
        if args.on_bug_not_in_module == "fail":
            failures.append(msg)
        else:
            warn(msg)

    bug_email = (bug.get("EngineerEmailID") or "").strip().lower()
    if not author_email:
        msg = "commit author email is empty"
        if args.on_email_mismatch == "fail":
            failures.append(msg)
        else:
            warn(msg)
    elif bug_email != author_email:
        msg = (f"bug {args.bug_id} Engineer email {bug_email!r} does not match "
               f"commit author email {author_email!r}")
        if args.on_email_mismatch == "fail":
            failures.append(msg)
        else:
            warn(msg)

    if failures:
        for msg in failures:
            log(f"FAIL: {msg}")
        sys.exit(1)

    log(f"OK: bug {args.bug_id} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
