# Starting an EDIPLUG AS2 node on a fresh VM behind a load balancer

Target layout:

```
Trading partners --HTTPS--> DNS as2.ediplug.com --> Load balancer (TLS, ACM certificate)
                                                        |  HTTP :80
                                                        v
                                              VM: nginx :8080 --> OpenAS2 (10080 receive, 10081 MDN, 10099 health)
                                                        |
                                                        v
                                              AWS RDS PostgreSQL (partners, partnerships, keys, certs, tracking)
```

What lives where:
- **Image (ECR)**: OpenAS2 4.15 + EDIPLUG code + all static config (`config.xml`, `openas2.properties`, logback, CA bundles).
- **RDS**: everything that changes at runtime. A new VM needs no partner, key or certificate files.
- **VM**: `docker-compose.yml`, `nginx/nginx.conf`, `.env` (secrets), optional `override/openas2.properties` (runtime
  property changes) and `./data` (inbox, outbox, MDNs, pending state). See `runtime-setup.md`.

Files: `ediplug/deploy/` in the `ediplug/db-config` branch of the OpenAs2App fork.

---

## 0. Decisions before you start

1. **CPU architecture.** `openas2-ediplug:arm64-v2` for ARM64 (AWS Graviton t4g / m7g / c7g, OCI Ampere),
   `openas2-ediplug:x86-v2` for x86-64 hosts.
2. **TLS on a load balancer or on the VM.** This runbook uses a load balancer (`docker-compose.yml`). For a single VM
   that terminates TLS itself with Let's Encrypt, see "Single VM with TLS on the VM" at the end.
3. **Load balancer type.**
   - **NLB** (recommended for AS2): static Elastic IPs per AZ, which many retailers require for firewall
     allowlisting. Use a **TLS listener on 443 with the ACM certificate**, forwarding TCP to target port 80. To see
     real client IPs, enable *proxy protocol v2* on the target group and switch nginx to
     `listen 8080 proxy_protocol;` + `real_ip_header proxy_protocol;`.
   - **ALB**: simpler HTTP features and no static IPs. HTTPS listener with ACM forwarding HTTP to port 80; nginx
     reads `X-Forwarded-For`. Set the ALB idle timeout ≥ 300 s for large messages.
   - Either way: health check `HTTP GET /health` on port 80, healthy 200, interval 30 s.
4. **One active node at a time.** Outbox, pending-MDN and resend state live in the node's local `./data`. An
   asynchronous MDN that lands on another node cannot be matched to its message. Run a single active target
   (keep a stopped standby, or a second target only for failover). Active-passive with `./data` on shared storage
   (OCI File Storage / AWS EFS) is described in `runtime-setup.md`; never run two OpenAS2 on the same data.

## 1. AWS resources (once per environment)

| Resource | Settings |
|---|---|
| ECR repository | `openas2-ediplug`, scan on push; tags `arm64-v2`, `x86-v2` |
| RDS PostgreSQL 16+ (tested on 18.3) | Multi-AZ, encrypted, not publicly accessible, parameter `rds.force_ssl=1` |
| Security group `as2-lb` | inbound 443 from partners (or 0.0.0.0/0) |
| Security group `as2-node` | inbound 80 **only from `as2-lb`**; SSH only from your admin IP / SSM |
| Security group `as2-db` | inbound 5432 only from `as2-node` |
| IAM instance role | `AmazonEC2ContainerRegistryReadOnly` (+ `AmazonSSMManagedInstanceCore` if using SSM) |
| ACM certificate | `as2.ediplug.com` (DNS validation in Cloudflare) |
| DNS (Cloudflare) | `as2.ediplug.com` CNAME to the LB DNS name, **DNS only (grey cloud)**: AS2 must not go through the Cloudflare proxy |

## 2. Database (once per environment)

As the RDS master user, from any host that can reach RDS (the VM itself, or a bastion), run the single script.
It creates the environment's two databases (`ediplug-<env>` empty, `openas2-<env>` with every table, trigger and
grant) and the three roles, asks for anything not given, and is idempotent:

