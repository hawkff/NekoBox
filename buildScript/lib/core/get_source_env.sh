export COMMIT_SING_BOX="341fd521f4f7286d979cf7add733844d9a7a8c68"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the hawkff fork at the pinned commit.
export VERSION_SING_BOX="1.14.3-29-g341fd521f"
