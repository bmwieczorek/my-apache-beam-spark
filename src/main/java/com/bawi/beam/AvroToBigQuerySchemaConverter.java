package com.bawi.beam;

import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableSchema;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class AvroToBigQuerySchemaConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AvroToBigQuerySchemaConverter.class);

    public static void main(String[] args) throws IOException {
        LOGGER.info("args=" + (args == null ? null : Arrays.asList(args)));
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException("Expected file paths to input avro schema and output table schema");
        }
        File inputFile = new File(args[0]);
        if (!inputFile.exists()) {
            throw new IllegalArgumentException("File " + inputFile + " with avro schema does not exists");
        }
        Schema avroSchema = new Schema.Parser().parse(inputFile);
        TableSchema tableSchema = convert(avroSchema);
        //tableSchema.setFactory(new JacksonFactory()); // older beam version
        tableSchema.setFactory(new GsonFactory());
        String tableSchemaString = tableSchema.toPrettyString();
        int start = tableSchemaString.indexOf("[");
        int end = tableSchemaString.lastIndexOf("]") + 1;
        Path outputPath = Paths.get(args[1]);
        Files.write(outputPath, tableSchemaString.substring(start, end).getBytes(UTF_8));
        LOGGER.info("Converted avro schema from {} and written table schema to {}", inputFile, outputPath);
    }

    public static TableSchema convert(Schema avroSchema) {
        TableSchema tableSchema = new TableSchema();
        List<TableFieldSchema> tableFieldSchemas = new ArrayList<>();
        for (Schema.Field field : avroSchema.getFields()) {
            tableFieldSchemas.add(getFieldSchema(field));
        }
        tableSchema.setFields(tableFieldSchemas);
        return tableSchema;
    }

    private static TableFieldSchema getFieldSchema(Schema.Field field) {
        return new TableFieldSchema()
                        .setName(field.name())
                        .setType(getTableFieldType(field.schema()))
                        .setMode(getTableFieldMode(field))
                        .setFields(getTableSubFields(field))
                        .setDescription(field.doc());
    }

    private static List<TableFieldSchema> getTableSubFields(Schema.Field field) {
        return getTableFieldSchemas(field.schema());
    }

    private static List<TableFieldSchema> getTableFieldSchemas(Schema schema) {
        List<TableFieldSchema> tableSubFieldsSchemas = new ArrayList<>();
        if (schema.getType() == Schema.Type.RECORD) {
            for (Schema.Field subField : schema.getFields()) {
                TableFieldSchema tableSubFieldSchema = getFieldSchema(subField);
                tableSubFieldsSchemas.add(tableSubFieldSchema);
            }
            return tableSubFieldsSchemas;
        }
        if (schema.getType() == Schema.Type.ARRAY) {
            Schema subSchema = schema.getElementType();
            return getTableFieldSchemas(subSchema);
        }
        if (schema.getType() == Schema.Type.UNION) {
            List<Schema> types = schema.getTypes();
            Schema subSchema = types.get(0).getType() == Schema.Type.NULL ?  types.get(1) : types.get(0);
            return getTableFieldSchemas(subSchema);
        }
        return null;
    }

    private static String getTableFieldType(Schema fieldSchema) {
        Schema.Type type = fieldSchema.getType();
        LogicalType logicalType = fieldSchema.getLogicalType();
        switch (type) {
            case RECORD:
                return "RECORD";
            case INT:
                return LogicalTypes.date().equals(logicalType)
                        ? "DATE" : "INTEGER";
            case LONG:
                return LogicalTypes.timestampMillis().equals(logicalType) || LogicalTypes.timestampMicros().equals(logicalType) ?
                        "TIMESTAMP" :
                        LogicalTypes.timeMicros().equals(logicalType) || LogicalTypes.timeMillis().equals(logicalType) ?
                                "TIME" :
                                "INTEGER";
            case BOOLEAN:
                return "BOOLEAN";
            case FLOAT:
            case DOUBLE:
                return "FLOAT";
            case BYTES:
                return logicalType instanceof LogicalTypes.Decimal ? "NUMERIC" : "BYTES";
            case STRING:
                return "STRING";
            case ARRAY:
                return getTableFieldType(fieldSchema.getElementType());
            case UNION:
                return getTableFieldType(getUnionNotNullType(fieldSchema.getTypes()));
            default:
                throw new IllegalArgumentException("Unknown fieldSchema type: " + type);

            // java 17
//            return switch (type) {
//                case RECORD -> "RECORD";
//                case INT -> LogicalTypes.date().equals(logicalType)
//                        ? "DATE" : "INTEGER";
//                case LONG -> LogicalTypes.timestampMillis().equals(logicalType) || LogicalTypes.timestampMicros()
//                        .equals(logicalType)
//                        ? "TIMESTAMP" : "INTEGER";
//                case BOOLEAN -> "BOOLEAN";
//                case FLOAT, DOUBLE -> "FLOAT";
//                case BYTES -> logicalType instanceof LogicalTypes.Decimal ? "NUMERIC" : "BYTES";
//                case STRING -> "STRING";
//                case ARRAY -> getTableFieldType(fieldSchema.getElementType());
//                case UNION -> getTableFieldType(getUnionNotNullType(fieldSchema.getTypes()));
//                default -> throw new IllegalArgumentException("Unknown fieldSchema type: " + type);
//            };
        }
    }

    private static Schema getUnionNotNullType(List<Schema> types) {
        return Schema.Type.NULL != types.get(0).getType() ? types.get(0) : types.get(1);
    }

    private static String getTableFieldMode(Schema.Field field) {
        Schema schema = field.schema();
        Schema.Type type = schema.getType();
        if (Schema.Type.UNION != type) {
            return Schema.Type.ARRAY == type ? "REPEATED" : "REQUIRED";
        }
        Schema subSchema = getUnionNotNullType(schema.getTypes());
        return Schema.Type.ARRAY == subSchema.getType() ? "REPEATED" : "NULLABLE";
    }
}