```sh
curl -fsSo ap-south-1-bundle.pem https://truststore.pki.rds.amazonaws.com/ap-south-1/ap-south-1-bundle.pem
EDIPLUG_ENV=prod EDIPLUG_APP_PASSWORD='<app role>' OPENAS2_RUNTIME_PASSWORD='<runtime role>' \
OPENAS2_ADMIN_PASSWORD='<writer role>' \
psql "host=<rds-endpoint> user=<master> dbname=postgres sslmode=verify-full sslrootcert=ap-south-1-bundle.pem" \
     -f ediplug/db/create_env_databases.sql
```

- `openas2_<env>` (runtime, used by the container): SELECT on config/certificate tables, SELECT/INSERT/UPDATE on
  `msg_metadata`, owns nothing. Use it as `AS2_DB_USER`, with `AS2_DB_NAME=openas2-<env>`.
- `as2_config_admin_<env>` (writer: the orchestrator): owns every table.
- `ediplug_app_<env>`: owner of `ediplug-<env>` (orchestrator tables come from its Alembic migrations).

Load data, either by **copying from another environment's database** (e.g. an existing `openas2-<env>`):

```sh
pg_dump "host=<source-host> user=<source owner> dbname=<source db> sslmode=verify-full sslrootcert=ap-south-1-bundle.pem" \
  -Fc --data-only \
  -t partner -t partner_attribute -t partnership -t partnership_attribute \
  -t as2_own_key -t as2_partner_certificate -t as2_ssl_trusted_ca -t msg_metadata > openas2-data.dump
# into the new database as its admin role, triggers enabled (version counters and alias checks run;
# config_version is seeded by create_env_databases.sql)
pg_restore -h <rds-endpoint> -U as2_config_admin_<env> -d openas2-<env> --data-only --single-transaction openas2-data.dump
```

or **from scratch** with the tools in the image (see `ediplug/README.md`):
`migrate_partnerships_to_db.sh` for a `partnerships.xml`, `ediplug/tools/p12-to-own-key-sql.sh` for key pairs
from a `.p12`, and plain `INSERT`s into `as2_partner_certificate` for partner PEMs.

Own private keys must be encrypted with the `AS2_KEY_ENCRYPTION_PASSWORD` the node will use. Keep that passphrase in
a password manager / AWS Secrets Manager: without it the keys in the database cannot be used.

## 3. Image (each release)

On a build host with the repository, Docker buildx and (on an ARM64 host, for the x86 variant) QEMU
(`sudo apt-get install docker-buildx qemu-user-static`):

```sh
REG=<account>.dkr.ecr.<region>.amazonaws.com
aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin $REG
# one image per architecture, straight to ECR; runs the test suite. --provenance/--sbom off: no untagged
# attestation entries in the repository
docker buildx build -f ediplug/Dockerfile --platform linux/arm64 --provenance=false --sbom=false -t $REG/openas2-ediplug:arm64-v2 --push .
docker buildx build -f ediplug/Dockerfile --platform linux/amd64 --provenance=false --sbom=false -t $REG/openas2-ediplug:x86-v2 --push .
```

## 4. The VM

Ubuntu 24.04 LTS, matching the image architecture, 2 vCPU / 4 GB minimum, 30 GB+ disk (inbox/MDN files grow;
monitor it). Attach the instance role and the `as2-node` security group.

```sh
# Docker Engine + compose v2 plugin (official repository)
sudo apt-get update && sudo apt-get install -y ca-certificates curl unzip
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker ubuntu && newgrp docker

# AWS CLI v2 (for ECR login via the instance role)
curl -fsSo awscliv2.zip "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip"
unzip -q awscliv2.zip && sudo ./aws/install && rm -rf aws awscliv2.zip

# Deployment folder
sudo mkdir -p /opt/ediplug && sudo chown ubuntu: /opt/ediplug && cd /opt/ediplug
#   copy ediplug/deploy/{docker-compose.yml,.env.example,nginx/nginx.conf,override/} here
cp .env.example .env && chmod 600 .env && nano .env      # fill in every <...>
mkdir -p data && sudo chown -R 1000:0 data && sudo chmod -R g+rwX data
#   optional property changes: cp override/openas2.properties.example override/openas2.properties, keep only changed keys
#   edit nginx/nginx.conf: set_real_ip_from = your VPC / LB subnets (and proxy_protocol for an NLB)

# Start
aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
docker compose pull
docker compose up -d
```

