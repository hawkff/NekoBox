export COMMIT_SING_BOX="adfee5f2fdc6d2aa48f4b0ab558e2f41bbe813d1"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the hawkff fork at the pinned commit.
export VERSION_SING_BOX="1.14.4-45-g8b6a7b3e4"
