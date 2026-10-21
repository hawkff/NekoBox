export COMMIT_SING_BOX="c186bd769b3d2e521c84b072e3399f5a4ebdd6d3"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the hawkff fork at the pinned commit.
export VERSION_SING_BOX="1.14.3-41-gc186bd769"