Make it survive reboots: Docker starts on boot and the containers have `restart: always`. ECR logins expire after
12 h, which only matters when pulling a new image, so log in again before `docker compose pull`.

## 5. Verify

```sh
docker compose ps                                   # all three healthy
docker logs EDIPLUG_AS2_SERVER 2>&1 | grep -E "ediplug-entrypoint|Loaded|ERROR|started"
#   expect: "Loaded N own key pairs and M partner certificates ... into keystore as2_certs"
#           "Loaded ... into keystore ssl_trust_certs", "Loaded P partners and Q partnerships", "OpenAS2 Server v4.15.0 started."
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/health          # 200 on the VM
curl -s -o /dev/null -w '%{http_code}\n' https://as2.ediplug.com/health   # 200 through the LB
```

Then send a test file through a test partnership (drop it in `data/outbox/<sender_as2_id>/<receiver_as2_id>/`,
`.edi` extension) and check the MDN under `data/mdn/...` and the `msg_metadata` row.

Startup failures to expect, and fixes:
- `AS2 keystore validation failed`: DB unreachable (security groups, `AS2_DB_*`), wrong
  `AS2_KEY_ENCRYPTION_PASSWORD`, or a broken own key row. The log lists every problem.
- `data directory ... is not writable` / `Permission denied` on `/opt/openas2/data`: `sudo chown -R 1000:0 data`.
- `... is not a file`: the override mount points at a missing file (Docker then creates a directory); mount the
  `override/` folder instead.
- Partner-level ERRORs (missing partner certificate, expired certificate): the node runs; only those partners fail.

## 6. Moving traffic from an existing node

1. Bring the new node up and make it a healthy LB target **without** traffic. With an NLB, keep it out of the
   target group until step 3.
2. Stop the old node's OpenAS2 (`docker compose stop openas2`). Shutdown waits for in-flight sends.
3. Copy the old node's `data/outbox`, `data/pendingMDN3`, `data/pendinginfoMDN3` and `data/resend` to the new node
   (`rsync -a`, then `chown -R 1000:0`), so queued sends and expected async MDNs continue.
4. Register the new node / switch DNS. Keep the old node stopped (not deleted) until traffic is confirmed.
5. If partners allowlist IPs, give them the NLB Elastic IPs **before** the switch.

## 7. Operations

| Task | How |
|---|---|
| Release a new image | push a new tag, set `OPENAS2_IMAGE` in `.env`, `docker compose pull && docker compose up -d openas2` (about 10 s outage while the container restarts) |
| Change a property | edit `override/openas2.properties`, `docker compose restart openas2`; the log lists the overridden keys |
| Roll back | previous tag in `.env`, `docker compose up -d openas2`; DB migrations are additive |
| Add/rotate partners, certificates, trusted CAs | write the tables; live within ~30 s, no restart (see `keystore-and-reloads.md`) |
| Logs | `docker logs EDIPLUG_AS2_SERVER` (json-file, rotated 5 x 50 MB); ERRORs are also emailed |
| Backups | RDS automated snapshots; `./data` (inbox/MDN archive) to S3 on a schedule |
| Secrets rotation | DB password: change it in RDS and `.env`, then `up -d`. `AS2_KEY_ENCRYPTION_PASSWORD`: re-encrypt every `as2_own_key` row with the new passphrase **before** changing `.env` |
| Disk | inbox and MDN files grow without limit: archive/expire them |

Never bake secrets into the image or commit `.env`. Rotate any credential that has been pasted into a chat or ticket.

