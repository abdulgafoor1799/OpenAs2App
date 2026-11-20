# ha-openas2-oci-setup

Playbook for an EDIPLUG OpenAS2 node on a **private** OCI VM behind a **Network Load Balancer**, with outbound traffic
through a **NAT gateway** and all state on **OCI File Storage (FSS)**. If the active VM dies, another VM mounts the same
FSS export and takes over with the same messages, queues, pending MDNs, configuration and TLS certificate. Starts
**after the VMs, NLB, NAT gateway and FSS exist**.

```
partners --HTTPS 443--> as2.ediplug.com = NLB reserved public IP (TCP pass-through, source IP preserved)
                              |  443 / 80 to the healthy backend (health check: HTTP :8081/health)
              +---------------+---------------+
          VM-A (active)                   VM-B (standby: prepared, stack stopped)
          nginx -> OpenAS2                (same deploy folder, same FSS mount)
              |                                |
              +---------- FSS /mnt/ediplug-shared (NFSv3) ----------+
outbound (ECR, RDS, partners' AS2 URLs, Let's Encrypt, Cloudflare API) --> NAT gateway public IP
```

## How the shared state works

Everything that must survive a VM is on FSS; everything else comes from git and the image:

| Path on FSS (`/mnt/ediplug-shared`) | Contents | Used as |
|---|---|---|
| `data/` (owner UID 1000) | inbox, outbox, MDNs, `pendingMDN3`, `pendinginfoMDN3`, `resend`, `sent`, errors | `/opt/openas2/data` |
| `certs/` | `fullchain.pem`, `privkey.pem` | nginx `/etc/nginx/certs` |
| `letsencrypt/` | certbot state (account, renewal config, archive) | `certbot --config-dir` |
| `config/.env` (600) | secrets + settings; `/opt/ediplug/.env` is a link to it | compose |
| `config/override/openas2.properties` | runtime OpenAS2 properties (`as2_async_mdn_url`, ...) | `/opt/openas2/override` |
| `secrets/aws-credentials`, `secrets/cloudflare.ini` (700/600) | ECR pull key, Cloudflare DNS token | deploy / certbot |
| `ha/active-node` | `<hostname> <epoch>`: who runs OpenAS2, last heartbeat | `ha-node.sh` |

| From git (`/opt/ediplug-src`) | From the image (ECR) |
|---|---|
| compose files, nginx template, scripts | OpenAS2 + `config.xml`, `openas2.properties` defaults, `logback.xml`, CA bundles |

**Only one VM may run OpenAS2 on the shared data.** Two instances would both send each outbox file and both work the
resend / pending-MDN queues. `scripts/ha-node.sh` enforces this:
- `start` refuses while another host's heartbeat in `ha/active-node` is younger than 90 s;
- the active node refreshes the heartbeat every 20 s (systemd timer) while OpenAS2 runs;
- if a node sees another host owning a fresh heartbeat, it stops its own stack within 20 s (split-brain guard);
- `deploy-openas2.sh` refuses to start OpenAS2 on a node that does not own the state;
- certificate renewal runs only on the owner.

It is a guard, not a quorum: a frozen VM that wakes up keeps running for up to 20 s before it notices. So for an
unplanned failover always **fence** first (stop the dead instance in OCI), then start the other node.

---

## 0. Already in place (check, do not build here)

