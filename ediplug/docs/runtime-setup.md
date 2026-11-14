# Running the EDIPLUG OpenAS2 image: data directory and runtime properties

Applies to image `openas2-ediplug:*-v2` (= `openas2:4.15.0-ediplug-s7`) and later. Earlier images (s5, s6) have no
override folder; see "Older images" at the end.

## What the container needs

| Container path | What | Provided by |
|---|---|---|
| `/opt/openas2/config_template/` | `config.xml`, `openas2.properties`, `logback.xml`, CA bundles | the image (read-only, never mount) |
| `/opt/openas2/config/` | working copy, filled from the template on a fresh container; `openas2.properties` is rebuilt on **every** start | the entrypoint (do not mount) |
| `/opt/openas2/override/openas2.properties` | optional: your property changes | **you**: mount a folder, read-only |
| `/opt/openas2/data/` | `inbox/`, `outbox/<sender>/<receiver>/`, `outbox/error/`, `mdn/`, `pendingMDN3/`, `pendinginfoMDN3/`, `resend/`, `sent/`, `temp/` | **you**: mount a folder or volume, writable by UID 1000 |
| `/opt/openas2/logs/` | daily log file (same lines as `docker logs`) | container (mount only if you want the files on the host) |
| environment | `AS2_DB_*`, `AS2_KEY_ENCRYPTION_PASSWORD`, `AS2_KEYSTORE_PASSWORD`, `SMTP_*` | `.env` |

Partners, partnerships, key pairs, partner certificates and trusted CAs are read from PostgreSQL. There are no key
files. The container runs as **UID 1000, GID 0**.

### Runtime properties (`override/openas2.properties`)

At every start the entrypoint (`/opt/openas2/bin/ediplug-entrypoint.sh`) builds `config/openas2.properties`:

| Situation | Effective properties |
|---|---|
| no `override/openas2.properties` | the image's file |
| file present, `OPENAS2_PROPERTIES_OVERRIDE_MODE=merge` (default) | image file, then your file appended; for a key in both, **your value wins** (Java keeps the last value of a repeated key) |
| file present, `OPENAS2_PROPERTIES_OVERRIDE_MODE=replace` | your file only (must be complete) |
| `OPENAS2_PROPERTIES_FILE` set to another path | that file as-is (the s5/s6 way) |

- Put only changed keys in a merge file. Image upgrades then still bring their new defaults.
- Secrets never go in the file. Use `$ENV{NAME}`; an unset variable stops OpenAS2 at startup.
- Properties are read at startup, so edit the file, then `docker compose restart openas2`. Partners and certificates
  in the database do not need a restart (live within 30 s).
- The log shows which keys were overridden (names only), e.g.
  `[ediplug-entrypoint] Merged runtime properties from /opt/openas2/override/openas2.properties ...; keys: pollerConfigBase.fileextensionfilter`.
- Windows line endings (CRLF) in the file are stripped.
- Template: `ediplug/deploy/override/openas2.properties.example`. Image defaults: `ediplug/config/openas2.properties`.

### The data directory

Keep the container path `/opt/openas2/data` and choose the **host** side with the volume mount. Changing
`storageBaseDir` is possible but only needed if you mount the data somewhere else inside the container. The
entrypoint checks before the JVM starts that the directory is writable and stops with
`data directory ... is not writable by UID 1000` otherwise. Subfolders are created on demand.

### Images and CPU architecture

| Tag | Runs on |
|---|---|
| `openas2-ediplug:multiarch-v2`, `:latest` | both: Docker picks the matching variant |
| `openas2-ediplug:arm64-v2` | ARM64: OCI Ampere A1, AWS Graviton, Apple silicon, Windows on ARM |
| `openas2-ediplug:x86-v2` | x86-64: Intel/AMD PCs (Windows 11 + Docker Desktop), OCI E4/E5 standard, AWS t3/m6i |

Windows 11 runs this image as a **Linux** container (Docker Desktop, WSL 2 backend); there is no Windows-container
build.

---

## 1. Local on Windows 11 (Docker Desktop)

