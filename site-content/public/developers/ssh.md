# SSH connections, commands, files and schedules

Bridgit can act as an SSH client from the Android phone. Use it for macOS, Linux,
Windows OpenSSH, NAS devices, servers, and other SSH hosts reachable by the phone.
The phone does the work; a chat or computer does not have to remain open.
A discovered device is only a hint, never a login or permission.

## Setup and trust

1. On the phone, enable **More → SSH connections → Allow SSH access**. Grant
   **SSH connections** to the intended agent. The settings API cannot enable this switch.
2. Add a connection on that screen, assigning it to an agent, or use the API below.
   Supply an authorized username and either a password or a PEM/OpenSSH private key
   (with its passphrase if encrypted). Password and keyboard-interactive are different:
   this version supports password and public-key authentication, not interactive MFA.
3. Verify the server's SHA-256 host-key fingerprint using a trusted source. On an
   authorized Mac/Linux host, a typical trusted-source command is
   `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256` (choose the server's
   actual negotiated key). `/ssh/probe` observes a key without sending credentials;
   its response alone does not establish trust. Confirm only after comparison.
4. Select the connection's grants: `exec`, `files_read`, `files_write`. A connection
   is private to its assigned agent. Shell access grants the remote account's full
   authority, including file access; the file grants do not sandbox shell commands.

Credentials are encrypted in app-private storage with Android Keystore and excluded
from backups. Inventory never returns passwords, keys or passphrases. SSH bodies are
redacted from the request audit log. Connection metadata is recorded in the outbound
log; commands, file contents and remote output are not included in that log.
Remote output can contain secrets and remains available to the authorized caller.
Do not put credentials in command text or schedule manifests.

## Common transport and limits

All paths below have prefix `/v1`, require the agent bearer token and the `ssh`
capability. The same endpoint schemas generate OpenAPI and MCP tools. Inspect the
installed phone's `/v1/guide`, `/v1/openapi.json` and MCP `tools/list` rather than
assuming a tool name. MCP uses its `idempotency_key` argument for the HTTP header.

Mutating endpoints and `/ssh/files` require **Idempotency-Key**. Use a unique key for
one intended operation, reuse it only to retry that same body. Cache lifetime is
bounded by Bridgit's normal idempotency policy; it is not permanent exactly-once
execution. After timeout, disconnect or an unknown outcome, inspect the remote host
before retrying. A remote command may keep running after its SSH channel closes.

At most two SSH API operations run concurrently. Each connection opens for one
operation and closes afterward. Connect timeout is up to 10 seconds; probe has a
12-second total budget and authentication test has 15 seconds. Command/file operations
accept `timeout_seconds` 1–120, default 30, including connection time. Revoking the
agent, replacing/removing its profile, switching SSH off, or pausing external calls
interrupts active connections. No automatic command retries, sudo, forwarding,
jump hosts, persistent interactive terminals, or remote process-termination guarantee.

Hostnames, IPv4 and unbracketed IPv6 plus ports 1–65535 are supported. Network routing
follows Android's current network/VPN policy. Remote hosts must be awake, reachable,
and running SSH. Discovery itself remains the separate direct-LAN feature.

## Connection API

| Method and path | Fields / result |
|---|---|
| `GET /ssh/connections` | Own connections with id, label, host, port, username, fingerprint, auth method, permissions; availability `not_checked` |
| `POST /ssh/probe` | `host`, optional `port` (22); returns fingerprint and algorithm, `verified:false`, `authenticated:false` |
| `POST /ssh/connections/save` | Full profile below; authenticates before saving; optional existing `connection` replaces only your own profile |
| `POST /ssh/connections/remove` | `connection`; forget credentials and interrupt active sessions |
| `POST /ssh/test` | `connection`; verify pin and login without executing a command |

Save fields: `name` (1–80 chars), `host`, `port` (default 22), `username` (1–128),
`fingerprint` (`SHA256:` plus 43 unpadded base64 characters), `confirm_host_key:true`,
exactly one of `password` (up to 4096 chars) or `private_key` (up to 32768 chars),
optional `passphrase` (up to 4096 chars), and nonempty `permissions` array containing
`exec`, `files_read`, and/or `files_write`. Supply all fields when replacing a
profile; omitted secrets are not silently retained. There are at most 64 profiles
on the phone. Changed host keys fail closed; independently verify the change before
replacing a connection. The owner can test and forget any connection on the phone.

## Command API

`POST /ssh/exec` requires the connection's `exec` grant.

| Field | Meaning |
|---|---|
| `connection` | Saved connection ID, required |
| `command` | Remote shell command, 1–32768 characters, required |
| `stdin` | UTF-8 standard input, at most 512 KiB; EOF follows |
| `cwd` | Optional safely quoted working directory; requires a POSIX remote shell |
| `env` | Up to 64 SSH environment requests; server `AcceptEnv` controls acceptance |
| `timeout_seconds` | Total deadline 1–120; default 30 |
| `max_output_bytes` | 1–1048576 bytes **per stream**; default 65536 |
| `pty` | Request a pseudo-terminal; default false; not an interactive session |
| `terminal` | PTY type, default `xterm` |
| `columns`, `rows` | PTY dimensions 1–500; defaults 80 × 24 |

