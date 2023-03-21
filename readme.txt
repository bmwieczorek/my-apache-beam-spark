export JAVA_HOME=$(/usr/libexec/java_home -v1.8)
export PATH=$JAVA_HOME/bin:$PATH
mvn -version

# Create cluster bucket
gsutil rm -r gs://${GCP_PROJECT}-bartek-dataproc
gsutil mb -l ${GCP_REGION} gs://${GCP_PROJECT}-bartek-dataproc

# Create dataproc cluster
gcloud dataproc clusters delete bartek-beam-on-spark --project ${GCP_PROJECT} --region us-central1
gcloud dataproc clusters create bartek-beam-on-spark \
--project ${GCP_PROJECT} --region us-central1 --zone="" --no-address \
--subnet ${GCP_SUBNETWORK} \
--master-machine-type n2-standard-4 --master-boot-disk-size 500 \
--num-workers 2 --worker-machine-type n2-standard-4 --worker-boot-disk-size 1000 \
--image-version 2.0-debian10 \
--scopes 'https://www.googleapis.com/auth/cloud-platform' \
--service-account=${GCP_SERVICE_ACCOUNT} \
--bucket ${GCP_PROJECT}-bartek-dataproc \
--optional-components DOCKER \
--enable-component-gateway \
--properties spark:spark.master.rest.enabled=true

# Redeploy
gsutil cp src/main/resources/log4j.properties gs://${GCP_PROJECT}-bartek-dataproc/
gsutil cp src/main/resources/log4j2.properties gs://${GCP_PROJECT}-bartek-dataproc/
gsutil rm gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar
mvn clean package -Pdist && gsutil cp target/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar gs://${GCP_PROJECT}-bartek-dataproc/


# MyLoggingJob
gcloud dataproc jobs submit spark --cluster=bartek-beam-on-spark --region=us-central1 \
--class=com.bawi.beam.MyLoggingJob \
--files "gs://${GCP_PROJECT}-bartek-dataproc/log4j2.properties#log4j2.properties" \
--jars=gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
-- \
--runner=SparkRunner

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
-- \
--runner=SparkRunner \
--evenOutput=gs://${GCP_PROJECT}-bartek-dataproc/even.txt \
--oddOutput=gs://${GCP_PROJECT}-bartek-dataproc/odd.txt


# MyGCSToBQJob
#gsutil cp target/myRecord.snappy.avro gs://${GCP_PROJECT}-bartek-dataproc/
bq mk --location US -d ${GCP_PROJECT}:bartek_person

gcloud dataproc jobs submit spark --cluster=bartek-beam-on-spark --region=us-central1 \
--class=com.bawi.beam.MyGCSToBQJob \
--files "gs://${GCP_PROJECT}-bartek-dataproc/log4j2.properties#log4j2.properties" \
--jars=gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
-- \
--runner=SparkRunner \
--input=gs://${GCP_PROJECT}-bartek-dataproc/myRecord.snappy.avro \
--tableSpec=${GCP_PROJECT}:bartek_person.bartek_person_table \
--tempLocation=gs://${GCP_PROJECT}-bartek-dataproc/temp


gcloud compute ssh --zone "${GCP_ZONE}" "bartek-beam-on-spark-m"  --tunnel-through-iap --project "${GCP_PROJECT}"

spark-submit --class com.bawi.beam.MyLoggingJob --master yarn \
--files "gs://${GCP_PROJECT}-bartek-dataproc/log4j2.properties#log4j2.properties" \
gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
--runner=SparkRunner

spark-submit --class com.bawi.beam.MyMultiOutputJob --master yarn --deploy-mode client \
gs://${GCP_PROJECT}-bartek-dataproc/my-apache-beam-spark-0.1-SNAPSHOT-shaded.jar \
--runner=SparkRunner \
--evenOutput=gs://${GCP_PROJECT}-bartek-dataproc/even.txt \
--oddOutput=gs://${GCP_PROJECT}-bartek-dataproc/odd.txt



gcloud dataproc jobs wait <job-id> --project ${GCP_PROJECT} --region ${GCP_REGION}
Waiting for job output...
23/03/12 10:29:32 INFO org.apache.beam.runners.spark.SparkRunner: Executing pipeline using the SparkRunner.
23/03/12 10:29:32 INFO org.apache.beam.runners.spark.translation.SparkContextFactory: Creating a brand new Spark Context.
23/03/12 10:29:33 INFO org.apache.spark.SparkEnv: Registering MapOutputTracker
23/03/12 10:29:33 INFO org.apache.spark.SparkEnv: Registering BlockManagerMaster
23/03/12 10:29:33 INFO org.apache.spark.SparkEnv: Registering BlockManagerMasterHeartbeat
23/03/12 10:29:33 INFO org.apache.spark.SparkEnv: Registering OutputCommitCoordinator
...
yarnApplications:
- name: MyMultiOutputJob
  progress: 1.0
  state: FINISHED


applicationId=$(gcloud dataproc jobs list --region=us-central1 --filter='placement.clusterName = bartek-beam-on-spark' --format=json | jq -r '.[0].yarnApplications[0].trackingUrl' | egrep -o 'application_[0-9_]+')
#gcloud compute ssh --zone "${GCP_ZONE}" "bartek-beam-on-spark-m"  --tunnel-through-iap --project "${GCP_PROJECT}" -- "yarn logs -applicationId $applicationId" > $applicationId.log.txt
gcloud compute ssh --zone "${GCP_ZONE}" "bartek-beam-on-spark-m"  --tunnel-through-iap --project "${GCP_PROJECT}" -- "yarn logs -applicationId $applicationId" | grep ' \[' | grep '\] ' > $applicationId-filtered.log.txt