| Item | Requirement |
|---|---|
| VMs | VM-A and VM-B: Ubuntu 22.04/24.04, same architecture, in the **private subnet**, different fault domains, SSH via OCI Bastion |
| Private subnet route table | `0.0.0.0/0 -> NAT gateway` (the VMs need ECR, RDS, partners, Let's Encrypt, Cloudflare, apt) |
| NAT gateway | reserved public IP: **this is our outbound IP**: partners allowlist it, and the RDS security group must allow it on 5432 |
| FSS | file system + mount target in the private subnet; export (e.g. `/ediplug-as2`) with source = private subnet CIDR, read/write, **identity squash: None**; snapshot policy |
| NSG / security list: FSS | from the VMs to the mount target: TCP 111, 2048-2050 and UDP 111, 2048 |
| NLB | public, reserved public IP; listeners TCP 443 and TCP 80; backend sets with **preserve source IP** on (nginx rate limits are per client IP); health check **HTTP, port 8081, path `/health`, 200**; backends VM-A and VM-B on 443 / 80 |
| NSG: VMs | ingress TCP 443 and 80 from the internet (source IP is preserved, so allow the client range, e.g. `0.0.0.0/0` or partner CIDRs); TCP 8081 from the NLB subnet; 22 from the Bastion |
| DNS (Cloudflare) | `A as2.ediplug.com -> <NLB public IP>`, DNS only (grey cloud) |
| RDS | `openas2-<env>` created with `create_env_databases.sql`, own keys/partners inserted; security group allows the **NAT gateway** IP |
| ECR pull key, Cloudflare token | as in `standalone-openas2-vm-setup.md` §0 |

## 1. Host preparation (both VMs)

```sh
sudo git clone --depth 1 -b ediplug/db-config https://github.com/abdulgafoor1799/OpenAs2App.git /opt/ediplug-src
cd /opt/ediplug-src/ediplug/deploy/scripts
sudo ./vm-bootstrap.sh --with-nfs
exit                                   # log in again (docker group)
```

## 2. Mount FSS (both VMs)

```sh
sudo mkdir -p /mnt/ediplug-shared
echo '<mount-target-ip>:/ediplug-as2 /mnt/ediplug-shared nfs nfsvers=3,hard,timeo=600,retrans=2,_netdev,nofail 0 0' | sudo tee -a /etc/fstab
sudo mount /mnt/ediplug-shared && mountpoint /mnt/ediplug-shared
```
`hard`: I/O waits for FSS instead of failing (no half-written files); `nofail`: the VM still boots if FSS is
unreachable. `ha-node.sh` refuses to start without the mount.

## 3. First-time shared state (VM-A, once for the environment)

```sh
cd /opt/ediplug-src/ediplug/deploy/scripts
sudo ./setup-node.sh --hostname as2.ediplug.com --shared /mnt/ediplug-shared
```
Creates the FSS layout above, `config/.env` (from `.env.example`, with `AS2_DATA_DIR`, `AS2_CERTS_DIR`,
`AS2_OVERRIDE_DIR`, `COMPOSE_FILE=docker-compose.yml:docker-compose.ha.yml` already set),
`config/override/openas2.properties` (`as2_async_mdn_url=https://as2.ediplug.com/mdn`) and `/opt/ediplug`.

Fill in `nano /mnt/ediplug-shared/config/.env` as in the standalone playbook §3, plus:
```
ECR_AWS_CREDENTIALS_FILE=/mnt/ediplug-shared/secrets/aws-credentials
OPENAS2_IMAGE=071370395173.dkr.ecr.ap-south-1.amazonaws.com/openas2-ediplug:arm64-v2     # tag to run
```

Secrets on FSS (readable by root only):
```sh
sudo sh -c 'umask 077; printf "[ecr-pull]\naws_access_key_id = <key>\naws_secret_access_key = <secret>\n" > /mnt/ediplug-shared/secrets/aws-credentials'
sudo sh -c 'umask 077; echo "dns_cloudflare_api_token = <token>" > /mnt/ediplug-shared/secrets/cloudflare.ini'
```

Certificate, with certbot state on FSS:
```sh
sudo ./issue-cert.sh --hostname as2.ediplug.com --email <ops e-mail> \
     --cloudflare-ini /mnt/ediplug-shared/secrets/cloudflare.ini \
     --certs-dir /mnt/ediplug-shared/certs --config-dir /mnt/ediplug-shared/letsencrypt
```
DNS-01 needs no inbound connection, so it works from the private VM through the NAT gateway.

## 4. Start the active node (VM-A)

```sh
cd /opt/ediplug-src/ediplug/deploy/scripts
sudo ./ha-node.sh install      # /etc/default/ediplug-ha (check AS2_HOSTNAME) + heartbeat and cert-renewal timers
sudo ./ha-node.sh start        # takes ownership, ECR login + pull, CPU check, starts, waits until healthy
sudo ./ha-node.sh status
```
Within ~30 s the NLB marks VM-A healthy (OCI console: NLB > backend sets > health).

## 5. Prepare the standby node (VM-B)

After §1 and §2:
```sh
cd /opt/ediplug-src/ediplug/deploy/scripts
sudo ./setup-node.sh --hostname as2.ediplug.com --shared /mnt/ediplug-shared   # keeps the existing FSS .env/override
sudo ./ha-node.sh install
sudo ./ha-node.sh status        # owner: VM-A; do NOT start here
```
Optional, makes a takeover faster (ECR login + pull ahead of time):
`sudo sh -c 'export AWS_SHARED_CREDENTIALS_FILE=/mnt/ediplug-shared/secrets/aws-credentials; aws --profile ecr-pull --region ap-south-1 ecr get-login-password | docker login -u AWS --password-stdin 071370395173.dkr.ecr.ap-south-1.amazonaws.com && docker pull <OPENAS2_IMAGE>'`.
VM-B stays in the NLB backend set; it is unhealthy (no stack), so it gets no traffic. To save cost you can stop the
VM-B instance; then a takeover starts with "start instance".

## 6. Verify

```sh
H=as2.ediplug.com
sudo /opt/ediplug-src/ediplug/deploy/scripts/ha-node.sh status         # owner VM-A, heartbeat < 20 s
docker logs EDIPLUG_AS2_SERVER 2>&1 | grep -E "ediplug-entrypoint|Loaded|started"
curl -s -o /dev/null -w '%{http_code}\n' https://$H/health             # 200, through the NLB
curl -s http://localhost:8081/health                                    # on the VM: OpenAS2 health
echo | openssl s_client -connect $H:443 -servername $H 2>/dev/null | openssl x509 -noout -issuer -enddate
curl -s https://ifconfig.me                                             # on the VM: = NAT gateway IP
ls /mnt/ediplug-shared/data                                              # OpenAS2 folders on FSS
```
Rate limits see the real client IP only if "preserve source IP" is on: `docker logs ediplug-nginx` must show partner
IPs, not NLB addresses. Test the HTTPS call above from **outside** OCI: with source IP preservation the replies must
go back through the NLB; if external calls time out while `:8081/health` on the VM works, check the NLB's
source-preservation / routing settings in the OCI documentation for your setup. Without preservation every request
appears to come from the NLB, and the per-IP limits in `nginx-tls.conf.template` must be raised accordingly.

## 7. Failover

**Planned** (maintenance, patching):
```sh
# VM-A
sudo /opt/ediplug-src/ediplug/deploy/scripts/ha-node.sh stop      # waits for in-flight sends, releases the state
# VM-B
sudo /opt/ediplug-src/ediplug/deploy/scripts/ha-node.sh start
```
Downtime: about the time OpenAS2 needs to start + one NLB health interval (≈1 min).

**Unplanned** (VM-A unreachable):
1. **Fence VM-A**: OCI console > instance > Stop (or Terminate). Do not skip this.
2. On VM-B (start the instance first if it was stopped): `sudo ha-node.sh status` shows VM-A's last heartbeat.
3. `sudo ha-node.sh start`. It refuses until the heartbeat is 90 s old; after fencing you may use
   `sudo ha-node.sh start --force` instead of waiting.
4. Check §6. Files that were being sent when VM-A died are in `data/pendingMDN3` / `resend` and continue; OpenAS2 logs
   a warning for anything it could not finish.

**Replace a VM** (new VM-C instead of VM-A): §1, §2, §5 on VM-C, add VM-C to both NLB backend sets, remove VM-A.

## 8. Operations

| Task | How |
|---|---|
| Deploy another image tag | on the **active** node: `sudo .../deploy-openas2.sh <tag>` (sudo: the ECR key on FSS is root-only; writes `OPENAS2_IMAGE` into the FSS `.env`, so a takeover uses the same tag); roll back: `sudo .../deploy-openas2.sh --rollback`. On the standby it refuses. |
| Change an OpenAS2 property | edit `/mnt/ediplug-shared/config/override/openas2.properties`, then on the active node `docker compose -f /opt/ediplug/docker-compose.yml restart openas2` |
| Update deploy files from git | on both nodes: `sudo git -C /opt/ediplug-src pull && sudo .../setup-node.sh --hostname as2.ediplug.com --shared /mnt/ediplug-shared`; then on the active node `docker compose -f /opt/ediplug/docker-compose.yml up -d` |
| Certificate | renewed by `ediplug-cert-renew.timer` on the owner node (state on FSS); check: `systemctl list-timers 'ediplug-*'`, `sudo certbot certificates --config-dir /mnt/ediplug-shared/letsencrypt` |
| Partners, keys, certificates | write the RDS tables; live within 30 s |
| Backups | FSS snapshot policy (data + certs + config); RDS automated backups |
| Monitoring | NLB backend health alarm (both unhealthy = outage); OpenAS2 ERROR e-mails; `ha-node.sh status` |

## 9. Notes and limits

- Writers into the outbox over FSS (e.g. the orchestrator) should write `name.tmp`, then rename to `name.edi` when
  complete: the poller only picks up `.edi` / `.EDI`.
- The heartbeat timer and the split-brain guard need systemd and the FSS mount; `ha-node.sh` stops OpenAS2 when the
  mount disappears.
- Outbound AS2 to partners, async MDNs we send, and DB traffic leave through the NAT gateway IP; inbound comes to the
  NLB IP. Give partners both.
- `/opt/ediplug` on each VM holds only files from git and the `.env` link; a VM can be rebuilt from scratch with §1,
  §2, §5.
