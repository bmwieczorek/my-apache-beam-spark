export JAVA_HOME=$(/usr/libexec/java_home -v1.8)
export PATH=$JAVA_HOME/bin:$PATH
mvn -version
mvn dependency:tree -Dverbose -Pdirect-runner > dep.txt

# Create cluster bucket
gsutil -m rm -r gs://${GCP_PROJECT}-bartek-dataproc
gsutil mb -l ${GCP_REGION} gs://${GCP_PROJECT}-bartek-dataproc

# Create dataproc cluster with spark logs in logs explorer
gcloud dataproc clusters delete bartek-beam-on-spark --project ${GCP_PROJECT} --region us-central1 --quiet
gcloud dataproc clusters create bartek-beam-on-spark \
--project ${GCP_PROJECT} --region us-central1 --zone="" --no-address \
--subnet ${GCP_SUBNETWORK} \
--master-machine-type n2-standard-4 --master-boot-disk-size 500 \
--num-workers 2 --worker-machine-type n2-standard-4 --worker-boot-disk-size 1000 \
--image-version 2.0.58-debian10 \
--scopes 'https://www.googleapis.com/auth/cloud-platform' \
--service-account=${GCP_SERVICE_ACCOUNT} \
--bucket ${GCP_PROJECT}-bartek-dataproc \
--optional-components DOCKER \
--enable-component-gateway \
--properties spark:spark.master.rest.enabled=true,dataproc:dataproc.logging.stackdriver.job.driver.enable=true,dataproc:dataproc.logging.stackdriver.enable=true,dataproc:jobs.file-backed-output.enable=true,dataproc:dataproc.logging.stackdriver.job.yarn.container.enable=true

# properties allow container logging
#DEFAULT 2023-03-24T15:31:54.644996703Z 2023-03-24 15:31:54,319 [Executor task launch worker for task 1.0 in stage 0.0 (TID 1)] INFO MyLoggingJob$MyFunction:19 - jdd
#DEFAULT 2023-03-24T15:31:54.645709773Z 2023-03-24 15:31:54,319 [Executor task launch worker for task 0.0 in stage 0.0 (TID 0)] INFO MyLoggingJob$MyFunction:19 - hello
#DEFAULT 2023-03-24T15:31:55.348449232Z 2023-03-24 15:31:54,479 [Executor task launch worker for task 2.0 in stage 0.0 (TID 2)] INFO MyLoggingJob$MyFunction:19 - conf


# Redeploy
gsutil cp src/main/resources/log4j.properties gs://${GCP_PROJECT}-bartek-dataproc/
#gsutil cp src/main/resources/log4j2.properties gs://${GCP_PROJECT}-bartek-dataproc/
gsutil rm gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar
mvn clean package -Pdist && gsutil cp target/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar gs://${GCP_PROJECT}-bartek-dataproc/


# MyLoggingJob
gcloud dataproc jobs submit spark --cluster=bartek-beam-on-spark --region=us-central1 \
--class=com.bawi.beam.MyLoggingJob \
--files "gs://${GCP_PROJECT}-bartek-dataproc/log4j.properties#log4j.properties" \
--jars=gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
--labels=job_name=bartek-myloggingjob \
-- \
--runner=SparkRunner


# MyMultiOutputJob
gcloud dataproc jobs submit spark --cluster=bartek-beam-on-spark --region=us-central1 \
--class=com.bawi.beam.MyMultiOutputJob \
--files "gs://${GCP_PROJECT}-bartek-dataproc/log4j.properties#log4j.properties" \
--jars=gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
--properties=spark.dynamicAllocation.enabled=false,spark.executor.instances=1,spark.executor.cores=1 \
--labels=job_name=bartek-mymultioutputjob \
-- \
--runner=SparkRunner \
--evenOutput=gs://${GCP_PROJECT}-bartek-dataproc/even.txt \
--oddOutput=gs://${GCP_PROJECT}-bartek-dataproc/odd.txt


# MyGCSToBQJob
#gsutil cp target/myRecord.snappy.avro gs://${GCP_PROJECT}-bartek-dataproc/
bq mk --location US -d ${GCP_PROJECT}:bartek_person

