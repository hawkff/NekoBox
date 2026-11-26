export COMMIT_SING_BOX="7cfeca4bb00a216e73373fa60b741dece022b55d"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the nyaakuza fork at the pinned commit.
export VERSION_SING_BOX="1.14.4-208-g7cfeca4bb"
