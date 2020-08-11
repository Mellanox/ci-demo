#!/bin/bash -leE

topdir=$(git rev-parse --show-toplevel)
cd $topdir


if [ ! -d .git ]; then
	echo "Error: should be run from project root"
	exit 1
fi

pkg_spec=$(ls -1 *.spec 2>/dev/null ||:)

if [ ! -f "$pkg_spec" ]; then
	echo packaging is not supported yet
	exit 0
fi

if [[ -x /usr/bin/dpkg-buildpackage ]]; then
      echo "==== Check debian package ===="

      pkg_bin=$(ls -1 ../clusterkit*.deb ||:)
      apt install $pkg_bin

else
      echo "==== Check rpm package ===="

      pkg_bin=$(ls -1 rpm-dist/$(uname -i)/*.rpm ||:)
      pkg_src=$(ls -1 rpm-dist/*.src.rpm ||:)
      rpm -ihv $pkg_bin
fi

if [ ! -f "$pkg_bin" ]; then
	echo Not found $pkg_bin
	exit 1
fi