gcloud dataproc jobs submit spark --cluster=bartek-beam-on-spark --region=us-central1 \
--class=com.bawi.beam.MyGCSToBQJob \
--files "gs://${GCP_PROJECT}-bartek-dataproc/log4j.properties#log4j.properties" \
--jars=gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
--labels=job_name=bartek-mygcstobqjob \
-- \
--runner=SparkRunner \
--input=gs://${GCP_PROJECT}-bartek-dataproc/myRecord.snappy.avro \
--tableSpec=${GCP_PROJECT}:bartek_person.bartek_person_table \
--tempLocation=gs://${GCP_PROJECT}-bartek-dataproc/temp

# query spark container logs from yarn
applicationId=$(gcloud dataproc jobs list --region=us-central1 --filter='placement.clusterName = bartek-beam-on-spark' --format=json | jq -r '.[0].yarnApplications[0].trackingUrl' | egrep -o 'application_[0-9_]+')
gcloud compute ssh --zone "${GCP_ZONE}" "bartek-beam-on-spark-m" --tunnel-through-iap --project "${GCP_PROJECT}" -- "yarn logs -applicationId $applicationId" | grep ' \[' | grep '\] ' > $applicationId-filtered.log.txt
gcloud compute ssh --zone "${GCP_ZONE}" "bartek-beam-on-spark-m" --tunnel-through-iap --project "${GCP_PROJECT}" -- "yarn logs -applicationId $applicationId" > $applicationId.log.txt

# query spark container logs in logs explorer
START_TIME="$(date -u -v-1M '+%Y-%m-%dT%H:%M:%SZ')" && \
END_TIME="$(date -u -v-120M '+%Y-%m-%dT%H:%M:%SZ')" && \
CLUSTER_NAME=bartek-beam-on-spark && \
LABELS_JOB_NAME=bartek-myloggingjob && \
SEARCH_PATTERN="MyLoggingJob" && \
LATEST_JOB_ID=$(gcloud dataproc jobs list --region=us-central1 --filter="placement.clusterName=${CLUSTER_NAME} AND labels.job_name=${LABELS_JOB_NAME}" --format=json --sort-by=~status.stateStartTime | jq -r ".[0].reference.jobId") && \
gcloud logging read --project ${GCP_PROJECT} "timestamp<=\"${START_TIME}\" AND timestamp>=\"${END_TIME}\" AND resource.type=cloud_dataproc_job AND labels.\"dataproc.googleapis.com/cluster_name\"=${CLUSTER_NAME} AND resource.labels.job_id=${LATEST_JOB_ID} AND \"${SEARCH_PATTERN}\"" --format "table(timestamp,resource.labels.job_id,logName,jsonPayload.message)"

# https://cloud.google.com/blog/products/management-tools/filtering-and-formatting-fun-with

java -Dlog4j.configuration=file:src/main/resources/log4j.properties -cp $HOME/.m2/repository/org/apache/avro/avro-tools/1.8.2/avro-tools-1.8.2.jar:$HOME/.m2/repository/org/xerial/snappy/snappy-java/1.1.8.4/snappy-java-1.1.8.4.jar org.apache.avro.tool.Main tojson src/test/resources/myRecord.snappy.avro


bq head ${GCP_PROJECT}:bartek_person.bartek_person_table

bq query --nouse_legacy_sql \
 "SELECT
    name, body, SAFE_CONVERT_BYTES_TO_STRING(body) as bodyAsString
  FROM
    ${GCP_PROJECT}.bartek_person.bartek_person_table"

##########################
# spark batch serverless #
##########################
gsutil -m rm -r gs://${GCP_PROJECT}-bartek-persistent-history-server-dataproc
gsutil mb -l ${GCP_REGION} gs://${GCP_PROJECT}-bartek-persistent-history-server

