# Updating a deployed Traccar on Ubuntu (config-preserving)

Runbook for updating an existing Traccar install with the newest code from
`master`, **without touching any configuration, database, media or logs**.

Written for this fork (`Itheras/nievartraccar`), which carries local changes on
top of upstream `traccar/traccar` — among them `HuabaoProtocolDecoder`,
`HuabaoProtocolEncoder`, `AtrackProtocolDecoder`, `Bit4MotionHandler` and
`EngineHoursHandler`.

---

## The update script (verified 2026-07-27)

Backs up the config, builds, stops Traccar, swaps the install, restores the
config, starts it back up. No database backup — the database is external and its
credentials live in `conf/traccar.xml`, which is preserved.

It runs as a script, not pasted commands, so `set -e` stops before `/opt/traccar`
is touched if the build fails. That ordering matters: the failure mode we hit was
a failed build followed by a deploy that emptied the install anyway.

Save it once:

```bash
cat > ~/update-traccar.sh <<'EOF'
#!/bin/bash
set -euo pipefail

SRC=~/src/traccar
STAGE=~/traccar-new
STAMP=$(date +%Y%m%d-%H%M%S)

echo "=== 1/6 update source ==="
cd "$SRC"
git checkout -- .gitmodules 2>/dev/null || true
git checkout master
git pull --ff-only origin master
# submodule URL override must live in .gitmodules: `git submodule sync` copies
# .gitmodules into .git/config and wipes a plain `git config` override
git config -f .gitmodules submodule.traccar-web.url https://github.com/traccar/traccar-web.git
git submodule sync --recursive
git submodule update --init --recursive
test -f traccar-web/package.json

echo "=== 2/6 build server ==="
# assemble, not build: `build` runs tests + checkstyle, which fail on this fork
./gradlew clean assemble --no-daemon
test -s target/tracker-server.jar

echo "=== 3/6 build web app ==="
( cd traccar-web && npm ci && npm run build )
test -s traccar-web/build/index.html

echo "=== 4/6 stage payload ==="
rm -rf "$STAGE"
mkdir -p "$STAGE"/{lib,web,schema,templates}
cp target/tracker-server.jar "$STAGE"/
cp target/lib/*              "$STAGE"/lib/
cp -r traccar-web/build/*    "$STAGE"/web/
cp schema/*                  "$STAGE"/schema/
cp -r templates/*            "$STAGE"/templates/
test -s "$STAGE/tracker-server.jar"
test "$(ls "$STAGE"/lib | wc -l)" -gt 50
echo "payload ok: $(ls "$STAGE"/lib | wc -l) jars"

echo "=== 5/6 deploy, preserving config ==="
sudo cp -a /opt/traccar/conf "/root/traccar-conf-$STAMP"
sudo systemctl stop traccar
sudo mv /opt/traccar "/opt/traccar.old-$STAMP"
sudo mkdir -p /opt/traccar
sudo cp -a "$STAGE"/. /opt/traccar/
sudo cp -a "/opt/traccar.old-$STAMP/jre" /opt/traccar/jre
if [ -d "/opt/traccar.old-$STAMP/data" ]; then
    sudo cp -a "/opt/traccar.old-$STAMP/data" /opt/traccar/data
fi
if [ -d "/opt/traccar.old-$STAMP/media" ]; then
    sudo cp -a "/opt/traccar.old-$STAMP/media" /opt/traccar/media
fi
sudo mkdir -p /opt/traccar/logs
sudo cp -a "/root/traccar-conf-$STAMP" /opt/traccar/conf
sudo chmod -R go+rX /opt/traccar
sudo test -s /opt/traccar/tracker-server.jar
sudo test -s /opt/traccar/conf/traccar.xml
sudo test -x /opt/traccar/jre/bin/java

echo "=== 6/6 start and verify ==="
sudo systemctl start traccar
sleep 25                       # Traccar needs ~10s to run Liquibase and bind 8082
sudo systemctl is-active traccar || true
curl -fsS http://localhost:8082/api/health && echo "health ok" || echo "NOT UP - check journalctl -u traccar -n 80"
curl -sS http://localhost:8082/api/server | head -c 200 || true
echo
echo "-----------------------------------------------"
echo "rollback dir : /opt/traccar.old-$STAMP"
echo "config backup: /root/traccar-conf-$STAMP"
echo "rollback     : sudo systemctl stop traccar && sudo rm -rf /opt/traccar && sudo mv /opt/traccar.old-$STAMP /opt/traccar && sudo systemctl start traccar"
EOF
chmod +x ~/update-traccar.sh
```

