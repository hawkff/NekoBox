export COMMIT_SING_BOX="1909a8c0ef91e2f07c30b1e0545cff662b0e97e3"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the nyaakuza fork at the pinned commit.
export VERSION_SING_BOX="1.14.4-61-g1909a8c0e"
