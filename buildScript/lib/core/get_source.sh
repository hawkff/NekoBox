#!/bin/bash
set -e

source "buildScript/init/env.sh"
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/nyaakuza/sing-box.git
fi
pushd sing-box
# Ensure we point at the fork, now at nyaakuza (a cached checkout may predate the moves
# from starifly and hawkff) and that the pinned commit is present before checking it out.
git remote set-url origin https://github.com/nyaakuza/sing-box.git
git fetch origin --tags --force
git checkout "$COMMIT_SING_BOX"
popd

####

popd
