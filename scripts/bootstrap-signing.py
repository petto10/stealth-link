"""One-time provisioning of Stealth Link release signing in a trusted GitHub runner."""

import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request

REPOSITORY = "petto10/stealth-link"
TAG = "v0.1.0-alpha"
CLI_CLIENT = "178c6fc778ccc68e1d6a"
SECRET_NAMES = {
    "ANDROID_SIGNING_CERT_SHA256",
    "ANDROID_SIGNING_KEYSTORE_B64",
    "ANDROID_SIGNING_PASSWORD",
}


def oauth(path, fields):
    body = urllib.parse.urlencode({"client_id": CLI_CLIENT, **fields}).encode()
    request = urllib.request.Request(
        "https://github.com/" + path,
        data=body,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def prepare(private_directory, public_directory):
    public_key = base64.b64decode(os.environ["REPLY_PUBLIC_KEY"], validate=True)
    private_directory.mkdir(mode=0o700)
    public_directory.mkdir(mode=0o700)
    public_key_file = private_directory / "reply-key.pem"
    public_key_file.write_bytes(public_key)
    subprocess.run(
        ["openssl", "pkey", "-pubin", "-in", str(public_key_file), "-noout"],
        check=True,
    )

    challenge = oauth("login/device/code", {"scope": "repo"})
    if challenge.get("verification_uri") != "https://github.com/login/device":
        raise RuntimeError("GitHub returned an unexpected verification address.")
    state = {
        "code": challenge["device_code"],
        "interval": int(challenge["interval"]),
        "expires_at": time.time() + int(challenge["expires_in"]),
    }
    (private_directory / "device-state.json").write_text(json.dumps(state))
    reply = json.dumps({
        "user_code": challenge["user_code"],
        "verification_uri": challenge["verification_uri"],
        "expires_at": state["expires_at"],
        "run_id": os.environ["GITHUB_RUN_ID"],
    })
    encrypted = subprocess.run(
        [
            "openssl", "pkeyutl", "-encrypt", "-pubin", "-inkey", str(public_key_file),
            "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256",
        ],
        input=reply.encode(),
        check=True,
        stdout=subprocess.PIPE,
    ).stdout
    (public_directory / "challenge.b64").write_bytes(base64.b64encode(encrypted))


def wait_for_owner(private_directory):
    state = json.loads((private_directory / "device-state.json").read_text())
    interval = state["interval"]
    while time.time() < state["expires_at"]:
        time.sleep(interval)
        answer = oauth(
            "login/oauth/access_token",
            {
                "device_code": state["code"],
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            },
        )
        if "access_token" in answer:
            return answer["access_token"]
        error = answer.get("error")
        if error == "slow_down":
            interval = int(answer.get("interval", interval + 5))
        elif error != "authorization_pending":
            raise RuntimeError("GitHub did not complete owner authorization.")
    raise RuntimeError("The device authorization expired.")


def gh(env, arguments, value=None):
    result = subprocess.run(
        ["gh", *arguments],
        env=env,
        input=value,
        stdout=subprocess.PIPE,
        check=True,
    )
    return result.stdout.decode().strip()


def provision(private_directory):
    env = dict(os.environ)
    env.pop("GITHUB_TOKEN", None)
    env.pop("GH_DEBUG", None)
    env["GH_TOKEN"] = wait_for_owner(private_directory)
    (private_directory / "device-state.json").unlink()

    if gh(env, ["api", "user", "--jq", ".login"]) != "petto10":
        raise RuntimeError("GitHub authorized a different account.")
    repository = gh(
        env, ["api", f"repos/{REPOSITORY}", "--jq", '.full_name + ":" + .visibility']
    )
    if repository != REPOSITORY + ":public":
        raise RuntimeError("The repository or its visibility changed.")
    gh(env, ["api", f"repos/{REPOSITORY}/actions/secrets/public-key", "--jq", ".key_id"])

    args = ["secret", "list", "--repo", REPOSITORY, "--json", "name", "--jq", ".[].name"]
    existing = {
        name for name in gh(env, args).splitlines() if name.startswith("ANDROID_SIGNING_")
    }
    if existing and existing != SECRET_NAMES:
        raise RuntimeError("The existing signing secrets are incomplete. Preserve them for review.")

    if not existing:
        keystore = private_directory / "stealth-link.p12"
        env["ANDROID_SIGNING_PASSWORD"] = secrets.token_hex(32)
        subprocess.run(
            [
                "keytool", "-genkeypair", "-alias", "stealth-link", "-storetype", "PKCS12",
                "-keystore", str(keystore), "-storepass:env", "ANDROID_SIGNING_PASSWORD",
                "-keypass:env", "ANDROID_SIGNING_PASSWORD", "-keyalg", "RSA",
                "-keysize", "3072", "-validity", "18262", "-dname",
                "CN=Stealth Link Android Release",
            ],
            env=env,
            check=True,
        )
        certificate = subprocess.run(
            [
                "keytool", "-exportcert", "-alias", "stealth-link", "-keystore", str(keystore),
                "-storepass:env", "ANDROID_SIGNING_PASSWORD",
            ],
            env=env,
            check=True,
            stdout=subprocess.PIPE,
        ).stdout
        fingerprint = hashlib.sha256(certificate).hexdigest()
        values = (
            ("ANDROID_SIGNING_CERT_SHA256", fingerprint.encode()),
            ("ANDROID_SIGNING_PASSWORD", env.pop("ANDROID_SIGNING_PASSWORD").encode()),
            ("ANDROID_SIGNING_KEYSTORE_B64", base64.b64encode(keystore.read_bytes())),
        )
        for name, value in values:
            gh(env, ["secret", "set", name, "--repo", REPOSITORY], value)
        if not SECRET_NAMES.issubset(set(gh(env, args).splitlines())):
            raise RuntimeError("GitHub did not confirm the full set of signing secrets.")
        print("RELEASE_CERT_SHA256=" + fingerprint, flush=True)

    gh(
        env, [
            "workflow", "run", "android-prerelease.yml", "--repo", REPOSITORY,
            "--ref", "main", "-f", f"tag={TAG}",
        ]
    )
    print("Signing secrets verified and prerelease workflow requested.", flush=True)


def main():
    if (
        os.environ.get("GITHUB_REPOSITORY") != REPOSITORY
        or os.environ.get("GITHUB_ACTOR") != "petto10"
        or os.environ.get("GITHUB_TRIGGERING_ACTOR") != "petto10"
        or os.environ.get("GITHUB_RUN_ATTEMPT") != "1"
        or os.environ.get("GITHUB_REF") != "refs/heads/main"
    ):
        raise RuntimeError("This setup must run as the repository owner on main.")

    os.umask(0o077)
    runner_temp = Path(os.environ["RUNNER_TEMP"])
    private_directory = runner_temp / "stealth-link-device-private"
    public_directory = runner_temp / "stealth-link-device-public"
    if sys.argv[1] == "prepare":
        prepare(private_directory, public_directory)
    elif sys.argv[1] == "provision":
        try:
            provision(private_directory)
        finally:
            shutil.rmtree(private_directory)
    else:
        raise RuntimeError("Unknown provisioning stage.")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, RuntimeError, subprocess.CalledProcessError, OSError, ValueError):
        print("Signing setup failed. Sensitive values were omitted.", file=sys.stderr)
        sys.exit(1)
