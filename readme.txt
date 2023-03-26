export JAVA_HOME=$(/usr/libexec/java_home -v1.8)
export PATH=$JAVA_HOME/bin:$PATH
mvn -version
mvn dependency:tree -Dverbose -Pdirect-runner > dep.txt

# Create cluster bucket
gsutil -m rm -r gs://${GCP_PROJECT}-bartek-dataproc
gsutil mb -l ${GCP_REGION} gs://${GCP_PROJECT}-bartek-dataproc

# Create dataproc cluster
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
-- \
--runner=SparkRunner


# MyMultiOutputJob
gcloud dataproc jobs submit spark --cluster=bartek-beam-on-spark --region=us-central1 \
--class=com.bawi.beam.MyMultiOutputJob \
--files "gs://${GCP_PROJECT}-bartek-dataproc/log4j.properties#log4j.properties" \
--jars=gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
--properties=spark.dynamicAllocation.enabled=false,spark.executor.instances=1,spark.executor.cores=1 \
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
-- \
--runner=SparkRunner \
--input=gs://${GCP_PROJECT}-bartek-dataproc/myRecord.snappy.avro \
--tableSpec=${GCP_PROJECT}:bartek_person.bartek_person_table \
--tempLocation=gs://${GCP_PROJECT}-bartek-dataproc/temp

applicationId=$(gcloud dataproc jobs list --region=us-central1 --filter='placement.clusterName = bartek-beam-on-spark' --format=json | jq -r '.[0].yarnApplications[0].trackingUrl' | egrep -o 'application_[0-9_]+')
gcloud compute ssh --zone "${GCP_ZONE}" "bartek-beam-on-spark-m" --tunnel-through-iap --project "${GCP_PROJECT}" -- "yarn logs -applicationId $applicationId" | grep ' \[' | grep '\] ' > $applicationId-filtered.log.txt
gcloud compute ssh --zone "${GCP_ZONE}" "bartek-beam-on-spark-m" --tunnel-through-iap --project "${GCP_PROJECT}" -- "yarn logs -applicationId $applicationId" > $applicationId.log.txt

java -Dlog4j.configuration=file:src/main/resources/log4j.properties -cp $HOME/.m2/repository/org/apache/avro/avro-tools/1.8.2/avro-tools-1.8.2.jar:$HOME/.m2/repository/org/xerial/snappy/snappy-java/1.1.8.4/snappy-java-1.1.8.4.jar org.apache.avro.tool.Main tojson src/test/resources/myRecord.snappy.avro


bq head ${GCP_PROJECT}:bartek_person.bartek_person_table

bq query --nouse_legacy_sql \
 "SELECT
    name, body, SAFE_CONVERT_BYTES_TO_STRING(body) as bodyAsString
  FROM
    ${GCP_PROJECT}.bartek_person.bartek_person_table"

# spark batch serverless
gcloud dataproc batches submit --project ${GCP_PROJECT} --region us-central1 spark \
 --batch bartek-batch-$RANDOM --class com.bawi.beam.MyLoggingJob --version 1.1 \
 --jars gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
 --subnet ${GCP_SUBNETWORK} --service-account ${GCP_SERVICE_ACCOUNT} \
 -- \
 --runner=SparkRunner

gcloud logging read --project ${GCP_PROJECT} "timestamp<=\"$(date -u '+%Y-%m-%dT%H:%M:%SZ')\" AND
>  timestamp>=\"$(date -u -v-173M '+%Y-%m-%dT%H:%M:%SZ')\" AND resource.type=cloud_dataproc_batch AND jsonPayload.component=executor AND jdd" --format "table(timestamp,resource.labels.batch_id,severity,jsonPayload.class,jsonPayload.message)"
TIMESTAMP                       BATCH_ID            SEVERITY  CLASS         MESSAGE
2023-03-26T09:50:46.571017878Z  bartek-batch-24468  INFO      MyLoggingJob  jdd

