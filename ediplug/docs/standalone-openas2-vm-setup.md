# standalone-openas2-vm-setup

Playbook for one EDIPLUG OpenAS2 node on a VM with a public IP, e.g. `as2-test.ediplug.com`, `as2-test2.ediplug.com`
(same pattern as `as2-dev.ediplug.com`). Starts **after the VM is created and running**; repeat it per VM.

```
partners --HTTPS 443--> <hostname> (Cloudflare DNS only) --> VM public IP
  nginx (80/443, Let's Encrypt, rate limits) --> OpenAS2 (image from ECR) --> RDS openas2-<env>
/opt/ediplug/  docker-compose.yml  .env  nginx/{nginx-tls.conf.template,certs/}  override/  data/
/opt/ediplug-src/  git checkout (deploy files + scripts); config.xml etc. are inside the image
```

Scripts used (all in `ediplug/deploy/scripts/` of the repo):

| Script | Does |
|---|---|
| `vm-bootstrap.sh` | Docker Engine + Compose v2, AWS CLI v2, certbot + Cloudflare plugin, git, jq |
| `setup-node.sh` | builds `/opt/ediplug` from git; creates `.env` and `override/openas2.properties` once, never overwrites them |
| `issue-cert.sh` | Let's Encrypt certificate (DNS-01 via Cloudflare) + renewal hook that reloads nginx |
| `deploy-openas2.sh` | list ECR tags, deploy a tag (pull, CPU check, start, wait until healthy), roll back |

---

## 0. Already in place (check, do not build here)

| Item | Value for this node |
|---|---|
| VM | Ubuntu 22.04/24.04, SSH access as `ubuntu`, public IP (reserved, so partners can allowlist it) |
| VM ingress (security list / NSG) | TCP 443 and 80 from the internet (or partner IPs); 22 from your admin IP |
| DNS (Cloudflare) | `A <hostname> -> <VM public IP>`, **DNS only (grey cloud)** |
| RDS | `create_env_databases.sql` run with `EDIPLUG_ENV=<env>` → database `openas2-<env>`, role `openas2_<env>` and its password; RDS security group allows `<VM public IP>/32` on 5432 |
| AS2 data | own key(s) inserted in `as2_own_key` (encrypted with the passphrase you will put in `.env`; at least one enabled), partners/partnerships as needed |
| ECR pull key | IAM user with `ediplug/deploy/iam/ecr-pull-only-policy.json`: access key + secret |
| Cloudflare token | API token with Zone → DNS → Edit on `ediplug.com` (the one in `/home/ubuntu/.secrets/certbot/cloudflare.ini` on the dev VM can be reused) |

Worksheet per node:

| | as2-test | as2-test2 |
|---|---|---|
| hostname | `as2-test.ediplug.com` | `as2-test2.ediplug.com` |
| env / database / role | `test` / `openas2-test` / `openas2_test` | `test2` / `openas2-test2` / `openas2_test2` |
| image tag | `arm64-v2` (Ampere) or `x86-v2` | same rule |

## 1. Get the deploy files and prepare the host (≈5 min)

```sh
sudo git clone --depth 1 -b ediplug/db-config https://github.com/abdulgafoor1799/OpenAs2App.git /opt/ediplug-src
cd /opt/ediplug-src/ediplug/deploy/scripts
sudo ./vm-bootstrap.sh
exit                                   # log out and back in, so docker works without sudo
```
Check after logging in again: `docker run --rm hello-world` and `docker compose version`.

## 2. Deploy folder

```sh
cd /opt/ediplug-src/ediplug/deploy/scripts
sudo ./setup-node.sh --hostname as2-test.ediplug.com
```
Creates `/opt/ediplug` with the compose file, nginx template, `data/` (owner UID 1000),
`override/openas2.properties` (`as2_async_mdn_url=https://as2-test.ediplug.com/mdn`) and `.env` (mode 600).

## 3. Fill in `.env`

`nano /opt/ediplug/.env`:

| Key | Value |
|---|---|
| `ECR_REGISTRY` | `071370395173.dkr.ecr.ap-south-1.amazonaws.com` |
| `ECR_REPOSITORY` / `AWS_REGION` / `ECR_AWS_PROFILE` | `openas2-ediplug` / `ap-south-1` / `ecr-pull` |
| `OPENAS2_IMAGE` | leave; `deploy-openas2.sh` sets it |
| `AS2_PUBLIC_HOSTNAME` | already set by `setup-node.sh` |
| `AS2_DB_HOST` | `ediplug.c9aci0wyweow.ap-south-1.rds.amazonaws.com` |
| `AS2_DB_NAME` / `AS2_DB_USER` / `AS2_DB_PASSWORD` | `openas2-test` / `openas2_test` / role password |
| `AS2_DB_SSLMODE` / `AS2_DB_SSLROOTCERT` | `verify-full` / `/opt/openas2/config/rds-global-bundle.pem` (defaults) |
| `AS2_KEY_ENCRYPTION_PASSWORD` | the passphrase the own keys in this database were encrypted with |
| `AS2_KEYSTORE_PASSWORD` | any long random value: `openssl rand -hex 24` |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASS` | alert e-mail (Gmail: app password) |

No quotes needed; a literal `$` must be written `$$`.

Check the database login before going further (prints the number of enabled own keys):
```sh
cd /opt/ediplug
docker run --rm --env-file .env postgres:16-alpine sh -c 'wget -qO /tmp/ca.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem &&
  PGPASSWORD="$AS2_DB_PASSWORD" psql "host=$AS2_DB_HOST user=$AS2_DB_USER dbname=$AS2_DB_NAME sslmode=verify-full sslrootcert=/tmp/ca.pem" \
  -Atc "select count(*) from as2_own_key where enabled"'
