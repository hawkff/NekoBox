export COMMIT_SING_BOX="13f9fc0b8f46cd4a57eb515e7b74fc13a411b644"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the hawkff fork at the pinned commit.
export VERSION_SING_BOX="1.14.3-30-g13f9fc0b8"
