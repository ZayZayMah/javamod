#!/bin/bash
# Builds ZayStaff.jar. Run inside GitHub Codespaces:  bash build.sh
set -e
cd "$(dirname "$0")"
need=25
cur=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/' || echo 0)
if [ "$cur" != "$need" ]; then
  echo ">> Downloading Java $need just for this build (only inside the Codespace)..."
  mkdir -p "$HOME/jdk$need"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/$need/ga/linux/x64/jdk/hotspot/normal/eclipse" -o /tmp/jdk.tgz
  tar -xzf /tmp/jdk.tgz -C "$HOME/jdk$need" --strip-components=1
  export JAVA_HOME="$HOME/jdk$need"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java -version
mvn -B -q package
cp target/ZayStaff.jar ./ZayStaff.jar
echo
echo "DONE. Right-click ZayStaff.jar in the file list on the left and choose Download."
