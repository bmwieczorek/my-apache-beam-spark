package com.bawi.beam;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.avro.coders.AvroGenericCoder;
import org.apache.beam.sdk.extensions.avro.io.AvroIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class MyGCSToBQJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(MyGCSToBQJob.class);

    private static final Schema SCHEMA = SchemaBuilder.record("myRecord").fields().requiredString("name").requiredBytes("body").endRecord();


    public static void main(String[] args) {
        args = MyPipelineUtils.updateArgsAndAutodetectRunnerIfLocal(args,
                "--input=src/test/resources/*.avro",
                "--tableSpec=" + System.getenv("GCP_PROJECT") + ":bartek_person.bartek_person_table",
                "--tempLocation=gs://" + System.getenv("GCP_PROJECT") + "-bartek-dataproc/temp"
        );

        MyPipelineOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(MyPipelineOptions.class);
        Pipeline pipeline = Pipeline.create(options);

        pipeline.apply(AvroIO
                        .parseGenericRecords((SerializableFunction<GenericRecord, GenericRecord>) genericRecord -> {
                            Utf8 name = (Utf8) genericRecord.get("name");
                            ByteBuffer byteBuffer = (ByteBuffer) genericRecord.get("body");
                            byte[] bytes = byteBuffer.array();
                            LOGGER.info(name.toString() + "," + new String(bytes));
                            return genericRecord;
                        }).withCoder(AvroGenericCoder.of(SCHEMA))
                        .from(options.getInput())
                )
                // requires org.apache.beam:beam-sdks-java-io-google-cloud-platform
                .apply(BigQueryIO.<GenericRecord>write()
                        .withAvroFormatFunction(r -> {
                            GenericRecord element = r.getElement();
                            LOGGER.info("element {}, schema {}", element, r.getSchema());
                            return element;
                        })
                        .withAvroSchemaFactory(qTableSchema -> SCHEMA)
                        .to(options.getTableSpec())
                        .useAvroLogicalTypes()
                        .withoutValidation()
                        .withSchema(AvroToBigQuerySchemaConverter.convert(SCHEMA))
                        .withWriteDisposition(WriteDisposition.WRITE_APPEND)
                        .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED));

        pipeline.run();
    }

    @SuppressWarnings("unused")
    public interface MyPipelineOptions extends PipelineOptions {
        @Validation.Required
        ValueProvider<String> getInput();
        void setInput(ValueProvider<String> value);

        @Validation.Required
        ValueProvider<String> getTableSpec();
        void setTableSpec(ValueProvider<String> value);
    }
}