Then every future update is:

```bash
~/update-traccar.sh
```

After it finishes:

```bash
sudo tail -n 60 /opt/traccar/logs/tracker-server.log
sudo ss -lntp | grep java | head
```

Check the web UI and confirm devices are reporting — especially Huabao/MiCODUS
and Atrack, the fork-modified protocols. Keep the printed rollback directory for
a few days, then remove it.

### First-time prerequisites only

```bash
sudo apt update
sudo apt install -y git unzip zip curl openjdk-21-jdk
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
mkdir -p ~/src && git clone https://github.com/Itheras/nievartraccar.git ~/src/traccar
```

If the nodesource script aborts on a broken third-party PPA, remove the offending
list file (e.g. `sudo rm /etc/apt/sources.list.d/*nginx-mainline*.list`) and rerun
it. Node 20 also builds the web app.

### Pulling upstream traccar instead of just your fork

Do this by hand before running the script — it can conflict, since this fork
modifies `HuabaoProtocolDecoder`, `HuabaoProtocolEncoder`, `AtrackProtocolDecoder`,
`Bit4MotionHandler`, `EngineHoursHandler` and others:

```bash
cd ~/src/traccar
git remote add upstream https://github.com/traccar/traccar.git   # once
git fetch upstream master
git checkout master
git merge upstream/master
git diff --name-only --diff-filter=U      # resolve, keeping both sides
git add -A && git commit
git push -u origin master
~/update-traccar.sh
```
---

## 0. What is preserved vs. replaced

The systemd unit (`setup/traccar.service`) runs:

```
WorkingDirectory=/opt/traccar
ExecStart=/opt/traccar/jre/bin/java -jar tracker-server.jar conf/traccar.xml
```

All runtime paths are relative to `/opt/traccar`:

| Path                            | Contains                                              | Action  |
|---------------------------------|-------------------------------------------------------|---------|
| `conf/traccar.xml`              | your configuration                                    | **KEEP** |
| `conf/*` (certs, GeoIP db, …)   | anything else you dropped in                          | **KEEP** |
| `data/`                         | H2 database (`database.url` default `jdbc:h2:./data/database`) | **KEEP** |
| `media/`                        | uploaded photos/media (`media.path` default `./media`)| **KEEP** |
| `logs/`                         | logs                                                   | **KEEP** |
| web override folder             | branding overrides (`web.override`, if configured)     | **KEEP** |
| `tracker-server.jar`            | the server                                             | replace |
| `lib/`                          | dependency jars                                        | replace |
| `web/`                          | web app (traccar-web build output)                     | replace |
| `schema/`                       | Liquibase changelogs                                   | replace |
| `templates/`                    | notification templates — **back up if you customized** | replace |
| `jre/`                          | bundled Java runtime                                   | keep, unless too old (§6) |

The update below never writes to `conf/`, `data/`, `media/` or `logs/`.

---

## 1. Pre-flight (on the server)

```bash
# What is running, and how
systemctl status traccar --no-pager
systemctl cat traccar --no-pager
ls -la /opt/traccar

# Current version and bundled Java
unzip -p /opt/traccar/tracker-server.jar META-INF/MANIFEST.MF | grep -i implementation-version
/opt/traccar/jre/bin/java -version

# Which database is in use (check the database.* entries)
sudo grep -E 'database\.(driver|url|user)' /opt/traccar/conf/traccar.xml

# Disk space (the build and backups need room)
df -h /opt /var /home
```

If `systemctl status traccar` says the unit does not exist, you are probably on
the Docker deployment — jump to §8.

---

## 2. Back up (do not skip)

```bash
sudo systemctl stop traccar

# Full install backup (config + data + media + logs), timestamped
sudo tar czf /root/traccar-full-$(date +%Y%m%d-%H%M).tar.gz -C /opt traccar

# Config-only quick copy, easy to find later
sudo cp -a /opt/traccar/conf /root/traccar-conf-$(date +%Y%m%d-%H%M)
```

If you use MySQL/MariaDB instead of the default H2, also dump the database
(Traccar runs Liquibase migrations on startup and they are **not reversible**):

```bash
mysqldump -u traccar -p --single-transaction --routines --triggers \
  traccar > /root/traccar-db-$(date +%Y%m%d-%H%M).sql
```

PostgreSQL:

```bash
pg_dump -U traccar -Fc traccar > /root/traccar-db-$(date +%Y%m%d-%H%M).dump
```

---

## 3. Build prerequisites

