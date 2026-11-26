export COMMIT_SING_BOX="3dcb5cc64c336cd1b82cb48d573a01081bb1fbfc"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the nyaakuza fork at the pinned commit.
export VERSION_SING_BOX="1.14.4-211-g3dcb5cc64"
