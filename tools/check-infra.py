"""Verify real local infrastructure. No third-party Python packages required.

Use --seed once, then restart Compose services and run without --seed to prove
persistence. Only the dedicated infra_probe database/collection/object is used.
Credentials stay in .env and never appear in console output or process arguments.
"""
import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROBE = b"pet-hospital-infra-persistence-v1"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(method, url, headers=None, body=None):
    req = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    try:
        with OPENER.open(req, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def s3(method, path, env, body=b""):
    now = dt.datetime.now(dt.timezone.utc)
    stamp, date = now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")
    digest = hashlib.sha256(body).hexdigest()
    headers = {
        "host": "127.0.0.1:8333",
        "x-amz-content-sha256": digest,
        "x-amz-date": stamp,
    }
    canonical_headers = "".join(f"{key}:{headers[key]}\n" for key in sorted(headers))
    signed = ";".join(sorted(headers))
    canonical = "\n".join([method, path, "", canonical_headers, signed, digest])
    scope = f"{date}/us-east-1/s3/aws4_request"
    to_sign = "\n".join(["AWS4-HMAC-SHA256", stamp, scope,
                          hashlib.sha256(canonical.encode()).hexdigest()])
    key = ("AWS4" + env["S3_SECRET_KEY"]).encode()
    for part in (date, "us-east-1", "s3", "aws4_request"):
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest()
    headers["Authorization"] = (
        f"AWS4-HMAC-SHA256 Credential={env['S3_ACCESS_KEY']}/{scope}, "
        f"SignedHeaders={signed}, Signature={signature}"
    )
    return request(method, "http://127.0.0.1:8333" + path, headers,
                   body if method == "PUT" else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", action="store_true")
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            env[key] = value
    for key in ("MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD", "QDRANT_API_KEY",
                "S3_ACCESS_KEY", "S3_SECRET_KEY"):
        require(bool(env.get(key)) and env[key] != "CHANGE_ME", f"Configure {key} first")

    # Reuse the container's configured credential without placing it in arguments.
    # Only our isolated probe database is written; application schemas are untouched.
    sql = ""
    if args.seed:
        sql += ("CREATE DATABASE IF NOT EXISTS infra_probe; "
                "CREATE TABLE IF NOT EXISTS infra_probe.persistence "
                "(id INT PRIMARY KEY, value VARCHAR(100) NOT NULL); "
                "INSERT INTO infra_probe.persistence VALUES (1, 'pet-hospital-infra-persistence-v1') "
                "ON DUPLICATE KEY UPDATE value=VALUES(value); ")
    sql += "SELECT value FROM infra_probe.persistence WHERE id=1;"
    process_env = dict(os.environ)
    process_env["COMPOSE_ANSI"] = "never"
    result = subprocess.run(
        [args.docker, "compose", "--env-file", str(ROOT / ".env"),
         "-f", str(ROOT / "infra/compose.yaml"), "exec", "-T", "mysql", "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root --batch --skip-column-names'],
        input=sql + "\n", text=True,
        capture_output=True, timeout=30, env=process_env, cwd=ROOT,
    )
    if result.returncode != 0 or result.stdout.strip() != PROBE.decode():
        detail = result.stderr.strip()
        for value in env.values():
            if value and len(value) >= 8:
                detail = detail.replace(value, "[redacted]")
        raise RuntimeError("MySQL persistence query failed (wait for healthy; seed first): " + detail[:500])
    print("PASS MySQL authenticated SQL and persisted value")

    qheaders = {"api-key": env["QDRANT_API_KEY"], "Content-Type": "application/json"}
    qbase = "http://127.0.0.1:6333/collections/infra_probe"
    if args.seed:
        status, _ = request("GET", qbase, qheaders)
        if status == 404:
            status, _ = request("PUT", qbase, qheaders,
                                json.dumps({"vectors": {"size": 512, "distance": "Cosine"}}).encode())
            require(status == 200, "Qdrant collection creation failed")
        else:
            require(status == 200, "Qdrant authenticated collection read failed")
        point = {"points": [{"id": 1, "vector": [1.0] + [0.0] * 511,
                              "payload": {"probe": PROBE.decode()}}]}
        status, _ = request("PUT", qbase + "/points?wait=true", qheaders, json.dumps(point).encode())
        require(status == 200, "Qdrant point write failed")
    status, data = request("GET", qbase + "/points/1", qheaders)
    require(status == 200 and json.loads(data)["result"]["payload"]["probe"] == PROBE.decode(),
            "Qdrant persistence check failed")
    status, _ = request("GET", qbase)
    require(status in (401, 403), "Qdrant anonymous access must be denied")
    print("PASS Qdrant authenticated point read, persistence, anonymous access denied")

    path = "/pet-private/infra-probe/persistence.txt"
    if args.seed:
        status, _ = s3("PUT", path, env, PROBE)
        require(status in (200, 201), f"S3 signed PUT failed: HTTP {status}")
    status, data = s3("GET", path, env)
    require(status == 200 and data == PROBE, f"S3 signed GET/persistence failed: HTTP {status}")
    status, _ = request("GET", "http://127.0.0.1:8333" + path)
    require(status in (401, 403), "S3 anonymous object read must be denied")
    print("PASS S3 signed PUT/GET or persisted GET, anonymous object read denied")
    print("All infrastructure checks passed. Probe data is retained for restart verification.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        # Do not print HTTP response bodies or subprocess output containing credentials.
        print(f"FAIL {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