1. Install Docker Desktop with the **WSL 2** backend (default) and Ubuntu from the Microsoft Store.
2. Get the image, either from ECR:
   ```powershell
   aws ecr get-login-password --region ap-south-1 | docker login --username AWS --password-stdin <account>.dkr.ecr.ap-south-1.amazonaws.com
   docker pull <account>.dkr.ecr.ap-south-1.amazonaws.com/openas2-ediplug:x86-v2
   ```
   or from a saved file: `docker load -i openas2-ediplug-x86-v2.tar.gz`.
3. Make the project folder **inside WSL** (Ubuntu shell). Bind mounts from `C:\` are slow and case-insensitive,
   and AS2 IDs are case-sensitive (`outbox/ACME` and `outbox/acme` would be the same folder):
   ```sh
   mkdir -p ~/ediplug && cd ~/ediplug
   # copy from the repo: ediplug/deploy/{docker-compose.local.yml,.env.example,override/}
   cp .env.example .env            # OPENAS2_IMAGE=openas2-ediplug:x86-v2 (or the ECR name); DB + key passwords
   mkdir -p data && sudo chown -R 1000:0 data && sudo chmod -R g+rwX data
   ```
   Edit with VS Code (`code .` from WSL). Save `.env` with LF line endings. In `.env`, `$` must be written `$$`.
   Alternative: a named volume (set `AS2_DATA_DIR=as2data` and uncomment `volumes:` in
   `docker-compose.local.yml`); reach the files with `docker cp` or `docker exec`.
4. Optional property changes: `cp override/openas2.properties.example override/openas2.properties` and keep only
   the lines you change, uncommented.
5. Database: either RDS dev (`AS2_DB_SSLMODE=verify-full`, your public IP allowed in the RDS security group) or a
   local PostgreSQL container created with `ediplug/db/create_env_databases.sql` (`AS2_DB_SSLMODE=disable`,
   `AS2_DB_HOST=host.docker.internal`).
6. Start and check:
   ```sh
   docker compose -f docker-compose.local.yml up -d
   docker logs -f EDIPLUG_AS2_LOCAL      # "[ediplug-entrypoint] Data directory ...", "OpenAS2 Server v4.15.0 started."
   curl http://localhost:10099/          # health
   ```
7. Test: put a `.edi` file in `data/outbox/<sender AS2 ID>/<receiver AS2 ID>/`; MDNs land in `data/mdn/`, received
   files in `data/inbox/`. From Windows Explorer the folder is `\\wsl$\Ubuntu\home\<you>\ediplug\data`.

## 2. On a Linux VM

```sh
sudo mkdir -p /opt/ediplug && sudo chown $USER: /opt/ediplug && cd /opt/ediplug
#   copy ediplug/deploy/{docker-compose.yml,.env.example,nginx/,override/} here (or clone a deploy repo)
cp .env.example .env && chmod 600 .env && nano .env      # OPENAS2_IMAGE, AS2_DB_*, passwords, SMTP
mkdir -p data && sudo chown -R 1000:0 data && sudo chmod -R g+rwX data
#   optional: override/openas2.properties from the .example
docker compose up -d
docker logs EDIPLUG_AS2_SERVER 2>&1 | grep -E "ediplug-entrypoint|Loaded|ERROR|started"
```

- Image tag must match the CPU (`uname -m`: `aarch64` = arm64, `x86_64` = x86), or use `multiarch-v2`.
- SELinux enforcing (Oracle Linux, RHEL): add `:z` to the bind mounts (`./data:/opt/openas2/data:rw,z`,
  `./override:/opt/openas2/override:ro,z`).
- Host log files (optional): `mkdir logs && sudo chown 1000:0 logs` and mount `./logs:/opt/openas2/logs`.
- Full first-time flow (database, load balancer, verification): `fresh-vm-setup.md`.

## 3. Active-passive on two VMs with shared storage (OCI)

**Shared storage: OCI File Storage Service (FSS)**, a managed NFS file system mounted on both VMs. Block Volume
multi-attach would need a cluster file system (OCFS2) on top. Object Storage is not a POSIX file system, so OpenAS2
cannot poll folders on it.

**Rule:** only one OpenAS2 runs against the shared `data/` at a time. Two running instances would both pick up the
same outbox file (duplicate sends) and both work the resend and pending-MDN queues.

What goes where:

| Item | Location |
|---|---|
| `data/` | **FSS**, mounted at the same path on both VMs |
| `docker-compose.yml`, `nginx/`, `override/openas2.properties` | each VM, identical (same git commit of a deploy repo) |
| `.env` (secrets) | each VM, identical (or fetched from OCI Vault at boot) |
| logs | each VM, local |
| database | RDS / OCI PostgreSQL (shared by nature) |

`override/` could also live on FSS (`/mnt/as2data/override`) so the two nodes can never differ. Keeping it in
git with the rest of the deploy folder lets changes be reviewed. Pick one and stick to it.

### Setup
1. Two VMs in **different fault domains** (Mumbai and Hyderabad have one availability domain each), same shape
   architecture, Docker, `/opt/ediplug` as in section 2.
2. FSS: create a file system and a mount target in the VMs' subnet. Export options: source = the two VMs' IPs (or the
   subnet), read/write, identity squash **None**. Security list / NSG: TCP 111, 2048-2050 and UDP 111, 2048 from the
   VMs to the mount target.
3. On both VMs:
   ```sh
   sudo apt-get install -y nfs-common            # Oracle Linux: nfs-utils
   sudo mkdir -p /mnt/as2data
   echo '<mount-target-ip>:/as2data /mnt/as2data nfs nfsvers=3,hard,timeo=600,retrans=2,_netdev,nosuid 0 0' | sudo tee -a /etc/fstab
   sudo mount -a
   ```
   Once, on one VM: `sudo mkdir -p /mnt/as2data/data && sudo chown -R 1000:0 /mnt/as2data/data && sudo chmod -R g+rwX /mnt/as2data/data`.
4. `docker-compose.yml` on both VMs: replace `./data:/opt/openas2/data:rw` with
   `/mnt/as2data/data:/opt/openas2/data:rw`. Remove the `autoheal` service and use `restart: unless-stopped`
   on `nginx` and `openas2`. With `restart: always`, Docker would start a stopped OpenAS2 again after a reboot of the
   passive VM. `storageBaseDir` stays unchanged.
5. OCI Flexible Load Balancer with a **reserved public IP** (partners allowlist one address). Listener HTTPS 443
   with the `as2.ediplug.com` certificate, backend set = both VMs on port 80, health check `HTTP GET /health`
   (nginx proxies it to OpenAS2's health port). The passive node fails the check, so traffic goes only to the active
   one.
6. Active node: `docker compose up -d`. Passive node: everything prepared (images pulled, `.env`, FSS mounted)
   but **nothing running** (`docker compose down`). nginx resolves `openas2` when it starts, so it cannot run
   without the OpenAS2 container anyway.

### Failover (manual)
1. **Fence the old active node**: `docker compose down` on it, or if it is unreachable stop the instance in the
   OCI console/CLI. Never start the second node while the first might still be running.
2. On the other node: `docker compose up -d`. Within one health-check interval the LB sends traffic to it.
3. Check `docker logs`: entrypoint lines, `Loaded ...`, `OpenAS2 Server v4.15.0 started.`, and any warning about files
   left in `pendingMDN3` by an interrupted send. The outbox drains, queued resends continue, and late async MDNs
   still match because the pending state is on FSS.
4. Fail back the same way.

Automating this later: a small supervisor on each node that holds a PostgreSQL advisory lock and runs OpenAS2 only
while it holds the lock. It must stop OpenAS2 as soon as it loses the DB connection. Test it well before relying
on it.

### NFS notes
- Files written into the outbox over NFS (e.g. by the orchestrator) should be written under a temporary name and
  renamed to `.edi` when complete. The extension filter (`edi,EDI`) then never sees a partial file.
- Protection: FSS snapshot policy for backups; FSS replication to another region for disaster recovery.
- OpenAS2 on OCI with the database on AWS RDS adds cross-cloud latency, egress cost and a dependency on both clouds.

---

## Older images (s5, s6)

No override folder. To change a property, copy the whole file out of the image, edit it, mount it as a single
file and point `OPENAS2_PROPERTIES_FILE` at it (it replaces the image's file):
```sh
docker run --rm --entrypoint cat openas2:4.15.0-ediplug-s6 /opt/openas2/config_template/openas2.properties > openas2.properties
# compose: - ./openas2.properties:/opt/openas2/custom/openas2.properties:ro
#          OPENAS2_PROPERTIES_FILE: /opt/openas2/custom/openas2.properties
```
This still works on v2 images.