gcloud dataproc clusters create bartek-persistent-history-server \
--project ${GCP_PROJECT} --region us-central1 --zone="" --no-address \
--single-node \
--image-version 2.0.58-debian10 \
--subnet ${GCP_SUBNETWORK} \
--scopes 'https://www.googleapis.com/auth/cloud-platform' \
--service-account=${GCP_SERVICE_ACCOUNT} \
--bucket ${GCP_PROJECT}-bartek-persistent-history-server \
--enable-component-gateway \
--properties=spark:spark.history.fs.logDirectory=gs://${GCP_PROJECT}-bartek-persistent-history-server/*/spark-job-history,mapred:mapreduce.jobhistory.read-only.dir-pattern=gs://${GCP_PROJECT}-bartek-persistent-history-server/*/mapreduce-job-history/done,yarn:yarn.nodemanager.remote-app-log-dir=gs://${GCP_PROJECT}-bartek-persistent-history-server/*/yarn-logs,spark:spark.history.custom.executor.log.url.applyIncompleteApplication=false,spark:spark.history.custom.executor.log.url={{YARN_LOG_SERVER_URL}}/{{NM_HOST}}:{{NM_PORT}}/{{CONTAINER_ID}}/{{CONTAINER_ID}}/{{USER}}/{{FILE_NAME}}


# submit spark serverless batch
gcloud dataproc batches submit --project ${GCP_PROJECT} --region us-central1 spark \
 --batch bartek-batch-$RANDOM --class com.bawi.beam.MyLoggingJob --version 1.1 \
 --jars gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
 --subnet ${GCP_SUBNETWORK} --service-account ${GCP_SERVICE_ACCOUNT} \
 -- \
 --runner=SparkRunner

gcloud dataproc batches submit --project ${GCP_PROJECT} --region us-central1 spark \
 --batch bartek-batch-$RANDOM --class com.bawi.beam.MyMultiOutputJob --version 1.1 \
 --jars gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
 --subnet ${GCP_SUBNETWORK} --service-account ${GCP_SERVICE_ACCOUNT} \
 --history-server-cluster projects/${GCP_PROJECT}/regions/us-central1/clusters/bartek-persistent-history-server \
 -- \
 --runner=SparkRunner \
 --evenOutput=gs://${GCP_PROJECT}-bartek-dataproc/even.txt \
 --oddOutput=gs://${GCP_PROJECT}-bartek-dataproc/odd.txt


# query metrics for spark serverless batch
#metric.type="custom.googleapis.com/spark/driver/DAGScheduler/messageProcessingTime/count" AND resource.type="cloud_dataproc_batch" AND resource.label."batch_id"="bartek-batch-22191" AND resource.label."location"="us-central1"
curl \
  "https://monitoring.googleapis.com/v3/projects/${GCP_PROJECT}/timeSeries?aggregation.alignmentPeriod=60s&filter=metric.type%3D%22custom.googleapis.com%2Fspark%2Fdriver%2FDAGScheduler%2FmessageProcessingTime%2Fcount%22&interval.endTime=2023-03-28T09%3A44%3A00Z&interval.startTime=2023-03-28T09%3A39%3A00Z&fields=timeSeries.points.interval.endTime%2CtimeSeries.points.value" \
  --header "Authorization: Bearer $(gcloud auth print-access-token)" \
  --header 'Accept: application/json' \
  --compressed

START_TIME="2023-03-28T09%3A39%3A00Z"
END_TIME="2023-03-28T09%3A44%3A00Z"
START_TIME=$(date -u -v-60M '+%Y-%m-%dT%H:%M:%SZ')
END_TIME=$(date -u -v-1M '+%Y-%m-%dT%H:%M:%SZ')
BATCH_ID=airshopping-gcstobigqueryjob-batch-6436
curl -s \
  "https://monitoring.googleapis.com/v3/projects/${GCP_PROJECT}/timeSeries?aggregation.alignmentPeriod=60s&filter=metric.type%3D%22custom.googleapis.com%2Fspark%2Fdriver%2FDAGScheduler%2FmessageProcessingTime%2Fcount%22%20AND%20resource.type%3D%22cloud_dataproc_batch%22%20AND%20resource.label.%22batch_id%22%3D%22${BATCH_ID}%22%20AND%20resource.label.%22location%22%3D%22${GCP_REGION}%22&interval.endTime=${END_TIME}&interval.startTime=${START_TIME}&fields=timeSeries.points.interval.endTime%2CtimeSeries.points.value" \
  --header "Authorization: Bearer $(gcloud auth print-access-token)" \
  --header 'Accept: application/json' \
  --compressed \
  | jq -r ".timeSeries[0].points[].value.doubleValue" | sort -n