```
`password authentication failed` = wrong role/password; timeout = RDS security group.

## 4. ECR pull key

```sh
aws configure --profile ecr-pull       # access key, secret, region ap-south-1, output json
/opt/ediplug-src/ediplug/deploy/scripts/deploy-openas2.sh --list      # shows the tags, proves the key works
```

## 5. TLS certificate

```sh
sudo install -d -m 700 /root/.secrets
sudo sh -c 'umask 077; echo "dns_cloudflare_api_token = <token>" > /root/.secrets/cloudflare.ini'
cd /opt/ediplug-src/ediplug/deploy/scripts
sudo ./issue-cert.sh --hostname as2-test.ediplug.com --email <ops e-mail> \
     --cloudflare-ini /root/.secrets/cloudflare.ini --certs-dir /opt/ediplug/nginx/certs
```
Prints the certificate subject/issuer (Let's Encrypt) and expiry. Renewal is automatic (stock `certbot.timer`, twice a
day); the hook copies the new files and reloads nginx.

## 6. Start OpenAS2 (tag chosen now)

```sh
cd /opt/ediplug-src/ediplug/deploy/scripts
./deploy-openas2.sh --list
./deploy-openas2.sh arm64-v2           # x86-v2 on an x86 VM; the script refuses a tag for the wrong CPU
```
It logs in to ECR, pulls, writes `OPENAS2_IMAGE` into `.env`, starts nginx + OpenAS2 + autoheal and waits until
healthy, then prints the key log lines.

## 7. Verify

```sh
H=as2-test.ediplug.com
docker compose -f /opt/ediplug/docker-compose.yml ps        # 3 containers, healthy
docker logs EDIPLUG_AS2_SERVER 2>&1 | grep -E "ediplug-entrypoint|Loaded|started"
#   ... keys: as2_async_mdn_url / Loaded N own key pairs ... / Loaded P partners and Q partnerships / Server v4.15.0 started.
curl -sI http://$H/health | head -1          # 301
curl -s -o /dev/null -w '%{http_code}\n' https://$H/health   # 200
curl -s -o /dev/null -w '%{http_code}\n' https://$H/x        # 403
echo | openssl s_client -connect $H:443 -servername $H 2>/dev/null | openssl x509 -noout -issuer -enddate
sudo certbot renew --dry-run --cert-name $H                    # may wait a few minutes (random delay)
```
Then a real exchange with a partner (or another of our nodes): file into
`/opt/ediplug/data/outbox/<our AS2 ID>/<partner AS2 ID>/` (`.edi`), MDN under `data/mdn/...`, row in `msg_metadata`.

## 8. Operations

| Task | Command |
|---|---|
| Deploy another tag | `./deploy-openas2.sh <tag>` (≈10 s restart) |
| Roll back | `./deploy-openas2.sh --rollback` |
| What is running | `./deploy-openas2.sh --current` |
| Change an OpenAS2 property | edit `/opt/ediplug/override/openas2.properties`, `docker compose -f /opt/ediplug/docker-compose.yml restart openas2` |
| Partners, certificates, keys | write the RDS tables; live within 30 s, no restart |
| Update deploy files from git | `sudo git -C /opt/ediplug-src pull && sudo /opt/ediplug-src/ediplug/deploy/scripts/setup-node.sh --hostname <h> && docker compose -f /opt/ediplug/docker-compose.yml up -d` (`.env`/override kept) |
| Logs | `docker logs -f EDIPLUG_AS2_SERVER`, `docker logs ediplug-nginx` (429s are logged as `limiting requests`) |
| Stop / start | `docker compose -f /opt/ediplug/docker-compose.yml down` / `./deploy-openas2.sh --start` |
| Backup | `/opt/ediplug/data` (inbox/MDN archive, queues) e.g. OCI block volume backup or a nightly tar to Object Storage; `.env` in your password manager |

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `deploy-openas2.sh --list`: AccessDenied | key lacks the policy, or wrong profile/region in `.env` |
| `... is amd64 but this host is arm64` | use the tag for this CPU |
| OpenAS2 exits: `AS2 keystore validation failed` | no enabled own key, wrong `AS2_KEY_ENCRYPTION_PASSWORD`, or DB unreachable; the log lists the problems |
| `data directory ... is not writable` | `sudo chown -R 1000:0 /opt/ediplug/data` |
| nginx restarts, `host not found in upstream "openas2"` | OpenAS2 is not running; fix it first (`docker logs EDIPLUG_AS2_SERVER`) |
| certbot: DNS problem / unauthorized | Cloudflare token scope, or the record is proxied (must be grey cloud) |
| partners get 429 | they exceed 5 req/s per IP on `/receive`; raise `as2_ip` in `nginx/nginx-tls.conf.template` (repo), redeploy files |