Returns `exit_code` (null if unknown), `stdout`, `stderr`, `stdout_truncated`,
`stderr_truncated`, `pty`, and `timed_out:false`. Extra output is drained and discarded
so a verbose process cannot exhaust phone memory. Output is decoded as UTF-8; use
SFTP for exact binary bytes. PTYs may combine stderr with stdout and change line
endings. Nonzero exit codes are returned normally to interactive callers; the
scheduler treats nonzero/unknown exit codes as failure and does not run success chains.
Timeout/revocation/transport errors return an error with `ssh_error`; they do not
claim the command completed or that it stopped remotely.

Example Mac tasks after authorized enrollment:

```json
{"connection":"ssh:YOUR_ID","command":"say 'Your tea is ready'"}
```

```json
{"connection":"ssh:YOUR_ID","command":"osascript -e 'display notification \"Your backup finished\" with title \"Bridgit\"'"}
```

The remote OS controls GUI access, notification permissions, audio volume, login
sessions and sleep. An SSH exit code does not prove someone saw or heard the result.
Use `afplay` for a remote audio file or `open` for a document when the remote Mac's
session permits it. No command runs until an authorized caller requests it.

## SFTP API

`POST /ssh/files` takes `connection`, `action`, literal `path`, and optional
`timeout_seconds`. Relative paths start at the SFTP user's home; absolute paths are
accepted subject to the server's permissions/chroot. There is no wildcard expansion.

| Action | Grant | Additional options / result |
|---|---|---|
| `stat` | `files_read` | `follow_symlinks` default false; attributes: size, directory, symlink, octal permissions, uid, gid, modified_seconds |
| `list` | `files_read` | `limit` 1–1000 (200), `offset` entry index (0); entries and next_offset or null; directory order may change between pages |
| `read` | `files_read` | `offset` byte position (0), `max_bytes` 1–524288 (524288); base64 bytes, bytes count, eof, next_offset |
| `write` | `files_write` | `data_base64`, `write_mode`: replace (default) or append, `confirm:true`; bytes_written, write_mode, resulting size |
| `mkdir` | `files_write` | Create one directory; parents must exist |
| `rename` | `files_write` | `destination`, `confirm:true`; server may replace destination |
| `remove` | `files_write` | `confirm:true`; remove a file |
| `rmdir` | `files_write` | `confirm:true`; remove an empty directory; never recursive |
| `chmod` | `files_write` | `mode`: 3–4 octal digits, e.g. `600`, `0755`; `confirm:true` |

Writes accept up to 512 KiB decoded per call. For larger uploads, send sequential
chunks: replace for the first, append for the rest. Every chunk needs a distinct
idempotency key. Do not use concurrent writers. A failed transfer may leave a partial
file. For publishing a complete file, upload to a unique temporary path, check size
(and verify content/checksum using the remote account if needed), then rename it.
Random positional writes and atomic no-clobber replacement are not provided.
Read/list offsets are nonnegative integers up to 10^12. `write` requires offset zero.

## Scheduling on the phone

Enable and grant **Scheduled actions** as well as **SSH connections**. Submit an
ordinary `POST /v1/schedule` manifest referencing a saved SSH connection. The owner
approves the manifest on the phone before it runs. The scheduler supplies the
idempotency key for each run. Authentication/host pins and current permissions are
checked on every execution; credentials are looked up at run time, never embedded
in the manifest. Connection save/remove/probe endpoints cannot be scheduled. Authentication-only `/ssh/test` may be scheduled as a connectivity check.

```json
{
  "manifest": {
    "timezone": "Europe/Warsaw",
    "actions": [{
      "id": "tea-reminder",
      "name": "Speak on my Mac",
      "when": {"daily": ["16:00"]},
      "do": {
        "call": "POST /v1/ssh/exec",
        "body": {"connection": "ssh:YOUR_ID", "command": "say 'Tea time'", "timeout_seconds": 15}
      }
    }]
  }
}
```

Use `GET /v1/schedule` for approval state, next run and last result,
`POST /v1/schedule/run/tea-reminder` to run an already approved action immediately,
and `POST /v1/schedule/pause {"paused":true}` to pause. Outcomes arrive through the
existing `schedule.*` events. Scheduled SFTP reads/writes use the same file endpoint.
SSH command output can be kept with `save_as`; treat saved results as sensitive.
The phone, Bridgit listener, connectivity and remote SSH host must remain available.
Android scheduling remains subject to the existing battery/alarm policies and missed-run
grace rules; it is not a server-grade exact-time guarantee.

## Testing and implementation

`SshTest` covers validation, secret-safe inventory, grants and bounds. `SshProtocolTest`
runs real SSH handshakes, password/private-key authentication, host-key rejection,
commands, stdin, output limits, deadlines and SFTP round trips against the isolated
`tools/tests/ssh_fixture.py` fixture. Set `BRIDGIT_SSH_FIXTURE` to its metadata JSON
when running Gradle tests; protocol tests skip explicitly when no fixture is supplied.
The fixture does not execute shell commands and confines files to a temporary folder.

SSH transport uses [mwiede/JSch](https://github.com/mwiede/jsch), with modern library
algorithm defaults and mandatory host-key pins. No legacy RSA/SHA-1 fallback is enabled.
