import os
import glob

def replace_in_file(filepath, replacements):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original = content
    for old, new in replacements:
        content = content.replace(old, new)
        
    if original != content:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Updated {filepath}")

def process_all():
    java_files = glob.glob('src/**/*.java', recursive=True)
    for filepath in java_files:
        replacements = [
            ("ObjectRecord<String, String>", "MapRecord<String, String, String>"),
            ("StreamListener<String, ObjectRecord<String, String>>", "StreamListener<String, MapRecord<String, String, String>>"),
            ("import org.springframework.data.redis.connection.stream.ObjectRecord;", "import org.springframework.data.redis.connection.stream.MapRecord;\nimport org.springframework.data.redis.connection.stream.StreamRecords;\nimport java.util.Collections;"),
            ("ObjectRecord.create(streamName, payload)", "StreamRecords.newRecord().in(streamName).ofMap(Collections.singletonMap(\"payload\", payload))"),
            ("ObjectRecord.create(targetStream, serialize(context))", "StreamRecords.newRecord().in(targetStream).ofMap(Collections.singletonMap(\"payload\", serialize(context)))"),
            ("String payload = record.getValue();", "String payload = record.getValue().get(\"payload\");"),
            ("String rawData = record.getValue();", "String rawData = record.getValue().get(\"payload\");"),
            ("ObjectRecord.create(\"test-stream\", \"{\\\"test\\\": \\\"payload\\\"}\")", "StreamRecords.newRecord().in(\"test-stream\").ofMap(Collections.singletonMap(\"payload\", \"{\\\"test\\\": \\\"payload\\\"}\"))"),
            ("ObjectRecord.create(\"whatsapp:ingress:stream\", msgPayload)", "StreamRecords.newRecord().in(\"whatsapp:ingress:stream\").ofMap(Collections.singletonMap(\"payload\", msgPayload))"),
            ("ObjectRecord.create(\"whatsapp:ingress:stream\", statusPayload())", "StreamRecords.newRecord().in(\"whatsapp:ingress:stream\").ofMap(Collections.singletonMap(\"payload\", statusPayload()))"),
            ("ObjectRecord.create(\"whatsapp:ingress:stream\", wrappedJson)", "StreamRecords.newRecord().in(\"whatsapp:ingress:stream\").ofMap(Collections.singletonMap(\"payload\", wrappedJson))"),
            ("ObjectRecord.create(\"whatsapp:ingress:stream\", messagePayload)", "StreamRecords.newRecord().in(\"whatsapp:ingress:stream\").ofMap(Collections.singletonMap(\"payload\", messagePayload))"),
            ("ObjectRecord.create(\"whatsapp:ingress:stream\", webhookJson)", "StreamRecords.newRecord().in(\"whatsapp:ingress:stream\").ofMap(Collections.singletonMap(\"payload\", webhookJson))"),
            ("ObjectRecord.create(\"whatsapp:ingress:stream\", \"{\\\"invalid\\\":\\\"json_no_entry\\\"}\")", "StreamRecords.newRecord().in(\"whatsapp:ingress:stream\").ofMap(Collections.singletonMap(\"payload\", \"{\\\"invalid\\\":\\\"json_no_entry\\\"}\"))"),
            ("ObjectRecord.create(aiStream, payload)", "StreamRecords.newRecord().in(aiStream).ofMap(Collections.singletonMap(\"payload\", payload))"),
            ("ObjectRecord.create(flowStream, payload)", "StreamRecords.newRecord().in(flowStream).ofMap(Collections.singletonMap(\"payload\", payload))"),
            ("ObjectRecord.create(deliveryStream, payload)", "StreamRecords.newRecord().in(deliveryStream).ofMap(Collections.singletonMap(\"payload\", payload))"),
            (".targetType(String.class)", "")
        ]
        
        replace_in_file(filepath, replacements)

process_all()