Build on the server, or on any Ubuntu box and copy the artifact over.
Gradle needs roughly 2 GB RAM — on a 1 GB VPS add swap first or build elsewhere.

```bash
sudo apt update
sudo apt install -y git unzip zip curl openjdk-21-jdk

# Node.js for the web app (traccar-web); 22 LTS is fine
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs

java -version && node -v && npm -v
```

JDK 21 builds this project correctly — `build.gradle` targets Java 17 bytecode
and the release workflow uses JDK 21. After merging upstream (§4), confirm the
requirement did not move:

```bash
grep -n "JavaVersion\|languageVersion" build.gradle
```

---

## 4. Get the newest code

```bash
mkdir -p ~/src && cd ~/src
git clone https://github.com/Itheras/nievartraccar.git traccar
cd ~/src/traccar
```

(If the clone already exists: `cd ~/src/traccar && git fetch origin`.)

### 4a. Newest from **your fork's** master

```bash
git checkout master
git pull --ff-only origin master
```

### 4b. Newest from **upstream traccar** master (keeping your local patches)

Your fork last synced with upstream in July 2025, so expect conflicts in
`HuabaoProtocolDecoder.java` — that file is the only one you have modified.

```bash
git remote add upstream https://github.com/traccar/traccar.git   # once
git fetch upstream master
git checkout master
git merge upstream/master

# If it conflicts:
git status
git diff --name-only --diff-filter=U
# edit src/main/java/org/traccar/protocol/HuabaoProtocolDecoder.java, keeping BOTH
# upstream's changes and your parseFlexibleInt / bit-13 sub-packaging additions
git add src/main/java/org/traccar/protocol/HuabaoProtocolDecoder.java
git commit
git push -u origin master
```

Your local additions to re-apply if the merge mangles them:
- `parseFlexibleInt(String)` helper (MiCODUS `0x9F` base-station strings)
- JT/T808 sub-packaging skip when `BitUtil.check(attribute, 13)`
- MiCODUS per-GNSS satellite-count handling

### 4c. Web submodule — URL override required

`.gitmodules` uses a relative URL (`../traccar-web.git`), which resolves to
`https://github.com/Itheras/traccar-web.git`. That fork does not exist, so the
submodule must be pointed at upstream. The override has to go into `.gitmodules`
— `git submodule sync` copies `.gitmodules` into `.git/config`, so a plain
`git config submodule.traccar-web.url` is wiped before the clone runs:

```bash
git config -f .gitmodules submodule.traccar-web.url https://github.com/traccar/traccar-web.git
git submodule sync --recursive
git submodule update --init --recursive
git -C traccar-web log --oneline -1
```

---

## 5. Build and stage the artifact

```bash
cd ~/src/traccar

# Backend -> target/tracker-server.jar and target/lib/
# assemble, not build: `build` also runs tests + checkstyle, which currently fail
# on this fork's master (AtrackProtocolDecoderTest, 7 checkstyle errors)
./gradlew clean assemble --no-daemon --stacktrace

ls -l target/tracker-server.jar && ls target/lib | wc -l

# Web app -> traccar-web/build/
cd traccar-web
npm ci
npm run build
cd ~/src/traccar
ls traccar-web/build/index.html
```

Stage exactly the files that get replaced:

```bash
rm -rf ~/traccar-new ~/traccar-new.tar.gz
mkdir -p ~/traccar-new/{lib,web,schema,templates}
cp target/tracker-server.jar ~/traccar-new/
cp target/lib/*             ~/traccar-new/lib/
cp -r traccar-web/build/*   ~/traccar-new/web/
cp schema/*                 ~/traccar-new/schema/
cp -r templates/*           ~/traccar-new/templates/
tar czf ~/traccar-new.tar.gz -C ~/traccar-new .
tar tzf ~/traccar-new.tar.gz | head
```

If you built on another machine, copy it across:

```bash
scp ~/traccar-new.tar.gz user@server:/tmp/
```

---

## 6. Deploy (config-preserving swap)

```bash
sudo systemctl stop traccar
sudo systemctl is-active traccar    # expect: inactive

STAMP=$(date +%Y%m%d-%H%M)

# Move the old code aside (rename, never delete — this is the rollback)
cd /opt/traccar
sudo mv tracker-server.jar tracker-server.jar.$STAMP
sudo mv lib       lib.$STAMP
sudo mv web       web.$STAMP
sudo mv schema    schema.$STAMP
sudo mv templates templates.$STAMP

# Unpack the new code — conf/, data/, media/, logs/ are untouched
sudo tar xzf ~/traccar-new.tar.gz -C /opt/traccar
# (if copied via scp: sudo tar xzf /tmp/traccar-new.tar.gz -C /opt/traccar)

# Permissions as the installer sets them
sudo chmod -R go+rX /opt/traccar

# Confirm config survived
sudo ls -la /opt/traccar/conf /opt/traccar/data
sudo grep -c entry /opt/traccar/conf/traccar.xml

sudo systemctl start traccar
```

