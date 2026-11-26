export COMMIT_SING_BOX="ffe64df0410954b8c2b6aca64fc699e159ede6a3"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the nyaakuza fork at the pinned commit.
export VERSION_SING_BOX="1.14.4-213-gffe64df04"
