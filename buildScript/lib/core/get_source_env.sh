export COMMIT_SING_BOX="3f22c3de7de471e2d8f11c8c92318c84c989a3a5"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the hawkff fork at the pinned commit.
export VERSION_SING_BOX="1.14.3-34-g3f22c3de7"
