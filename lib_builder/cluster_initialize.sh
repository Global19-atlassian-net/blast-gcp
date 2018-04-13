#!/bin/bash
#set -euxo pipefail
#set -o errexit
#set -o nounset
#set -o xtrace
#exec >  >(tee -ia /tmp/cluster_initialize.log)
#exec 2> >(tee -ia /tmp/cluster_initialize.log >&2)

# Copy this script to GS bucket with:
# gsutil cp  cluster_initialize.sh "$PIPELINEBUCKET/scripts/cluster_initialize.sh"

ROLE=$(/usr/share/google/get_metadata_value attributes/dataproc-role)

apt-get install libdw-dev nmap netcat -y

# /mnt/ram-disk
#phymem=$(free|awk '/^Mem:/{print $2}')
sudo mkdir /mnt/ram-disk
echo 'tmpfs /mnt/ram-disk tmpfs nodev,nosuid,noexec,nodiratime,size=50% 0 0' \
    | sudo tee -a /etc/fstab
sudo mount -t tmpfs -o size=50% /mnt/ram-disk


PIPELINEBUCKET="gs://blastgcp-pipeline-test"
DBBUCKET="gs://nt_50mb_chunks/"
BLASTTMP=/tmp/blast/
BLASTDBDIR=$BLASTTMP/db

#curl -sSO https://repo.stackdriver.com/stack-install.sh
#sudo bash stack-install.sh --write-gcm 2>&1 | tee -a stack-install.log

curl -sSO https://dl.google.com/cloudagents/install-monitoring-agent.sh
sudo bash install-monitoring-agent.sh | tee -a stack-install.sh

curl -sSO https://dl.google.com/cloudagents/install-logging-agent.sh
sudo bash install-logging-agent.sh | tee -a stack-install.sh

cd /tmp
cat << DONE > libblast-log.conf
<source>
    @type tail
    format none
    path /tmp/blastjni.*.log
    pos_file /var/tmp/fluentd.blastjni.pos
    read_from_head true
    tag blastjni-log
</source>
DONE
cp libblast-log.conf /etc/google-fluentd/config.d/libblast-log.conf

service google-fluentd restart
logger -t cluster_initialize.sh "Logging agent begun with NCBI cluster_initialize.sh"


mkdir -p $BLASTDBDIR

if [[ "${ROLE}" == 'Master' ]]; then
    # For master node only, skip copy
    echo "master node, skipping DB copy"
    # Need maven to build jars
    apt-get update -y
    apt-get install maven -y
    # Auto terminate cluster in 8 hours
    sudo shutdown -h +480
else
    # Worker node, copy DBs from GCS
    # FIX: Future mapper will compute db lengths needed by Blast libraries
    MAXJOBS=8
    parts=`gsutil ls $DBBUCKET  | cut -d'.' -f2 | sort -Ru`
    for part in $parts; do
        logger -t cluster_initialize.sh "Preloading NCBI Blast DB $part"
        piece="nt_50M.$part"
        mkdir -p $BLASTDBDIR/$piece
        cd $BLASTDBDIR/$piece
        #mkdir lock
        gsutil -m cp $DBBUCKET$piece.*in . &
        gsutil -m cp $DBBUCKET$piece.*sq . &
        gsutil -m cp $DBBUCKET$piece.*hr . &
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
logger -t cluster_initialize.sh "NCBI cluster_initialize.sh complete"
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

