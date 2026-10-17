export COMMIT_SING_BOX="16a4325d383f57af4dedfa0b03a49838669e7e38"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the hawkff fork at the pinned commit.
export VERSION_SING_BOX="1.14.3-36-g16a4325d3"
