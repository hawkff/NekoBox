export COMMIT_SING_BOX="f774300f20aac455695a1aacac197287b9af3813"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX. Matches `git describe
# --tags` on the nyaakuza fork at the pinned commit.
export VERSION_SING_BOX="1.14.4-59-gf774300f2"
