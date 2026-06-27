import boto3
import gzip
import orjson

ranges = []

session = boto3.Session(profile_name='surf')
s3_client = session.client('s3')

paginator = s3_client.get_paginator('list_objects_v2')
page_iterator = paginator.paginate(
    Bucket='globalise-data',
    Prefix='objects/inventory/'
)

objects = []
for response in page_iterator:
    objects.extend(response.get('Contents', []))

for content in objects:
    key = content['Key']

    if key.endswith('.txt') or key.endswith('.index.json'):

        print(key)

        obj = s3_client.get_object(Bucket='globalise-data', Key=key)
        body = obj['Body'].read()
        encoding = obj.get('ContentEncoding', '')

        if encoding == 'gzip':
            body = gzip.decompress(body)

        if key.endswith('.index.json'):
            jsonbody = orjson.loads(body)

            start = jsonbody["date_start"]
            end = jsonbody["date_end"]
            inv = jsonbody["inventory_number"]