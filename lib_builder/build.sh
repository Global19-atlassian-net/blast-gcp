#!/usr/bin/env bash
set -o nounset
set -o pipefail
set -o errexit

PIPELINEBUCKET="gs://blastgcp-pipeline-test"

set +errexit
distro=$(grep Debian /etc/os-release | wc -l)
set -o errexit
if [ "$distro" -ne 0 ]; then
    export DISTRO="Debian 8"
    export BUILDENV="google"
    export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
    export PATH="$JAVA_HOME/bin:$PATH"
    export BLASTDB=/tmp/blast/
    export SPARK_HOME=/usr/lib/spark/
    export LD_LIBRARY_PATH=".:$PWD/ext"
else
    export DISTRO="CentOS 7"
    export BUILDENV="ncbi"
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))
    export LD_LIBRARY_PATH="../pipeline:/opt/ncbi/gcc/4.9.3/lib64/:$PWD/ext"
    export BLASTDB=/net/frosty/vol/blast/db/blast
    BLASTBYDATE="/panfs/pan1.be-md.ncbi.nlm.nih.gov/blastprojects/blast_build/c++/"
    export SPARK_HOME=/usr/local/spark/2.2.0/

fi

echo "Building at $BUILDENV on $DISTRO"

JAVA_INC=" -I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
export CLASSPATH="."

rm -f *.class
rm -rf gov
rm -f *test.result
rm -f *.jar
rm -f /tmp/blastjni.$USER.log
rm -f signatures
rm -f core.* hs_err_* output.*


# FIX: Unfortunately, BlastJNI.h can only be built @ Google, due to
#packages,  but is required by g++ # at NCBI. Revisit after Jira BG-21
#MAIN_JAR="sprint4.jar"
MAIN_JAR="../pipeline/target/sparkblast-1-jar-with-dependencies.jar"
DEPENDS="$SPARK_HOME/jars/*:$MAIN_JAR:."

echo "Compiling Java"
pushd ../pipeline > /dev/null
mvn -q package -f mike_pom.xml
popd > /dev/null
#NOTE: javah deprecated in Java 9, removed in Java 10
JAVASRCDIR="../pipeline/src/main/java"
#    $JAVASRCDIR/BLAST_REQUEST.java \
    #    $JAVASRCDIR/BLAST_PARTITION.java \
    #    $JAVASRCDIR/BLAST_HSP_LIST.java \
    #    $JAVASRCDIR/BLAST_TB_LIST.java \
    #    $JAVASRCDIR/BLAST_LIB.java \
javac -Xlint:all -Xlint:-path -Xlint:-serial -cp $DEPENDS:. -d . -h . \
    ./BLAST_TEST.java
echo "Creating JNI header"
javac -Xlint:all -Xlint:-path -Xlint:-serial -cp $DEPENDS:. -d . -h . \
    ../pipeline/src/main/java/BLAST_LIB.java

javap -p -s ../pipeline/target/classes/gov/nih/nlm/ncbi/blastjni/BLAST_LIB.class >> signatures
javap -p -s ../pipeline/target/classes/gov/nih/nlm/ncbi/blastjni/BLAST_HSP_LIST.class >> signatures
javap -p -s ../pipeline/target/classes/gov/nih/nlm/ncbi/blastjni/BLAST_TB_LIST.class >> signatures

md5sum -c signatures.md5
#md5sum signatures > signatures.md5

echo "Creating JAR"
#jar cf $MAIN_JAR gov/nih/nlm/ncbi/blastjni/*class
cp $MAIN_JAR .
#rm -rf gov

