#!/bin/bash -leE

topdir=$(git rev-parse --show-toplevel)
cd $topdir

inst_dir=$topdir/install

if [ ! -d .git ]; then
	echo "Error: should be run from project root"
	exit 1
fi

echo Running tests

ncpus=$(cat /proc/cpuinfo|grep processor|wc -l)
export AUTOMAKE_JOBS=$ncpus

./autogen.sh
./configure --prefix $inst_dir

make -j $ncpus all
make -j $ncpus install


LD_LIBRARY_PATH=$inst_dir/lib $inst_dir/bin/hello-world
