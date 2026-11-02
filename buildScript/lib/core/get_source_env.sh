export COMMIT_SING_BOX="8e3b20c8842bea04b29fd7e3081f002be7a5981a"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the nyaakuza fork at the pinned commit.
export VERSION_SING_BOX="1.14.4-48-g8e3b20c88"