if [ "$BUILDENV" = "ncbi" ]; then
    rm -f libblastjni.o ../pipeline/libblastjni.so
    echo "Compiling and linking blastjni.cpp"
    cppcheck --enable=all --platform=unix64 --std=c++11 blastjni.cpp
    # Note: Library order important
    #       Hidden dl_open for libdw
    # Eugene has:
    #        -static-libstdc++  # Needed for NCBI's Spark cluster (RHEL7?)
    #-ldbapi_driver -lncbi_xreader \
        GPPCOMMAND="
        g++ blastjni.cpp \
        -std=gnu++11 \
        -Wall -O  -I . \
        -Wextra -pedantic \
        -shared \
        -fPIC \
        $JAVA_INC \
        -L./int/blast/libs \
        -I $BLASTBYDATE/include \
        -I $BLASTBYDATE/ReleaseMT/inc \
        -L $BLASTBYDATE/ReleaseMT/lib \
        -I/panfs/pan1.be-md.ncbi.nlm.nih.gov/blastprojects/blast_build/lmdb-0.9.21 \
        -L/panfs/pan1.be-md.ncbi.nlm.nih.gov/blastprojects/blast_build/lmdb-0.9.21 \
        -L . \
        -L ext \
        -fopenmp -lxblastformat -lalign_format -ltaxon1 -lblastdb_format \
        -lgene_info -lxformat -lxcleanup -lgbseq -lmlacli \
        -lmla -lmedlars -lpubmed -lvalid -ltaxon3 -lxalnmgr \
        -lblastxml -lblastxml2 -lxcgi -lxhtml -lproteinkmer \
        -lxblast -lxalgoblastdbindex -lcomposition_adjustment \
        -lxalgodustmask -lxalgowinmask -lseqmasks_io -lseqdb \
        -lblast_services -lxalnmgr -lxobjutil -lxobjread \
        -lvariation -lcreaders -lsubmit -lxnetblastcli \
        -lxnetblast -lblastdb -lscoremat -ltables -lxregexp \
        -lncbi_xloader_genbank -lncbi_xreader_id1 \
        -lncbi_xreader \
        -lncbi_xreader_id2 \
        -lxconnect -lid1 -lid2 -lxobjmgr \
        -lgenome_collection -lseqedit -lseqsplit -lsubmit \
        -lseqset -lseq -lseqcode -lsequtil -lpub -lmedline \
        -lbiblio -lgeneral -lxser -lxutil -lxncbi -lxcompress \
        -llmdb-static -lpthread -lz -lbz2 \
        -L/netopt/ncbi_tools64/lzo-2.05/lib64 \
        -llzo2 -ldl -lz -lnsl -ldw -lrt -ldl -lm -lpthread \
        -o ../pipeline/libblastjni.so"
        scan-build --use-analyzer /usr/local/llvm/3.8.0/bin/clang $GPPCOMMAND
        $GPPCOMMAND
fi


#if [ "$BUILDENV" = "google" ]; then
echo "Testing JNI"
set +errexit
ldd ../pipeline/libblastjni.so | grep found
if [[ $? -ne 1 ]]; then
    echo "Missing a shared library"
    echo "LD_LIBRARY_PATH is $LD_LIBRARY_PATH"
    exit 1
fi
#-verbose:jni \
    #-Djava.library.path="../pipeline" \
    java \
    -Xcheck:jni -Xdiag -Xfuture \
    -cp $MAIN_JAR:.  \
    gov.nih.nlm.ncbi.blastjni.BLAST_TEST \
    > output.$$ 2>&1
sort output.$$ | grep -e "000 " > test.result
CMP=$(cmp test.result test.expected)
if [[ $? -ne 0 ]]; then
    cat -tn output.$$
    #rm -f output.$$
    sdiff -w 70 test.result test.expected
    echo "Testing of JNI failed"
    exit 1
fi
rm -f output.$$
set -o errexit
echo "Test OK"
#fi

if [ "$BUILDENV" = "google" ]; then
    echo "Copying to Cloud Storage Bucket"
    gsutil cp \
        cluster_initialize.sh \
        "$PIPELINEBUCKET/scripts/cluster_initialize.sh"
fi

echo "Build Complete"
date
echo
exit 0

