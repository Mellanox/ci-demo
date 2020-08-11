#!/bin/bash -eE

release_dir=/.autodirect/sw/release/sw_acceleration/clusterkit
do_release=${do_release:=0}

topdir=$(git rev-parse --show-toplevel)
cd $topdir


if [ ! -d .git ]; then
	echo "Error: should be run from project root"
	exit 1
fi

ncpus=$(cat /proc/cpuinfo|grep processor|wc -l)
export AUTOMAKE_JOBS=$ncpus

./autogen.sh
./configure
make distcheck

pkg_spec=$(ls -1 *.spec 2>/dev/null ||:)

if [ ! -f "$pkg_spec" ]; then
	echo packaging is not supported yet
	exit 0
fi


rm -rf rpm-dist

if [[ -x /usr/bin/dpkg-buildpackage ]]; then
      echo "==== Build debian package ===="
      stdbuf -e0 -o0 dpkg-buildpackage -us -uc |& tee rpmbuild.log
else
      echo "==== Build rpm package ===="
      stdbuf -e0 -o0 ./contrib/buildrpm.sh -s -t -b |& tee rpmbuild.log
fi

pattern='warning: '
if grep -q "$pattern" rpmbuild.log; then
	echo "[ERROR] rpm build generated warnings: - please review" 
	grep "$pattern" rpmbuild.log
fi

if [[ -x /usr/bin/dpkg-buildpackage ]]; then
	exit 0
fi

rpm_bin=$(ls -1 rpm-dist/$(uname -i)/*.rpm)
rpm_src=$(ls -1 rpm-dist/*.src.rpm)

echo binary rpm = $rpm_bin
echo source rpm = $rpm_src
echo latest_txt = $(basename $rpm_src)


if [ -d $release_dir -a $do_release -gt 0 ]; then
	cp -f $rpm_src $release_dir
	echo $latest_txt > $release_dir/latest.txt
fi