Only if the bundled runtime is older than the new code requires
(`/opt/traccar/jre/bin/java -version` from §1):

```bash
sudo apt install -y openjdk-21-jre-headless
sudo mv /opt/traccar/jre /opt/traccar/jre.old
sudo ln -s /usr/lib/jvm/java-21-openjdk-amd64 /opt/traccar/jre
sudo systemctl restart traccar
```

---

## 7. Verify

```bash
sudo systemctl status traccar --no-pager
sudo journalctl -u traccar -n 100 --no-pager
sudo tail -n 100 /opt/traccar/logs/tracker-server.log

# Liquibase migrations ran and the API is up
curl -sS http://localhost:8082/api/server | head -c 400; echo
curl -fsS http://localhost:8082/api/health; echo     # this fork has /api/health

# Protocol listeners are bound again
sudo ss -lntp | grep java | head -20
```

Then check the web UI in a browser and confirm devices are reporting. Watch the
log for a few minutes for decoder errors on your Huabao/MiCODUS devices.

Once you are confident (a few days), clean up:

```bash
sudo rm -rf /opt/traccar/*.20*  /opt/traccar/lib.20* /opt/traccar/jre.old
```

---

## 8. Rollback

```bash
sudo systemctl stop traccar
cd /opt/traccar
STAMP=<the stamp you used above>
sudo rm -rf lib web schema templates tracker-server.jar
sudo mv tracker-server.jar.$STAMP tracker-server.jar
sudo mv lib.$STAMP       lib
sudo mv web.$STAMP       web
sudo mv schema.$STAMP    schema
sudo mv templates.$STAMP templates
sudo systemctl start traccar
```

Database schema changes applied by Liquibase are **not** undone by this. If the
new version migrated the schema and you must go back, restore the dump from §2:

```bash
mysql -u traccar -p traccar < /root/traccar-db-<stamp>.sql
```

Whole-install fallback:

```bash
sudo systemctl stop traccar
sudo mv /opt/traccar /opt/traccar.broken
sudo tar xzf /root/traccar-full-<stamp>.tar.gz -C /opt
sudo systemctl start traccar
```

---

## 9. If the deployment is Docker instead

`setup/docker-compose/` uses the official `traccar/traccar:latest` image, which
does **not** contain this fork's Huabao changes. Config lives in the compose
file / environment variables and the mounted volumes, so an image update keeps
it:

```bash
cd /opt/traccar            # wherever the compose file lives
sudo cp docker-compose.yaml docker-compose.yaml.bak-$(date +%Y%m%d)
sudo docker compose pull
sudo docker compose up -d
sudo docker compose ps
sudo docker compose logs -f --tail=100 traccar
```

To run this fork under Docker you must build your own image (no Dockerfile in
this repo) — build per §5, then base an image on `eclipse-temurin:21-jre` that
copies `tracker-server.jar`, `lib/`, `web/`, `schema/` and `templates/` into
`/opt/traccar`, keeping `conf/`, `data/`, `media/` and `logs/` as volumes.

---

## Notes and gotchas

1. **Never run `rm -rf /opt/traccar/*`.** The swap in §6 renames instead of
   deleting, which both preserves config and gives you a rollback.
2. **Custom notification templates** live in `/opt/traccar/templates` and are
   overwritten by the update. Diff them before deploying:
   `diff -r /opt/traccar/templates ~/traccar-new/templates`.
3. **The official `.run` installer** preserves `conf/traccar.xml` (see
   `setup/setup.sh`) but overwrites `templates/` and `web/`, and it ships
   upstream code — it will not include this fork's patches. Building your own
   installer requires `setup/package.sh` plus `makeself`, `jlink` and the
   Temurin tarballs.
4. **New config keys** appear between versions; existing keys are not removed
   from your `traccar.xml`, so nothing breaks. Review
   <https://www.traccar.org/configuration-file/> after a large version jump.
5. **Downgrades are not supported** by Liquibase — restoring the pre-upgrade
   database dump is the only way back.
6. Rebuilding only `tracker-server.jar` + `lib/` is enough when the update
   contains no schema or web changes; across a large version jump (as here),
   deploy `schema/` and `web/` too.
