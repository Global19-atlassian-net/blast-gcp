#!/bin/bash
#set -euxo pipefail
#set -o errexit
#set -o nounset
#set -o xtrace
#exec >  >(tee -ia /tmp/cluster_initialize.log)
#exec 2> >(tee -ia /tmp/cluster_initialize.log >&2)

# Copy this script to GS bucket with:
# gsutil cp  cluster_initialize.sh "$PIPELINEBUCKET/scripts/cluster_initialize.sh"

cd /tmp

ROLE=$(/usr/share/google/get_metadata_value attributes/dataproc-role)

#apt-get install nmap netcat -y

# /mnt/ram-disk
#phymem=$(free|awk '/^Mem:/{print $2}')
sudo mkdir /mnt/ram-disk
echo 'tmpfs /mnt/ram-disk tmpfs nodev,nosuid,noexec,nodiratime,size=50% 0 0' \
    | sudo tee -a /etc/fstab
sudo mount -t tmpfs -o size=50% /mnt/ram-disk


PIPELINEBUCKET="gs://blastgcp-pipeline-test"
DBBUCKET="gs://nt_50mb_chunks/"
BLASTTMP=/tmp/blast/
BLASTDBDIR=$BLASTTMP/db/prefetched

#curl -sSO https://repo.stackdriver.com/stack-install.sh
#sudo bash stack-install.sh --write-gcm 2>&1 | tee -a stack-install.log

curl -sSO https://dl.google.com/cloudagents/install-monitoring-agent.sh
sudo bash install-monitoring-agent.sh | tee -a stackdriver-install.log 2>&1

curl -sSO https://dl.google.com/cloudagents/install-logging-agent.sh
sudo bash install-logging-agent.sh | tee -a stackdriver-install.log 2>&1

#cd /tmp
#cat << DONE > libblast-log.conf
#<source>
## Automatically generated by cluster_initialize.sh
#    @type tail
#    format none
#    path /tmp/blastjni.*.log
#    pos_file /var/tmp/fluentd.blastjni.pos
#    read_from_head true
#    tag blastjni-log
#</source>
#DONE
#cp libblast-log.conf /etc/google-fluentd/config.d/libblast-log.conf
#service google-fluentd restart


cat << 'DONE2' > log4j.proto
# Automatically generatged by cluster_initialize.sh

log4j.appender.tmpfile=org.apache.log4j.FileAppender
log4j.appender.tmpfile.File=/tmp/blastjni.${user.name}.log
log4j.appender.tmpfile.layout=org.apache.log4j.PatternLayout
log4j.appender.tmpfile.layout.ConversionPattern=%m%n

#log4j.appender.sparkfile=org.apache.log4j.FileAppender
#log4j.appender.sparkfile.File=/var/log/spark/blastjni.${user.name}.log
#log4j.appender.sparkfile.layout=org.apache.log4j.PatternLayout
##log4j.appender.sparkfile.layout.ConversionPattern=%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n
#log4j.appender.sparkfile.layout.ConversionPattern=%d [%p] [%t] %c: %m%n

log4j.logger.gov.nih.nlm.ncbi.blastjni.BLAST_LIB=INFO, tmpfile
#, sparkfile
log4j.logger.gov.nih.nlm.ncbi.blastjni.BLAST_TEST=TRACE, tmpfile
DONE2
cat log4j.proto >> /etc/spark/conf.dist/log4j.properties


logger -t cluster_initialize.sh "BLASTJNI Logging agent begun with cluster_initialize.sh"

# Auto terminate cluster in 8 hours
sudo shutdown -h +480

mkdir -p $BLASTDBDIR

if [[ "${ROLE}" == 'Master' ]]; then
    # For master node only, skip copy
    echo "master node, skipping DB copy"
    # Need maven to build jars, virtualenv for installing Google APIs for tests
    apt-get update -y
    apt-get install -y -u maven python python-dev python3 python3-dev virtualenv
    #protobuf-compiler
    # chromium # for looking at webserver with X11 forwarding?
#    sudo easy_install pip
#    sudo pip install --upgrade virtualenv
#    sudo pip install --user --upgrade google-cloud-storage
#    sudo pip install --user --upgrade google-cloud-pubsub
else
    # Worker node, copy DBs from GCS
    # FIX exit: Expected from Wolfgang's partition_mapper EOB 4/19
    MAXJOBS=8
    parts=`gsutil ls $DBBUCKET  | cut -d'.' -f2 | sort -Ru`
    for part in $parts; do
        logger -t cluster_initialize.sh "BLASTJNI Preloading Blast DB $part"
        piece="nt_50M.$part"
        mkdir -p $BLASTDBDIR/$piece
        cd $BLASTDBDIR/$piece
        #mkdir lock
        gsutil -m cp $DBBUCKET$piece.*in . &
        gsutil -m cp $DBBUCKET$piece.*sq . &
        gsutil -m cp $DBBUCKET$piece.*ax . &
        touch done
        #rmdir lock

        j=`jobs | wc -l`
        while [ $j -ge $MAXJOBS ]; do
            j=`jobs | wc -l`
            echo "$j waiting ..."
            sleep 0.5
        done
    done
fi

# Set lax permissions
cd $BLASTTMP
chown -R spark:spark $BLASTTMP
chmod -R ugo+rxw $BLASTTMP

ls -laR $BLASTTMP

echo Cluster Initialized
logger -t cluster_initialize.sh "BLASTJNI cluster_initialize.sh complete"
date

exit 0


# Future enhancements:
# run-init-actions-early? To get RAM before Spark/YARN?
# Cheap Chaos Monkey (shutdown -h +$RANDOM)
# Start daemons
# pre-warm databases
# Schedule things (cron or systemd timer)
# Configure user environments
# Submit stream, keep it alive:
#     https://github.com/GoogleCloudPlatform/dataproc-initialization-actions/tree/master/post-init