## Single VM with TLS on the VM (Let's Encrypt), e.g. as2-dev.ediplug.com

No load balancer: nginx on the VM listens on 80/443 with a Let's Encrypt certificate (DNS-01 via Cloudflare), rate
limits per client IP, and proxies `/receive`, `/mdn`, `/health` to OpenAS2. Files: `ediplug/deploy/docker-compose.tls.yml`,
`ediplug/deploy/nginx/nginx-tls.conf.template`.

1. DNS (Cloudflare): `A <hostname> -> <VM public IP>`, **DNS only**. VM firewall / security list: 80 and 443 open.
2. Database: section 2 above (`create_env_databases.sql` with the environment name); allow the VM's IP on 5432 in the
   RDS security group; load own keys, partners and partnerships.
3. Deploy folder:
   ```sh
   sudo apt-get install -y docker-compose-v2 certbot python3-certbot-dns-cloudflare
   sudo mkdir -p /opt/ediplug && sudo chown $USER: /opt/ediplug && cd /opt/ediplug
   cp <repo>/ediplug/deploy/docker-compose.tls.yml docker-compose.yml
   cp -r <repo>/ediplug/deploy/nginx <repo>/ediplug/deploy/override . && cp <repo>/ediplug/deploy/.env.example .env
   chmod 600 .env && nano .env     # OPENAS2_IMAGE, AS2_PUBLIC_HOSTNAME, AS2_DB_*, key passwords, SMTP
   printf 'as2_async_mdn_url=https://%s/mdn\n' <hostname> > override/openas2.properties   # image default is prod's URL
   mkdir -p data nginx/certs && sudo chown -R 1000:0 data
   ```
4. Certificate + renewal hook (the Cloudflare API token needs Zone:DNS:Edit; file mode 600):
   ```sh
   sudo certbot certonly --dns-cloudflare --dns-cloudflare-credentials /home/ubuntu/.secrets/certbot/cloudflare.ini -d <hostname>
   sudo tee /etc/letsencrypt/renewal-hooks/deploy/ediplug-<hostname>-cert-sync.sh >/dev/null <<'HOOK'
   #!/bin/sh
   # Copies the renewed certificate to the nginx folder and reloads nginx; ignores other certificates.
   set -e
   NAME=<hostname>; DST=/opt/ediplug/nginx/certs
   [ "${RENEWED_LINEAGE:-/etc/letsencrypt/live/$NAME}" = "/etc/letsencrypt/live/$NAME" ] || exit 0
   install -m 644 /etc/letsencrypt/live/$NAME/fullchain.pem $DST/fullchain.pem
   install -m 600 /etc/letsencrypt/live/$NAME/privkey.pem   $DST/privkey.pem
   docker exec ediplug-nginx nginx -s reload 2>/dev/null || true
   HOOK
   sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/ediplug-<hostname>-cert-sync.sh
   sudo /etc/letsencrypt/renewal-hooks/deploy/ediplug-<hostname>-cert-sync.sh     # seed the certs once
   ```
   The nginx container reads `privkey.pem` as root, so mode 600 owned by root is fine.
5. Start: ECR login (section 4), `docker compose pull && docker compose up -d`.
6. Verify: section 5, plus
   - `curl -I http://<hostname>/health` gives 301; `curl https://<hostname>/health` gives 200; other paths 403;
     `curl -k https://<VM IP>/` fails the TLS handshake (catch-all).
   - `sudo certbot renew --dry-run` succeeds.

Rate limits (per client IP, in `nginx-tls.conf.template`): everything 20 req/s (burst 40) and 30 connections;
`/receive` and `/mdn` additionally 5 req/s (burst 20) and 10 connections; `/health` 1 req/s (burst 5). Over the limit
nginx answers 429 and logs `limiting requests`. AS2 bodies up to 50 MB (`client_max_body_size`), 300 s for the
synchronous MDN. Partner IP allowlists: uncomment the `allow` / `deny all` lines in `/receive` and `/mdn`.
