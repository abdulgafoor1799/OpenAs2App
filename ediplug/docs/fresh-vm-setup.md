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
- **VM**: `docker-compose.yml`, `nginx/nginx.conf`, `.env` (secrets) and `./data` (inbox, outbox, MDNs, pending state).

Files: `ediplug/deploy/` in the `ediplug/db-config` branch of the OpenAs2App fork.

---

## 0. Decisions before you start

1. **CPU architecture.** The image is built on the current ARM64 VM, so it runs on ARM64 hosts (AWS Graviton:
   t4g / m7g / c7g). For x86 instances, build a multi-arch image (`docker buildx build --platform
   linux/amd64,linux/arm64 ...`).
2. **Load balancer type.**
   - **NLB** (recommended for AS2): static Elastic IPs per AZ, which many retailers require for firewall
     allowlisting. Use a **TLS listener on 443 with the ACM certificate**, forwarding TCP to target port 80. To see
     real client IPs, enable *proxy protocol v2* on the target group and switch nginx to
     `listen 8080 proxy_protocol;` + `real_ip_header proxy_protocol;`.
   - **ALB**: simpler HTTP features and no static IPs. HTTPS listener with ACM forwarding HTTP to port 80; nginx
     reads `X-Forwarded-For`. Set the ALB idle timeout ≥ 300 s for large messages.
   - Either way: health check `HTTP GET /health` on port 80, healthy 200, interval 30 s.
3. **One active node at a time.** Outbox, pending-MDN and resend state live in the node's local `./data`. An
   asynchronous MDN that lands on another node cannot be matched to its message. Run a single active target
   (keep a stopped standby, or a second target only for failover) until `./data` is on shared storage (e.g. EFS)
   and tested with two nodes.

## 1. AWS resources (once per environment)

| Resource | Settings |
|---|---|
| ECR repository | `ediplug-openas2`, immutable tags, scan on push |
| RDS PostgreSQL 16 | Multi-AZ, encrypted, not publicly accessible, parameter `rds.force_ssl=1` |
| Security group `as2-lb` | inbound 443 from partners (or 0.0.0.0/0) |
| Security group `as2-node` | inbound 80 **only from `as2-lb`**; SSH only from your admin IP / SSM |
| Security group `as2-db` | inbound 5432 only from `as2-node` |
| IAM instance role | `AmazonEC2ContainerRegistryReadOnly` (+ `AmazonSSMManagedInstanceCore` if using SSM) |
| ACM certificate | `as2.ediplug.com` (DNS validation in Cloudflare) |
| DNS (Cloudflare) | `as2.ediplug.com` CNAME to the LB DNS name, **DNS only (grey cloud)**: AS2 must not go through the Cloudflare proxy |

## 2. Database (once per environment)

As the RDS master user, from any host that can reach RDS (the VM itself, or a bastion):

```sh
export PGHOST=<rds-endpoint> PGUSER=<master> PGSSLMODE=verify-full PGSSLROOTCERT=global-bundle.pem
curl -fsSo global-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
psql -d postgres -c "CREATE DATABASE openas2"
for f in V1__partnership_tables V2__config_version V3__partner_certificates V4__own_keys V5__ssl_trusted_ca; do
  psql -v ON_ERROR_STOP=1 -d openas2 -f ediplug/db/$f.sql
done
psql -d openas2 -c "\password openas2"            # runtime role used by OpenAS2 (SELECT on config tables)
psql -d openas2 -c "\password as2_config_admin"   # writer role for the EDIPLUG backend
```

Load data, either by **copying from the current server**:

```sh
# on the current VM (local postgres container)
docker exec ediplug-postgres pg_dump -U postgres -Fc --data-only \
  -t partner -t partner_attribute -t partnership -t partnership_attribute \
  -t as2_own_key -t as2_partner_certificate -t as2_ssl_trusted_ca -t msg_metadata \
  openas2 > openas2-data.dump
# into RDS, triggers enabled (version counters and alias checks run; config_version is seeded by V2)
pg_restore -d openas2 --data-only --single-transaction openas2-data.dump
```

or **from scratch** with the tools in the image (see `ediplug/README.md`):
`migrate_partnerships_to_db.sh` for a `partnerships.xml`, `ediplug/tools/p12-to-own-key-sql.sh` for key pairs
from a `.p12`, and plain `INSERT`s into `as2_partner_certificate` for partner PEMs.

Own private keys must be encrypted with the `AS2_KEY_ENCRYPTION_PASSWORD` the node will use. Keep that passphrase in
a password manager / AWS Secrets Manager: without it the keys in the database cannot be used.

## 3. Image (each release)

On a build host with the repository (and buildx for multi-arch):

```sh
docker build -f ediplug/Dockerfile -t ediplug-openas2:4.15.0-ediplug-s5 .          # runs the test suite
aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
docker tag ediplug-openas2:4.15.0-ediplug-s5 <account>.dkr.ecr.<region>.amazonaws.com/ediplug-openas2:4.15.0-ediplug-s5
docker push <account>.dkr.ecr.<region>.amazonaws.com/ediplug-openas2:4.15.0-ediplug-s5
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
#   copy ediplug/deploy/{docker-compose.yml,.env.example,nginx/nginx.conf} here
cp .env.example .env && chmod 600 .env && nano .env      # fill in every <...>
mkdir -p data && sudo chown -R 1000:0 data && sudo chmod -R g+rwX data
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
docker logs EDIPLUG_AS2_SERVER 2>&1 | grep -E "Loaded|ERROR|started"
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
- `Permission denied` on `/opt/openas2/data`: `sudo chown -R 1000:0 data`.
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
| Roll back | previous tag in `.env`, `docker compose up -d openas2`; DB migrations are additive |
| Add/rotate partners, certificates, trusted CAs | write the tables; live within ~30 s, no restart (see `keystore-and-reloads.md`) |
| Logs | `docker logs EDIPLUG_AS2_SERVER` (json-file, rotated 5 x 50 MB); ERRORs are also emailed |
| Backups | RDS automated snapshots; `./data` (inbox/MDN archive) to S3 on a schedule |
| Secrets rotation | DB password: change it in RDS and `.env`, then `up -d`. `AS2_KEY_ENCRYPTION_PASSWORD`: re-encrypt every `as2_own_key` row with the new passphrase **before** changing `.env` |
| Disk | inbox and MDN files grow without limit: archive/expire them |

Never bake secrets into the image or commit `.env`. Rotate any credential that has been pasted into a chat or ticket.
