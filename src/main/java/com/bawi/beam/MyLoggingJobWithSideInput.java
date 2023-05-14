package com.bawi.beam;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class MyLoggingJobWithSideInput {
    private static final Logger LOGGER = LoggerFactory.getLogger(MyLoggingJobWithSideInput.class);
    private static final String TAG = "sideInputTag";

    private static class LoggingFn extends DoFn<Integer, String> {

        @ProcessElement
        public void process(@Element Integer element, OutputReceiver<String> outputReceiver, @SideInput(TAG) Map<Integer, String> sideInputMap) {
            String value = element + sideInputMap.get(element % 2);
            LOGGER.info("processing {}", value);
            outputReceiver.output(value);
        }
    }

    private static class LoggingViewFn extends DoFn<KV<Integer, String>, KV<Integer, String>> {

        @ProcessElement
        public void process(@Element KV<Integer, String> element, OutputReceiver<KV<Integer, String>> outputReceiver) {
            LOGGER.info("view {}", element);
            outputReceiver.output(element);
        }
    }


    public static void main(String[] args) {
        args = MyPipelineUtils.updateArgsAndAutodetectRunnerIfLocal(args);
        PipelineOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().create();
        Pipeline pipeline = Pipeline.create(options);

        Map<Integer, String> map = new HashMap<>();
        map.put(0, "a");
        map.put(1, "b");

        PCollectionView<Map<Integer, String>> mapView = pipeline
                .apply("Create view", Create.of(map))
                .apply("LogView", ParDo.of(new LoggingViewFn()))
                .apply(View.asMap());

        pipeline
                .apply("Create data", Create.of(1, 3, 2))
                .apply("LogData", ParDo.of(new LoggingFn()).withSideInput(TAG, mapView));

        pipeline.run();
    }
}

